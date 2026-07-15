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

/**
 * NVIDIA NIM's llama-nemotron-embed model is "asymmetric" and requires a
 * non-standard "input_type" field ("query" or "passage") that Spring AI's
 * typed OpenAiEmbeddingOptions has no way to send. This bean calls NVIDIA
 * directly instead, so we can include that field.
 *
 * For now this always sends input_type="passage", which is correct for
 * storing documents. A future improvement would use "query" specifically
 * when embedding the user's search question at retrieval time.
 */
public class NvidiaEmbeddingModel implements EmbeddingModel {

    private final RestClient restClient;
    private final String model;

    public NvidiaEmbeddingModel(String baseUrl, String apiKey, String model) {
        this.model = model;
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
                "input_type", "passage",
                "encoding_format", "float"
        );

        NvidiaEmbeddingApiResponse apiResponse = restClient.post()
                .uri("/v1/embeddings")
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