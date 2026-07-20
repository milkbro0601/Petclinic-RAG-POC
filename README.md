# PetClinic RAG PoC

A backend-only Retrieval-Augmented Generation (RAG) proof of concept built with Spring Boot. Ingests documents (TXT, DOCX, images), generates embeddings, stores them in a vector database, and answers user questions through semantic retrieval + LLM generation.

Built as a learning project and team task, covering two objectives:
- **Task 1**: Research and evaluate vector database implementations (Java ecosystem) — ✅ **Complete**
- **Task 2**: Build a working program that ingests files and loads them into a vector DB — ✅ **Complete**

---

## Tech Stack

| Component | Choice | Why |
|---|---|---|
| Language / Framework | Java 21, Spring Boot 4.1, Spring AI 2.0 | Team's existing stack |
| LLM + Embedding provider | NVIDIA NIM (OpenAI-compatible API) | Free tier, no billing risk, supports both text and true multimodal image embeddings |
| Chat model | `meta/llama-3.1-8b-instruct` | Fast, reliable — see [Key Learnings](#key-learnings--debugging-log) for why the 70B model was dropped |
| Text embedding model | `nvidia/llama-nemotron-embed-1b-v2` | Handles asymmetric query/passage embeddings |
| Multimodal (image) embedding model | `nvidia/llama-nemotron-embed-vl-1b-v2` | Embeds images directly, no OCR needed |
| **Vector store (final choice)** | **PGvector** (`spring-ai-starter-vector-store-pgvector`) | Official Spring AI support, genuine restart persistence, zero custom code. See [Task 1: Vector Store Comparison](#task-1-vector-store-comparison) for the full evaluation against JVector, ObjectBox, and the original `SimpleVectorStore` baseline. |
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
│   ├── vectorstore/
│   │   ├── JVectorStore           — custom VectorStore impl (Task 1 candidate)
│   │   ├── VectorDocumentEntity   — ObjectBox @Entity for vector-indexed chunks
│   │   └── ObjectBoxVectorStore   — custom VectorStore impl (Task 1 candidate)
│   ├── ChunkingService            — wraps TokenTextSplitter
│   ├── MultimodalImageService     — handles the image-embedding-only path
│   └── RagQueryService            — orchestrates retrieval + LLM answer
├── dto/
│   ├── QueryRequest
│   └── QueryResponse
└── config/
    ├── AiConfig                   — embedding model + vector store beans (see Appendix B for toggling between stores)
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

**Summary**: both pipelines (ingestion and retrieval) are functionally complete and tested end-to-end across all required file types (TXT, DOCX, PNG/JPG via two different strategies), and re-verified working against the final chosen vector store (PGvector).

---

## Task 1: Vector Store Comparison

Four `VectorStore` implementations were built and tested end-to-end against the exact same ingestion/retrieval pipeline: the original `SimpleVectorStore` baseline, **PGvector** (official Spring AI support), and two fully custom implementations — **JVector** and **ObjectBox** — written by hand against Spring AI's `VectorStore` interface, since neither library has first-party Spring AI integration.

### Recommendation: PGvector

**PGvector is the recommended choice for taking this RAG system toward production.**

Reasoning:
- **It's the only store with zero unresolved gaps.** Every test — ingestion, grounded retrieval, restart persistence, multimodal regression — passed cleanly. JVector has a real, currently-unresolved persistence gap. ObjectBox works well but carries more inherent risk (a less-mature Maven story, native platform binaries per OS).
- **"Official support" translated into real, measurable value during this evaluation.** Every problem hit while building JVector and ObjectBox — Spring bean collisions, wrong class names, missing setup folders, auto-configuration exclusion traps — came from having to hand-roll behavior that Spring AI's PGvector integration simply handles for free. That's exactly the kind of ongoing maintenance burden a team inherits long after a PoC ships.
- **The "needs Docker/Postgres" cost is real but manageable.** Nearly every production Java team already operates Postgres somewhere — it's ordinary, well-understood infrastructure, not an exotic dependency. That cost is far smaller than owning and maintaining custom `VectorStore` code indefinitely.
- **JVector and ObjectBox remain valuable secondary findings**, not dead ends. If a future requirement genuinely needs zero external infrastructure (e.g. an offline or edge deployment), ObjectBox is the stronger candidate of the two custom builds — it just needs the same level of production hardening PGvector already has by default.

### Comparison Table

| Criterion | SimpleVectorStore | PGvector | JVector | ObjectBox |
|---|---|---|---|---|
| Spring AI official support | Yes (built-in) | Yes (starter) | None — custom | None — custom |
| External infra required | None | Postgres (Docker) | None | None |
| Setup time | Instant | ~15 min (docker + deps + properties) | ~1 hr (custom class + debugging) | ~2 hr (Maven annotation-processor setup + entity + class-naming debugging) |
| Lines of custom code | 0 | 0 | ~180 | ~150 (entity + store) |
| Persistence across restarts | No | **Yes** | No (not implemented) | **Yes** (confirmed, built-in) |
| Native platform binaries required | No | No (server-side only) | No (pure Java) | **Yes** (macOS/Linux/Windows-specific) |
| Query latency | Comparable across all four in informal testing — not a meaningful differentiator at this data scale | | | |
| Documentation quality | Excellent (official) | Excellent (official) | Pre-GA, docs lag actual API | Good, but Maven path is secondary to Gradle |
| Production-ready | No (Spring AI's own docs say so) | Yes | No (this PoC's implementation) | Closer than JVector — real persistence, but Maven tooling risk noted |

### Per-store findings

**SimpleVectorStore (baseline)** — Spring AI's own in-memory implementation, used to build and validate the ingestion/retrieval pipeline before Task 1 began. Confirmed wiped on every restart, exactly as Spring AI's own documentation states. Not a real candidate — establishes the floor the other three are compared against.

**PGvector** — Official `spring-ai-starter-vector-store-pgvector`. Setup: run Postgres via Docker, add two Maven dependencies, set `spring.datasource.*` and `spring.ai.vectorstore.pgvector.*` properties. No custom code needed — Spring AI's auto-configuration creates the `PgVectorStore` bean automatically as long as no manual `vectorStore` bean is defined and no `spring.autoconfigure.exclude` entry blocks it (see Key Learnings #14 — this exact trap was hit and fixed during testing). Restart persistence confirmed by direct `psql` inspection of the `vector_store` table, not just by the app continuing to return correct answers.

**JVector** — `io.github.jbellis:jvector`, a pre-GA (`4.0.0-rc.8-hf1`) embedded ANN graph-index library with no Spring AI integration. Required a full custom `VectorStore` implementation. Key limitation: `GraphIndexBuilder` is a batch-build API, not a live incremental-insert API like PGvector's `INSERT` — this PoC's implementation rebuilds the entire graph on every `add()` call, a reasonable shortcut for a PoC but a real cost at scale. No persistence layer was implemented (JVector does support this via `OnDiskGraphIndex`, flagged as a follow-up, not attempted here). Confirmed via both a fake-embedding standalone smoke test and the full app with real NVIDIA embeddings that restart wipes all data, as expected for an in-memory-only design.

**ObjectBox** — `io.objectbox:objectbox-java`, a full embedded NoSQL object database with a built-in HNSW vector index (`@HnswIndex` on a `float[]` entity field). The riskiest candidate going in, since ObjectBox's actively-maintained tooling is Gradle-based — the dedicated Maven plugin (`objectbox-maven-plugin`) hasn't been updated since 2022. That risk was resolved by using ObjectBox's officially documented alternative Maven path: wiring the `objectbox-processor` annotation processor directly into `maven-compiler-plugin`, which does not depend on the stale plugin at all. Required a one-time `objectbox-models/` folder creation and one class-naming correction (`VectorDistanceType`, not the Swift-binding name `HnswDistanceType`, which doesn't exist in the Java API). The only one of the three custom builds with genuine, confirmed persistence — proven via a standalone smoke test that closed and reopened the same on-disk database directory without any Spring context involved, and reconfirmed through the full live app after a real restart. Ships native platform binaries per OS (`objectbox-macos` in this project) — a real deployment consideration PGvector and JVector don't share.

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

11. **Spring Boot 4's bean-definition-overriding is disabled by default.** Defining a manual `vectorStore` bean while Spring AI's `PgVectorStoreAutoConfiguration` also tries to create one under the same name throws `BeanDefinitionOverrideException` at startup — the auto-configuration does NOT gracefully back off on its own via `@ConditionalOnMissingBean` the way expected. Fix: explicitly exclude it via `spring.autoconfigure.exclude=org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration` whenever a custom `vectorStore` bean is active.

12. **That exclude property must be kept in sync with `AiConfig.java` manually — it is not self-correcting.** Removing a custom `vectorStore` bean from `AiConfig.java` does NOT automatically restore PGvector's auto-configured bean if the exclude line is still present in `application.properties`. When this happened, Spring AI silently fell back to `SimpleVectorStore` with no error at all — the app kept working, just against the wrong store, which is a much easier mistake to miss than an outright crash. Treat these two files as one linked toggle, not two independent settings.

13. **Two separate `<plugin>` blocks for the same Maven plugin (same groupId/artifactId) in one `pom.xml` collide.** Adding ObjectBox's annotation processor as a second, separate `maven-compiler-plugin` block silently broke Lombok's existing annotation processing. Fix: merge both processors into the same `<execution>`'s `<annotationProcessorPaths>`, not separate plugin declarations.

14. **ObjectBox's Java distance-type enum is `io.objectbox.annotation.VectorDistanceType`, not `HnswDistanceType`.** The latter name only exists in ObjectBox's Swift binding — an easy cross-language mix-up when researching the API via search results that don't clearly separate bindings.

15. **ObjectBox's official, currently-maintained Maven setup does NOT require the dedicated `objectbox-maven-plugin`** (last released 2022) for basic use — only for making entity *relations* easier to use. A flat entity with no relations only needs the `objectbox-processor` wired into `maven-compiler-plugin`'s annotation processor paths, sidestepping the stale-plugin risk entirely.

16. **ObjectBox requires a manually-created `objectbox-models/` folder before the first compile** — the annotation processor writes its schema JSON there and fails loudly (not silently) if the folder doesn't already exist. This folder should be committed to git (it's ObjectBox's source of truth for stable entity IDs across schema changes); `objectbox-data/` (the actual database files) should not be.

17. **JVector's `GraphSearcher.getNodes()` returns a raw `NodeScore[]` array, not a `List`** — needed `Arrays.stream(...)` rather than calling `.stream()` directly. Similarly, `SearchRequest.getSimilarityThreshold()` returns a primitive `double` (default `0.0`, meaning "accept everything"), not a nullable `Double` — no null-check needed or possible.

18. **ObjectBox's `nearestNeighbors()` returns a distance-based score (lower = more similar)**, the opposite direction from JVector's and PGvector's cosine similarity (higher = more similar) — any similarity-threshold filtering logic must account for this inversion per store.

19. **IntelliJ run configurations do not inherit shell environment variables** (like `NVIDIA_API_KEY` exported in `.zshrc`) — they must be set explicitly per run configuration (or in the shared configuration template) inside IntelliJ, or the app fails at startup with a `PlaceholderResolutionException`.

---

## Known Simplifications (for a POC, not production)

- Multimodal image documents are stored with their base64 data URI as the "text" field (needed for the embedding call), rather than separately storing the raw image file with a reference — a production system would separate these.
- No deduplication — re-uploading the same file creates duplicate chunks.
- No authentication/authorization on any endpoint.
- Chunking always uses default parameters; not yet tuned per file type.
- JVector's implementation rebuilds its entire graph index on every `add()` call rather than inserting incrementally — acceptable for PoC-scale document counts, not for production volume.
- Neither JVector's nor ObjectBox's custom `VectorStore` implementations support Spring AI's metadata `Filter.Expression` delete/search API — both currently throw `UnsupportedOperationException` for that path.
- JVector has no persistence layer implemented in this PoC (data is lost on every restart) — a real follow-up task, not a limitation of the JVector library itself.

---

# Vector Store Comparison — Live Demo Runbook
### SimpleVectorStore vs PGvector vs JVector vs ObjectBox

This is a step-by-step script for demoing all four vector store implementations. Each section is self-contained: setup → run → test → expected output → how to switch to the next one.

---

## Before you start: one-time prep

Have three terminal tabs ready:
- **Tab 1**: Docker commands
- **Tab 2**: `application.properties` editing / IntelliJ
- **Tab 3**: curl test commands

---

## PART 1 — SimpleVectorStore (baseline, in-memory, zero setup)

### 1.1 Config: AiConfig.java

Comment out the PGvector, JVector, and ObjectBox `vectorStore` beans, and uncomment the SimpleVectorStore one:
```java
@Bean
public VectorStore vectorStore(EmbeddingModel embeddingModel) {
    return SimpleVectorStore.builder(embeddingModel).build();
}
```

### 1.2 Config: application.properties

No `spring.autoconfigure.exclude` needed. Postgres `spring.datasource.*` properties can stay commented out.

### 1.3 Run and test

```bash
curl -X POST http://localhost:8081/api/documents -F "file=@test.txt"
curl -X POST http://localhost:8081/api/query -H "Content-Type: application/json" -d '{"question": "What is retrieval augmented generation?"}'
```
**Expected:** grounded answer with `"sources":["test.txt"]`

### 1.4 Restart persistence test

Stop the app, restart it, run the same query curl **without re-uploading**.

**Expected: EMPTY** — `"sources":[]`. In-memory only, by Spring AI's own design.

---

## PART 2 — PGvector (official support, external Postgres)

### 2.1 Start Postgres

```bash
docker start pgvector-test
```
(First time on a fresh machine — see Appendix A.)

### 2.2 Config: application.properties

Remove/comment the `spring.autoconfigure.exclude` line entirely. Uncomment:
```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/postgres
spring.datasource.username=postgres
spring.datasource.password=postgres

spring.ai.vectorstore.pgvector.initialize-schema=true
spring.ai.vectorstore.pgvector.index-type=HNSW
spring.ai.vectorstore.pgvector.distance-type=COSINE_DISTANCE
spring.ai.vectorstore.pgvector.dimensions=2048
```

### 2.3 Config: AiConfig.java

Comment out **all** custom `vectorStore` beans — leave none defined at all, so PGvector's auto-configuration creates the bean.

### 2.4 Run and test

```bash
curl -X POST http://localhost:8081/api/documents -F "file=@test.txt"
docker exec -it pgvector-test psql -U postgres -c "SELECT count(*) FROM vector_store;"
curl -X POST http://localhost:8081/api/query -H "Content-Type: application/json" -d '{"question": "What is retrieval augmented generation?"}'
```
**Expected:** psql count = 2; grounded answer with sources.

### 2.5 Restart persistence test (the payoff)

Stop, restart, query again without re-ingesting.

**Expected: STILL WORKS** — same grounded answer, same source.

### 2.6 Multimodal regression check

```bash
curl -X POST http://localhost:8081/api/documents/multimodal-image -F "file=@ocr-test.png"
```

---

## PART 3 — JVector (embedded, no infra, custom-built, no persistence)

### 3.1 Stop Postgres

```bash
docker stop pgvector-test
```

### 3.2 Config: application.properties

Comment out the Postgres datasource block. Add:
```properties
spring.autoconfigure.exclude=org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration,org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.data.jdbc.autoconfigure.DataJdbcRepositoriesAutoConfiguration
```
(Optional VM option for full SIMD performance: `--add-modules jdk.incubator.vector`)

### 3.3 Config: AiConfig.java

Comment out all other beans, activate JVector's:
```java
@Bean
public VectorStore vectorStore(
        @Qualifier("embeddingModel") EmbeddingModel embeddingModel,
        @Value("${nvidia.embedding.dimension:2048}") int dimension) {
    return new com.petclinic.rag.service.vectorstore.JVectorStore(embeddingModel, dimension);
}
```

### 3.4 Run and test

Confirm the app starts with Postgres **fully stopped** — proves zero external infra.
```bash
curl -X POST http://localhost:8081/api/documents -F "file=@test.txt"
curl -X POST http://localhost:8081/api/query -H "Content-Type: application/json" -d '{"question": "What is retrieval augmented generation?"}'
```

### 3.5 Restart persistence test (expected to fail — this is the finding)

Stop, restart, query again without re-ingesting.

**Expected: EMPTY.** JVector is embedded/in-memory only in this PoC — data lives inside the JVM process and disappears when it ends.

### 3.6 Multimodal regression check

Same as Part 2.6.

---

## PART 4 — ObjectBox (embedded, no infra, custom-built, genuine persistence)

### 4.1 Docker — not needed

Same as JVector — Postgres stays stopped.

### 4.2 Config: application.properties

Same state as JVector (Postgres commented out, same `spring.autoconfigure.exclude` line active). ObjectBox needs no additional exclude entries of its own.

### 4.3 Config: AiConfig.java

Comment out JVector's bean, activate ObjectBox's:
```java
@Bean
public VectorStore vectorStore(
        @Qualifier("embeddingModel") EmbeddingModel embeddingModel,
        @Value("${objectbox.db-directory:objectbox-data}") String dbDirectory) {
    return new com.petclinic.rag.service.vectorstore.ObjectBoxVectorStore(embeddingModel, dbDirectory);
}
```

### 4.4 Run and test

```bash
curl -X POST http://localhost:8081/api/documents -F "file=@test.txt"
curl -X POST http://localhost:8081/api/query -H "Content-Type: application/json" -d '{"question": "What is retrieval augmented generation?"}'
ls -la objectbox-data/
```
**Expected:** grounded answer; real `data.mdb`/`lock.mdb` LMDB files present on disk.

### 4.5 Restart persistence test (the payoff for ObjectBox)

Stop, restart, query again without re-ingesting.

**Expected: STILL WORKS** — same "zero external infra" profile as JVector, but genuinely persists, unlike JVector.

### 4.6 Multimodal regression check

Same as Part 2.6.

---

## Appendix A — Docker command to (re)create the PGvector container from scratch

```bash
docker run -d --name pgvector-test \
  -p 5432:5432 \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -v pgvector-data:/var/lib/postgresql/data \
  pgvector/pgvector:pg16
```

## Appendix B — Quick reference: which bean is active

Only ever have **one** `vectorStore` bean uncommented in `AiConfig.java` at a time, kept in sync with `application.properties`'s `spring.autoconfigure.exclude` line (see Key Learning #12 — these two must be toggled together, not independently):

| Store | AiConfig.java | `spring.autoconfigure.exclude` | Postgres |
|---|---|---|---|
| SimpleVectorStore | SimpleVectorStore bean active | not needed | not needed |
| PGvector | **no bean defined** | removed/commented | running |
| JVector | JVectorStore bean active | present | stopped |
| ObjectBox | ObjectBoxVectorStore bean active | present (same line as JVector) | stopped |

## Appendix C — ObjectBox one-time Maven setup notes

Required once per fresh machine, not per-run:
1. `mkdir -p objectbox-models` in the project root — commit this folder to git (schema tracking); `objectbox-data/` should be gitignored instead (actual binary DB files)
2. Confirm `pom.xml` has the ObjectBox annotation processor merged into the **same** `maven-compiler-plugin` execution as Lombok's — two separate plugin blocks with matching coordinates will collide (Key Learning #13)
3. Add the correct platform-specific native dependency for the target OS (`objectbox-macos`, `objectbox-linux`, or `objectbox-windows`) — this project currently only has macOS