package com.petclinic.rag.controller;

import com.petclinic.rag.service.MultimodalImageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Separate endpoint from /api/documents, deliberately — this uses the
 * multimodal (image-native) embedding strategy instead of OCR, so both
 * can be tested independently on the same image for Task 1's comparison.
 */
@RestController
public class MultimodalIngestController {

    private final MultimodalImageService multimodalImageService;

    public MultimodalIngestController(MultimodalImageService multimodalImageService) {
        this.multimodalImageService = multimodalImageService;
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
            multimodalImageService.embedAndStore(file);
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