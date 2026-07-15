package com.petclinic.rag.service.extraction;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Component
public class TxtExtractor implements TextExtractor {

    @Override
    public String extract(InputStream inputStream, String filename) {
        try {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read TXT file: " + filename, e);
        }
    }

    @Override
    public boolean supports(String filename) {
        return filename != null && filename.toLowerCase().endsWith(".txt");
    }
}