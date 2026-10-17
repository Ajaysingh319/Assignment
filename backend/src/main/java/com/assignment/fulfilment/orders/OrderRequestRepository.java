package com.assignment.fulfilment.orders;

import com.assignment.fulfilment.orders.domain.OrderRequest;
import com.assignment.fulfilment.orders.domain.OrderRequestOutcome;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRequestRepository extends JpaRepository<OrderRequest, UUID> {

    @Query("""
            select r from OrderRequest r
            where (:outcome is null or r.outcome = :outcome)
              and (:keyPrefix is null or r.idempotencyKey like :keyPrefix)
            order by r.createdAt desc
            """)
    List<OrderRequest> search(
            @Param("outcome") OrderRequestOutcome outcome,
            @Param("keyPrefix") String keyPrefixPattern,
            Pageable pageable
    );

    @Query("""
            select r.outcome, count(r) from OrderRequest r
            where (:keyPrefix is null or r.idempotencyKey like :keyPrefix)
            group by r.outcome
            """)
    List<Object[]> countByOutcome(@Param("keyPrefix") String keyPrefixPattern);

    List<OrderRequest> findByOrderIdOrderByCreatedAtAsc(UUID orderId);
}
