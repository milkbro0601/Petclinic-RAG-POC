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
