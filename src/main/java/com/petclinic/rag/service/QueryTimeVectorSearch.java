package com.petclinic.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class QueryTimeVectorSearch {

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel queryEmbeddingModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QueryTimeVectorSearch(JdbcTemplate jdbcTemplate,
                                 @Qualifier("queryEmbeddingModel") EmbeddingModel queryEmbeddingModel) {
        this.jdbcTemplate = jdbcTemplate;
        this.queryEmbeddingModel = queryEmbeddingModel;
    }

    public List<Document> similaritySearch(String query, int topK, Double similarityThreshold) {
        float[] queryVector = queryEmbeddingModel.embed(
                Document.builder().text(query).build());
        PGvector pgVector = new PGvector(queryVector);

        String sql = """
                SELECT id, content, metadata, 1 - (embedding <=> ?) AS score
                FROM vector_store
                ORDER BY embedding <=> ?
                LIMIT ?
                """;

        List<Document> results = jdbcTemplate.query(sql,
                (rs, rowNum) -> {
                    String id = rs.getString("id");
                    String content = rs.getString("content");
                    String metadataJson = rs.getString("metadata");
                    double score = rs.getDouble("score");

                    Map<String, Object> metadata;
                    try {
                        metadata = metadataJson == null
                                ? Map.of()
                                : objectMapper.readValue(metadataJson, Map.class);
                    } catch (Exception e) {
                        metadata = Map.of();
                    }

                    return Document.builder()
                            .id(id)
                            .text(content)
                            .metadata(metadata)
                            .score(score)
                            .build();
                },
                pgVector, pgVector, topK);

        if (similarityThreshold == null) {
            return results;
        }
        return results.stream()
                .filter(d -> d.getScore() != null && d.getScore() >= similarityThreshold)
                .toList();
    }
}