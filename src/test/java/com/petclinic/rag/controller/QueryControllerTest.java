package com.petclinic.rag.controller;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.petclinic.rag.dto.QueryRequest;
import com.petclinic.rag.dto.QueryResponse;
import com.petclinic.rag.service.RagQueryService;

@ExtendWith(MockitoExtension.class)
class QueryControllerTest {

    @Mock
    private RagQueryService ragQueryService;

    @InjectMocks
    private QueryController queryController;

    @Test
    void query_returnsServiceResponse() {
        QueryRequest request = new QueryRequest("What is the policy?");
        QueryResponse expected = new QueryResponse("Use the guide", List.of("guide.txt"));

        when(ragQueryService.answer("What is the policy?")).thenReturn(expected);

        QueryResponse response = queryController.query(request);

        assertEquals(expected, response);
    }
}
