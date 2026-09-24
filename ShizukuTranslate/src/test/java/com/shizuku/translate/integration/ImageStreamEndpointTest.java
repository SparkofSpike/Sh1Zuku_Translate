package com.shizuku.translate.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shizuku.translate.dto.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * Drives {@code POST /translate/image/stream} over real HTTP (Tomcat, multipart parsing, the
 * async SSE writer and the Spring Security filter chain) instead of MockMvc, because the whole
 * point of the endpoint is that events leave the server while the model is still working — a
 * buffered implementation would pass a MockMvc test and still look frozen in a browser.
 *
 * <p>The model client is mocked, so no provider call happens; the streaming shape itself is real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jwt.secret=integration-test-secret-0123456789abcdefghijklmnopqrstuv",
        "app.jwt.issuer=shizuku-translate-test",
        "spring.datasource.url=jdbc:h2:mem:imagestream;DB_CLOSE_DELAY=-1",
        "deepseek.api.key=test-key",
        "app.mail.host="
})
class ImageStreamEndpointTest {

    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x01
    };

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AiModelClient aiModelClient;

    private String baseUrl() {
        return "http://127.0.0.1:" + port + "/api/v1";
    }

    @BeforeEach
    void seedUser() {
        // Only insert once: the first test writes a translation record and token-usage rows for
        // this user, and the foreign keys stop it from being deleted and recreated between
        // methods of the same class run.
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = 'streamuser'", Integer.class);
        if (existing != null && existing > 0) {
            return;
        }
        jdbcTemplate.update("INSERT INTO users (username, email, password_hash, email_verified, created_at, updated_at) "
                        + "VALUES ('streamuser', 'streamuser@example.com', ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                passwordEncoder.encode("pass1234"));
    }

    @Test
    void imageStreamDeliversTokensWhileTheModelIsStillWorking() throws Exception {
        String jwt = login();

        // The model pauses after the first token until the test has actually read it off the
        // wire. An implementation that buffered the whole answer would block here and blow the
        // await timeout instead of streaming.
        CountDownLatch allowSecondToken = new CountDownLatch(1);
        AtomicInteger imagesInRequest = new AtomicInteger();
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<AiModelClient.ImagePayload> payloads = invocation.getArgument(2);
            imagesInRequest.set(payloads.size());
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onToken = invocation.getArgument(4);
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<TokenUsage> onComplete = invocation.getArgument(5);
            onToken.accept("第一页译文");
            if (!allowSecondToken.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("client never read the first token; stream is buffered");
            }
            onToken.accept("第二页译文");
            onComplete.accept(new TokenUsage());
            return null;
        }).when(aiModelClient).chatStreamWithImages(anyString(), anyString(), any(), any(AiModelClient.AiModelConfig.class),
                any(), any(), any(), any(), any());

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/translate/image/stream"))
                .header("Authorization", "Bearer " + jwt)
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(
                        multipartBody("{\"sourceText\":\"\",\"model\":\"deepseek-flash\"}")))
                .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"),
                "expected an SSE response, got " + response.headers().firstValue("Content-Type"));

        List<String> tokens = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        boolean done = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty()) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> event = objectMapper.readValue(data, Map.class);
                if (event.get("token") instanceof String token) {
                    tokens.add(token);
                    if (tokens.size() == 1) {
                        // Proves the first token reached the client while the model is paused.
                        allowSecondToken.countDown();
                    }
                }
                if (event.get("error") != null) errors.add(String.valueOf(event.get("error")));
                if (Boolean.TRUE.equals(event.get("done"))) {
                    done = true;
                    break;
                }
            }
        }

        assertEquals(List.of(), errors);
        assertTrue(done, "stream ended without a done event");
        assertEquals(List.of("第一页译文", "第二页译文"), tokens);
        assertEquals(1, imagesInRequest.get(), "the uploaded page must reach the model client");
    }

    @Test
    void emptyUploadFailsAsAPlainHttpErrorBeforeAnyStreamStarts() throws Exception {
        String jwt = login();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/translate/image/stream"))
                .header("Authorization", "Bearer " + jwt)
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBodyWithoutImages("{\"sourceText\":\"\"}")))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        // The caller has to see a normal 4xx with a readable message, not a broken stream.
        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("请至少上传一张图片"), response.body());
    }

    private static final String BOUNDARY = "----shizukuTestBoundary";

    private byte[] multipartBody(String requestJson) throws Exception {
        return buildMultipart(true, requestJson);
    }

    private byte[] multipartBodyWithoutImages(String requestJson) throws Exception {
        return buildMultipart(false, requestJson);
    }

    private byte[] buildMultipart(boolean withImage, String requestJson) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (withImage) {
            out.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write("Content-Disposition: form-data; name=\"images\"; filename=\"page.png\"\r\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.write("Content-Type: image/png\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.write(PNG_BYTES);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write("Content-Disposition: form-data; name=\"request\"\r\n".getBytes(StandardCharsets.UTF_8));
        out.write("Content-Type: application/json\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(requestJson.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private String login() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"streamuser\",\"password\":\"pass1234\"}"))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return objectMapper.readTree(response.body()).get("token").asText();
    }
}
