package com.petclinic.rag.controller;

import com.petclinic.rag.service.FileDeduplicationService;
import com.petclinic.rag.service.MultimodalImageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class MultimodalIngestControllerTest {

    @Mock
    private MultimodalImageService multimodalImageService;

    @Mock
    private FileDeduplicationService deduplicationService;

    private MockMvc mockMvc;

    private void setUp() {
        MultimodalIngestController controller =
                new MultimodalIngestController(multimodalImageService, deduplicationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void ingestMultimodal_returnsBadRequest_whenFileIsEmpty() throws Exception {
        setUp();
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "empty.png", "image/png", new byte[0]);

        mockMvc.perform(multipart("/api/documents/multimodal-image").file(emptyFile))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Uploaded file is empty."));

        verifyNoInteractions(deduplicationService, multimodalImageService);
    }

    @Test
    void ingestMultimodal_returnsUnsupportedMediaType_whenFileIsNotImage() throws Exception {
        setUp();
        MockMultipartFile txtFile = new MockMultipartFile(
                "file", "notanimage.txt", "text/plain", "some text".getBytes());

        mockMvc.perform(multipart("/api/documents/multimodal-image").file(txtFile))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("Only PNG/JPG images are supported for multimodal embedding."));

        verifyNoInteractions(deduplicationService, multimodalImageService);
    }

    @Test
    void ingestMultimodal_acceptsPngExtension() throws Exception {
        setUp();
        byte[] content = "fake png bytes".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "image.png", "image/png", content);

        when(deduplicationService.computeHash(content)).thenReturn("pnghash");
        when(deduplicationService.isAlreadyIngested("pnghash", "multimodal-image")).thenReturn(false);

        mockMvc.perform(multipart("/api/documents/multimodal-image").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("stored"))
                .andExpect(jsonPath("$.strategy").value("multimodal-embedding"));

        verify(multimodalImageService).embedAndStore(any());
        verify(deduplicationService).markIngested("pnghash", "image.png", "multimodal-image");
    }

    @Test
    void ingestMultimodal_acceptsJpgAndJpegExtensions() throws Exception {
        setUp();
        byte[] content = "fake jpg bytes".getBytes();
        MockMultipartFile jpgFile = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", content);

        when(deduplicationService.computeHash(content)).thenReturn("jpghash");
        when(deduplicationService.isAlreadyIngested("jpghash", "multimodal-image")).thenReturn(false);

        mockMvc.perform(multipart("/api/documents/multimodal-image").file(jpgFile))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("stored"));
    }

    @Test
    void ingestMultimodal_returnsConflict_whenImageAlreadyIngested() throws Exception {
        setUp();
        byte[] content = "duplicate image bytes".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "dup.png", "image/png", content);

        when(deduplicationService.computeHash(content)).thenReturn("duphash");
        when(deduplicationService.isAlreadyIngested("duphash", "multimodal-image")).thenReturn(true);

        mockMvc.perform(multipart("/api/documents/multimodal-image").file(file))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("skipped"))
                .andExpect(jsonPath("$.filename").value("dup.png"));

        // Must short-circuit before calling the embedding service.
        verifyNoInteractions(multimodalImageService);
        verify(deduplicationService, never()).markIngested(anyString(), anyString(), anyString());
    }

    @Test
    void ingestMultimodal_returnsInternalServerError_whenEmbeddingServiceThrows() throws Exception {
        setUp();
        byte[] content = "content that will fail".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "fail.png", "image/png", content);

        when(deduplicationService.computeHash(content)).thenReturn("failhash");
        when(deduplicationService.isAlreadyIngested("failhash", "multimodal-image")).thenReturn(false);
        doThrow(new RuntimeException("NVIDIA API call failed"))
                .when(multimodalImageService).embedAndStore(any());

        mockMvc.perform(multipart("/api/documents/multimodal-image").file(file))
                .andExpect(status().isInternalServerError());

        // A failed embed must NOT mark the file as ingested — must be retry-able.
        verify(deduplicationService, never()).markIngested(anyString(), anyString(), anyString());
    }

    @Test
    void ingestMultimodal_dedupChecksAgainstMultimodalImageTypeNotText() throws Exception {
        // Regression test for the composite-key design: this endpoint must
        // check ingestion_type="multimodal-image", never "text" — otherwise
        // a file already ingested via /api/documents (text) would incorrectly
        // be blocked here too, even though they're legitimately separate
        // pipelines producing different embeddings.
        setUp();
        byte[] content = "same bytes as a text upload".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "shared.png", "image/png", content);

        when(deduplicationService.computeHash(content)).thenReturn("sharedhash");
        when(deduplicationService.isAlreadyIngested("sharedhash", "multimodal-image")).thenReturn(false);

        mockMvc.perform(multipart("/api/documents/multimodal-image").file(file))
                .andExpect(status().isOk());

        verify(deduplicationService).isAlreadyIngested("sharedhash", "multimodal-image");
        verify(deduplicationService, never()).isAlreadyIngested(anyString(), eq("text"));
    }
}