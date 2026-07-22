package com.petclinic.rag.service;

import com.petclinic.rag.dto.QueryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagQueryServiceTest {

    @Mock
    private QueryTimeVectorSearch queryTimeVectorSearch;

    @Mock
    private ChatClient chatClient;

    @Mock
    private QueryRouter queryRouter;

    @Mock
    private QueryRewriter queryRewriter;

    // Mocks for the ChatClient fluent chain used in RagQueryService's
    // answerAsChat()/answerFromDocuments() — note this controller uses
    // .system(...).user(...) chaining, not just .user(...) like QueryRouter/
    // CompressingChatMemory did, so the mock chain needs an extra step.
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    private RagQueryService ragQueryService;

    private static final String QUESTION = "What is the refund policy?";
    private static final String CONVERSATION_ID = "conv-1";
    private static final String REWRITTEN_QUERY = "refund policy standalone query";

    @BeforeEach
    void setUp() {
        ragQueryService = new RagQueryService(queryTimeVectorSearch, chatClient, queryRouter, queryRewriter);
        when(queryRewriter.rewrite(QUESTION, CONVERSATION_ID)).thenReturn(REWRITTEN_QUERY);
    }

    @Test
    void answer_returnsDocumentGroundedAnswer_whenRelevantChunksFound() {
        List<Document> chunks = List.of(
                new Document("Refunds are processed within 30 days.", Map.of("source", "policy.txt")),
                new Document("Contact support for refund requests.", Map.of("source", "policy.txt"))
        );
        when(queryTimeVectorSearch.similaritySearch(eq(REWRITTEN_QUERY), eq(4), eq(0.2)))
                .thenReturn(chunks);
        mockChatClientResponse("Refunds are processed within 30 days of purchase.");

        QueryResponse response = ragQueryService.answer(QUESTION, CONVERSATION_ID);

        assertThat(response.answer()).isEqualTo("Refunds are processed within 30 days of purchase.");
        assertThat(response.sources()).containsExactly("policy.txt");

        // Must search using the REWRITTEN query, not the raw question.
        verify(queryTimeVectorSearch).similaritySearch(REWRITTEN_QUERY, 4, 0.2);
        // Retrieval succeeded, so the classifier should never be consulted.
        verifyNoInteractions(queryRouter);
    }

    @Test
    void answer_deduplicatesSourcesFromMultipleChunksOfSameFile() {
        List<Document> chunks = List.of(
                new Document("chunk 1", Map.of("source", "policy.txt")),
                new Document("chunk 2", Map.of("source", "policy.txt")),
                new Document("chunk 3", Map.of("source", "other.txt"))
        );
        when(queryTimeVectorSearch.similaritySearch(anyString(), anyInt(), anyDouble()))
                .thenReturn(chunks);
        mockChatClientResponse("Some answer.");

        QueryResponse response = ragQueryService.answer(QUESTION, CONVERSATION_ID);

        assertThat(response.sources()).containsExactlyInAnyOrder("policy.txt", "other.txt");
    }

    @Test
    void answer_refuses_whenNoChunksFoundAndRouterClassifiesRetrieve() {
        when(queryTimeVectorSearch.similaritySearch(eq(REWRITTEN_QUERY), eq(4), eq(0.2)))
                .thenReturn(List.of());
        when(queryRouter.classify(QUESTION)).thenReturn(QueryRouter.Intent.RETRIEVE);

        QueryResponse response = ragQueryService.answer(QUESTION, CONVERSATION_ID);

        assertThat(response.answer())
                .isEqualTo("I don't have any relevant documents to answer that question. Try uploading some first.");
        assertThat(response.sources()).isEmpty();

        // Classifier must receive the ORIGINAL question, not the rewritten search query.
        verify(queryRouter).classify(QUESTION);
        verifyNoInteractions(chatClient);
    }

    @Test
    void answer_answersConversationally_whenNoChunksFoundAndRouterClassifiesChat() {
        when(queryTimeVectorSearch.similaritySearch(eq(REWRITTEN_QUERY), eq(4), eq(0.2)))
                .thenReturn(List.of());
        when(queryRouter.classify(QUESTION)).thenReturn(QueryRouter.Intent.CHAT);
        mockSimpleChatClientResponse("Hi there! How can I help you today?");

        QueryResponse response = ragQueryService.answer(QUESTION, CONVERSATION_ID);

        assertThat(response.answer()).isEqualTo("Hi there! How can I help you today?");
        assertThat(response.sources()).isEmpty();
    }

    @Test
    void answer_alwaysRewritesQueryBeforeSearching() {
        when(queryTimeVectorSearch.similaritySearch(anyString(), anyInt(), anyDouble()))
                .thenReturn(List.of());
        when(queryRouter.classify(anyString())).thenReturn(QueryRouter.Intent.CHAT);
        mockSimpleChatClientResponse("some reply");

        ragQueryService.answer(QUESTION, CONVERSATION_ID);

        verify(queryRewriter).rewrite(QUESTION, CONVERSATION_ID);
    }

    // --- helpers ---

    // Used by answerFromDocuments(): .system(...).user(...).advisors(...).call().content()
    private void mockChatClientResponse(String answerText) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(java.util.function.Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(answerText);
    }

    // Used by answerAsChat(): .user(...).advisors(...).call().content() — no .system(...)
    private void mockSimpleChatClientResponse(String answerText) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(answerText);
    }
}