package com.assignment.fulfilment.orders;

import com.assignment.fulfilment.common.exception.InvalidOrderStateTransitionException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expires PENDING_PAYMENT orders older than {@code app.reservation-expiry-minutes} and releases their stock.
 * Each order is expired in its own transaction under a row lock (OrderService.expireOrder), so a payment that
 * confirms the order concurrently wins or loses cleanly; a late success after expiry is caught by reconciliation.
 */
@Component
public class OrderExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryScheduler.class);

    private final OrderService orderService;
    private final long expiryMinutes;

    public OrderExpiryScheduler(
            OrderService orderService,
            @Value("${app.reservation-expiry-minutes:30}") long expiryMinutes
    ) {
        this.orderService = orderService;
        this.expiryMinutes = expiryMinutes;
    }

    @Scheduled(fixedDelayString = "${app.order-expiry-check-interval-ms:30000}", initialDelay = 15000)
    public int expireStaleOrders() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(expiryMinutes));
        List<UUID> candidates = orderService.findExpirableOrderIds(cutoff);
        int expired = 0;
        for (UUID orderId : candidates) {
            try {
                orderService.expireOrder(orderId);
                expired++;
            } catch (InvalidOrderStateTransitionException e) {
                log.info("Order {} changed state before it could expire: {}", orderId, e.getMessage());
            } catch (RuntimeException e) {
                log.error("Failed to expire order {}", orderId, e);
            }
        }
        if (expired > 0) {
            log.info("Expired {} PENDING_PAYMENT orders older than {} minutes and released their reservations", expired, expiryMinutes);
        }
        return expired;
    }
}
