# TDD --- Distributed Order Fulfilment and Reconciliation Platform

## 1. Architecture decisions

### Chosen architecture: modular monolith

One Spring Boot application contains clear modules. This reduces
deployment and debugging overhead while preserving domain boundaries.

Modules: 1. `catalog` --- products and warehouses. 2. `inventory` ---
stock, reservation, release, fulfilment. 3. `orders` --- order creation
and lifecycle. 4. `payments` --- simulated payment outcomes and
callbacks. 5. `events` --- transactional outbox, Kafka
publisher/consumers, idempotency, retries and DLT. 6. `reconciliation`
--- mismatch detection, audit and simulated corrective actions. 7.
`dashboard` --- read APIs/aggregates for the React UI. 8. `common` ---
error model, IDs, timestamps, logging and shared utilities.

Keep controllers thin. Business rules belong in services/domain logic.
Database access belongs in repositories. Avoid circular module
dependencies.

### Stack

-   Java 17 or newer supported by the selected Spring Boot release.
-   Spring Boot, Maven.
-   Spring Web, Validation, Spring Data JPA, PostgreSQL driver, Flyway.
-   Spring Kafka.
-   JUnit 5, Mockito, Spring Boot Test.
-   React + Vite; TypeScript if comfortable, otherwise JavaScript.
-   PostgreSQL as source of truth.
-   Kafka for asynchronous events.
-   Redis is not required for correctness and is deferred.
-   No Docker or Docker Compose.

Pin compatible dependency versions using the Spring Boot parent/BOM. Do
not invent versions; use a stable compatible set at implementation time.

## 2. Deployment/topology

``` text
Browser (React/Vite)
       |
       | HTTPS / JSON
       v
Spring Boot modular monolith
  | catalog
  | inventory
  | orders
  | payments
  | event processing
  | reconciliation
  | dashboard APIs
       |                 \
       | SQL              \ Kafka publish/consume
       v                   v
Managed PostgreSQL       Managed Kafka
```

Local development: - Run PostgreSQL and Kafka as installed local
services if available, or use verified free managed services. - Do not
use containers. - Backend uses environment variables for connection
details. - Frontend uses a configurable API base URL. - If free managed
Kafka is unavailable, keep a documented local Kafka option and make the
application fail clearly when event infrastructure is not configured. Do
not pretend Kafka was tested if it was not.

## 3. Suggested repository structure

``` text
Assignment/
├── backend/
│   ├── pom.xml
│   └── src/
│       ├── main/java/.../
│       │   ├── catalog/
│       │   ├── inventory/
│       │   ├── orders/
│       │   ├── payments/
│       │   ├── events/
│       │   ├── reconciliation/
│       │   ├── dashboard/
│       │   └── common/
│       └── main/resources/
│           ├── application.yml
│           └── db/migration/
├── frontend/
├── docs/
│   ├── PRD.md
│   ├── TDD.md
│   └── CODEZERO_PROMPTS.md
├── .env.example
├── .gitignore
└── README.md
```

Adjust package names to the real base package. Never generate a second
nested repository or duplicate project root.

## 4. Data model

Use UUIDs or another consistent ID strategy. Store timestamps in UTC
using `timestamptz` where appropriate. Use `NUMERIC` for monetary
amounts, not floating point. Quantities should be positive integers
unless fractional inventory is explicitly required.

### `products`

-   `id` UUID PK
-   `sku` VARCHAR unique not null
-   `name` VARCHAR not null
-   `description` nullable
-   `active` BOOLEAN not null
-   `created_at`, `updated_at`

### `warehouses`

-   `id` UUID PK
-   `code` VARCHAR unique not null
-   `name` VARCHAR not null
-   `location` nullable
-   `active` BOOLEAN not null

### `inventory`

One row per product and warehouse. - `product_id` FK - `warehouse_id`
FK - `available_qty` BIGINT not null, \>= 0 - `reserved_qty` BIGINT not
null, \>= 0 - `fulfilled_qty` BIGINT not null, \>= 0 - `version` BIGINT
for optimistic versioning if useful - `updated_at` - PK
`(product_id, warehouse_id)`

For tracked stock, maintain
`available_qty + reserved_qty + fulfilled_qty = tracked total`; the
total may be stored explicitly or derived and updated through audited
stock adjustments. Never silently alter inventory outside an explicit
operation.

### `orders`

-   `id` UUID PK
-   `status` VARCHAR/enum not null
-   `idempotency_key` VARCHAR unique not null for create requests
-   `request_hash` VARCHAR not null
-   `currency` CHAR(3)
-   `total_amount` NUMERIC(12,2)
-   `created_at`, `updated_at`, `confirmed_at`, `cancelled_at`,
    `fulfilled_at`

If a key is scoped by caller, use a composite unique constraint
`(caller_scope, idempotency_key)`. For this assignment, a global key is
acceptable if caller identity is not implemented.

### `order_items`

-   `id` UUID PK
-   `order_id` FK
-   `product_id` FK
-   `quantity` BIGINT \> 0
-   `unit_price` NUMERIC(12,2) if price is in scope
-   `line_total` NUMERIC(12,2)

### `reservations`

-   `id` UUID PK
-   `order_id` FK
-   `product_id` FK
-   `warehouse_id` FK
-   `quantity` BIGINT \> 0
-   `status` `RESERVED`, `CONFIRMED`, `RELEASED`, or `FULFILLED`
-   `created_at`, `updated_at`, `released_at`, `fulfilled_at`
-   Index on `(order_id, status)`

If one order line is split across warehouses, multiple reservation rows
can represent that allocation.

### `payments`

-   `id` UUID PK
-   `order_id` FK
-   `provider_reference` nullable/unique when present
-   `status` `PENDING`, `SUCCEEDED`, `FAILED`, `TIMED_OUT`
-   `amount` NUMERIC(12,2)
-   `currency` CHAR(3)
-   `last_callback_id` nullable
-   `created_at`, `updated_at`, `succeeded_at`

### `payment_callbacks`

-   `id` UUID PK
-   `provider_callback_id` unique not null
-   `payment_id` FK
-   `received_payload` JSONB (exclude secrets/sensitive data)
-   `result`
-   `received_at`, `processed_at`

### `processed_events`

-   `consumer_name` VARCHAR not null
-   `event_id` UUID/VARCHAR not null
-   `processed_at` timestamptz not null
-   PK `(consumer_name, event_id)`

### `outbox_events`

-   `id` UUID PK (stable event ID)
-   `aggregate_type`, `aggregate_id`
-   `event_type`, `event_version`
-   `payload` JSONB
-   `created_at`
-   `published_at` nullable
-   `attempt_count` not null default 0
-   `last_error` nullable
-   Index on `(published_at, created_at)`

### `event_failures`

-   `id` UUID PK
-   `event_id`, `topic`, `partition_number`, `offset_number`
-   `consumer_name`
-   `attempt_count`
-   `last_error`
-   `status` `RETRYING`, `DEAD_LETTERED`, `REPLAYED`, `RESOLVED`
-   `first_failed_at`, `last_failed_at`, `resolved_at`

### `reconciliation_records`

-   `id` UUID PK
-   `mismatch_key` VARCHAR unique not null
-   `order_id` FK nullable
-   `payment_id` FK nullable
-   `mismatch_type`
-   `status` `OPEN`, `ACTION_REQUIRED`, `RESOLVED`, `DISMISSED`
-   `details` JSONB
-   `detected_at`, `updated_at`, `resolved_at`

### `audit_log`

-   `id` UUID PK
-   `entity_type`, `entity_id`
-   `action`
-   `actor` (e.g. `SYSTEM` or `OPERATOR`)
-   `details` JSONB
-   `created_at`

## 5. Invariants and transaction boundaries

### Inventory invariant

No available quantity may become negative. All reserve/release/fulfil
operations use database transactions and conditional updates. PostgreSQL
is the correctness authority; Redis is not required.

For a single inventory row, use an atomic update pattern equivalent to:

``` sql
UPDATE inventory
SET available_qty = available_qty - :quantity,
    reserved_qty = reserved_qty + :quantity,
    updated_at = now()
WHERE product_id = :productId
  AND warehouse_id = :warehouseId
  AND available_qty >= :quantity;
```

Check affected row count. Zero rows means insufficient stock or a
missing inventory row. For multi-line orders: 1. Validate all products
and quantities. 2. Start one transaction. 3. Process product/warehouse
allocations in deterministic order. 4. Apply conditional updates and
insert reservation rows. 5. If any line cannot be completely allocated,
throw an exception that rolls back the entire transaction. 6. Persist
the order and outbox event in the same transaction, or carefully
coordinate creation so failed reservations cannot leave a live order. 7.
Verify behavior under concurrent tests using real PostgreSQL, not mocks.

Do not implement `SELECT available_qty` followed by an unconditional
update; that is race-prone.

### Cancellation

Within one transaction, lock/check the order state, transition only if
currently cancellable, release only reservations in `RESERVED`, update
inventory by exactly those reservation quantities, write audit/outbox
records, and commit. A repeated cancellation must not release stock
again.

### Fulfilment

Only a confirmed order can be fulfilled. Within one transaction, move
each eligible reservation from reserved to fulfilled, update order
state, and write audit/outbox records. A repeated fulfilment must not
apply twice.

### Idempotency

For order creation, canonicalize the request and compute a stable hash.
Store the key and hash under a unique constraint. On duplicate: - same
hash: return the stored order/result; - different hash: HTTP 409. Handle
concurrent requests using the database unique constraint and then
re-read the winning record.

### Outbox

Write the outbox event in the same DB transaction as the domain state
change. A publisher sends unpublished events to Kafka and then marks
them published. A crash after Kafka accepts the event but before the DB
update can produce duplicates; consumers must therefore be idempotent.
Do not claim end-to-end exactly-once delivery.

### Idempotent consumer

Use a transaction and a unique `(consumer_name, event_id)` record. The
event's database effects and processed marker should commit atomically.
On a duplicate marker conflict, skip business effects. If processing has
external side effects, use another outbox rather than relying on the
processed marker alone.

## 6. Order/payment state machines

### Order

``` text
PENDING_PAYMENT ── successful payment/confirmation ──> CONFIRMED ── fulfil ──> FULFILLED
       |
       └── cancel or expiry ──> CANCELLED
```

Reject all unlisted transitions. If the assignment permits confirmation
before payment finality, document that rule explicitly; otherwise
successful payment is required for confirmation.

### Reservation

``` text
RESERVED ── confirmation ──> CONFIRMED ── fulfilment ──> FULFILLED
    |
    └── cancellation/expiry ──> RELEASED
```

### Payment

-   `PENDING -> SUCCEEDED`
-   `PENDING -> FAILED`
-   `PENDING -> TIMED_OUT`
-   A late callback can refine a timed-out payment if the simulated
    provider says it actually succeeded.
-   Duplicate callback IDs are ignored after their first durable
    processing.
-   A late success for a cancelled order must create a reconciliation
    mismatch and simulated refund-review action; it must not resurrect
    the order or re-reserve inventory.

Define and test race behavior between cancellation and successful
payment. Use row locks/conditional transitions and a deterministic rule:
whichever valid transaction commits first determines the order
transition; any later contradictory payment outcome is recorded for
reconciliation.

## 7. Kafka event design

Use stable event IDs and versioned JSON payloads. Include: - `eventId` -
`eventType` - `eventVersion` - `occurredAt` - `aggregateType` -
`aggregateId` - `correlationId` - `payload`

Suggested topics: - `order-events` - `inventory-events` -
`payment-events` - `order-events.DLT` - `inventory-events.DLT` -
`payment-events.DLT`

Use `aggregateId` as the Kafka key to preserve per-aggregate ordering.
Use a bounded retry policy with exponential backoff or Spring Kafka
retry topics where supported by the chosen dependency version. Persist
exhausted failures and publish to DLT. Manual retry must be audited and
must preserve the original event ID unless an explicit replay envelope
is used; consumers remain idempotent.

For a 72-hour implementation, avoid overengineering. Implement one
end-to-end event flow first, prove duplicate handling, then extend to
other event types.

## 8. API contract (suggested)

Use JSON and consistent error responses. Exact paths may change if the
README remains accurate.

### Catalogue

-   `POST /api/products`
-   `GET /api/products`
-   `POST /api/warehouses`
-   `GET /api/warehouses`
-   `GET /api/inventory`
-   `POST /api/inventory/adjustments` --- explicit audited stock
    adjustment only

### Orders

-   `POST /api/orders` --- requires `Idempotency-Key`
-   `GET /api/orders`
-   `GET /api/orders/{orderId}`
-   `POST /api/orders/{orderId}/confirm`
-   `POST /api/orders/{orderId}/cancel`
-   `POST /api/orders/{orderId}/fulfil`

### Payments

-   `POST /api/orders/{orderId}/payments/simulate` --- accepts outcome
    `SUCCEEDED`, `FAILED`, or `TIMED_OUT`
-   `POST /api/payments/callbacks` --- simulated callback with unique
    callback ID
-   `GET /api/payments`
-   `GET /api/payments/{paymentId}`

### Events

-   `GET /api/events/failures`
-   `POST /api/events/failures/{failureId}/retry`

### Reconciliation

-   `POST /api/reconciliation/run`
-   `GET /api/reconciliation/records`
-   `GET /api/reconciliation/records/{recordId}`
-   `POST /api/reconciliation/records/{recordId}/resolve` --- audited
    simulated action

### Dashboard

-   `GET /api/dashboard/summary`
-   `GET /api/dashboard/orders`
-   `GET /api/dashboard/inventory`
-   `GET /api/dashboard/payments`
-   `GET /api/dashboard/events`

### Operations

-   `GET /actuator/health` or a small health endpoint.
-   Swagger/OpenAPI if time permits.

## 9. Error handling and validation

Use appropriate HTTP codes: - `400` malformed/invalid input. - `404`
entity not found. - `409` idempotency key reused with a different
payload, insufficient stock where modeled as conflict, or invalid state
transition. - `422` only if the API consistently uses semantic
validation errors. - `500` unexpected failures with a correlation ID;
never return stack traces or secrets.

Validate positive quantity, existing active product/warehouse, non-empty
order lines, valid currency, and supported payment outcomes.

## 10. Testing strategy

### Unit tests

-   Order transition rules.
-   Inventory allocation algorithm.
-   Request hashing/idempotency decision.
-   Payment transition rules.
-   Reconciliation mismatch classification.
-   Retry policy and error classification.

### Integration tests (real PostgreSQL required for concurrency correctness)

1.  Seed 10 available units.
2.  Start 100 concurrent requests to reserve one unit each.
3.  Assert exactly 10 succeed, 90 fail, available=0, reserved=10, and no
    negative stock.
4.  Multi-item order where the second product is unavailable leaves the
    first product's stock unchanged.
5.  Duplicate cancel does not release twice.
6.  Duplicate fulfil does not move stock twice.
7.  Duplicate idempotency key with same payload returns same order.
8.  Same key with changed payload returns 409.
9.  Duplicate event does not duplicate its effects.
10. Payment failure/callback duplicates and late success after
    cancellation.
11. Reconciliation rerun creates no duplicate action.
12. Retry exhaustion creates a visible DLT/failure record.

Do not use mocks as proof of concurrency correctness. Tests that need
PostgreSQL/Kafka must run against a verified local installation or a
dedicated free test environment. Do not run destructive test cases
against production data.

### Frontend checks

-   Loading/empty/error states.
-   Status filters.
-   Dashboard refresh after an action.
-   API error messages.
-   No hardcoded production metrics.

## 11. Configuration

Use environment variables and document them in `.env.example`, with
placeholder values only: - `SPRING_PROFILES_ACTIVE` - `DATABASE_URL` or
separate DB host/name/user variables - `DATABASE_USERNAME` -
`DATABASE_PASSWORD` - `KAFKA_BOOTSTRAP_SERVERS` -
`KAFKA_SECURITY_PROTOCOL` - `KAFKA_SASL_MECHANISM` and credentials if
the provider requires them - `CORS_ALLOWED_ORIGINS` -
`VITE_API_BASE_URL`

Never commit real `.env` files, API keys, DB passwords, Kafka
credentials, or cloud tokens. Enable TLS for managed services. Confirm
provider free-tier limits and sleeping/cold-start behavior before
choosing them. Never upgrade to paid resources without explicit
approval.

## 12. Delivery and deployment

-   Build and test backend locally without containers.
-   Build and test frontend locally.
-   Deploy React to a free static hosting tier and Spring Boot to a free
    Java-capable host only after confirming current limits.
-   Use managed PostgreSQL/Kafka only if a suitable free tier is
    actually available and stays within quota.
-   Run Flyway migrations on startup/deployment with a least-privilege
    application user where feasible.
-   Configure CORS and environment variables.
-   Verify health, one complete order flow, payment callback,
    reconciliation, and dashboard after deployment.
-   If free Kafka cannot be obtained in time, document the limitation
    honestly and preserve Kafka integration in the code; do not claim an
    unverified cloud flow works.

## 13. Implementation order

1.  Inspect repo, requirements, and tooling.
2.  Create docs and project skeleton.
3.  PostgreSQL/Flyway and catalogue.
4.  Inventory atomic reservation and concurrency tests.
5.  Order lifecycle and API idempotency.
6.  Transactional outbox and Kafka consumer idempotency.
7.  Simulated payments and callback races.
8.  Reconciliation and audit actions.
9.  React dashboard.
10. End-to-end tests and failure testing.
11. Free-tier deployment if available.
12. README, final audit, demo evidence, and clean Git history.
