package com.petclinic.rag.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class FileDeduplicationService {

    private final JdbcTemplate jdbcTemplate;

    public FileDeduplicationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String computeHash(byte[] fileBytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(fileBytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 is guaranteed present on any standard JVM
            throw new IllegalStateException("MD5 algorithm not available", e);
        }
    }

    public boolean isAlreadyIngested(String fileHash, String ingestionType) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ingested_files WHERE file_hash = ? AND ingestion_type = ?",
                Integer.class, fileHash, ingestionType);
        return count != null && count > 0;
    }

    public void markIngested(String fileHash, String filename, String ingestionType) {
        jdbcTemplate.update(
                "INSERT INTO ingested_files (file_hash, ingestion_type, filename) VALUES (?, ?, ?)",
                fileHash, ingestionType, filename);
    }
}