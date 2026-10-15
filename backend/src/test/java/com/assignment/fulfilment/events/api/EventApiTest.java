package com.assignment.fulfilment.events.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.assignment.fulfilment.events.consumer.EventRetryHandler;
import com.assignment.fulfilment.events.consumer.OrderEventConsumer;
import com.assignment.fulfilment.events.domain.EventFailure;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EventApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventRetryHandler eventRetryHandler;

    @Test
    @DisplayName("API: List failures, retry failed event, and publish outbox")
    void testEventApiEndpoints() throws Exception {
        String eventId = UUID.randomUUID().toString();
        String payload = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"ORDER_CANCELLED\",\"eventVersion\":1,\"occurredAt\":\"" + Instant.now() + "\",\"aggregateType\":\"ORDER\",\"aggregateId\":\"" + UUID.randomUUID() + "\",\"payload\":\"{}\"}";

        EventFailure failure = eventRetryHandler.handleFailure(
                eventId,
                "order-events",
                0,
                500L,
                OrderEventConsumer.CONSUMER_NAME,
                "Database timeout",
                payload
        );

        // 1. GET /api/events/failures
        mockMvc.perform(get("/api/events/failures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].eventId").isNotEmpty());

        // 2. POST /api/events/failures/{failureId}/retry -> 200 OK
        mockMvc.perform(post("/api/events/failures/" + failure.getId() + "/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty());

        // 3. POST /api/events/outbox/publish -> 200 OK
        mockMvc.perform(post("/api/events/outbox/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedCount").isNumber());
    }
}
