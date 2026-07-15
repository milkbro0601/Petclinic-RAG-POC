package com.petclinic.rag.controller;

import com.petclinic.rag.service.extraction.ExtractorFactory;
import com.petclinic.rag.service.extraction.TextExtractor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
public class IngestController {

    private final ExtractorFactory extractorFactory;
    private final VectorStore vectorStore;

    public IngestController(ExtractorFactory extractorFactory, VectorStore vectorStore) {
        this.extractorFactory = extractorFactory;
        this.vectorStore = vectorStore;
    }

    @PostMapping("/api/documents")
    public ResponseEntity<Map<String, Object>> ingest(@RequestParam("file") MultipartFile file) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Uploaded file is empty."));
        }

        String filename = file.getOriginalFilename();

        try {
            TextExtractor extractor = extractorFactory.getExtractor(filename);
            String extractedText = extractor.extract(file.getInputStream(), filename);

            if (extractedText == null || extractedText.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "No text could be extracted from: " + filename));
            }

            // store the whole document as a single chunk. (for this step)
            Document document = new Document(extractedText, Map.of("source", filename));
            vectorStore.add(List.of(document));

            return ResponseEntity.ok(Map.of(
                    "filename", filename,
                    "extractedCharacters", extractedText.length(),
                    "status", "stored"
            ));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .body(Map.of("error", e.getMessage()));

        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to read uploaded file: " + e.getMessage()));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Ingestion failed: " + e.getMessage()));
        }
    }
}