package com.petclinic.rag.service;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class CompressingChatMemory implements ChatMemory {

    private static final int TOKEN_THRESHOLD = 3000;
    private static final int KEEP_RECENT_MESSAGES = 6;

    private final ChatMemoryRepository repository;
    private final ChatClient summarizerChatClient;
    private final Encoding tokenEncoding;

    public CompressingChatMemory(ChatMemoryRepository repository, ChatClient summarizerChatClient) {
        this.repository = repository;
        this.summarizerChatClient = summarizerChatClient;
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.tokenEncoding = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        List<Message> existing = new ArrayList<>(repository.findByConversationId(conversationId));
        existing.addAll(messages);
        repository.saveAll(conversationId, existing);
    }

    @Override
    public List<Message> get(String conversationId) {
        List<Message> allMessages = repository.findByConversationId(conversationId);
        int totalTokens = estimateTokens(allMessages);

        if (totalTokens <= TOKEN_THRESHOLD || allMessages.size() <= KEEP_RECENT_MESSAGES) {
            return allMessages;
        }

        int splitIndex = allMessages.size() - KEEP_RECENT_MESSAGES;
        List<Message> older = allMessages.subList(0, splitIndex);
        List<Message> recent = allMessages.subList(splitIndex, allMessages.size());

        String summary = summarize(older);

        List<Message> result = new ArrayList<>();
        result.add(new SystemMessage("Summary of earlier conversation: " + summary));
        result.addAll(recent);
        return result;
    }

    @Override
    public void clear(String conversationId) {
        repository.deleteByConversationId(conversationId);
    }

    private int estimateTokens(List<Message> messages) {
        String combined = messages.stream()
                .map(Message::getText)
                .collect(Collectors.joining("\n"));
        return tokenEncoding.countTokens(combined);
    }

    private String summarize(List<Message> messages) {
        String transcript = messages.stream()
                .map(m -> m.getMessageType() + ": " + m.getText())
                .collect(Collectors.joining("\n"));

        String prompt = """
                Summarize the following conversation history concisely, in 3-5 sentences.

                Write the summary from the perspective of describing facts ABOUT THE
                USER you (the assistant) are talking to. Use phrasing like "The user's
                name is ___" or "The user mentioned ___" — do NOT write it as a
                third-person story about someone else, since it will be read by you,
                the same assistant, as context about the person currently chatting
                with you.

                Preserve any specific facts, preferences, names, or decisions the user
                mentioned — these matter more than conversational pleasantries.

                Conversation:
                %s
                """.formatted(transcript);

        return summarizerChatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }
}