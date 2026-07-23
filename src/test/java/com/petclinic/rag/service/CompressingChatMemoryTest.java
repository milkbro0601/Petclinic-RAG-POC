package com.petclinic.rag.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompressingChatMemoryTest {

    @Mock
    private ChatMemoryRepository repository;

    @Mock
    private ChatClient summarizerChatClient;

    // Mocks for the ChatClient fluent chain: prompt().user(...).call().content()
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;

    private CompressingChatMemory chatMemory;

    private static final String CONVERSATION_ID = "conv-1";

    @BeforeEach
    void setUp() {
        chatMemory = new CompressingChatMemory(repository, summarizerChatClient);
    }

    // --- add() tests ---

    @Test
    void add_appendsNewMessagesToExistingHistory() {
        List<Message> existing = new ArrayList<>(List.of(new UserMessage("hello")));
        List<Message> newMessages = List.of(new AssistantMessage("hi there"));

        when(repository.findByConversationId(CONVERSATION_ID)).thenReturn(existing);

        chatMemory.add(CONVERSATION_ID, newMessages);

        ArgumentCaptor<List<Message>> savedCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(eq(CONVERSATION_ID), savedCaptor.capture());

        List<Message> saved = savedCaptor.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getText()).isEqualTo("hello");
        assertThat(saved.get(1).getText()).isEqualTo("hi there");
    }

    // --- get() tests: below-threshold behavior ---

    @Test
    void get_returnsRawMessagesUnchanged_whenBelowTokenThreshold() {
        List<Message> shortHistory = List.of(
                new UserMessage("hi"),
                new AssistantMessage("hello")
        );
        when(repository.findByConversationId(CONVERSATION_ID)).thenReturn(shortHistory);

        List<Message> result = chatMemory.get(CONVERSATION_ID);

        assertThat(result).isEqualTo(shortHistory);
        verifyNoInteractions(summarizerChatClient);
    }

    @Test
    void get_returnsRawMessagesUnchanged_whenMessageCountAtOrBelowKeepRecent() {
        // Even with a huge token count, if message count <= KEEP_RECENT_MESSAGES (6),
        // no compression should trigger.
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            messages.add(new UserMessage("word ".repeat(2000))); // large token count
        }
        when(repository.findByConversationId(CONVERSATION_ID)).thenReturn(messages);

        List<Message> result = chatMemory.get(CONVERSATION_ID);

        assertThat(result).isEqualTo(messages);
        verifyNoInteractions(summarizerChatClient);
    }

    // --- get() tests: compression triggers ---

    @Test
    void get_triggersCompression_whenOverTokenThresholdAndMessageCount() {
        List<Message> history = buildLargeHistory(10); // 10 messages, well over 6, large token count

        when(repository.findByConversationId(CONVERSATION_ID)).thenReturn(history);
        mockSummarizerResponse("The user's name is Alex and they work in logistics.");

        List<Message> result = chatMemory.get(CONVERSATION_ID);

        // Should be: 1 SystemMessage (summary) + last 6 recent messages
        assertThat(result).hasSize(7);
        assertThat(result.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(result.get(0).getText()).contains("Summary of earlier conversation:");
        assertThat(result.get(0).getText()).contains("Alex");

        // Recent messages should be the last 6 from the original list, unchanged
        List<Message> expectedRecent = history.subList(history.size() - 6, history.size());
        assertThat(result.subList(1, result.size())).isEqualTo(expectedRecent);
    }

    @Test
    void get_summaryPromptInstructsSecondPersonFraming() {
        // Regression test for the bug found this session: summaries phrased in
        // third person caused the LLM to disown the facts as "about someone else."
        List<Message> history = buildLargeHistory(10);
        when(repository.findByConversationId(CONVERSATION_ID)).thenReturn(history);
        mockSummarizerResponse("The user's name is Alex.");

        chatMemory.get(CONVERSATION_ID);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(promptCaptor.capture());

        // Normalize whitespace/newlines so text-block line wrapping doesn't
        // break substring matching.
        String prompt = promptCaptor.getValue().replaceAll("\\s+", " ");

        assertThat(prompt).containsIgnoringCase("about the user");
        assertThat(prompt).containsIgnoringCase("do not write it as a");
    }

    @Test
    void get_doesNotModifyRawStorage_whenCompressionTriggers() {
        // Compression must only affect the read-time view, never the underlying
        // repository — raw history must remain fully intact.
        List<Message> history = buildLargeHistory(10);
        when(repository.findByConversationId(CONVERSATION_ID)).thenReturn(history);
        mockSummarizerResponse("Summary text.");

        chatMemory.get(CONVERSATION_ID);

        verify(repository, never()).saveAll(anyString(), any());
        verify(repository, never()).deleteByConversationId(anyString());
    }

    // --- clear() test ---

    @Test
    void clear_delegatesToRepository() {
        chatMemory.clear(CONVERSATION_ID);

        verify(repository).deleteByConversationId(CONVERSATION_ID);
    }

    // --- helpers ---

    private List<Message> buildLargeHistory(int messageCount) {
        List<Message> messages = new ArrayList<>();
        // Each message padded to comfortably push total token count past 3000
        // when combined across `messageCount` messages.
        String padding = "This is filler content to increase token count. ".repeat(50);
        for (int i = 0; i < messageCount; i++) {
            if (i % 2 == 0) {
                messages.add(new UserMessage("Message " + i + ": " + padding));
            } else {
                messages.add(new AssistantMessage("Reply " + i + ": " + padding));
            }
        }
        return messages;
    }

    private void mockSummarizerResponse(String summaryText) {
        when(summarizerChatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(summaryText);
    }
}