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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                    config, tokens::add, completion -> usage.set(completion.usage()), error::set, () -> { }, () -> false);

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

    @Test
    void indexTranslateStreamsImagesWithoutAuthHeaderAndWithItsOwnTokenCap() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedAuth = new AtomicReference<>("missing");
        server.createContext("/chat/completions", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedAuth.set(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n"
                    + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":1,\"total_tokens\":6}}\n\n"
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
                    "index-translate", "", "http://127.0.0.1:" + server.getAddress().getPort(),
                    AiModelClient.INDEX_TRANSLATE_MODEL, "disabled");

            List<String> tokens = new CopyOnWriteArrayList<>();
            AtomicReference<String> error = new AtomicReference<>();

            client.chatStreamWithImages("system prompt", "翻译这页",
                    List.of(new AiModelClient.ImagePayload(
                                    new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}, "image/png")),
                    config, tokens::add, completion -> { }, error::set, () -> { }, () -> false);

            assertNull(error.get());
            assertEquals(List.of("好"), tokens);
            // The endpoint is public and keyless: an Authorization header makes it reject on TPM limits.
            assertEquals("null", receivedAuth.get());

            @SuppressWarnings("unchecked")
            Map<String, Object> sent = new ObjectMapper().readValue(receivedBody.get(), Map.class);
            assertEquals(8192, sent.get("max_tokens"));
            assertEquals(Boolean.TRUE, sent.get("stream"));
            assertEquals(Map.of("include_usage", true), sent.get("stream_options"));
            // `thinking` is DeepSeek-only and must not reach the Index endpoint.
            assertTrue(!sent.containsKey("thinking"));
            assertTrue(receivedBody.get().contains("\"type\":\"image_url\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aStreamThatHitsTheOutputCapIsReportedAsTruncated() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            // A real provider sends the stop reason on the last content chunk, together with the
            // (possibly empty) delta — this is exactly the shape a capped answer takes.
            String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"半截译文\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n"
                    + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":8192,\"total_tokens\":8201}}\n\n"
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
                    "index-translate", "", "http://127.0.0.1:" + server.getAddress().getPort(),
                    AiModelClient.INDEX_TRANSLATE_MODEL, "disabled");

            AtomicReference<AiModelClient.StreamCompletion> completion = new AtomicReference<>();
            AtomicReference<String> error = new AtomicReference<>();

            client.chatStreamWithImages("system", "翻译这页",
                    List.of(new AiModelClient.ImagePayload(
                                    new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}, "image/png")),
                    config, token -> { }, completion::set, error::set, () -> { }, () -> false);

            assertNull(error.get());
            assertTrue(completion.get().truncated(), "finish_reason=length must mark the answer as partial");
            assertEquals(8201, completion.get().usage().getTotalTokens());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aNormalStreamIsNotMarkedAsTruncated() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1,\"total_tokens\":4}}\n\n"
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

            AtomicReference<AiModelClient.StreamCompletion> completion = new AtomicReference<>();
            client.chatStreamWithImages("system", "翻译这页",
                    List.of(new AiModelClient.ImagePayload(
                                    new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}, "image/png")),
                    config, token -> { }, completion::set, error -> { }, () -> { }, () -> false);

            assertFalse(completion.get().truncated());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nonStreamingVisionAnswerAlsoFlagsATruncatedResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"半截译文\"},"
                    + "\"finish_reason\":\"length\"}],"
                    + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":8192,\"total_tokens\":8199}}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            AiModelClient client = new AiModelClient(new ObjectMapper());
            AiModelClient.AiModelConfig config = new AiModelClient.AiModelConfig(
                    "index-translate", "", "http://127.0.0.1:" + server.getAddress().getPort(),
                    AiModelClient.INDEX_TRANSLATE_MODEL, "disabled");

            AiModelClient.DeepSeekResult result = client.chatWithImages("system", "翻译这页",
                    List.of(new AiModelClient.ImagePayload(
                            new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}, "image/png")), config);

            assertEquals("半截译文", result.getContent());
            assertTrue(result.isTruncated());
        } finally {
            server.stop(0);
        }
    }
}
