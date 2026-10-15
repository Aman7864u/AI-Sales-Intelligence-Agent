package com.example.salesagent.rag;

import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Adds documents to / searches the MongoDB Atlas vector store. */
@Service
public class KnowledgeService {

    private final VectorStore vectorStore;
    private final int topK;
    private final double threshold;
    private final TokenTextSplitter splitter = new TokenTextSplitter(400, 100, 10, 5000, true);

    public KnowledgeService(VectorStore vectorStore,
                            @Value("${app.rag.top-k:4}") int topK,
                            @Value("${app.rag.similarity-threshold:0.4}") double threshold) {
        this.vectorStore = vectorStore;
        this.topK = topK;
        this.threshold = threshold;
    }

    /** Splits the text into chunks, embeds them with Ollama and stores them. Returns the chunk count. */
    public int ingest(String title, String docType, String source, String content) {
        Document doc = new Document(content, Map.<String, Object>of(
                "title", title, "docType", docType, "source", source));
        List<Document> chunks = splitter.apply(List.of(doc));
        vectorStore.add(chunks);
        return chunks.size();
    }

    public List<Document> search(String query) {
        return search(query, topK);
    }

    public List<Document> search(String query, int k) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(Math.max(1, Math.min(10, k)))
                .similarityThreshold(threshold)
                .build());
    }
}
