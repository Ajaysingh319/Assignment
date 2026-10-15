package com.assignment.fulfilment.events.api;

import com.assignment.fulfilment.events.EventService;
import com.assignment.fulfilment.events.domain.EventFailureStatus;
import com.assignment.fulfilment.events.dto.EventFailureResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping("/failures")
    public ResponseEntity<List<EventFailureResponse>> listFailures(
            @RequestParam(name = "status", required = false) EventFailureStatus status
    ) {
        return ResponseEntity.ok(eventService.listFailures(status));
    }

    @GetMapping("/failures/dead-lettered")
    public ResponseEntity<List<EventFailureResponse>> listDeadLettered() {
        return ResponseEntity.ok(eventService.listFailures(EventFailureStatus.DEAD_LETTERED));
    }

    @GetMapping("/failures/summary")
    public ResponseEntity<com.assignment.fulfilment.events.dto.EventFailureSummaryResponse> getSummary() {
        return ResponseEntity.ok(eventService.getSummary());
    }

    @GetMapping("/failures/{failureId}")
    public ResponseEntity<EventFailureResponse> getFailure(@PathVariable("failureId") UUID failureId) {
        return ResponseEntity.ok(eventService.getFailure(failureId));
    }

    @PostMapping("/failures/{failureId}/retry")
    public ResponseEntity<EventFailureResponse> retryFailure(@PathVariable("failureId") UUID failureId) {
        return ResponseEntity.ok(eventService.retryFailure(failureId));
    }

    @PostMapping("/failures/retry-eligible")
    public ResponseEntity<List<EventFailureResponse>> retryEligibleFailures() {
        return ResponseEntity.ok(eventService.retryEligibleFailures());
    }

    @PostMapping("/outbox/publish")
    public ResponseEntity<Map<String, Integer>> triggerPublish() {
        int published = eventService.triggerOutboxPublish();
        return ResponseEntity.ok(Map.of("publishedCount", published));
    }
}
