package com.petclinic.rag.service;

import com.petclinic.rag.dto.QueryResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class RagQueryService {

    private static final int TOP_K = 4;
    private static final double RELEVANCE_THRESHOLD = 0.2;

    private final QueryTimeVectorSearch queryTimeVectorSearch;
    private final ChatClient chatClient;
    private final QueryRouter queryRouter;

    public RagQueryService(QueryTimeVectorSearch queryTimeVectorSearch, ChatClient chatClient, QueryRouter queryRouter) {
        this.queryTimeVectorSearch = queryTimeVectorSearch;
        this.chatClient = chatClient;
        this.queryRouter = queryRouter;
    }

    public QueryResponse answer(String question, String conversationId) {
        List<Document> relevantChunks = queryTimeVectorSearch.similaritySearch(question, TOP_K, RELEVANCE_THRESHOLD);

        if (!relevantChunks.isEmpty()) {
            return answerFromDocuments(question, conversationId, relevantChunks);
        }

        QueryRouter.Intent intent = queryRouter.classify(question);
        if (intent == QueryRouter.Intent.RETRIEVE) {
            return new QueryResponse(
                    "I don't have any relevant documents to answer that question. Try uploading some first.",
                    List.of()
            );
        }
        return answerAsChat(question, conversationId);
    }

    private QueryResponse answerAsChat(String question, String conversationId) {
        String answer = chatClient.prompt()
                .user(question)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
        return new QueryResponse(answer, List.of());
    }

    private QueryResponse answerFromDocuments(String question, String conversationId, List<Document> relevantChunks) {
        String context = relevantChunks.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        String prompt = """
                Answer the question using ONLY the context below.
                If the context doesn't contain enough information to answer, say so honestly
                instead of making something up.

                Context:
                %s

                Question: %s
                """.formatted(context, question);

        String answer = chatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();

        List<String> sources = relevantChunks.stream()
                .map(doc -> (String) doc.getMetadata().getOrDefault("source", "unknown"))
                .distinct()
                .toList();

        return new QueryResponse(answer, sources);
    }
}