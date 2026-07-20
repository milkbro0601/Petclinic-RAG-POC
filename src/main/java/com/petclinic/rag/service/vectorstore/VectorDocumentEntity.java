package com.petclinic.rag.service.vectorstore;

import io.objectbox.annotation.Entity;
import io.objectbox.annotation.VectorDistanceType;
import io.objectbox.annotation.HnswIndex;
import io.objectbox.annotation.Id;
import io.objectbox.annotation.Index;

@Entity
public class VectorDocumentEntity {

    @Id
    public long id;

    @Index
    public String docId;

    public String text;

    public String source;

    @HnswIndex(dimensions = 2048, distanceType = VectorDistanceType.COSINE)
    public float[] embedding;
}