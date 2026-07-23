package com.petclinic.rag.service.extraction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExtractorFactoryTest {

    @Mock
    private TextExtractor tikaExtractor;

    @Mock
    private TextExtractor ocrExtractor;

    private ExtractorFactory factory;

    @BeforeEach
    void setUp() {
        factory = new ExtractorFactory(List.of(tikaExtractor, ocrExtractor));
    }

    @Test
    void getExtractor_returnsMatchingExtractor() {
        when(tikaExtractor.supports("report.pdf")).thenReturn(true);

        TextExtractor result = factory.getExtractor("report.pdf");

        assertThat(result).isEqualTo(tikaExtractor);
    }

    @Test
    void getExtractor_throwsWhenNoExtractorSupportsFile() {
        when(tikaExtractor.supports("archive.zip")).thenReturn(false);
        when(ocrExtractor.supports("archive.zip")).thenReturn(false);

        assertThatThrownBy(() -> factory.getExtractor("archive.zip"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("archive.zip");
    }
}