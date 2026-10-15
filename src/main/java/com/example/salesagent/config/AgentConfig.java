package com.example.salesagent.config;

import com.example.salesagent.tools.SalesTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    /**
     * Publishes the sales tools through the MCP server starter. Any MCP client can now connect to
     * http://localhost:8080/sse and call the same read-only operations the in-app agent uses.
     */
    @Bean
    ToolCallbackProvider salesToolCallbackProvider(SalesTools salesTools) {
        return MethodToolCallbackProvider.builder().toolObjects(salesTools).build();
    }
}
