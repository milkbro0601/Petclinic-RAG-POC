package com.petclinic.rag.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class QueryRewriter {

    private final CompressionQueryTransformer queryTransformer;
    private final ChatMemory chatMemory;

    public QueryRewriter(ChatClient.Builder rewriteChatClientBuilder, ChatMemory chatMemory) {
        this.queryTransformer = CompressionQueryTransformer.builder()
                .chatClientBuilder(rewriteChatClientBuilder)
                .build();
        this.chatMemory = chatMemory;
    }

    public String rewrite(String question, String conversationId) {
        List<Message> history = chatMemory.get(conversationId);
        if (history.isEmpty()) {
            return question; // nothing to rewrite against, skip the extra LLM call
        }

        Query query = Query.builder()
                .text(question)
                .history(history)
                .build();

        return queryTransformer.transform(query).text();
    }
}