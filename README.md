#

A sales manager asks a business question in plain English. The agent queries sales data through
**approved read-only tools**, retrieves relevant reports with **RAG**, and answers with evidence and
recommended actions. A live dashboard shows the same data.

| Layer | Technology |
|---|---|
| Backend | Spring Boot 3.5, Java 21 |
| LLM + embeddings | Spring AI with Ollama (`llama3.1`, `nomic-embed-text`) |
| Database | MongoDB Atlas (cloud, or local via `mongodb-atlas-local`) |
| RAG | Spring AI `VectorStore` on MongoDB Atlas Vector Search |
| Tool calling | Spring AI `@Tool` methods |
| MCP | Spring AI MCP server (SSE) exposing the same tools |
| UI | Static HTML/CSS/JS served by Spring Boot, Chart.js |

## How the six steps map to code

| Step | Where |
|---|---|
| 1. Understand the question | `SalesAgentService.systemPrompt()` (includes today's date so "last month" is exact) |
| 2. Query data with controlled tools | `tools/SalesTools` calling `analytics/SalesAnalyticsService` (fixed aggregations) |
| 3. Revenue and month comparison | `SalesAnalyticsService.summary / compare / changes` |
| 4. RAG over reports and product notes | `rag/KnowledgeService`, documents in `src/main/resources/knowledge` |
| 5. Evidence-backed recommendations | System prompt output structure + tool trace and sources returned to the UI |
| 6. Dashboard | `static/` + `GET /api/dashboard` |

## Run it

1. **Ollama**
   ```bash
   ollama pull llama3.1
   ollama pull nomic-embed-text
   ollama serve        # skip if it already runs
   ```
   (The app also pulls missing models on start, which can take a few minutes.)
2. **MongoDB with vector search** - pick one:
   - Local: `docker compose up -d` (uses `mongodb/mongodb-atlas-local`, nothing else to configure)
   - Atlas cloud: `export MONGODB_URI="mongodb+srv://USER:PASS@CLUSTER.mongodb.net/"` and allow your IP in Atlas network access
3. **Start the app**
   ```bash
   mvn spring-boot:run
   ```
4. Open http://localhost:8080

On first start the app generates about 18,000 demo sales records (12 months, 20 products, a few of them
deliberately weak last month) and embeds the five documents in `knowledge/`. Both are skipped on later starts.

Settings you will likely change (environment variables): `MONGODB_URI`, `MONGODB_DATABASE`,
`OLLAMA_BASE_URL`, `OLLAMA_CHAT_MODEL`, `OLLAMA_EMBED_MODEL`. To use your own data set
`app.seed.enabled=false` and load documents shaped like `model/SaleRecord`.

## Using your own documents (RAG)

- Drop `.md` files into `src/main/resources/knowledge/` (name them `report-...`, `product-...`, `policy-...`) and call `POST /api/knowledge/reload`, or
- `curl -X POST localhost:8080/api/knowledge/documents -H 'Content-Type: application/json' -d '{"title":"Q3 plan","docType":"report","content":"..."}'`
- Check retrieval: `GET /api/knowledge/search?q=why did the speaker decline`

## MCP

The same read-only tools are published as an MCP server at `http://localhost:8080/sse`.
Try it with the MCP Inspector (`npx @modelcontextprotocol/inspector`, transport SSE, that URL) or add it to a
client that supports remote servers, for example:
```json
{ "mcpServers": { "sales": { "command": "npx", "args": ["-y", "mcp-remote", "http://localhost:8080/sse"] } } }
```
Tools: `getMonthlyRevenue`, `compareWithPreviousMonth`, `getTopProducts`, `getUnderperformingProducts`,
`getCategoryBreakdown`, `getProductRevenueTrend`, `listProducts`.

## HTTP API

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/months` | Months with data and the default month |
| GET | `/api/dashboard?year=2026&month=9` | KPIs, trend, categories, product movers |
| POST | `/api/agent/ask` `{"question": "..."}` | Answer, tool calls, retrieved sources |
| GET | `/api/knowledge/search?q=` | Test vector search |
| POST | `/api/knowledge/documents` | Add a document |
| POST | `/api/knowledge/reload` | Re-embed bundled documents |

## Security notes

- The model never writes queries. It can only call the fixed tools, and every tool validates year, month, limit and name inputs.
- Tools are read-only. Use a MongoDB user with read access to `sales` if you point the agent at real data, and a separate writable collection for the vector store.
- There is no login. Put the app behind your SSO or add Spring Security before exposing it beyond your machine, and protect `/api/knowledge/**` and the MCP endpoints.

## Troubleshooting

- **Answers say there is no context / sources list is empty on the first run.** Atlas builds the vector index in the background. Wait about a minute and ask again. If the index was not created, create a Vector Search index named `vector_index` on the `product_knowledge` collection: `{"fields":[{"type":"vector","path":"embedding","numDimensions":768,"similarity":"cosine"}]}` (768 matches `nomic-embed-text`; change it if you change the embedding model).
- **"The model did not call any sales tool".** Use a model that supports tool calling (`llama3.1`, `qwen2.5`, `mistral-nemo`). Very small models often skip tools; try a larger one.
- **Slow first answer.** Local models load into memory on first use. The HTTP read timeout is 300 seconds (`spring.http.client.read-timeout`).
- **Cannot reach Ollama.** `ollama serve`, then check `OLLAMA_BASE_URL`.

## Project layout

```
src/main/java/com/example/salesagent
  agent/      SalesAgentService      prompt + RAG + tool calling
  analytics/  SalesAnalyticsService  read-only Mongo aggregations, result records
  tools/      SalesTools, ToolTrace  @Tool methods, evidence trail
  rag/        KnowledgeService/Loader  embed + search documents
  config/     AgentConfig            ChatClient and MCP tool publishing
  web/        REST controllers
  seed/       DataSeeder             demo data
src/main/resources
  application.yml, knowledge/*.md, static/ (index.html, css, js)
```
