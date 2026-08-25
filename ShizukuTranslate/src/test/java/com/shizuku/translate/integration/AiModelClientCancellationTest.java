package com.shizuku.translate.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiModelClientCancellationTest {

    @Test
    void preCancelledRequestDoesNotInvokeCallbacks() {
        AiModelClient client = new AiModelClient(new ObjectMapper());
        AtomicInteger connected = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        client.chatStream("system", "user",
                new AiModelClient.AiModelConfig("openai", "key", "http://localhost:1", "model", "disabled"),
                token -> { },
                usage -> completed.incrementAndGet(),
                error -> errors.incrementAndGet(),
                connected::incrementAndGet,
                () -> true);

        assertEquals(0, connected.get());
        assertEquals(0, completed.get());
        assertEquals(0, errors.get());
    }
}
