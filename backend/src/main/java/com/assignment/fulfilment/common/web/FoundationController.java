package com.assignment.fulfilment.common.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class FoundationController {

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "fulfilment-platform",
                "timestamp", Instant.now().toString()
        );
    }

    @PostMapping(value = "/foundation/echo", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> echo(@Valid @RequestBody EchoRequest request) {
        return Map.of("message", request.message());
    }

    public record EchoRequest(@NotBlank(message = "must not be blank") String message) {
    }
}
