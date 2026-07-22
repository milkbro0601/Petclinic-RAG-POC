package com.petclinic.rag.service.extraction;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class TikaGenericExtractor implements TextExtractor {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            ".doc", ".docx", ".pdf", ".txt", ".csv", ".json", ".md",
            ".xls", ".xlsx", ".ppt", ".pptx"
    );

    @Override
    public String extract(InputStream inputStream, String filename) {
        InputStreamResource resource = new InputStreamResource(inputStream) {
            @Override
            public String getFilename() {
                return filename;
            }
        };

        TikaDocumentReader reader = new TikaDocumentReader(resource);
        List<Document> documents = reader.get();

        return documents.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n"));
    }

    @Override
    public boolean supports(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase();
        return SUPPORTED_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }
}