package com.petclinic.rag.service;

import com.petclinic.rag.service.extraction.DocxExtractor;
import com.petclinic.rag.service.extraction.ExtractorFactory;
import com.petclinic.rag.service.extraction.TextExtractor;
import com.petclinic.rag.service.extraction.TxtExtractor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit tests for the extractors — no Spring context, no HTTP,
 * no file upload required. This is the fastest way to confirm the
 * extraction logic itself works before wiring it into a controller.
 */
class ExtractionTest {

    @Test
    void txtExtractor_readsPlainText() {
        TxtExtractor extractor = new TxtExtractor();
        String content = "Cats are great pets for busy owners.";
        InputStream stream = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        String result = extractor.extract(stream, "notes.txt");

        assertEquals(content, result);
    }

    @Test
    void txtExtractor_supportsOnlyTxtFiles() {
        TxtExtractor extractor = new TxtExtractor();

        assertTrue(extractor.supports("notes.txt"));
        assertTrue(!extractor.supports("notes.docx"));
    }

    @Test
    void extractorFactory_picksCorrectExtractor() {
        ExtractorFactory factory = new ExtractorFactory(
                List.of(new TxtExtractor(), new DocxExtractor()));

        TextExtractor picked = factory.getExtractor("report.txt");

        assertTrue(picked instanceof TxtExtractor);
    }

    @Test
    void extractorFactory_throwsForUnsupportedFileType() {
        ExtractorFactory factory = new ExtractorFactory(
                List.of(new TxtExtractor(), new DocxExtractor()));

        try {
            factory.getExtractor("image.png");
            assertTrue(false, "Expected an exception for unsupported file type");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("image.png"));
        }
    }
}