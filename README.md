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
| Text extraction | Apache Tika (`AutoDetectParser`, via a single consolidated `TikaGenericExtractor`) | Handles DOC/DOCX, PDF, TXT, CSV, JSON, MD, XLS/XLSX, PPT/PPTX — one extractor class, no new dependencies per format. See [Extractor Architecture](#extractor-architecture) below. |
| Image OCR | Tesseract via Tess4J | Extracts literal text from images (PNG/JPG/TIFF) |
| Text splitting | Spring AI `TokenTextSplitter` | Token-aware chunking (not naive character count) |
| Chat memory | `JdbcChatMemoryRepository` (Postgres-backed, table `spring_ai_chat_memory`) | Persists conversation history across restarts — confirmed via a dedicated restart test, see [Regression Testing](#regression-testing-file-types-dedup-multimodal-restart-persistence) |
| Deduplication | `FileDeduplicationService` (MD5 hash, table `ingested_files`) | Prevents re-ingesting the same file twice; returns `409` on a repeat upload |

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
│   ├── IngestController           — POST /api/documents (all Tika-handled formats + OCR image)
│   ├── MultimodalIngestController — POST /api/documents/multimodal-image
│   └── QueryController            — POST /api/query
├── service/
│   ├── extraction/
│   │   ├── TextExtractor          — common interface (Strategy pattern)
│   │   ├── TikaGenericExtractor   — wraps Tika's AutoDetectParser; handles .doc .docx .pdf .txt .csv .json .md .xls .xlsx .ppt .pptx
│   │   ├── ImageOcrExtractor      — wraps Tesseract/Tess4J; handles .png .jpg .tiff
│   │   └── ExtractorFactory       — picks the right extractor by filename (unchanged by the consolidation)
│   ├── vectorstore/
│   │   ├── JVectorStore           — custom VectorStore impl (Task 1 candidate)
│   │   ├── VectorDocumentEntity   — ObjectBox @Entity for vector-indexed chunks
│   │   └── ObjectBoxVectorStore   — custom VectorStore impl (Task 1 candidate)
│   ├── ChunkingService            — wraps TokenTextSplitter
│   ├── FileDeduplicationService   — MD5 hash check against ingested_files, returns 409 on repeat upload
│   ├── MultimodalImageService     — handles the image-embedding-only path
│   ├── CompressingChatMemory      — chat history compression (not covered by this session's regression testing)
│   ├── QueryRewriter               — query rewriting (not covered by this session's regression testing)
│   ├── QueryRouter                 — routes a query between retrieval and memory (not covered by this session's regression testing)
│   ├── QueryTimeVectorSearch      — retrieval-time vector search
│   └── RagQueryService            — orchestrates retrieval + LLM answer
├── dto/
│   ├── QueryRequest                — { question, conversationId }
│   └── QueryResponse               — { answer, sources }
└── config/
    ├── AiConfig                   — embedding model + vector store beans (see Appendix B for toggling between stores)
    └── NvidiaEmbeddingModel       — custom EmbeddingModel (see Key Learnings), now with retry logic (3 attempts, 2s backoff) — see Known Limitations
```

> Note: `CompressingChatMemory`, `QueryRewriter`, and `QueryRouter` exist in the codebase and are part of the original project scope (conversation memory, history compression, query rewriting), but this session's regression testing did not specifically exercise or verify their behavior — flagged here for a future session rather than silently omitted.

---

## Extractor Architecture

Originally, each file type had its own extractor class (`TxtExtractor`, `DocxExtractor`, a later `PdfExtractor`) — each one was a thin, nearly-identical wrapper around Spring AI's `TikaDocumentReader`. Since Tika's `AutoDetectParser` already natively handles format detection for `.doc .docx .pdf .txt .csv .json .md .xls .xlsx .ppt .pptx` with no additional dependencies, these were consolidated into a single `TikaGenericExtractor`.

```
Before: TxtExtractor, DocxExtractor, PdfExtractor  (3 near-duplicate classes)
After:  TikaGenericExtractor                       (1 class, same coverage, 8 more formats for free)
```

`ImageOcrExtractor` (Tesseract/Tess4J) was extended separately to also support `.tiff`, since Tess4J handles it natively.

**Zero changes were needed** to `ExtractorFactory` or `IngestController` — the existing strategy pattern (Spring auto-wiring `List<TextExtractor>`, `ExtractorFactory` picking the first one whose `supports(filename)` matches) absorbed the refactor cleanly. This is the kind of case where the original design was already correct; it just needed fewer, more general implementations behind the same interface.

Coverage is verified with real, non-fake sample files (verified via `file` command, not renamed placeholders) in `src/test/resources/sample-files/`, one per format, each containing a unique marker string for confirming genuine extraction:

| Format | Sample file | Marker string pattern |
|---|---|---|
| DOCX | `sample.docx` | `DOCX_EXTRACTION_TEST_MARKER` |
| PDF | `sample.pdf` | `PDF_EXTRACTION_TEST_MARKER` |
| XLSX | `sample.xlsx` | `XLSX_EXTRACTION_TEST_MARKER` |
| PPTX | `sample.pptx` | `PPTX_EXTRACTION_TEST_MARKER` |
| CSV | `sample.csv` | `CSV_EXTRACTION_TEST_MARKER` |
| JSON | `sample.json` | `JSON_EXTRACTION_TEST_MARKER` |
| MD | `sample.md` | `MD_EXTRACTION_TEST_MARKER` |
| TIFF (OCR) | `sample.tiff` | `TIFF_EXTRACTION_TEST_MARKER` |

Test coverage: `TikaGenericExtractorTest` (unit, synthetic content for txt/csv/json/md), `TikaGenericExtractorIntegrationTest` (real binary sample files), `ImageOcrExtractorTest`, `ExtractorFactoryTest` — all passing.

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
| PDF / XLSX / PPTX / CSV / JSON / MD extraction | ✅ Done | Extractor consolidated into `TikaGenericExtractor`; each format has a real sample file with a unique marker string in `src/test/resources/sample-files/`, verified via unit + integration tests and a full ingest→query round trip |
| TIFF image support | ✅ Done | Extended into `ImageOcrExtractor`, no new dependency |
| Deduplication (`FileDeduplicationService`) | ✅ Done | Re-uploading the same file returns `409` with `status: "skipped"`; confirmed via both terminal and website UI |
| Chat memory persistence (`JdbcChatMemoryRepository`) | ✅ Done | Restart persistence test (see [Regression Testing](#regression-testing-file-types-dedup-multimodal-restart-persistence)) — fact recalled correctly after a full app restart, with `sources: []` proving it came from memory, not retrieval |
| Full regression sweep (all 8 formats + dedup + multimodal + OCR + query) | ✅ Done | Clean-slate: `TRUNCATE`'d all three tables, full rebuild + restart, re-tested everything via both terminal (curl) and the website UI |

**Summary**: both pipelines (ingestion and retrieval) are functionally complete and tested end-to-end across all supported file types (DOC/DOCX, PDF, TXT, CSV, JSON, MD, XLS/XLSX, PPT/PPTX, PNG/JPG/TIFF via OCR, PNG/JPG via multimodal), re-verified working against the final chosen vector store (PGvector), and confirmed to persist correctly across a full application restart for both the vector store and chat memory.

---

## Regression Testing: file types, dedup, multimodal, restart persistence

A full clean-slate regression pass was run after the extractor consolidation and dedup/chat-memory additions, to confirm nothing regressed:

**Setup**: `TRUNCATE`'d `vector_store`, `ingested_files`, and `spring_ai_chat_memory`, full rebuild + app restart.

**Tested via both terminal (curl) and the website UI**, since the app needs to be demoed to the team lead through the UI, not just the API:

| Check | Result |
|---|---|
| All 8 file types ingest correctly | ✅ Pass |
| Dedup returns `409 skipped` on re-upload | ✅ Pass |
| Multimodal image — photo-type | ✅ Pass |
| Multimodal image — diagram-type | ⚠️ Known limitation (see [Known Limitations](#known-limitations)) |
| OCR image path | ✅ Pass |
| Full ingest → query cycle, grounded answer with sources | ✅ Pass |
| **Restart persistence — vector store AND chat memory** | ✅ Pass |

**Restart persistence test detail** (the most important check — this is what actually proves the persistence claims above, not just that the app happens to keep working):

1. Pre-restart: sent a query establishing a fact in a specific `conversationId` ("remember `XLSX_EXTRACTION_TEST_MARKER`")
2. Full app restart (Postgres container left running)
3. Post-restart: queried the *same* `conversationId` and asked what fact was given
4. **Result**: the fact was recalled correctly, with `sources: []` — proving the answer came from `JdbcChatMemoryRepository`, not a fresh vector retrieval. A clean, unambiguous pass.

---

## API Reference

A Postman collection covering all endpoints (ingestion for all 8 formats, multimodal image, dedup demo, and the query/RAG cycle with conversation-memory examples) is available in `postman/Petclinic-RAG-POC.postman_collection.json`. Import it directly into Postman — collection variables `base_url` and `conversation_id` are pre-configured.

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

20. **`tess4j:5.11.0` transitively pulls an old `pdfbox:3.0.1`, which breaks PDF parsing once Tika is added.** Adding PDF support via `TikaDocumentReader` failed at runtime with `NoSuchMethodError: PDF2XHTML.setIgnoreContentStreamSpaceGlyphs()` — the root cause is that `tika-parser-pdf-module:3.3.1` requires PDFBox 3.0.3+, but `tess4j` silently downgrades it via a transitive dependency. Same failure pattern as an earlier `commons-io` version conflict, also caused by `tess4j`. Fix: add an explicit `org.apache.pdfbox:pdfbox:3.0.5` dependency to `pom.xml` to force the correct version, confirmed via `mvn dependency:tree -Dincludes=org.apache.pdfbox`.

21. **Nearly-identical single-format extractor classes are a maintainability smell when the underlying library already handles format detection.** `TxtExtractor`, `DocxExtractor`, and the new `PdfExtractor` were all just thin wrappers around `TikaDocumentReader` with no format-specific logic. Since Tika's `AutoDetectParser` already natively handles `.doc .docx .pdf .txt .csv .json .md .xls .xlsx .ppt .pptx` with no additional dependencies, all three were consolidated into a single `TikaGenericExtractor`. The `ExtractorFactory`'s strategy pattern (Spring auto-wiring `List<TextExtractor>`) absorbed the change with zero modification needed to the factory or the controller — a case where the existing pattern was already sound and just needed fewer, more general implementations behind it.

22. **`FileDeduplicationService` and `JdbcChatMemoryRepository` both require Postgres regardless of which `VectorStore` bean is active** — they were added after Task 1's vector store comparison, and are wired independently of the `vectorStore` bean. This means the "zero external infrastructure" claim from Task 1's JVector/ObjectBox findings (see [Comparison Table](#comparison-table)) **no longer holds for the app as a whole** — it's now only true for vector storage specifically. Following the old runbook's step to stop Postgres while testing JVector/ObjectBox causes Spring Boot to silently fall back to embedded H2, which doesn't understand Postgres/pgvector SQL syntax, producing a `BadSqlGrammarException` on both dedup checks and vector similarity search. Fix: keep Postgres running for all four vector store configurations now; only the `vectorStore` bean itself gets toggled.

---

## Known Simplifications (for a POC, not production)

- Multimodal image documents are stored with their base64 data URI as the "text" field (needed for the embedding call), rather than separately storing the raw image file with a reference — a production system would separate these.
- No authentication/authorization on any endpoint.
- Chunking always uses default parameters; not yet tuned per file type.
- JVector's implementation rebuilds its entire graph index on every `add()` call rather than inserting incrementally — acceptable for PoC-scale document counts, not for production volume.
- Neither JVector's nor ObjectBox's custom `VectorStore` implementations support Spring AI's metadata `Filter.Expression` delete/search API — both currently throw `UnsupportedOperationException` for that path.
- JVector has no persistence layer implemented in this PoC (data is lost on every restart) — a real follow-up task, not a limitation of the JVector library itself.

---

## Known Limitations

### NVIDIA multimodal embedding: diagram-type images fail consistently

NVIDIA's multimodal embedding endpoint (`nvidia/llama-nemotron-embed-vl-1b-v2`) intermittently returns `502 Bad Gateway: {"error": "timed out"}` after roughly 120 seconds — but this isn't random flakiness across all images. Repeated, targeted testing narrowed it down to a specific pattern:

- **Dense, diagram/screenshot-style images (sharp edges, dense text) fail consistently** — the same test image (`spring-ai.png`, a 107KB architecture diagram) failed 3 out of 3 times.
- **Real photographs of a similar file size succeed reliably**, typically in under 1 second (`test_sample.png`).

Ruled out as the cause: file size alone (107KB is not large), pixel dimensions alone (no clean threshold emerged — 483×708 fails, but other dimension tests were inconsistent), and image format (both PNG and JPEG fail for diagram-type content).

**Conclusion**: this behaves like a genuine backend limitation or bug on NVIDIA's side specific to dense/diagram-style image content, not something fixable from the client. Retry logic was added to `NvidiaEmbeddingModel.call()` (3 attempts, 2s backoff, retrying on 5xx/`HttpServerErrorException` and I/O timeouts, failing fast on 4xx) — this helps with genuine transient flakiness but does **not** resolve the diagram-specific consistent failure.

**Practical takeaway**: avoid live-demoing multimodal image upload with diagram or screenshot-type images. Use photo-type images for reliable demo results.

### "Zero external infrastructure" no longer applies to the whole app

Task 1's comparison table (above) lists JVector and ObjectBox as requiring no external infrastructure — that was accurate for vector storage at the time it was written, but two features added afterward changed this:

- `FileDeduplicationService` (table `ingested_files`)
- `JdbcChatMemoryRepository` (table `spring_ai_chat_memory`)

Both are wired independently of the `vectorStore` bean and require Postgres regardless of which vector store is active. So "zero external infra" now only holds for the vector-storage layer specifically — the app as a whole always needs Postgres. See Key Learning #22 for the full detail, including the `BadSqlGrammarException` failure mode this caused when following the old runbook's "stop Postgres" step for JVector/ObjectBox testing.

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