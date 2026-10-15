package com.example.salesagent.web;

import com.example.salesagent.agent.SalesAgentService;
import com.example.salesagent.agent.SalesAgentService.AgentAnswer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    public record AskRequest(@NotBlank @Size(max = 1000) String question) {
    }

    private final SalesAgentService agent;

    public AgentController(SalesAgentService agent) {
        this.agent = agent;
    }

    @PostMapping("/ask")
    public AgentAnswer ask(@Valid @RequestBody AskRequest request) {
        return agent.ask(request.question().trim());
    }
}
