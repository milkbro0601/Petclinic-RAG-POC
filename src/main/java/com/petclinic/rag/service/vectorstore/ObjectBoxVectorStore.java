package com.petclinic.rag.service.vectorstore;

import io.objectbox.Box;
import io.objectbox.BoxStore;
import io.objectbox.query.ObjectWithScore;
import io.objectbox.query.Query;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ObjectBoxVectorStore implements VectorStore, AutoCloseable {

    private final EmbeddingModel embeddingModel;
    private final BoxStore boxStore;
    private final Box<VectorDocumentEntity> box;

    public ObjectBoxVectorStore(EmbeddingModel embeddingModel, String dbDirectory) {
        this.embeddingModel = embeddingModel;
        this.boxStore = MyObjectBox.builder()
                .directory(new File(dbDirectory))
                .build();
        this.box = boxStore.boxFor(VectorDocumentEntity.class);
    }

    @Override
    public void add(List<Document> documents) {
        List<VectorDocumentEntity> entities = new ArrayList<>();
        for (Document doc : documents) {
            float[] embedding = embeddingModel.embed(doc);

            VectorDocumentEntity entity = new VectorDocumentEntity();
            entity.docId = doc.getId();
            entity.text = doc.getText();
            entity.source = doc.getMetadata() != null
                    ? String.valueOf(doc.getMetadata().get("source"))
                    : null;
            entity.embedding = embedding;
            entities.add(entity);
        }
        box.put(entities);
    }

    @Override
    public void delete(List<String> idList) {
        try (Query<VectorDocumentEntity> query = box.query(
                        VectorDocumentEntity_.docId.oneOf(idList.toArray(new String[0])))
                .build()) {
            List<VectorDocumentEntity> toDelete = query.find();
            box.remove(toDelete);
        }
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        throw new UnsupportedOperationException(
                "Filter-expression delete not implemented for ObjectBoxVectorStore PoC");
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        float[] queryEmbedding = embeddingModel.embed(request.getQuery());

        try (Query<VectorDocumentEntity> query = box.query(
                        VectorDocumentEntity_.embedding.nearestNeighbors(queryEmbedding, request.getTopK()))
                .build()) {

            List<ObjectWithScore<VectorDocumentEntity>> results = query.findWithScores();

            List<Document> documents = new ArrayList<>();
            for (ObjectWithScore<VectorDocumentEntity> result : results) {
                VectorDocumentEntity entity = result.get();

                double distance = result.getScore();

                documents.add(Document.builder()
                        .id(entity.docId)
                        .text(entity.text)
                        .metadata("source", entity.source)
                        .build());
            }
            return documents;
        }
    }

    @Override
    public String getName() {
        return "ObjectBoxVectorStore";
    }

    @Override
    public Optional<Object> getNativeClient() {
        return Optional.ofNullable(boxStore);
    }

    @Override
    public void close() {
        if (boxStore != null && !boxStore.isClosed()) {
            boxStore.close();
        }
    }
}