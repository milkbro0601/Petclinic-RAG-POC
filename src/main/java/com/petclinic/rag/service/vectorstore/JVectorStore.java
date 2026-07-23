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

public class JVectorStore implements VectorStore {

    private final EmbeddingModel embeddingModel;
    private final int dimension;
    private final VectorSimilarityFunction similarityFunction;
    private final VectorTypeSupport vts;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private final List<Document> documents = new ArrayList<>();
    private final List<VectorFloat<?>> vectors = new ArrayList<>();

    private volatile ImmutableGraphIndex graph;
    private volatile RandomAccessVectorValues ravv;

    public JVectorStore(EmbeddingModel embeddingModel, int dimension) {
        this.embeddingModel = embeddingModel;
        this.dimension = dimension;
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