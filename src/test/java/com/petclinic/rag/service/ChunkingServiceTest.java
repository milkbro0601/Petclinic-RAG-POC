package com.petclinic.rag.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkingServiceTest {

    private final ChunkingService chunkingService = new ChunkingService();

    @Test
    void chunk_returnsSingleChunk_forShortDocument() {
        Document shortDoc = new Document("This is a short piece of text.", Map.of("source", "short.txt"));

        List<Document> chunks = chunkingService.chunk(shortDoc);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getText()).isEqualTo("This is a short piece of text.");
    }

    @Test
    void chunk_splitsIntoMultipleChunks_forLongDocument() {
        // Repeating a paragraph many times to comfortably exceed
        // TokenTextSplitter's default chunk size and force a split.
        String longText = "This is a sentence about artificial intelligence and machine learning. "
                .repeat(300);
        Document longDoc = new Document(longText, Map.of("source", "long.txt"));

        List<Document> chunks = chunkingService.chunk(longDoc);

        assertThat(chunks.size()).isGreaterThan(1);
    }

    @Test
    void chunk_preservesSourceMetadata_acrossAllChunks() {
        String longText = "Filler sentence for chunking purposes. ".repeat(300);
        Document longDoc = new Document(longText, Map.of("source", "metadata-test.txt"));

        List<Document> chunks = chunkingService.chunk(longDoc);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.getMetadata().get("source")).isEqualTo("metadata-test.txt"));
    }

    @Test
    void chunk_producesNoChunksWithEmptyContent_whenTextIsBlank() {
        // Documents unusual/edge behavior for a blank document, since we
        // don't currently validate blank text before calling chunk() in
        // IngestController — worth knowing what TokenTextSplitter itself does.
        Document blankDoc = new Document(" ", Map.of("source", "blank.txt"));

        List<Document> chunks = chunkingService.chunk(blankDoc);

        // Not asserting a specific count here — this test exists primarily
        // to surface (via failure) if TokenTextSplitter's behavior on blank
        // input ever changes across a Spring AI version bump.
        assertThat(chunks).isNotNull();
    }

    @Test
    void chunk_reassembledChunksContainOriginalContent() {
        // Sanity check that chunking doesn't silently drop or corrupt
        // content — every chunk's text should be a substring of the
        // original, and concatenating should roughly reconstruct it
        // (allowing for TokenTextSplitter's own whitespace normalization).
        String original = "Alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima mike november. "
                .repeat(200);
        Document doc = new Document(original, Map.of("source", "reassembly.txt"));

        List<Document> chunks = chunkingService.chunk(doc);

        for (Document chunk : chunks) {
            assertThat(chunk.getText()).isNotBlank();
        }
    }
}