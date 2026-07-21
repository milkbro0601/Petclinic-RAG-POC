package com.petclinic.rag.config;

import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class AiConfig {

    @Bean
    @Primary
    public EmbeddingModel embeddingModel(
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.embedding.options.model}") String model) {
        return new NvidiaEmbeddingModel(baseUrl, apiKey, model);
    }

    @Bean
    public EmbeddingModel multimodalEmbeddingModel(
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${nvidia.multimodal-embedding-model}") String model) {
        return new NvidiaEmbeddingModel(baseUrl, apiKey, model);
    }

    @Bean
    public EmbeddingModel queryEmbeddingModel(
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.embedding.options.model}") String model) {
        return new NvidiaEmbeddingModel(baseUrl, apiKey, model, "query");
    }

    /*
    // PGvector — currently INACTIVE. To switch back to PGvector: comment out the
    // vectorStore bean below, remove/comment the spring.autoconfigure.exclude line
    // in application.properties, and make sure the pgvector Docker container is
    // running + spring.datasource.* properties are uncommented.
    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
    */

    /*
    // JVector — currently INACTIVE. Embedded, in-memory graph index, no external
    // infra required, but no persistence across restarts (confirmed by testing).
    //
    // To switch back to JVector: comment out the ObjectBox bean below, uncomment
    // this bean, and re-add the spring.autoconfigure.exclude line for PGvector in
    // application.properties (Postgres DataSource still needs excluding too if
    // Postgres isn't running).
    @Bean
    public VectorStore vectorStore(
            @org.springframework.beans.factory.annotation.Qualifier("embeddingModel") EmbeddingModel embeddingModel,
            @Value("${nvidia.embedding.dimension:2048}") int dimension) {
        return new com.petclinic.rag.service.vectorstore.JVectorStore(embeddingModel, dimension);
    }
    */

    /*
    // ObjectBox — currently INACTIVE. Was the last store demoed for Task 1's
    // comparison; the final recommendation and active choice is PGvector (see
    // README). To reactivate ObjectBox: uncomment this bean, add back the
    // spring.autoconfigure.exclude line for PGvector in application.properties,
    // and stop the Postgres container.
    //
    // Embedded NoSQL object database with a built-in HNSW vector index. Genuine
    // disk persistence confirmed via standalone smoke test and live app restart.
    @Bean
    public VectorStore vectorStore(
            @org.springframework.beans.factory.annotation.Qualifier("embeddingModel") EmbeddingModel embeddingModel,
            @Value("${objectbox.db-directory:objectbox-data}") String dbDirectory) {
        return new com.petclinic.rag.service.vectorstore.ObjectBoxVectorStore(embeddingModel, dbDirectory);
    }
    */

    @Bean
    public org.springframework.ai.chat.client.ChatClient summarizerChatClient(
            org.springframework.ai.chat.model.ChatModel chatModel) {
        return org.springframework.ai.chat.client.ChatClient.builder(chatModel)
                .defaultOptions(ChatOptions.builder()
                        .temperature(0.3)
                        .build().mutate())
                .build();
    }

    @Bean
    public org.springframework.ai.chat.memory.ChatMemory chatMemory(
            org.springframework.ai.chat.memory.ChatMemoryRepository chatMemoryRepository,
            @org.springframework.beans.factory.annotation.Qualifier("summarizerChatClient")
            org.springframework.ai.chat.client.ChatClient summarizerChatClient) {
        return new com.petclinic.rag.service.CompressingChatMemory(chatMemoryRepository, summarizerChatClient);
    }

    @Bean
    public org.springframework.ai.chat.client.ChatClient.Builder rewriteChatClientBuilder(
            org.springframework.ai.chat.model.ChatModel chatModel) {
        return org.springframework.ai.chat.client.ChatClient.builder(chatModel)
                .defaultOptions(ChatOptions.builder()
                        .temperature(0.0)
                        .build().mutate());
    }

    @Bean
    public org.springframework.ai.chat.client.ChatClient chatClient(
            org.springframework.ai.chat.model.ChatModel chatModel,
            org.springframework.ai.chat.memory.ChatMemory chatMemory) {
        return org.springframework.ai.chat.client.ChatClient.builder(chatModel)
                .defaultAdvisors(
                        org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    @Bean
    public org.springframework.ai.chat.client.ChatClient classifierChatClient(
            org.springframework.ai.chat.model.ChatModel chatModel) {
        return org.springframework.ai.chat.client.ChatClient.builder(chatModel)
                .defaultOptions(ChatOptions.builder()
                        .temperature(0.0)
                        .build().mutate())
                .build();
    }

    @Bean
    public VectorStore multimodalVectorStore(
            @org.springframework.beans.factory.annotation.Qualifier("multimodalEmbeddingModel") EmbeddingModel multimodalEmbeddingModel) {
        return SimpleVectorStore.builder(multimodalEmbeddingModel).build();
    }
}