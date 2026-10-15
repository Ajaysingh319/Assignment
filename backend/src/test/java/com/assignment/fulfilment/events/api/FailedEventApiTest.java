package com.assignment.fulfilment.events.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.assignment.fulfilment.events.consumer.EventRetryHandler;
import com.assignment.fulfilment.events.consumer.OrderEventConsumer;
import com.assignment.fulfilment.events.domain.EventFailure;
import com.assignment.fulfilment.events.repository.EventFailureRepository;
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
class FailedEventApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventRetryHandler eventRetryHandler;

    @Autowired
    private EventFailureRepository eventFailureRepository;

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String createEnvelopeJson(String eventId) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"ORDER_CONFIRMED\",\"eventVersion\":1,\"occurredAt\":\""
                + Instant.now() + "\",\"aggregateType\":\"ORDER\",\"aggregateId\":\"" + UUID.randomUUID() + "\",\"payload\":\"{}\"}";
    }

    @Test
    @DisplayName("API: List failures, dead-lettered, summary, get single failure, and retry")
    void testFailedEventApiEndpoints() throws Exception {
        String eventId = "evt-api-" + uid();
        String payload = createEnvelopeJson(eventId);

        EventFailure failure = eventRetryHandler.handleFailure(
                eventId,
                "order-events",
                0,
                300L,
                OrderEventConsumer.CONSUMER_NAME,
                "API simulated consumer exception",
                payload
        );
        failure.recordRetry("Dead lettered API exception", 1);
        failure = eventFailureRepository.save(failure);
        UUID failureId = failure.getId();

        // 1. GET /api/events/failures -> 200 OK
        mockMvc.perform(get("/api/events/failures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // 2. GET /api/events/failures/dead-lettered -> 200 OK
        mockMvc.perform(get("/api/events/failures/dead-lettered"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // 3. GET /api/events/failures/summary -> 200 OK
        mockMvc.perform(get("/api/events/failures/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalFailures").isNumber())
                .andExpect(jsonPath("$.deadLetteredCount").isNumber());

        // 4. GET /api/events/failures/{failureId} -> 200 OK
        mockMvc.perform(get("/api/events/failures/" + failureId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(failureId.toString()))
                .andExpect(jsonPath("$.eventId").value(eventId))
                .andExpect(jsonPath("$.topic").value("order-events"))
                .andExpect(jsonPath("$.consumerName").value(OrderEventConsumer.CONSUMER_NAME))
                .andExpect(jsonPath("$.attemptCount").isNumber())
                .andExpect(jsonPath("$.firstFailedAt").isNotEmpty())
                .andExpect(jsonPath("$.lastError").isNotEmpty())
                .andExpect(jsonPath("$.status").value("DEAD_LETTERED"))
                .andExpect(jsonPath("$.eligibleForRetry").value(true));

        // 5. POST /api/events/failures/{failureId}/retry -> 200 OK
        mockMvc.perform(post("/api/events/failures/" + failureId + "/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(failureId.toString()))
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty())
                .andExpect(jsonPath("$.eligibleForRetry").value(false));

        // 6. POST /api/events/failures/retry-eligible -> 200 OK
        mockMvc.perform(post("/api/events/failures/retry-eligible"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
