# PetClinic RAG PoC

A backend-only Retrieval-Augmented Generation (RAG) proof of concept built with Spring Boot. Ingests documents (TXT, DOCX, images), generates embeddings, stores them in a vector database, and answers user questions through semantic retrieval + LLM generation.

Built as a learning project and team task, covering two objectives:
- **Task 1**: Research and evaluate a few vector database implementations (Java/Node.js ecosystem)
- **Task 2**: Build a working program that ingests files and loads them into a vector DB

---

## Tech Stack

| Component | Choice | Why |
|---|---|---|
| Language / Framework | Java 21, Spring Boot 4.1, Spring AI 2.0 | Team's existing stack |
| LLM + Embedding provider | NVIDIA NIM (OpenAI-compatible API) | Free tier, no billing risk, supports both text and true multimodal image embeddings |
| Chat model | `meta/llama-3.1-8b-instruct` | Fast, reliable — see [Key Learnings](#key-learnings--debugging-log) for why the 70B model was dropped |
| Text embedding model | `nvidia/llama-nemotron-embed-1b-v2` | Handles asymmetric query/passage embeddings |
| Multimodal (image) embedding model | `nvidia/llama-nemotron-embed-vl-1b-v2` | Embeds images directly, no OCR needed |
| Vector store (current) | `SimpleVectorStore` (Spring AI, in-memory) | Zero-setup baseline used to build and validate the pipeline; **not the final Task 1 choice** — see [Task 1 Status](#task-1-status) |
| Text extraction | Apache Tika (`spring-ai-tika-document-reader`) | Handles DOCX/TXT parsing |
| Image OCR | Tesseract via Tess4J | Extracts literal text from images |
| Text splitting | Spring AI `TokenTextSplitter` | Token-aware chunking (not naive character count) |

---

## Architecture

### Ingestion pipeline
```
Upload file → Extract text (Tika / OCR) → Split into chunks → Generate embeddings → Store in vector DB
```

### Retrieval pipeline
```
User question → Embed question → Similarity search (top-k) → Build prompt with retrieved chunks → Call LLM → Return grounded answer
```

### Two image-handling strategies (for Task 1 comparison)
1. **OCR** (`/api/documents`) — extracts literal text from the image via Tesseract, then embeds the text like any other document. Works well for screenshots, scanned text, documents.
2. **Multimodal embedding** (`/api/documents/multimodal-image`) — base64-encodes the image and embeds it directly via NVIDIA's vision-language embedding model, no text extraction step. Works even on images with no literal text (photos, charts, diagrams).

### Code structure
```
com.petclinic.rag
├── controller/
│   ├── HealthController          — health check endpoint
│   ├── IngestController          — POST /api/documents (TXT/DOCX/image via OCR)
│   ├── MultimodalIngestController — POST /api/documents/multimodal-image
│   └── QueryController           — POST /api/query
├── service/
│   ├── extraction/
│   │   ├── TextExtractor          — common interface (Strategy pattern)
│   │   ├── TxtExtractor
│   │   ├── DocxExtractor          — wraps Apache Tika
│   │   ├── ImageOcrExtractor      — wraps Tesseract/Tess4J
│   │   └── ExtractorFactory       — picks the right extractor by filename
│   ├── ChunkingService            — wraps TokenTextSplitter
│   ├── MultimodalImageService     — handles the image-embedding-only path
│   └── RagQueryService            — orchestrates retrieval + LLM answer
├── dto/
│   ├── QueryRequest
│   └── QueryResponse
└── config/
    ├── AiConfig                   — embedding model + vector store beans
    └── NvidiaEmbeddingModel       — custom EmbeddingModel (see Key Learnings)
```

---

## Task 2 Progress — what's been built and verified

| Feature | Status | How it was verified |
|---|---|---|
| Spring Boot skeleton | ✅ Done | App starts cleanly on port 8081 |
| NVIDIA embedding + chat model config | ✅ Done | Confirmed via curl against NVIDIA's API directly, then via app |
| Vector store wiring | ✅ Done | `/api/test-embedding` round-trip (store + similarity search) succeeded |
| TXT extraction | ✅ Done | Unit tested (no Spring context needed) |
| DOCX extraction | ✅ Done | Unit tested via `ExtractorFactory` |
| File upload endpoint (`/api/documents`) | ✅ Done | Uploaded real TXT files, confirmed storage |
| Chunking | ✅ Done | Tested at 3 sizes: short text (1 chunk, correctly not split), medium text (~2,225 chars, still 1 chunk — correctly under the 800-token threshold), long text (8,366 chars → 2 chunks, correctly split) |
| Query endpoint (`/api/query`) | ✅ Done | Full round trip: uploaded a RAG-related paragraph, asked "What is retrieval augmented generation?", got a correct grounded answer citing the right source file |
| Image OCR extraction | ✅ Done | Generated a test image with known text, uploaded it, OCR extracted 74 characters matching the source sentence, query correctly retrieved and answered from it |
| Multimodal image embedding | ✅ Done | Verified via curl against NVIDIA directly first, then confirmed working through the app's `/api/documents/multimodal-image` endpoint |

**Summary**: both pipelines (ingestion and retrieval) are functionally complete and tested end-to-end across all required file types (TXT, DOCX, PNG/JPG via two different strategies).

---

## Task 1 Status

**Not yet started.** Everything above was built against `SimpleVectorStore` — an in-memory, zero-setup vector store — deliberately chosen to build and validate the application logic quickly without fighting infrastructure setup while debugging embedding/chat model configuration.

**Next steps for Task 1**:
1. Set up and test 2-3 candidate vector DBs from the team's list (JVector, PGvector, H2, ObjectBox)
2. Swap each in as the `VectorStore` bean (same interface — ingestion/query code doesn't change)
3. Re-run the existing upload/query tests against each
4. Document comparison: setup effort, API ergonomics, persistence, performance
5. `SimpleVectorStore`'s own limitation (in-memory, wiped on every restart) is itself a useful data point for this comparison

---

## Key Learnings / Debugging Log

Documenting these because each one was a real, non-obvious issue worth remembering:

1. **`<dependencyManagement>` vs `<dependencies>`** — declaring a dependency in `dependencyManagement` only sets its version; it doesn't add it to the classpath. Must also list it under `dependencies`.

2. **Spring AI 2.0 switched to the official OpenAI Java SDK** under the hood, which follows a different `base-url` convention than the older Spring AI client — the URL must already include `/v1` (e.g. `https://integrate.api.nvidia.com/v1`), whereas the old client appended it automatically. Got bitten by this twice (once for embeddings, once for chat) after a property reverted.

3. **`SimpleVectorStore` and `spring-ai-vector-store`** are a separate Maven artifact from `spring-ai-starter-model-openai` — the chat/embedding starter doesn't pull it in automatically.

4. **NVIDIA's `llama-nemotron-embed` models are "asymmetric"** and require a non-standard `input_type` field (`"query"` or `"passage"`) that Spring AI's typed `OpenAiEmbeddingOptions` has no way to send. Solved by writing a small custom `EmbeddingModel` that calls NVIDIA directly via `RestClient`, giving full control over the request body.

5. **Model size vs. latency tradeoff is real**: `meta/llama-3.3-70b-instruct` timed out after 60+ seconds on NVIDIA's free tier (likely a cold-start/overload issue), while `meta/llama-3.1-8b-instruct` responded in under 100ms. Switched to the smaller model for a responsive interactive demo — a deliberate, documented tradeoff rather than an accident.

6. **Tesseract/Tess4J needs the native library path set explicitly** on macOS — JNA doesn't search Homebrew's `/opt/homebrew/lib` by default, requiring a `-Djna.library.path` VM option.

7. **`TokenTextSplitter`'s no-arg constructor is deprecated**; `TokenTextSplitter.builder().build()` (with nothing set) is the current recommended way to get the same defaults, and avoids a separate known bug where setting *some* but not *all* builder parameters silently resets the others to invalid defaults.

8. **Chunking only triggers above the token threshold** (default 800 tokens ≈ 3,200 characters) — smaller documents correctly remain as a single chunk, which is expected behavior, not a bug.

9. **`SimpleVectorStore` is in-memory only** — every app restart wipes all previously ingested documents. Must re-upload before querying in a fresh run.

10. **Always verify third-party API contracts with `curl` before writing app code against them** — this caught real issues (wrong request shape, missing fields, wrong URLs) faster than debugging through the full Spring stack trace each time.

---

## Known Simplifications (for a POC, not production)

- Multimodal image documents are stored with their base64 data URI as the "text" field (needed for the embedding call), rather than separately storing the raw image file with a reference — a production system would separate these.
- No deduplication — re-uploading the same file creates duplicate chunks.
- No authentication/authorization on any endpoint.
- Chunking always uses default parameters; not yet tuned per file type.

---

# Vector Store Comparison — Live Demo Runbook
### SimpleVectorStore vs PGvector vs JVector

This is a step-by-step script for demoing all three vector store implementations to your team lead. Each section is self-contained: setup → run → test → expected output → how to switch to the next one.

---

## Before you start: one-time prep

Have three terminal tabs ready:
- **Tab 1**: Docker commands
- **Tab 2**: `application.properties` editing / IntelliJ
- **Tab 3**: curl test commands

---

## PART 1 — SimpleVectorStore (baseline, in-memory, zero setup)

This is your original PoC store — no external infra, Spring AI's own built-in implementation.

### 1.1 Config: enable SimpleVectorStore

In `AiConfig.java`, comment out **both** the PGvector and JVector `vectorStore` beans, and uncomment the SimpleVectorStore one:

```java
@Bean
public VectorStore vectorStore(EmbeddingModel embeddingModel) {
    return SimpleVectorStore.builder(embeddingModel).build();
}
```

### 1.2 Config: application.properties

No `spring.autoconfigure.exclude` needed here (SimpleVectorStore doesn't conflict with anything). Postgres `spring.datasource.*` properties can stay commented out — not needed for this store.

### 1.3 No Docker needed

SimpleVectorStore requires zero external infrastructure. Skip Docker entirely for this demo section.

### 1.4 Run the app

Start `RagApplication` in IntelliJ as normal.

### 1.5 Test — ingest

```bash
curl -X POST http://localhost:8081/api/documents \
  -F "file=@/Users/fwd/Documents/Petclinic-RAG-POC/test.txt"
```
**Expected:** `{"filename":"test.txt","status":"stored","extractedCharacters":8367,"chunksStored":2}`

### 1.6 Test — query

```bash
curl -X POST http://localhost:8081/api/query \
  -H "Content-Type: application/json" \
  -d '{"question": "What is retrieval augmented generation?"}'
```
**Expected:** grounded answer with `"sources":["test.txt"]`

### 1.7 Test — restart persistence

Stop the app in IntelliJ, restart it, **without re-uploading test.txt**, run the same query curl again.

**Expected result: EMPTY** — `"answer":"I don't have any relevant documents..."`, `"sources":[]`

**Key talking point:** SimpleVectorStore is in-memory only, by Spring AI's own design (their docs explicitly say "not for production use"). This is your original placeholder — confirms why Task 1 exists at all.

---

## PART 2 — PGvector (official Spring AI support, external Postgres)

### 2.1 Start Postgres in Docker

```bash
docker start pgvector-test
```
(If the container doesn't exist yet, create it first — see Appendix A.)

Verify it's running:
```bash
docker ps
```
Should show `pgvector-test` as `Up`.

### 2.2 Config: application.properties

Uncomment the Postgres datasource block:
```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/postgres
spring.datasource.username=postgres
spring.datasource.password=postgres

spring.ai.vectorstore.pgvector.initialize-schema=true
spring.ai.vectorstore.pgvector.index-type=HNSW
spring.ai.vectorstore.pgvector.distance-type=COSINE_DISTANCE
spring.ai.vectorstore.pgvector.dimensions=2048
```

Comment out (or remove) the `spring.autoconfigure.exclude` line entirely — PGvector's auto-configuration needs to run.

### 2.3 Config: AiConfig.java

Comment out **both** the SimpleVectorStore bean and the JVector bean — leave **no explicit `vectorStore` bean at all**. This lets Spring AI's `PgVectorStoreAutoConfiguration` auto-create the `PgVectorStore` bean.

### 2.4 Run the app

Start `RagApplication` in IntelliJ.

### 2.5 Test — ingest

```bash
curl -X POST http://localhost:8081/api/documents \
  -F "file=@/Users/fwd/Documents/Petclinic-RAG-POC/test.txt"
```
**Expected:** same shape response, `chunksStored: 2`.

### 2.6 Verify data actually landed in Postgres (impressive live demo moment)

```bash
docker exec -it pgvector-test psql -U postgres -c "SELECT count(*) FROM vector_store;"
```
**Expected:** count matches your chunk count (2).

### 2.7 Test — query

```bash
curl -X POST http://localhost:8081/api/query \
  -H "Content-Type: application/json" \
  -d '{"question": "What is retrieval augmented generation?"}'
```
**Expected:** grounded answer with `"sources":["test.txt"]`

### 2.8 Test — restart persistence (the big differentiator)

Stop the Spring Boot app, restart it, **without re-uploading test.txt**, run the query curl again.

**Expected result: STILL WORKS** — same grounded answer, same source. This is the payoff moment of the whole demo.

### 2.9 Test — multimodal regression check

```bash
curl -X POST http://localhost:8081/api/documents/multimodal-image \
  -F "file=@/Users/fwd/Documents/Petclinic-RAG-POC/ocr-test.png"
```
**Expected:** `{"strategy":"multimodal-embedding","status":"stored",...}` — confirms the separate `multimodalVectorStore` bean is unaffected.

---

## PART 3 — JVector (embedded, no infra, custom-built)

### 3.1 Stop Postgres (to genuinely prove "zero external infra")

```bash
docker stop pgvector-test
```
This step matters — leaving Postgres running would hide the fact that JVector doesn't need it.

### 3.2 Config: application.properties

Comment OUT the Postgres datasource block again:
```properties
# spring.datasource.url=jdbc:postgresql://localhost:5432/postgres
# spring.datasource.username=postgres
# spring.datasource.password=postgres
```

Add the exclude line (prevents PGvector AND the JDBC/DataSource auto-configuration from trying to connect):
```properties
spring.autoconfigure.exclude=org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration,org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.data.jdbc.autoconfigure.DataJdbcRepositoriesAutoConfiguration
```

(Optional, for full JVector SIMD performance — add as a VM option in IntelliJ's run config: `--add-modules jdk.incubator.vector`)

### 3.3 Config: AiConfig.java

Comment out the SimpleVectorStore bean and the PGvector bean. Uncomment/keep active the JVector bean:
```java
@Bean
public VectorStore vectorStore(
        @Qualifier("embeddingModel") EmbeddingModel embeddingModel,
        @Value("${nvidia.embedding.dimension:2048}") int dimension) {
    return new com.petclinic.rag.service.vectorstore.JVectorStore(embeddingModel, dimension);
}
```

### 3.4 Run the app

Start `RagApplication` in IntelliJ. **Confirm it starts successfully with Postgres fully stopped** — this itself is the "zero external infra" proof.

### 3.5 Test — ingest

```bash
curl -X POST http://localhost:8081/api/documents \
  -F "file=@/Users/fwd/Documents/Petclinic-RAG-POC/test.txt"
```
**Expected:** same shape response, `chunksStored: 2`.

### 3.6 Test — query

```bash
curl -X POST http://localhost:8081/api/query \
  -H "Content-Type: application/json" \
  -d '{"question": "What is retrieval augmented generation?"}'
```
**Expected:** grounded answer with `"sources":["test.txt"]`

### 3.7 Test — restart persistence (expected to fail — this is the finding)

Stop the app, restart it, **without re-uploading test.txt**, run the query curl again.

**Expected result: EMPTY** — `"answer":"I don't have any relevant documents..."`, `"sources":[]`

**Key talking point:** JVector is an embedded, in-memory library — data lives inside the JVM process, not in a separate database. When the process ends, the data goes with it. (JVector does support on-disk persistence via `OnDiskGraphIndex`, not implemented in this PoC — flagged as a follow-up item, not a limitation of the library itself.)

### 3.8 Test — multimodal regression check

```bash
curl -X POST http://localhost:8081/api/documents/multimodal-image \
  -F "file=@/Users/fwd/Documents/Petclinic-RAG-POC/ocr-test.png"
```
**Expected:** same success response — confirms JVector swap didn't break the untouched multimodal path.

---

## PART 4 — Query latency comparison (optional, nice bonus stat)

Run this same command against each store while it's active, right after ingesting test.txt:

```bash
curl -w "\n%{time_total}\n" -X POST http://localhost:8081/api/query \
  -H "Content-Type: application/json" \
  -d '{"question": "What is retrieval augmented generation?"}'
```

The number after the JSON response is total request time in seconds. Record it for each store.

---

## Comparison Table (fill in during/after demo)

| Criterion | SimpleVectorStore | PGvector | JVector |
|---|---|---|---|
| Spring AI official support | Yes (built-in) | Yes (starter) | None — custom |
| External infra required | None | Postgres (Docker) | None |
| Setup time | Instant | ~15 min (docker + deps + properties) | ~1 hr (custom class + debugging) |
| Lines of custom code | 0 | 0 | ~180 |
| Persistence across restarts | No | **Yes** | No (not implemented) |
| Query latency | ___ sec | ___ sec | ___ sec |
| Documentation quality | Excellent (official) | Excellent (official) | Pre-GA, docs lag actual API |
| Production-ready | No (Spring AI's own docs say so) | Yes | No (this PoC's implementation) |

---

## Appendix A — Docker command to (re)create the PGvector container from scratch

Only needed if `pgvector-test` doesn't exist yet (e.g. fresh machine):

```bash
docker run -d --name pgvector-test \
  -p 5432:5432 \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -v pgvector-data:/var/lib/postgresql/data \
  pgvector/pgvector:pg16
```

## Appendix B — Quick reference: which bean is active

Only ever have **one** of these three uncommented in `AiConfig.java` at a time:
1. `SimpleVectorStore.builder(...)` bean
2. No bean at all (PGvector auto-config takes over) — remove the `spring.autoconfigure.exclude` line
3. `new JVectorStore(...)` bean — add the `spring.autoconfigure.exclude` line, stop Postgres