package com.shizuku.translate.controller;

import com.shizuku.translate.service.feedback.TranslationFeedbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-level contract tests for the anonymous feedback endpoints: validation before the
 * service is touched, and correct delegation when the payload is valid.
 */
class FeedbackControllerTest {

    private TranslationFeedbackService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(TranslationFeedbackService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new FeedbackController(service))
                .setControllerAdvice(new com.shizuku.translate.exception.GlobalExceptionHandler())
                .build();
    }

    @Test
    void rateAcceptsValidPayloadAndDelegates() throws Exception {
        mockMvc.perform(post("/api/v1/feedback/rate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-1\",\"rating\":4,\"tags\":[\"tone\"],"
                                + "\"comment\":null,\"deviceFp\":\"fp\",\"permalink\":\"/translate\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        verify(service).submitRate(eq("r-1"), eq(4), any(), eq(null), eq("fp"), eq("/translate"));
    }

    @Test
    void rateRejectsOutOfRangeRating() throws Exception {
        mockMvc.perform(post("/api/v1/feedback/rate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-1\",\"rating\":9}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void rateRejectsMissingRequestId() throws Exception {
        mockMvc.perform(post("/api/v1/feedback/rate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":3}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void eventAcceptsCopyAndCarriesDeviceContext() throws Exception {
        mockMvc.perform(post("/api/v1/feedback/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-2\",\"event\":\"copy\",\"payload\":{},"
                                + "\"deviceFp\":\"fp-2\",\"permalink\":\"/translate\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        verify(service).submitEvent(eq("r-2"), eq("copy"), any());
    }

    @Test
    void eventRejectsUnknownType() throws Exception {
        mockMvc.perform(post("/api/v1/feedback/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"r-2\",\"event\":\"delete_everything\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
