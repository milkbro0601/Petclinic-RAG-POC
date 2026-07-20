package com.petclinic.rag.service;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import com.petclinic.rag.dto.QueryResponse;

@ExtendWith(MockitoExtension.class)
class RagQueryServiceTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private ChatModel chatModel;

    @InjectMocks
    private RagQueryService ragQueryService;

    @Test
    void answer_returnsFallbackWhenNoRelevantDocuments() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        QueryResponse response = ragQueryService.answer("What is the clinic policy?");

        assertEquals("I don't have any relevant documents to answer that question. Try uploading some first.", response.answer());
        assertTrue(response.sources().isEmpty());
        verify(chatModel, never()).call(anyString());
    }

    @Test
    void answer_usesContextAndSourcesWhenDocumentsExist() {
        Document document = new Document("The clinic opens at 8am.", Map.of("source", "guide.txt"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));
        when(chatModel.call(anyString())).thenReturn("The clinic opens at 8am.");

        QueryResponse response = ragQueryService.answer("When does the clinic open?");

        assertEquals("The clinic opens at 8am.", response.answer());
        assertEquals(List.of("guide.txt"), response.sources());
        verify(chatModel).call(contains("The clinic opens at 8am"));
    }
}
