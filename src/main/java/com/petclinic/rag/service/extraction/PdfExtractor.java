package com.petclinic.rag.service.extraction;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class PdfExtractor implements TextExtractor {

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
        return filename != null && filename.toLowerCase().endsWith(".pdf");
    }
}