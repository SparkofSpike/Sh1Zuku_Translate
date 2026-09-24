package com.shizuku.translate.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shizuku.translate.dto.TokenUsage;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The streaming vision path talks to a real (loopback) HTTP endpoint instead of a mock, so the
 * contract the upstream provider actually sees — stream flag, usage opt-in, image payload and
 * page-order hints — is asserted on the wire.
 */
class AiModelClientVisionStreamTest {

    @Test
    void visionStreamAsksForStreamingUsageAndForwardsTokens() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> receivedBody = new AtomicReference<>();
        server.createContext("/chat/completions", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n"
                    + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":2,\"total_tokens\":13}}\n\n"
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
                    "deepseek", "key", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "deepseek-flash", "disabled");

            List<String> tokens = new CopyOnWriteArrayList<>();
            AtomicReference<TokenUsage> usage = new AtomicReference<>();
            AtomicReference<String> error = new AtomicReference<>();

            client.chatStreamWithImages("system prompt", "接着上文继续翻译",
                    List.of(new AiModelClient.ImagePayload(
                                    new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}, "image/png")),
                    config, tokens::add, usage::set, error::set, () -> { }, () -> false);

            assertNull(error.get());
            assertEquals(List.of("你", "好"), tokens);
            // Without the usage opt-in the provider reports nothing and the image path would
            // silently stop recording token usage.
            assertEquals(13, usage.get().getTotalTokens());

            @SuppressWarnings("unchecked")
            Map<String, Object> sent = new ObjectMapper().readValue(receivedBody.get(), Map.class);
            assertEquals(Boolean.TRUE, sent.get("stream"));
            assertEquals(Map.of("include_usage", true), sent.get("stream_options"));
            assertTrue(receivedBody.get().contains("\"type\":\"image_url\""));
            assertTrue(receivedBody.get().contains("data:image/png;base64,"));
            assertTrue(receivedBody.get().contains("接着上文继续翻译"));
        } finally {
            server.stop(0);
        }
    }
}
