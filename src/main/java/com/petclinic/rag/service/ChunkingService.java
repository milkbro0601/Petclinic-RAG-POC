package com.petclinic.rag.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ChunkingService {

    private final TokenTextSplitter splitter = TokenTextSplitter.builder().build();

    public List<Document> chunk(Document document) {
        return splitter.apply(List.of(document));
    }
}