package com.assignment.fulfilment.dashboard.dto;

import com.assignment.fulfilment.common.audit.AuditLog;
import java.time.Instant;
import java.util.UUID;

public record AuditEntryResponse(
        UUID id,
        String entityType,
        String entityId,
        String action,
        String actor,
        String details,
        Instant createdAt
) {
    public static AuditEntryResponse from(AuditLog log) {
        return new AuditEntryResponse(
                log.getId(), log.getEntityType(), log.getEntityId(), log.getAction(), log.getActor(),
                log.getDetails(), log.getCreatedAt()
        );
    }
}
