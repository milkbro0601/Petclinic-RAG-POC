package com.petclinic.rag.service.extraction;

import java.io.InputStream;

public interface TextExtractor {

    String extract(InputStream inputStream, String filename);

    boolean supports(String filename);
}