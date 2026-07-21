package com.petclinic.rag.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryRouterTest {

    @Mock
    private ChatClient classifierChatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    private QueryRouter queryRouter;

    private void setUpRouter() {
        queryRouter = new QueryRouter(classifierChatClient);
    }

    private void mockClassifierResponse(String rawResponse) {
        when(classifierChatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(rawResponse);
    }

    @Test
    void classify_returnsRetrieve_whenLlmRespondsRetrieve() {
        setUpRouter();
        mockClassifierResponse("RETRIEVE");

        QueryRouter.Intent result = queryRouter.classify("What is the refund policy?");

        assertThat(result).isEqualTo(QueryRouter.Intent.RETRIEVE);
    }

    @Test
    void classify_returnsChat_whenLlmRespondsChat() {
        setUpRouter();
        mockClassifierResponse("CHAT");

        QueryRouter.Intent result = queryRouter.classify("Hi, how are you?");

        assertThat(result).isEqualTo(QueryRouter.Intent.CHAT);
    }

    @Test
    void classify_isCaseInsensitive() {
        setUpRouter();
        mockClassifierResponse("chat");

        QueryRouter.Intent result = queryRouter.classify("thanks!");

        assertThat(result).isEqualTo(QueryRouter.Intent.CHAT);
    }

    @Test
    void classify_trimsWhitespaceFromResponse() {
        setUpRouter();
        mockClassifierResponse("  RETRIEVE  \n");

        QueryRouter.Intent result = queryRouter.classify("What are the store hours?");

        assertThat(result).isEqualTo(QueryRouter.Intent.RETRIEVE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "I'm not sure",
            "Unclear",
            "",
            "MAYBE",
            "no idea what to pick"
    })
    void classify_defaultsToRetrieve_whenResponseIsAmbiguousOrDoesNotContainChat(String ambiguousResponse) {
        // Design decision baked into QueryRouter.classify(): only a response
        // containing "CHAT" routes to CHAT — everything else (including garbage,
        // empty, or ambiguous LLM output) defaults to RETRIEVE. This is the
        // safer failure mode: worst case is an unnecessary "no documents found"
        // refusal, not a hallucinated answer from general knowledge.
        setUpRouter();
        mockClassifierResponse(ambiguousResponse);

        QueryRouter.Intent result = queryRouter.classify("some question");

        assertThat(result).isEqualTo(QueryRouter.Intent.RETRIEVE);
    }

    @Test
    void classify_returnsChat_whenResponseContainsChatAsSubstring() {
        // Documents the actual matching rule: it's a simple substring check,
        // not an exact match — any response containing "CHAT" anywhere routes
        // to CHAT, even if phrased ambiguously around it.
        setUpRouter();
        mockClassifierResponse("retrieve or chat, hard to say");

        QueryRouter.Intent result = queryRouter.classify("some question");

        assertThat(result).isEqualTo(QueryRouter.Intent.CHAT);
    }

    @Test
    void classify_sendsQuestionTextWithinThePrompt() {
        setUpRouter();
        mockClassifierResponse("CHAT");

        String question = "What's the weather like today?";
        queryRouter.classify(question);

        org.mockito.ArgumentCaptor<String> promptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(requestSpec).user(promptCaptor.capture());

        assertThat(promptCaptor.getValue()).contains(question);
    }
}