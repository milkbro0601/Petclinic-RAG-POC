package com.petclinic.rag.service.extraction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ImageOcrExtractorTest {

    @ParameterizedTest
    @ValueSource(strings = {"photo.png", "photo.jpg", "photo.jpeg", "scan.tiff",
            "PHOTO.PNG", "SCAN.TIFF"})
    void supports_returnsTrueForImageExtensions(String filename) {
        ImageOcrExtractor extractor = new ImageOcrExtractor("/usr/local/share/tessdata");
        assertThat(extractor.supports(filename)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"doc.pdf", "sheet.xlsx", "notes.txt"})
    void supports_returnsFalseForNonImageExtensions(String filename) {
        ImageOcrExtractor extractor = new ImageOcrExtractor("/usr/local/share/tessdata");
        assertThat(extractor.supports(filename)).isFalse();
    }
}