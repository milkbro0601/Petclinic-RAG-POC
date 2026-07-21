package com.petclinic.rag.config;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

public class NvidiaEmbeddingModel implements EmbeddingModel {

    private final RestClient restClient;
    private final String model;
    private final String inputType;

    public NvidiaEmbeddingModel(String baseUrl, String apiKey, String model) {
        this(baseUrl, apiKey, model, "passage");
    }

    public NvidiaEmbeddingModel(String baseUrl, String apiKey, String model, String inputType) {
        this.model = model;
        this.inputType = inputType;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> inputs = request.getInstructions();

        Map<String, Object> body = Map.of(
                "model", model,
                "input", inputs,
                "input_type", inputType,
                "encoding_format", "float"
        );

        NvidiaEmbeddingApiResponse apiResponse = restClient.post()
                .uri("/embeddings")
                .body(body)
                .retrieve()
                .body(NvidiaEmbeddingApiResponse.class);

        List<Embedding> embeddings = apiResponse.data().stream()
                .map(d -> new Embedding(d.embedding(), d.index()))
                .toList();

        return new EmbeddingResponse(embeddings, new EmbeddingResponseMetadata());
    }

    @Override
    public float[] embed(Document document) {
        return call(new EmbeddingRequest(List.of(document.getText()), null))
                .getResult().getOutput();
    }

    record NvidiaEmbeddingApiResponse(List<NvidiaEmbeddingData> data) {}
    record NvidiaEmbeddingData(float[] embedding, int index) {}
}