package com.petclinic.rag.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class MultimodalImageService {

    private final VectorStore multimodalVectorStore;

    public MultimodalImageService(@Qualifier("multimodalVectorStore") VectorStore multimodalVectorStore) {
        this.multimodalVectorStore = multimodalVectorStore;
    }

    public void embedAndStore(MultipartFile file) {
        String filename = file.getOriginalFilename();
        String mimeType = detectMimeType(filename);

        String base64Image;
        try {
            base64Image = Base64.getEncoder().encodeToString(file.getBytes());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read image file: " + filename, e);
        }

        String dataUri = "data:" + mimeType + ";base64," + base64Image;

        Document document = new Document(dataUri, Map.of(
                "source", filename,
                "strategy", "multimodal-embedding"
        ));

        multimodalVectorStore.add(List.of(document));
    }

    private String detectMimeType(String filename) {
        if (filename == null) return "image/png";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        return "image/png";
    }
}