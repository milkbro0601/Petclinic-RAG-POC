package com.petclinic.rag.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileDeduplicationServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private FileDeduplicationService deduplicationService;

    @BeforeEach
    void setUp() {
        deduplicationService = new FileDeduplicationService(jdbcTemplate);
    }

    @Test
    void computeHash_producesConsistentHashForSameContent() {
        byte[] content = "hello world".getBytes();

        String hash1 = deduplicationService.computeHash(content);
        String hash2 = deduplicationService.computeHash(content);

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void computeHash_producesDifferentHashesForDifferentContent() {
        String hashA = deduplicationService.computeHash("content A".getBytes());
        String hashB = deduplicationService.computeHash("content B".getBytes());

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    void computeHash_producesKnownMd5ValueForKnownInput() {
        String hash = deduplicationService.computeHash("hello world".getBytes());

        assertThat(hash).isEqualTo("5eb63bbbe01eeed093cb22bb8f5acdc3");
    }

    @Test
    void computeHash_returnsLowercase32CharacterHexString() {
        String hash = deduplicationService.computeHash("some file content".getBytes());

        assertThat(hash).hasSize(32);
        assertThat(hash).matches("[0-9a-f]{32}");
    }

    @Test
    void computeHash_handlesEmptyByteArray() {
        String hash = deduplicationService.computeHash(new byte[0]);

        assertThat(hash).isEqualTo("d41d8cd98f00b204e9800998ecf8427e");
    }

    @Test
    void isAlreadyIngested_returnsTrue_whenCountIsGreaterThanZero() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("abc123"), eq("text")))
                .thenReturn(1);

        boolean result = deduplicationService.isAlreadyIngested("abc123", "text");

        assertThat(result).isTrue();
    }

    @Test
    void isAlreadyIngested_returnsFalse_whenCountIsZero() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("abc123"), eq("text")))
                .thenReturn(0);

        boolean result = deduplicationService.isAlreadyIngested("abc123", "text");

        assertThat(result).isFalse();
    }

    @Test
    void isAlreadyIngested_returnsFalse_whenCountIsNull() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("abc123"), eq("text")))
                .thenReturn(null);

        boolean result = deduplicationService.isAlreadyIngested("abc123", "text");

        assertThat(result).isFalse();
    }

    @Test
    void isAlreadyIngested_queriesWithBothHashAndIngestionType() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any()))
                .thenReturn(0);

        deduplicationService.isAlreadyIngested("samehash123", "multimodal-image");

        verify(jdbcTemplate).queryForObject(
                contains("file_hash = ?"), eq(Integer.class), eq("samehash123"), eq("multimodal-image"));
    }

    @Test
    void markIngested_insertsHashFilenameAndType() {
        deduplicationService.markIngested("hash123", "test.txt", "text");

        verify(jdbcTemplate).update(
                contains("INSERT INTO ingested_files"),
                eq("hash123"), eq("text"), eq("test.txt"));
    }
}