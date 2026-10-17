package com.assignment.fulfilment.common.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findByEntityTypeAndEntityId(String entityType, String entityId);

    List<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, String entityId);

    List<AuditLog> findByEntityIdInOrderByCreatedAtAsc(java.util.Collection<String> entityIds);

    @org.springframework.data.jpa.repository.Query("""
            select a from AuditLog a
            where (:entityType is null or a.entityType = :entityType)
              and (:action is null or a.action = :action)
            order by a.createdAt desc
            """)
    List<AuditLog> search(
            @org.springframework.data.repository.query.Param("entityType") String entityType,
            @org.springframework.data.repository.query.Param("action") String action,
            org.springframework.data.domain.Pageable pageable
    );
}
