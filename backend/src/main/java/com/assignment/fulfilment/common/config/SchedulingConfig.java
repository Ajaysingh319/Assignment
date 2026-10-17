package com.assignment.fulfilment.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the background jobs: outbox publication and pending-order expiry.
 * Without this, {@code @Scheduled} methods are silently ignored and outbox events stay unpublished.
 * Unit tests disable it via {@code app.scheduling.enabled=false} to keep them deterministic.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
