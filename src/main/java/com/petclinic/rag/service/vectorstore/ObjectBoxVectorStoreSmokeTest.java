package com.petclinic.rag.service.vectorstore;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

public class ObjectBoxVectorStoreSmokeTest {

    private static final int DIMENSIONS = 2048;

    public static void main(String[] args) throws Exception {
        Path dbDir = java.nio.file.Files.createTempDirectory("objectbox-smoketest");
        String dbPath = dbDir.toString();
        System.out.println("Using ObjectBox test directory: " + dbPath);

        EmbeddingModel fakeEmbeddingModel = new FakeEmbeddingModel(DIMENSIONS) {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                return null;
            }
        };

        Document doc1 = Document.builder()
                .id("doc-1")
                .text("The quick brown fox jumps over the lazy dog")
                .metadata("source", "test1.txt")
                .build();

        Document doc2 = Document.builder()
                .id("doc-2")
                .text("Retrieval augmented generation combines search and LLMs")
                .metadata("source", "test2.txt")
                .build();

        Document doc3 = Document.builder()
                .id("doc-3")
                .text("Cats and dogs are common household pets")
                .metadata("source", "test3.txt")
                .build();

        System.out.println("\n=== PHASE 1: writing 3 documents ===");
        try (ObjectBoxVectorStore store = new ObjectBoxVectorStore(fakeEmbeddingModel, dbPath)) {
            store.add(List.of(doc1, doc2, doc3));
            System.out.println("Add complete. Running similarity search (same instance)...");

            List<Document> results = store.similaritySearch(
                    SearchRequest.builder().query("What is RAG?").topK(2).build());

            System.out.println("Search returned " + results.size() + " result(s):");
            for (Document d : results) {
                System.out.println("  - id=" + d.getId() + " text=\"" + d.getText() + "\"");
            }

            if (results.isEmpty()) {
                System.out.println("FAIL: expected at least 1 result, got 0. Check entity/query wiring.");
            } else {
                System.out.println("PASS: add() + similaritySearch() executed without error (same instance).");
            }
        }

        System.out.println("\nStore closed. Simulating an app restart by opening a NEW store instance"
                + " pointed at the SAME directory, without re-adding any documents...");

        System.out.println("\n=== PHASE 2: persistence check (new instance, same directory) ===");
        try (ObjectBoxVectorStore reopenedStore = new ObjectBoxVectorStore(fakeEmbeddingModel, dbPath)) {
            List<Document> resultsAfterReopen = reopenedStore.similaritySearch(
                    SearchRequest.builder().query("What is RAG?").topK(3).build());

            System.out.println("Search returned " + resultsAfterReopen.size() + " result(s) after reopening:");
            for (Document d : resultsAfterReopen) {
                System.out.println("  - id=" + d.getId() + " text=\"" + d.getText() + "\"");
            }

            if (resultsAfterReopen.size() == 3) {
                System.out.println("PASS: persistence confirmed — all 3 documents survived store close/reopen,"
                        + " with zero custom persistence code required.");
            } else {
                System.out.println("FAIL: expected 3 documents to persist, got "
                        + resultsAfterReopen.size() + ". Persistence is NOT working as expected.");
            }

            System.out.println("\nTesting delete()...");
            reopenedStore.delete(List.of("doc-1"));
            List<Document> afterDelete = reopenedStore.similaritySearch(
                    SearchRequest.builder().query("fox").topK(3).build());
            System.out.println("After deleting doc-1, search returned " + afterDelete.size()
                    + " result(s) (expected 2, since doc-1 should be gone).");
        }

        deleteDirectoryRecursively(dbDir.toFile());
        System.out.println("\nTest directory cleaned up.");
    }

    private static void deleteDirectoryRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteDirectoryRecursively(child);
            }
        }
        file.delete();
    }

    static abstract class FakeEmbeddingModel implements EmbeddingModel {
        private final int dimension;

        FakeEmbeddingModel(int dimension) {
            this.dimension = dimension;
        }

        @Override
        public float[] embed(String text) {
            float[] vector = new float[dimension];
            int hash = text.hashCode();
            for (int i = 0; i < dimension; i++) {
                vector[i] = ((hash >> (i % 32)) & 1) == 0 ? 0.1f : 0.9f;
            }
            return vector;
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }
    }
}