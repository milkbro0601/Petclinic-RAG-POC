package com.petclinic.rag.controller;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class HealthController {

    @Autowired
    private VectorStore vectorStore;

    @GetMapping("/api/test-embedding")
    public String testEmbedding() {
        vectorStore.add(List.of(new Document("Cats are great pets for busy owners.")));
        var results = vectorStore.similaritySearch("What pet is good for busy people?");
        return results.isEmpty() ? "No results" : results.get(0).getText();
    }
}