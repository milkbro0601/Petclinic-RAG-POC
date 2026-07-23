package com.petclinic.rag.service.extraction;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TikaGenericExtractorIntegrationTest {

    private final TikaGenericExtractor extractor = new TikaGenericExtractor();

    @Test
    void extract_realDocxFile_extractsText() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/sample-files/sample.docx")) {
            String result = extractor.extract(is, "sample.docx");
            assertThat(result).isNotBlank();
        }
    }

    @Test
    void extract_realPdfFile_extractsText() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/sample-files/sample.pdf")) {
            String result = extractor.extract(is, "sample.pdf");
            assertThat(result).isNotBlank();
        }
    }

    @Test
    void extract_realXlsxFile_extractsText() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/sample-files/sample.xlsx")) {
            String result = extractor.extract(is, "sample.xlsx");
            assertThat(result).isNotBlank();
        }
    }

    @Test
    void extract_realPptxFile_extractsText() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/sample-files/sample.pptx")) {
            String result = extractor.extract(is, "sample.pptx");
            assertThat(result).isNotBlank();
        }
    }
}