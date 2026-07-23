package com.petclinic.rag.service.extraction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TikaGenericExtractorTest {

    private final TikaGenericExtractor extractor = new TikaGenericExtractor();

    @ParameterizedTest
    @ValueSource(strings = {
            "report.doc", "report.docx", "report.pdf",
            "notes.txt", "data.csv", "config.json", "readme.md",
            "sheet.xls", "sheet.xlsx", "deck.ppt", "deck.pptx",
            "REPORT.PDF", "Notes.TXT"
    })
    void supports_returnsTrueForAllExpectedExtensions(String filename) {
        assertThat(extractor.supports(filename)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"image.png", "photo.jpg", "scan.tiff", "archive.zip", "video.mp4"})
    void supports_returnsFalseForUnsupportedExtensions(String filename) {
        assertThat(extractor.supports(filename)).isFalse();
    }

    @Test
    void supports_returnsFalseForNullFilename() {
        assertThat(extractor.supports(null)).isFalse();
    }

    @Test
    void extract_extractsTextFromPlainTxtFile() {
        String content = "Hello, this is a test document.";
        InputStream inputStream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        String result = extractor.extract(inputStream, "test.txt");

        assertThat(result).contains("Hello, this is a test document.");
    }

    @Test
    void extract_extractsTextFromCsvFile() {
        String csv = "name,age\nAlice,30\nBob,25";
        InputStream inputStream = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));

        String result = extractor.extract(inputStream, "test.csv");

        assertThat(result).contains("Alice").contains("Bob");
    }

    @Test
    void extract_extractsTextFromJsonFile() {
        String json = "{\"message\": \"integration test content\"}";
        InputStream inputStream = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));

        String result = extractor.extract(inputStream, "test.json");

        assertThat(result).contains("integration test content");
    }

    @Test
    void extract_extractsTextFromMarkdownFile() {
        String md = "# Heading\n\nSome **bold** content here.";
        InputStream inputStream = new ByteArrayInputStream(md.getBytes(StandardCharsets.UTF_8));

        String result = extractor.extract(inputStream, "test.md");

        assertThat(result).contains("Heading").contains("bold");
    }
}