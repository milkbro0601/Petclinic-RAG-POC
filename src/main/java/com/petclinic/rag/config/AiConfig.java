package com.petclinic.rag.config;

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
    // ObjectBox - currently InACTIVE.
    @Bean
    public VectorStore vectorStore(
            @org.springframework.beans.factory.annotation.Qualifier("embeddingModel") EmbeddingModel embeddingModel,
            @Value("${objectbox.db-directory:objectbox-data}") String dbDirectory) {
        return new com.petclinic.rag.service.vectorstore.ObjectBoxVectorStore(embeddingModel, dbDirectory);
    }
    */

    @Bean
    public VectorStore multimodalVectorStore(
            @org.springframework.beans.factory.annotation.Qualifier("multimodalEmbeddingModel") EmbeddingModel multimodalEmbeddingModel) {
        return SimpleVectorStore.builder(multimodalEmbeddingModel).build();
    }
}