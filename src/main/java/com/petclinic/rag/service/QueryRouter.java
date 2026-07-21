package com.petclinic.rag.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class QueryRouter {

    private final ChatClient classifierChatClient;

    public QueryRouter(@Qualifier("classifierChatClient") ChatClient classifierChatClient) {
        this.classifierChatClient = classifierChatClient;
    }

    public enum Intent {
        RETRIEVE,   // needs document search
        CHAT        // conversational
    }

    public Intent classify(String message) {
        String prompt = """
                Classify the following user message into exactly one category.
                Respond with ONLY the single word RETRIEVE or CHAT — no punctuation, no explanation.

                RETRIEVE: the message is asking a question that likely needs to be
                answered by searching uploaded documents (facts, policies, content
                specific to what was uploaded).

                CHAT: the message is conversational — greetings, thanks, small talk,
                or a question clearly answerable from prior conversation alone
                without needing to search documents.

                Message: "%s"
                """.formatted(message);

        String rawResponse = classifierChatClient.prompt()
                .user(prompt)
                .call()
                .content();

        String response = rawResponse.trim().toUpperCase();

        return response.contains("CHAT") ? Intent.CHAT : Intent.RETRIEVE;
    }
}