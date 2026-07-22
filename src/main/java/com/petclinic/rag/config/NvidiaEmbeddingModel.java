package com.petclinic.rag.config;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NvidiaEmbeddingModel implements EmbeddingModel {

    private final RestClient restClient;
    private final String model;
    private final String inputType;
    private final List<String> modality;

    public NvidiaEmbeddingModel(String baseUrl, String apiKey, String model) {
        this(baseUrl, apiKey, model, "passage", null);
    }

    public NvidiaEmbeddingModel(String baseUrl, String apiKey, String model, String inputType) {
        this(baseUrl, apiKey, model, inputType, null);
    }

    public NvidiaEmbeddingModel(String baseUrl, String apiKey, String model, String inputType, List<String> modality) {
        this.model = model;
        this.inputType = inputType;
        this.modality = modality;

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .build()
        );
        requestFactory.setReadTimeout(Duration.ofSeconds(180));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> inputs = request.getInstructions();

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("input", inputs);
        body.put("input_type", inputType);
        body.put("encoding_format", "float");
        if (modality != null) {
            body.put("modality", modality);
            body.put("truncate", "NONE");
        }

        int maxAttempts = 3;
        long backoffMillis = 2000; // wait 2s before retrying
        RuntimeException lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long start = System.currentTimeMillis();
            try {
                System.out.println("NVIDIA embed call attempt " + attempt + "/" + maxAttempts);

                NvidiaEmbeddingApiResponse apiResponse = restClient.post()
                        .uri("/embeddings")
                        .body(body)
                        .retrieve()
                        .body(NvidiaEmbeddingApiResponse.class);

                long elapsed = System.currentTimeMillis() - start;
                System.out.println("NVIDIA embed call SUCCEEDED after " + elapsed + "ms on attempt " + attempt);

                List<Embedding> embeddings = apiResponse.data().stream()
                        .map(d -> new Embedding(d.embedding(), d.index()))
                        .toList();

                return new EmbeddingResponse(embeddings, new EmbeddingResponseMetadata());

            } catch (HttpServerErrorException e) {
                // 5xx errors (502, 503, 504) are retryable — likely transient NVIDIA-side issues
                long elapsed = System.currentTimeMillis() - start;
                System.out.println("NVIDIA embed call FAILED (server error) after " + elapsed
                        + "ms on attempt " + attempt + ": " + e.getMessage());
                lastException = e;

            } catch (ResourceAccessException e) {
                // Connection/read timeouts on our own client side are also retryable
                long elapsed = System.currentTimeMillis() - start;
                System.out.println("NVIDIA embed call FAILED (I/O error) after " + elapsed
                        + "ms on attempt " + attempt + ": " + e.getMessage());
                lastException = e;

            } catch (HttpClientErrorException e) {
                // 4xx errors (e.g. 400 bad request) will not succeed on retry — fail fast
                System.out.println("NVIDIA embed call FAILED (client error, not retrying): " + e.getMessage());
                throw e;
            }

            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(backoffMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted", ie);
                }
            }
        }

        throw new RuntimeException("NVIDIA embedding call failed after " + maxAttempts + " attempts", lastException);
    }

    @Override
    public float[] embed(Document document) {
        return call(new EmbeddingRequest(List.of(document.getText()), null))
                .getResult().getOutput();
    }

    @Override
    public int dimensions() {
        return 2048;
    }

    record NvidiaEmbeddingApiResponse(List<NvidiaEmbeddingData> data) {}
    record NvidiaEmbeddingData(float[] embedding, int index) {}
}