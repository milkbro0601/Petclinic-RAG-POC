package com.petclinic.rag.dto;

public record QueryRequest(String question, String conversationId) {
}