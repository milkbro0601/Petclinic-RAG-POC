package com.petclinic.rag.service.vectorstore;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;

import java.util.List;

/**
 * Throwaway standalone test — run this as a plain main() BEFORE wiring JVectorStore
 * into AiConfig/Spring. Goal: isolate "does JVector itself work" from "does Spring
 * context wiring work" (same principle as gotcha #11 — verify third-party API shape
 * with something simple before involving Spring).
 *
 * Uses a fake EmbeddingModel that returns deterministic vectors instead of calling
 * NVIDIA's API, so this test has zero network dependency and runs in milliseconds.
 *
 * Delete this class once JVectorStore is confirmed working end-to-end via AiConfig —
 * it's scaffolding, not a permanent part of the codebase.
 */
public class JVectorStoreSmokeTest {

    public static void main(String[] args) {
        int dimension = 8; // small dimension for a fast, readable manual test

        EmbeddingModel fakeEmbeddingModel = new FakeEmbeddingModel(dimension) {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                return null;
            }
        };
        JVectorStore store = new JVectorStore(fakeEmbeddingModel, dimension);

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

        System.out.println("Adding 3 documents...");
        store.add(List.of(doc1, doc2, doc3));
        System.out.println("Add complete. Running similarity search...");

        SearchRequest request = SearchRequest.builder()
                .query("What is RAG?")
                .topK(2)
                .build();

        List<Document> results = store.similaritySearch(request);

        System.out.println("Search returned " + results.size() + " result(s):");
        for (Document d : results) {
            System.out.println("  - id=" + d.getId() + " text=\"" + d.getText() + "\"");
        }

        if (results.isEmpty()) {
            System.out.println("FAIL: expected at least 1 result, got 0. Check graph build/search wiring.");
        } else {
            System.out.println("PASS: JVectorStore add() + similaritySearch() executed without error.");
            System.out.println("NOTE: this fake embedding model does not encode real semantic meaning — "
                    + "this test only proves the JVector plumbing works, not retrieval quality. "
                    + "Retrieval quality is validated separately via the real curl tests against NVIDIA embeddings.");
        }

        System.out.println("\nTesting delete()...");
        store.delete(List.of("doc-1"));
        List<Document> afterDelete = store.similaritySearch(
                SearchRequest.builder().query("fox").topK(3).build());
        System.out.println("After deleting doc-1, search returned " + afterDelete.size() + " result(s) "
                + "(expected 2, since doc-1 should be gone).");
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