# PRD --- Distributed Order Fulfilment and Reconciliation Platform

**Project name:** Assignment\
**Working title:** Distributed Order Fulfilment and Reconciliation
Platform\
**Delivery window:** 72 hours\
**Primary goal:** Demonstrate reliable order/inventory workflows,
asynchronous event processing, payment reconciliation, automated tests,
and a usable operational dashboard.

## 1. Problem statement

An order fulfilment platform must accept orders containing multiple
products, reserve inventory without overselling, handle
confirmation/cancellation/fulfilment, process asynchronous events
safely, simulate payment-provider outcomes, and detect inconsistencies
between order and payment states.

The platform must remain correct when requests or events are duplicated,
workers fail, callbacks arrive late, or several customers attempt to
reserve the last units concurrently.

## 2. Goals

1.  Prevent inventory overselling under concurrent requests.
2.  Support multi-item orders with all-or-nothing reservation.
3.  Enforce valid order and reservation state transitions.
4.  Use Kafka for asynchronous domain events and make consumers
    idempotent.
5.  Use a transactional outbox to avoid losing events between a database
    commit and publication.
6.  Simulate payment success, failure, timeout, and duplicate callbacks
    without connecting to a real payment gateway.
7.  Detect and audit payment/order mismatches; make corrective actions
    safe to repeat.
8.  Provide a React dashboard for orders, stock, payments, failed
    events, and reconciliation.
9.  Demonstrate key invariants with automated tests.
10. Deliver a clear README, API documentation, architecture diagrams,
    Git history, and reproducible setup instructions.

## 3. Non-goals

-   Real payment processing or actual refunds.
-   Separate microservices for every domain.
-   A dedicated authentication service.
-   Kubernetes or Docker/Docker Compose.
-   Redis-based correctness or distributed locks in the critical
    inventory path.
-   Production-grade multi-region deployment.
-   Real email/SMS notifications.
-   Complex warehouse-routing optimization.

Use a modular monolith. PostgreSQL is the source of truth. Redis is
optional and must not be introduced unless the core system is already
complete and tested.

## 4. Users and key journeys

### Operator

-   View orders and filter by status.
-   Inspect order items and lifecycle timestamps.
-   View available, reserved, and fulfilled inventory by product and
    warehouse.
-   Inspect simulated payment outcomes.
-   Run reconciliation and inspect mismatches/corrective-action records.
-   Inspect failed events, retry counts, and retry eligible events.

### Customer/API caller (simulated)

-   Create an order with multiple line items.
-   Retry a request safely with the same `Idempotency-Key`.
-   Confirm, cancel, or fulfil an eligible order.
-   Trigger a simulated payment outcome.

## 5. Functional requirements

### FR-1: Product and warehouse catalogue

-   Create/list products and warehouses.
-   Maintain inventory per product and warehouse.
-   Validate positive quantities and required fields.
-   Expose inventory summaries with available, reserved, and fulfilled
    quantities.

### FR-2: Inventory reservation

-   Reserve all order lines or reserve none.
-   Allocate stock deterministically across eligible warehouses, for
    example by sorted warehouse ID.
-   Use PostgreSQL transactions and atomic conditional updates; do not
    rely on an application-side read-then-write check alone.
-   If any line cannot be fully reserved, roll back the entire
    reservation.
-   Release reserved stock on cancellation or expiry.
-   Move reserved quantities to fulfilled quantities when fulfilment
    succeeds.
-   Maintain the invariant:
    `available + reserved + fulfilled = tracked total` for each
    product/warehouse, unless an explicit audited stock adjustment
    changes the tracked total.

### FR-3: Order lifecycle

-   Create an order with one or more product lines.
-   Require `Idempotency-Key` for order creation.
-   Same key + same request payload returns the original result.
-   Same key + different payload returns HTTP 409.
-   Support valid transitions only:
    -   `PENDING_PAYMENT -> CONFIRMED`
    -   `PENDING_PAYMENT -> CANCELLED`
    -   `CONFIRMED -> FULFILLED`
-   A pending order may be cancelled by an explicit request or expiry
    process.
-   Reject invalid transitions with a clear error response.
-   Persist state-change timestamps and audit history.

### FR-4: Asynchronous events

-   Persist domain changes and outbox events in the same database
    transaction.
-   Publish outbox records to Kafka asynchronously.
-   Assume publication can be duplicated.
-   Consumers must deduplicate using a durable processed-event table.
-   Retry transient failures with bounded attempts and backoff.
-   Route exhausted failures to dead-letter topics and persist failure
    details for the dashboard.
-   Provide a safe, audited retry action for failed events.

### FR-5: Simulated payments

-   Simulate `SUCCEEDED`, `FAILED`, and `TIMED_OUT` outcomes.
-   Support duplicate and delayed callbacks.
-   Store provider/reference IDs and callback/event IDs.
-   Process callbacks idempotently.
-   Do not assume a timeout means the payment definitely failed.
-   Prevent late callbacks from silently causing illegal order
    transitions.
-   Do not make actual charges or refunds.

### FR-6: Reconciliation

-   Compare order, reservation, and payment records.
-   Detect at least:
    -   payment succeeded but order is cancelled;
    -   order confirmed/fulfilled but no successful payment is recorded,
        where applicable;
    -   payment/order states are otherwise inconsistent;
    -   stale pending payments/orders beyond a configurable age.
-   Store a reconciliation record with mismatch type, affected entities,
    detection time, status, and action history.
-   Corrective actions are simulated only (for example, flag
    `REFUND_REVIEW_REQUIRED`); no real money movement.
-   Repeated reconciliation runs must not create duplicate unresolved
    records or repeat the same corrective action.

### FR-7: Dashboard

-   Orders: status filters, order detail, items, timestamps.
-   Inventory: product/warehouse availability and reserved/fulfilled
    quantities.
-   Payments: payment state and callback history.
-   Reconciliation: mismatch list and action status.
-   Failed events: topic/event ID, error, attempts, timestamps, retry
    action.
-   Show loading, empty, and error states.
-   Use backend data; do not hardcode sample dashboard metrics.

### FR-8: Observability and operations

-   Health endpoint and useful structured logs.
-   Correlation/request ID where practical.
-   Clear validation and domain error responses.
-   Track event attempts, failure reasons, and state transitions.

## 6. Non-functional requirements

-   **Correctness:** database transactions and constraints enforce
    inventory correctness.
-   **Idempotency:** repeated API requests, events, and payment
    callbacks do not duplicate effects.
-   **Consistency:** all important state transitions are transactional;
    asynchronous projections are eventually consistent and observable.
-   **Testability:** core domain logic can be tested without cloud
    services; integration tests use real PostgreSQL/Kafka where needed.
-   **Security:** no secrets in Git; validate input; configure CORS
    explicitly; never expose credentials in logs.
-   **Performance:** demonstrate a concurrency test with 100
    simultaneous attempts to reserve 1 unit each against stock of 10:
    exactly 10 succeed and 90 fail.
-   **Usability:** dashboard workflows are understandable without
    reading source code.
-   **Cost:** use free tiers only; verify current limits before choosing
    a provider; no paid upgrades.
-   **Environment:** no Docker/Docker Compose. Backend and frontend must
    run locally without containers. Managed cloud services may be used
    for PostgreSQL/Kafka if free availability is confirmed.

## 7. Acceptance criteria

1.  Product, warehouse, inventory, and order APIs work with validation.
2.  Multi-line reservation is all-or-nothing.
3.  Concurrent reservation test proves no overselling.
4.  Duplicate order request with the same key does not create another
    order.
5.  Same idempotency key with a different payload returns 409.
6.  Cancellation/expiry releases inventory exactly once.
7.  Outbox publisher and consumers tolerate duplicate delivery.
8.  Failed event retries are bounded and exhausted messages are visible
    as failed/DLT events.
9.  Duplicate payment callbacks do not duplicate payment effects.
10. Reconciliation detects a successful payment for a cancelled order
    and records a simulated corrective action.
11. Re-running reconciliation is idempotent.
12. Dashboard loads real API data and supports operational inspection.
13. Automated tests cover concurrency, duplicate events, payment
    failures/callbacks, and cancellation.
14. README documents architecture, schema, API examples, event flow,
    environment setup, tests, deployment, limitations, and design
    decisions.
15. GitHub repository contains no secrets and has meaningful incremental
    commits.

## 8. Priority order

**P0 --- must complete:** schema/migrations, catalogue, transactional
inventory reservation, order lifecycle/idempotency, simulated payments,
reconciliation, key tests, README.

**P1 --- core assignment quality:** outbox/Kafka, processed-event
deduplication, retry/DLT, operational dashboard, failure injection.

**P2 --- only after P0/P1:** cloud deployment, UI polish, optional
Redis, extra metrics. Do not add optional infrastructure before core
invariants pass.

## 9. Delivery evidence

-   GitHub repository with incremental commits.
-   PRD and TDD.
-   Automated test output.
-   Architecture and sequence diagrams in README or `docs/`.
-   Screenshots or short recording of the dashboard, failed-event flow,
    and reconciliation result.
-   Deployment URL only after a real deployment has been verified.
-   Short explanation of trade-offs and known limitations.
