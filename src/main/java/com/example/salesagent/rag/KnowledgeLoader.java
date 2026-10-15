package com.example.salesagent.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Loads the markdown files in src/main/resources/knowledge into the vector store on first start.
 * File names follow "<docType>-<name>.md" (product-, report-, policy-) and the first "# " line is the title.
 */
@Component
@Order(2)
public class KnowledgeLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeLoader.class);

    private final KnowledgeService knowledge;
    private final MongoTemplate mongo;
    private final boolean autoload;
    private final String collection;

    public KnowledgeLoader(KnowledgeService knowledge, MongoTemplate mongo,
                           @Value("${app.knowledge.autoload:true}") boolean autoload,
                           @Value("${app.knowledge.collection:product_knowledge}") String collection) {
        this.knowledge = knowledge;
        this.mongo = mongo;
        this.autoload = autoload;
        this.collection = collection;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!autoload) {
            return;
        }
        try {
            if (mongo.collectionExists(collection) && mongo.getCollection(collection).countDocuments() > 0) {
                log.info("Knowledge base already loaded, skipping.");
                return;
            }
            log.info("Embedding knowledge documents with Ollama (first start only)...");
            int chunks = loadAll();
            log.info("Knowledge base ready: {} chunks.", chunks);
        } catch (Exception e) {
            log.warn("Could not load the knowledge base ({}). Make sure Ollama is running with the embedding model, "
                    + "then call POST /api/knowledge/reload.", e.getMessage());
        }
    }

    /** Clears the vector collection and re-embeds every file in classpath:knowledge. */
    public int reload() throws IOException {
        if (mongo.collectionExists(collection)) {
            mongo.getCollection(collection).deleteMany(new Document());
        }
        return loadAll();
    }

    private int loadAll() throws IOException {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:knowledge/*.md");
        Arrays.sort(files, (a, b) -> String.valueOf(a.getFilename()).compareTo(String.valueOf(b.getFilename())));
        int total = 0;
        for (Resource file : files) {
            String name = file.getFilename();
            String text = new String(file.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String docType = name.contains("-") ? name.substring(0, name.indexOf('-')) : "note";
            total += knowledge.ingest(titleOf(text, name), docType, name, text);
        }
        return total;
    }

    private static String titleOf(String markdown, String fallback) {
        return markdown.lines()
                .filter(l -> l.startsWith("# "))
                .map(l -> l.substring(2).trim())
                .findFirst()
                .orElse(fallback);
    }
}
