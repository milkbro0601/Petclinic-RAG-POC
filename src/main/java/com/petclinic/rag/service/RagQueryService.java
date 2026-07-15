package com.petclinic.rag.service;

import com.petclinic.rag.dto.QueryResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class RagQueryService {

    private static final int TOP_K = 4;

    private final VectorStore vectorStore;
    private final ChatModel chatModel;

    public RagQueryService(VectorStore vectorStore, ChatModel chatModel) {
        this.vectorStore = vectorStore;
        this.chatModel = chatModel;
    }

    public QueryResponse answer(String question) {
        SearchRequest searchRequest = SearchRequest.builder()
                .query(question)
                .topK(TOP_K)
                .build();

        List<Document> relevantChunks = vectorStore.similaritySearch(searchRequest);

        if (relevantChunks.isEmpty()) {
            return new QueryResponse(
                    "I don't have any relevant documents to answer that question. Try uploading some first.",
                    List.of()
            );
        }

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

        String answer = chatModel.call(prompt);

        List<String> sources = relevantChunks.stream()
                .map(doc -> (String) doc.getMetadata().getOrDefault("source", "unknown"))
                .distinct()
                .toList();

        return new QueryResponse(answer, sources);
    }
}