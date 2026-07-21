package com.petclinic.rag.controller;

import com.petclinic.rag.service.ChunkingService;
import com.petclinic.rag.service.FileDeduplicationService;
import com.petclinic.rag.service.extraction.ExtractorFactory;
import com.petclinic.rag.service.extraction.TextExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class IngestControllerTest {

    @Mock
    private ExtractorFactory extractorFactory;

    @Mock
    private ChunkingService chunkingService;

    @Mock
    private VectorStore vectorStore;

    @Mock
    private FileDeduplicationService deduplicationService;

    @Mock
    private TextExtractor textExtractor;

    private MockMvc mockMvc;

    private void setUp() {
        IngestController controller = new IngestController(
                extractorFactory, chunkingService, vectorStore, deduplicationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void ingest_returnsBadRequest_whenFileIsEmpty() throws Exception {
        setUp();
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "empty.txt", "text/plain", new byte[0]);

        mockMvc.perform(multipart("/api/documents").file(emptyFile))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Uploaded file is empty."));

        verifyNoInteractions(deduplicationService, extractorFactory, chunkingService, vectorStore);
    }

    @Test
    void ingest_returnsConflict_whenFileAlreadyIngested() throws Exception {
        setUp();
        byte[] content = "some file content".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.txt", "text/plain", content);

        when(deduplicationService.computeHash(content)).thenReturn("hash123");
        when(deduplicationService.isAlreadyIngested("hash123", "text")).thenReturn(true);

        mockMvc.perform(multipart("/api/documents").file(file))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("skipped"))
                .andExpect(jsonPath("$.filename").value("test.txt"));

        // Must short-circuit before extraction/chunking/storage when duplicate.
        verifyNoInteractions(extractorFactory, chunkingService, vectorStore);
        verify(deduplicationService, never()).markIngested(anyString(), anyString(), anyString());
    }

    @Test
    void ingest_storesDocument_whenFileIsNew() throws Exception {
        setUp();
        byte[] content = "some new file content".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "new.txt", "text/plain", content);

        when(deduplicationService.computeHash(content)).thenReturn("newhash456");
        when(deduplicationService.isAlreadyIngested("newhash456", "text")).thenReturn(false);
        when(extractorFactory.getExtractor("new.txt")).thenReturn(textExtractor);
        when(textExtractor.extract(any(InputStream.class), eq("new.txt")))
                .thenReturn("extracted text content");

        List<Document> chunks = List.of(
                new Document("chunk 1", Map.of("source", "new.txt")),
                new Document("chunk 2", Map.of("source", "new.txt"))
        );
        when(chunkingService.chunk(any(Document.class))).thenReturn(chunks);

        mockMvc.perform(multipart("/api/documents").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("stored"))
                .andExpect(jsonPath("$.filename").value("new.txt"))
                .andExpect(jsonPath("$.chunksStored").value(2))
                .andExpect(jsonPath("$.extractedCharacters").value("extracted text content".length()));

        verify(vectorStore).add(chunks);
        // Dedup record must be written only AFTER a successful store.
        verify(deduplicationService).markIngested("newhash456", "new.txt", "text");
    }

    @Test
    void ingest_returnsBadRequest_whenExtractedTextIsBlank() throws Exception {
        setUp();
        byte[] content = "unreadable content".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "blank.txt", "text/plain", content);

        when(deduplicationService.computeHash(content)).thenReturn("hash789");
        when(deduplicationService.isAlreadyIngested("hash789", "text")).thenReturn(false);
        when(extractorFactory.getExtractor("blank.txt")).thenReturn(textExtractor);
        when(textExtractor.extract(any(InputStream.class), eq("blank.txt"))).thenReturn("   ");

        mockMvc.perform(multipart("/api/documents").file(file))
                .andExpect(status().isBadRequest());

        // Must not mark as ingested or store anything if extraction produced nothing usable.
        verify(vectorStore, never()).add(any());
        verify(deduplicationService, never()).markIngested(anyString(), anyString(), anyString());
    }

    @Test
    void ingest_returnsUnsupportedMediaType_whenExtractorFactoryRejectsFileType() throws Exception {
        setUp();
        byte[] content = "some content".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "weird.xyz", "application/octet-stream", content);

        when(deduplicationService.computeHash(content)).thenReturn("hashXYZ");
        when(deduplicationService.isAlreadyIngested("hashXYZ", "text")).thenReturn(false);
        when(extractorFactory.getExtractor("weird.xyz"))
                .thenThrow(new IllegalArgumentException("No extractor found for file type: .xyz"));

        mockMvc.perform(multipart("/api/documents").file(file))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("No extractor found for file type: .xyz"));
    }

    @Test
    void ingest_returnsInternalServerError_whenVectorStoreThrows() throws Exception {
        setUp();
        byte[] content = "content that will fail to store".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "fail.txt", "text/plain", content);

        when(deduplicationService.computeHash(content)).thenReturn("failhash");
        when(deduplicationService.isAlreadyIngested("failhash", "text")).thenReturn(false);
        when(extractorFactory.getExtractor("fail.txt")).thenReturn(textExtractor);
        when(textExtractor.extract(any(InputStream.class), eq("fail.txt"))).thenReturn("some text");
        when(chunkingService.chunk(any(Document.class)))
                .thenReturn(List.of(new Document("chunk", Map.of("source", "fail.txt"))));
        doThrow(new RuntimeException("DB connection failed")).when(vectorStore).add(any());

        mockMvc.perform(multipart("/api/documents").file(file))
                .andExpect(status().isInternalServerError());

        // A failed store must NOT mark the file as ingested — must be retry-able.
        verify(deduplicationService, never()).markIngested(anyString(), anyString(), anyString());
    }
}