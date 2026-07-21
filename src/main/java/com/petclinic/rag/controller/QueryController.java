package com.petclinic.rag.controller;

import com.petclinic.rag.dto.QueryRequest;
import com.petclinic.rag.dto.QueryResponse;
import com.petclinic.rag.service.RagQueryService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QueryController {

    private final RagQueryService ragQueryService;

    public QueryController(RagQueryService ragQueryService) {
        this.ragQueryService = ragQueryService;
    }

    @PostMapping("/api/query")
    public QueryResponse query(@RequestBody QueryRequest request) {
        return ragQueryService.answer(request.question(), request.conversationId());
    }
}