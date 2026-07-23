package com.petclinic.rag.controller;

import com.petclinic.rag.service.FileDeduplicationService;
import com.petclinic.rag.service.MultimodalImageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
public class MultimodalIngestController {

    private static final String INGESTION_TYPE = "multimodal-image";

    private final MultimodalImageService multimodalImageService;
    private final FileDeduplicationService deduplicationService;

    public MultimodalIngestController(MultimodalImageService multimodalImageService,
                                      FileDeduplicationService deduplicationService) {
        this.multimodalImageService = multimodalImageService;
        this.deduplicationService = deduplicationService;
    }

    @PostMapping("/api/documents/multimodal-image")
    public ResponseEntity<Map<String, Object>> ingestMultimodal(@RequestParam("file") MultipartFile file) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Uploaded file is empty."));
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !(filename.toLowerCase().endsWith(".png")
                || filename.toLowerCase().endsWith(".jpg")
                || filename.toLowerCase().endsWith(".jpeg"))) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .body(Map.of("error", "Only PNG/JPG images are supported for multimodal embedding."));
        }

        try {
            byte[] fileBytes = file.getBytes();
            String fileHash = deduplicationService.computeHash(fileBytes);

            if (deduplicationService.isAlreadyIngested(fileHash, INGESTION_TYPE)) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of(
                                "filename", filename,
                                "status", "skipped",
                                "reason", "This exact image has already been ingested via multimodal embedding."
                        ));
            }

            multimodalImageService.embedAndStore(file);
            deduplicationService.markIngested(fileHash, filename, INGESTION_TYPE);

            return ResponseEntity.ok(Map.of(
                    "filename", filename,
                    "strategy", "multimodal-embedding",
                    "status", "stored"
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Multimodal embedding failed: " + e.getMessage()));
        }
    }
}