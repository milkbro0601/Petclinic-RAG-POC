package com.petclinic.rag.service.extraction;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ExtractFactory {

    private final List<TextExtractor> extractors;

    public ExtractFactory(List<TextExtractor> extractors) {
        this.extractors = extractors;
    }

    public TextExtractor getExtractor(String filename) {
        return extractors.stream()
                .filter(extractor -> extractor.supports(filename))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No extractor available for file: " + filename));
    }
}