package com.assignment.fulfilment.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "entity_type", nullable = false, length = 64)
    private String entityType;

    @Column(name = "entity_id", nullable = false, length = 255)
    private String entityId;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(nullable = false, length = 64)
    private String actor;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditLog() {
    }

    public AuditLog(String entityType, String entityId, String action, String actor, String details) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.action = action;
        this.actor = actor;
        this.details = details;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }
}
