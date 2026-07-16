package com.petclinic.rag.service.vectorstore;

import io.github.jbellis.jvector.graph.GraphIndexBuilder;
import io.github.jbellis.jvector.graph.GraphSearcher;
import io.github.jbellis.jvector.graph.ImmutableGraphIndex;
import io.github.jbellis.jvector.graph.ListRandomAccessVectorValues;
import io.github.jbellis.jvector.graph.RandomAccessVectorValues;
import io.github.jbellis.jvector.graph.similarity.BuildScoreProvider;
import io.github.jbellis.jvector.graph.similarity.DefaultSearchScoreProvider;
import io.github.jbellis.jvector.graph.similarity.SearchScoreProvider;
import io.github.jbellis.jvector.graph.SearchResult;
import io.github.jbellis.jvector.util.Bits;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Custom VectorStore implementation backed by JVector (embedded, in-memory graph index).
 *
 * Lives in service/vectorstore/ alongside the (upcoming) ObjectBoxVectorStore, following
 * the same Strategy-pattern convention already established in service/extraction/
 * (TxtExtractor, DocxExtractor, ImageOcrExtractor picked via ExtractorFactory). Here,
 * bean selection happens in AiConfig instead of a dedicated factory class, since only
 * one VectorStore bean can be active in the Spring context at a time.
 *
 * IMPORTANT DESIGN NOTE (Task 1 comparison finding):
 * JVector's GraphIndexBuilder is a BATCH builder — it constructs a graph from a fixed
 * RandomAccessVectorValues snapshot. It is not a live "insert one row" API like PGvector's
 * INSERT. For this PoC, add() takes the naive approach of appending to an in-memory list
 * and REBUILDING the entire graph index on every call. This is fine for demo-scale document
 * counts, but is a real architectural cost vs. PGvector — worth calling out explicitly in
 * the Task 1 evaluation table as "lines of custom code" AND "write-path complexity".
 *
 * PERSISTENCE NOTE:
 * This class does NOT yet use JVector's native OnDiskGraphIndex format. It only holds
 * documents + vectors in memory — restart-persistence is not implemented here. If your
 * comparison table needs a persistence entry for JVector, that requires a follow-up
 * (either wiring OnDiskGraphIndex, or a cruder custom save/load of the raw vectors to disk
 * and rebuilding the graph on startup).
 *
 * Verify class/method names against your actual jvector 4.0.0-rc.8-hf1 javadocs before
 * compiling — this is a pre-GA (release candidate) library and API details can differ
 * from what's shown in the public tutorials.
 */
public class JVectorStore implements VectorStore {

    private final EmbeddingModel embeddingModel;
    private final int dimension;
    private final VectorSimilarityFunction similarityFunction;
    private final VectorTypeSupport vts;

    // Guards rebuild-on-write; GraphSearcher instances are not thread-safe, so each
    // search call creates its own searcher against the current immutable graph reference.
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private final List<Document> documents = new ArrayList<>();
    private final List<VectorFloat<?>> vectors = new ArrayList<>();

    private volatile ImmutableGraphIndex graph;
    private volatile RandomAccessVectorValues ravv;

    public JVectorStore(EmbeddingModel embeddingModel, int dimension) {
        this.embeddingModel = embeddingModel;
        this.dimension = dimension;
        // COSINE chosen to match the distance-type=COSINE_DISTANCE used in your
        // PGvector setup, so the two are comparable apples-to-apples.
        this.similarityFunction = VectorSimilarityFunction.COSINE;
        this.vts = VectorizationProvider.getInstance().getVectorTypeSupport();
    }

    @Override
    public void add(List<Document> newDocuments) {
        lock.writeLock().lock();
        try {
            for (Document doc : newDocuments) {
                float[] embedding = embeddingModel.embed(doc);
                documents.add(doc);
                vectors.add(vts.createFloatVector(embedding));
            }
            rebuildGraph();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void delete(List<String> idList) {
        lock.writeLock().lock();
        try {
            for (int i = documents.size() - 1; i >= 0; i--) {
                if (idList.contains(documents.get(i).getId())) {
                    documents.remove(i);
                    vectors.remove(i);
                }
            }
            rebuildGraph();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        // Not implemented for this PoC — PGvector/SimpleVectorStore support metadata
        // filter expressions natively; JVector has no built-in metadata filter concept,
        // so this would need a manual pre-filter pass before search. Flag as a known gap.
        throw new UnsupportedOperationException(
                "Filter-expression delete not implemented for JVectorStore PoC");
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        lock.readLock().lock();
        try {
            if (graph == null || documents.isEmpty()) {
                return List.of();
            }

            float[] queryEmbedding = embeddingModel.embed(request.getQuery());
            VectorFloat<?> queryVector = vts.createFloatVector(queryEmbedding);

            SearchScoreProvider ssp =
                    DefaultSearchScoreProvider.exact(queryVector, similarityFunction, ravv);

            try (GraphSearcher searcher = new GraphSearcher(graph)) {
                SearchResult result = searcher.search(ssp, request.getTopK(), Bits.ALL);

                // getNodes() returns a plain NodeScore[] array, not a List — use
                // Arrays.stream(), not .stream() directly on the array.
                //
                // getSimilarityThreshold() returns a primitive double (default 0.0,
                // meaning "accept everything"), not a nullable Double — no null check needed.
                return Arrays.stream(result.getNodes())
                        .filter(ns -> ns.score >= request.getSimilarityThreshold())
                        .map(ns -> documents.get(ns.node))
                        .collect(Collectors.toList());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Rebuilds the entire graph index from the current in-memory vector list.
     * Called on every add()/delete() — see class-level note on why this is a
     * PoC-level shortcut rather than a production incremental-write pattern.
     */
    private void rebuildGraph() {
        if (vectors.isEmpty()) {
            graph = null;
            ravv = null;
            return;
        }

        RandomAccessVectorValues newRavv =
                new ListRandomAccessVectorValues(vectors, dimension);

        BuildScoreProvider bsp =
                BuildScoreProvider.randomAccessScoreProvider(newRavv, similarityFunction);

        // Reasonable defaults per JVector's own tutorial — tune if recall/latency
        // numbers in your comparison table look off.
        int M = 32;
        int beamWidth = 100;
        float neighborOverflow = 1.2f;
        float alpha = 1.2f;
        boolean addHierarchy = true;
        boolean refineFinalGraph = true;

        try (GraphIndexBuilder builder = new GraphIndexBuilder(
                bsp, dimension, M, beamWidth, neighborOverflow, alpha,
                addHierarchy, refineFinalGraph)) {
            this.graph = builder.build(newRavv);
            this.ravv = newRavv;
        } catch (Exception e) {
            throw new RuntimeException("Failed to rebuild JVector graph index", e);
        }
    }

    @Override
    public String getName() {
        return "JVectorStore";
    }

    @Override
    public Optional<Object> getNativeClient() {
        return Optional.ofNullable(graph);
    }
}