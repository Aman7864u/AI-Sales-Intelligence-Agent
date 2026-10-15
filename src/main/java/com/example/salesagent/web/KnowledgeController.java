package com.example.salesagent.web;

import com.example.salesagent.rag.KnowledgeLoader;
import com.example.salesagent.rag.KnowledgeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Manage the documents the agent uses as business context (RAG). */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    public record NewDocument(@NotBlank @Size(max = 200) String title,
                              @NotBlank @Size(max = 40) String docType,
                              @NotBlank @Size(max = 50000) String content) {
    }

    private final KnowledgeService knowledge;
    private final KnowledgeLoader loader;

    public KnowledgeController(KnowledgeService knowledge, KnowledgeLoader loader) {
        this.knowledge = knowledge;
        this.loader = loader;
    }

    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam String q, @RequestParam(defaultValue = "4") int k) {
        return knowledge.search(q, k).stream()
                .map(d -> Map.<String, Object>of(
                        "title", String.valueOf(d.getMetadata().getOrDefault("title", "")),
                        "docType", String.valueOf(d.getMetadata().getOrDefault("docType", "")),
                        "score", d.getScore() == null ? 0.0 : d.getScore(),
                        "text", d.getText() == null ? "" : d.getText()))
                .toList();
    }

    @PostMapping("/documents")
    public Map<String, Object> add(@Valid @RequestBody NewDocument doc) {
        int chunks = knowledge.ingest(doc.title(), doc.docType().toLowerCase(), "api", doc.content());
        return Map.of("title", doc.title(), "chunks", chunks);
    }

    /** Clears the vector collection and re-embeds the bundled markdown files. */
    @PostMapping("/reload")
    public Map<String, Object> reload() throws IOException {
        return Map.of("chunks", loader.reload());
    }
}
