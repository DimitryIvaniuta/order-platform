# Order Platform — Reactive, Event‑Driven Microservices (Spring Boot 3.5, Java 21)

This monorepo implements a production‑grade order processing platform built with **Spring WebFlux**, **R2DBC (PostgreSQL)**, **Kafka (Reactor‑Kafka)**, and **the Outbox + Saga** patterns. It demonstrates multi‑tenant JWT security, cart & discounts, shipping quotes, payments, and end‑to‑end orchestration.

---

## Contents

* [Architecture](#architecture)
* [Modules](#modules)
* [Data Flow (Happy Path)](#data-flow-happy-path)
* [Saga & Outbox](#saga--outbox)
* [Tech Stack](#tech-stack)
* [Run It Locally](#run-it-locally)
* [Configuration](#configuration)
* [Security (JWT, Multi‑Tenant)](#security-jwt-multi-tenant)
* [Database & Migrations](#database--migrations)
* [Kafka Topics](#kafka-topics)
* [HTTP APIs (Preview)](#http-apis-preview)
* [Troubleshooting](#troubleshooting)
* [Development Tips](#development-tips)

---

## Architecture

```
Frontend (SPA)
   │  JSON/HTTPS
   ▼
API Gateway  ──▶  Routes to microservices via Spring Cloud Gateway (WebFlux)
   │             - Token relay (Authorization header)
   │             - Correlation Id header
   │             - SSE for Saga status
   ▼
Order Service  ──┬─►  PostgreSQL (R2DBC, Flyway)
                 │
                 ├─►  Outbox table → OutboxPublisher → Kafka (events topic)
                 │
                 └─►  Inventory / Payment / Shipping via Kafka (event choreography)

Inventory Service ─► Kafka consumer / producer (reserve/release)
Payment Service   ─► Kafka consumer / producer (authorize/capture/refund)
Shipping Service  ─► Kafka consumer / producer (quote, dispatch)

(common module provides shared security, kafka, dto, and utilities)
```

**Saga:** Event‑choreography saga; each service consumes domain events, performs local transaction(s), and emits follow‑up events. The **gateway** projects saga status to clients via SSE (and/or polling).

---

## Modules

```
common/                Shared auto‑configs (security, kafka), DTOs, utils
api-gateway/           Spring Cloud Gateway (WebFlux), SSE saga projection
order-service/         Carts, orders, discounts, shipping quote requests, outbox
inventory-service/     Reserve/release product stock (event driven)
payment-service/       Authorize / capture / refund (fake and/or provider adapter)
shipping-service/      Compute shipping quotes, dispatch (event driven)
```

---

## Data Flow (Happy Path)

1. **Frontend → POST /api/orders** (Gateway).
2. **Gateway → order‑service** (token relay + correlation id).
3. Order‑service validates JWT (tenant/user), creates **cart/order** in Postgres using R2DBC.
4. Order‑service writes an **outbox** row (e.g., `ORDER_CREATED`) in the same logical flow.
5. **OutboxPublisher** claims/leases rows and publishes JSON events to Kafka (`order.events.v1`).
6. **Inventory/Shipping/Payment** consume events, do local work (reserve, quote, authorize), emit follow‑ups.
7. **Gateway SagaEventConsumer** projects last known saga state to `saga_status` and exposes it via SSE to the frontend.
8. Frontend shows progress (e.g., STARTED → RESERVED → PRICED → AUTHORIZED → COMPLETED) and fetches the final order over HTTP.

---

## Saga & Outbox

* **Outbox pattern (transactional messaging):** Outbox rows are claimed with `FOR UPDATE SKIP LOCKED`, short **lease** applied (prevents duplicate workers). Publisher marks rows **PUBLISHED** or **FAILED with exponential backoff**.
* **Partitioning/ordering:** Kafka message **key = orderId** so all events for an order stay ordered.
* **Idempotency:** Consumers upsert by `(tenantId, sagaId)` (or `(tenantId, orderId)`) to avoid double‑apply.
* **Saga states:** gateway maps event types → coarse states (e.g., `ORDER_CREATED → STARTED`, `INVENTORY_RESERVED → RESERVED`, `SHIPPING_QUOTED → PRICED`, `PAYMENT_AUTHORIZED → PAID`, `ORDER_COMPLETED → COMPLETED`, failures → `FAILED`).

---

## Tech Stack

* **Language:** Java 21
* **Framework:** Spring Boot 3.5, WebFlux (reactive)
* **Security:** Spring Security Resource Server (JWT), custom reactive Jwt converter (common)
* **DB:** PostgreSQL, **R2DBC** (`io.r2dbc:r2dbc-postgresql`), Flyway migrations
* **Messaging:** Apache Kafka, Reactor‑Kafka
* **Gateway:** Spring Cloud Gateway (2025.x), token relay, observability
* **Docs:** springdoc‑openapi‑webflux‑ui (Swagger UI)

---

## Run It Locally

### 1) Prerequisites

* JDK 21, Gradle Wrapper
* Docker (for Postgres & Kafka)

### 2) Start infrastructure

```bash
# Postgres & Kafka (example compose)
docker compose -f infra/docker-compose.yml up -d
```

### 3) Build & run services

```bash
# Whole monorepo
./gradlew clean build

# Run services (separate terminals)
./gradlew :api-gateway:bootRun
./gradlew :order-service:bootRun
./gradlew :inventory-service:bootRun
./gradlew :payment-service:bootRun
./gradlew :shipping-service:bootRun
```

### 4) Open API (Swagger UI)

* Gateway: `http://localhost:8080/swagger-ui.html` (proxied) or per service UI.

---

## Configuration

Spring Boot **YAML** (per service) with shared conventions via **common** properties:

```yaml
server:
  port: 8080

kafka:
  bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:9092}
  client-id: ${APP_ID:gateway}
  group-id: ${APP_GROUP:gateway-projection}
  producer:
    acks: all
    compression-type: lz4
  topics:
    events:
      order: order.events.v1
    commands:
      order-create: order.command.create.v1

security:
  jwt:
    issuer: http://localhost:8080/api
    audience: order-platform-gateway
  claims:
    tenant: mt           # or tenant_id (configure once)
    roles: roles         # where authorities live (array/string)
```

> **Tip:** Use `.env` or environment variables to override. Common auto‑configs (security/kafka) read from these keys.

---

## Security (JWT, Multi‑Tenant)

* Gateway and services run as **OAuth2 Resource Servers** with **reactive** JWT validation.
* **Token relay:** Gateway forwards the original `Authorization: Bearer …` and `X-Correlation-Id` to downstream services.
* **Multi‑tenant converter (common):** Maps tenant and roles from configurable claims to `GrantedAuthority` and a `TENANT` context holder.
* **Required claims:** `iss`, `aud`, `sub` (user id or token subject), tenant claim (e.g., `mt`), roles (e.g., `ADMIN`, `ORDER_READ`).

---

## Database & Migrations

* Managed via **Flyway**. Each service owns its schema objects.
* **R2DBC** is used for all runtime data access. No JDBC driver is on the classpath.
* JSON attributes stored as `jsonb` with custom **R2dbcCustomConversions**.

**Example:** order tables (simplified)

```
orders(id, tenant_id, user_id, subtotal_amount, discount_amount,
       discount_code, shipping_fee, shipping_option, total_amount, status,
       created_at, updated_at)

order_items(id, tenant_id, order_id, product_id, sku, name,
            attributes_json, currency, quantity, unit_price, line_total,
            created_at, updated_at)

outbox(id, created_on, tenant_id, saga_id, aggregate_type, aggregate_id,
       event_type, event_key, payload, headers_json, attempts, lease_until,
       created_at, updated_at) PARTITIONED BY RANGE (created_on)
```

---

## Kafka Topics

* **Events:** `order.events.v1` (shared bus for order lifecycle; key = `orderId`)
* **Commands:** `order.command.create.v1` (optional command channel)

**Consumers**

* shipping‑service: quotes (request/response over events)
* inventory‑service: reserves/releases
* payment‑service: authorizes/captures/refunds
* api‑gateway: saga projection (`saga_status`)

---

## HTTP APIs (Preview)

> Full OpenAPI is available at Swagger UI; below is a snapshot.

### Gateway (proxied)

* `POST /api/orders` — create order/cart
* `POST /api/orders/cart/items` — add cart item
* `PATCH /api/orders/cart/items/{itemId}` — update item (qty/attrs)
* `POST /api/orders/cart/discount` — apply discount code
* `POST /api/orders/cart/shipping` — choose shipping option (triggers quote)
* `POST /api/orders/cart/submit` — submit order
* `GET  /api/orders/{id}` — fetch order with items
* `GET  /api/saga/{sagaId}/status` — SSE/poll saga status

### Payment

* `POST /payments/authorize` — start auth
* `POST /payments/{id}/capture` — capture part/remaining
* `POST /payments/{id}/refund` — refund
* `GET  /payments/{id}` — view

**Payloads** are JSON; Money amounts are either **BigDecimal** (HTTP contracts) or **minor units** internally (DB/events).

---

## Troubleshooting

### 1) `IllegalArgumentException: Cannot encode parameter of type io.r2dbc.spi.Parameters$InParameter`

**Cause:** Passing `Parameters.in(...)` or nullables directly to `DatabaseClient` in named binds.

**Fix:** Bind **concrete values and types**; avoid `InParameter` objects. Example helper:

```java
static DatabaseClient.GenericExecuteSpec bindMaybe(DatabaseClient.GenericExecuteSpec s,
                                                   String name, @Nullable Object v) {
  return (v == null) ? s.bindNull(name, String.class) : s.bind(name, v);
}
```

Use SQL like `... WHERE lease_until IS NULL OR lease_until < now()` instead of a `:now` param.

### 2) Issuer mismatch

`The Issuer "X" provided … did not match requested issuer "Y"`
**Fix:** Align `security.jwt.issuer` with the token `iss`, and use the same value in resource servers.

### 3) 401 after gateway

* Check **audience** and **scopes** mapping (common converter).
* Ensure gateway **token relay** is enabled and resource servers are configured identically.

### 4) OutboxPublisher backpressure / interval overflow

Use `Flux.interval(...).onBackpressureDrop()` or sample ticks; ensure you **subscribe** with an unbounded request (Reactor‑Kafka handles demand downstream). The provided publisher does this.

### 5) R2DBC JSONB mapping

Register custom `R2dbcCustomConversions` for `jsonb` ↔ `Map<String,Object>` and enum codecs for `OrderStatus`.

---

## Development Tips

* Prefer **idempotent** consumers (UPSERT by `(tenantId, sagaId)` or `(tenantId, orderId)`).
* Keep all events for an order in the **same Kafka partition** (key = orderId).
* Centralize topic names under `kafka.topics.*` and read via **AppKafkaProperties** (common). Avoid hard‑coding.
* One **KafkaSender** bean per app; remove duplicates to prevent bean name clashes.
* Validate all external inputs using `jakarta.validation` annotations on DTOs.
* Use **SSE** for live saga progress; fallback to polling if needed.

