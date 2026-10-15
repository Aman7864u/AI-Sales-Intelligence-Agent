package com.example.salesagent.agent;

import com.example.salesagent.rag.KnowledgeService;
import com.example.salesagent.tools.SalesTools;
import com.example.salesagent.tools.ToolTrace;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The agent loop:
 *  1. understand the question (the model, guided by the system prompt)
 *  2. retrieve business context from the vector store (RAG)
 *  3. let the model call the approved read-only sales tools for numbers
 *  4. produce an evidence-backed answer with recommendations
 */
@Service
public class SalesAgentService {

    private static final Logger log = LoggerFactory.getLogger(SalesAgentService.class);

    public record Source(String title, String docType, String snippet, Double score) {
    }

    public record AgentAnswer(String answer, List<ToolTrace.Call> toolCalls, List<Source> sources,
                              long elapsedMs, String model) {
    }

    private final ChatClient chatClient;
    private final KnowledgeService knowledge;
    private final SalesTools salesTools;
    private final String model;
    private final String currency;

    public SalesAgentService(ChatClient chatClient, KnowledgeService knowledge, SalesTools salesTools,
                             @Value("${spring.ai.ollama.chat.options.model}") String model,
                             @Value("${app.currency:USD}") String currency) {
        this.chatClient = chatClient;
        this.knowledge = knowledge;
        this.salesTools = salesTools;
        this.model = model;
        this.currency = currency;
    }

    public AgentAnswer ask(String question) {
        long started = System.currentTimeMillis();

        // 2. RAG: product notes, reports and policies relevant to the question
        List<Document> docs;
        try {
            docs = knowledge.search(question);
        } catch (RuntimeException e) {
            if (isConnectionProblem(e)) {
                throw e;
            }
            log.warn("Knowledge search failed, continuing without context: {}", e.getMessage());
            docs = List.of();
        }

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt()),
                new UserMessage(userPrompt(question, docs))));

        // 3 + 4. the model calls tools as needed and writes the answer
        ToolTrace.begin();
        String answer;
        List<ToolTrace.Call> calls;
        try {
            answer = chatClient.prompt(prompt).tools(salesTools).call().content();
        } finally {
            calls = ToolTrace.end();
        }

        if (answer == null || answer.isBlank()) {
            answer = "The model returned an empty answer. Try rephrasing the question.";
        }
        return new AgentAnswer(answer.trim(), calls, docs.stream().map(SalesAgentService::toSource).toList(),
                System.currentTimeMillis() - started, model);
    }

    // ------------------------------------------------------------------ prompts

    private String systemPrompt() {
        LocalDate today = LocalDate.now();
        YearMonth current = YearMonth.from(today);
        YearMonth last = current.minusMonths(1);
        return """
                You are the Sales Intelligence Agent for a retail company. You help sales managers understand \
                performance and decide what to do next.

                Dates: today is %s. The current month is %d-%02d and is still in progress. "Last month" means \
                year %d, month %d. When you call a tool, pass the year and month as numbers.

                How to work:
                1. Work out what the manager is asking.
                2. Always use the sales tools to get numbers. Never invent or estimate figures. If a tool returns \
                no data, say so.
                3. Use the BUSINESS CONTEXT passages (product notes, reports, policies) to explain likely causes \
                and to ground your recommendations. Only use context that is relevant. If the context does not \
                explain a result, say that the cause is unknown rather than guessing.
                4. Structure the answer with these bold headings: **Summary** (two sentences), **Evidence** \
                (bullets with the figures), **Likely causes** (tie each to the context and label inferences as \
                inferences), **Recommended actions** (3 to 5 concrete, prioritised steps).
                5. Currency is %s. Round revenue to whole numbers and percentages to one decimal. Keep the \
                answer under 350 words.
                """.formatted(today, current.getYear(), current.getMonthValue(),
                last.getYear(), last.getMonthValue(), currency);
    }

    private static String userPrompt(String question, List<Document> docs) {
        String context = docs.isEmpty()
                ? "(no relevant documents were found in the knowledge base)"
                : docs.stream()
                        .map(d -> "[" + d.getMetadata().getOrDefault("title", "Untitled") + " / "
                                + d.getMetadata().getOrDefault("docType", "note") + "]\n" + d.getText())
                        .collect(Collectors.joining("\n\n---\n\n"));
        return "BUSINESS CONTEXT (retrieved from the knowledge base):\n" + context
                + "\n\nMANAGER QUESTION:\n" + question;
    }

    private static Source toSource(Document d) {
        String text = d.getText() == null ? "" : d.getText().replaceAll("\\s+", " ").trim();
        String snippet = text.length() > 220 ? text.substring(0, 220) + "..." : text;
        return new Source(String.valueOf(d.getMetadata().getOrDefault("title", "Untitled")),
                String.valueOf(d.getMetadata().getOrDefault("docType", "note")), snippet, d.getScore());
    }

    private static boolean isConnectionProblem(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.ConnectException || t instanceof org.springframework.web.client.ResourceAccessException) {
                return true;
            }
        }
        return false;
    }
}
