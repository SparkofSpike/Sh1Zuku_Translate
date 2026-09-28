package com.shizuku.translate.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The text streaming path must pin the DeepSeek thinking mode on the wire. DeepSeek reasons
 * by default when the field is absent, so "disabled" has to be sent explicitly instead of
 * dropped — a regression here silently turns the fast default path into a slow one. Exercised
 * against a loopback HTTP server so the assertion is on the request body, not a mock.
 */
class AiModelClientStreamTest {

    @Test
    void textStreamSendsThinkingDisabledExplicitly() throws Exception {
        String body = capturedStreamBody("disabled", "deepseek");
        assertTrue(body.contains("\"thinking\":{\"type\":\"disabled\"}"), body);
    }

    @Test
    void textStreamSendsThinkingEnabledWhenRequested() throws Exception {
        String body = capturedStreamBody("enabled", "deepseek");
        assertTrue(body.contains("\"thinking\":{\"type\":\"enabled\"}"), body);
    }

    @Test
    void otherProvidersDoNotReceiveTheDeepSeekThinkingField() throws Exception {
        String body = capturedStreamBody("disabled", "openai");
        assertTrue(!body.contains("thinking"), body);
    }

    private static String capturedStreamBody(String thinkingType, String provider) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> receivedBody = new AtomicReference<>();
        server.createContext("/chat/completions", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                    + "data: [DONE]\n\n";
            byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            AiModelClient client = new AiModelClient(new ObjectMapper());
            AiModelClient.AiModelConfig config = new AiModelClient.AiModelConfig(
                    provider, "key", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "deepseek-flash", thinkingType);

            client.chatStream("system prompt", "translate this", config,
                    token -> { }, usage -> { }, error -> { });
            return receivedBody.get();
        } finally {
            server.stop(0);
        }
    }
}
