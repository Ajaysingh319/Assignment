#!/usr/bin/env node
// End-to-end workflow verification against the RUNNING backend + PostgreSQL + Kafka.
// Every check reads persisted state back through the API; nothing is assumed.
//
//   node scripts/e2e-demo.mjs
//
// Scenarios: multi-product order, automatic outbox publication + consumption, payment failure/success,
// duplicate callback, fulfilment, cancellation release, timeout + late success after cancellation,
// reconciliation + refund corrective action, worker failure retry, dead-lettering, duplicate event delivery.

const BASE = process.env.API_BASE_URL ?? "http://localhost:8080";
const stamp = new Date().toISOString().replace(/[-:T]/g, "").slice(0, 14);
let failures = 0;

async function call(method, path, body, headers = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { "Content-Type": "application/json", Accept: "application/json", ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { json = text; }
  if (res.status >= 300) throw new Error(`${method} ${path} -> HTTP ${res.status}: ${text}`);
  return json;
}

function check(ok, label, detail = "") {
  if (!ok) failures++;
  console.log(`${ok ? "PASS" : "FAIL"}  ${label}${detail ? `  (${detail})` : ""}`);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitFor(label, probe, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    last = await probe();
    if (last?.ok) return last;
    await sleep(750);
  }
  return last ?? { ok: false };
}

const details = (id) => call("GET", `/api/orders/${id}/details`);
const inventoryOf = async (productId, warehouseId) =>
  (await call("GET", "/api/inventory")).items.find((p) => p.productId === productId).warehouses.find((w) => w.warehouseId === warehouseId);
const createOrder = (key, items) => call("POST", "/api/orders", { currency: "USD", items }, { "Idempotency-Key": key });
const eventOf = (d, type) => d.events.find((e) => e.eventType === type);

async function main() {
  console.log(`\nE2E workflow ${stamp} against ${BASE}\n`);

  // Setup: two products, one warehouse
  const pA = await call("POST", "/api/products", { sku: `E2E-A-${stamp}`, name: `E2E Widget A ${stamp}`, active: true });
  const pB = await call("POST", "/api/products", { sku: `E2E-B-${stamp}`, name: `E2E Widget B ${stamp}`, active: true });
  const wh = await call("POST", "/api/warehouses", { code: `E2E-WH-${stamp}`, name: `E2E Warehouse ${stamp}`, location: "Demo", active: true });
  await call("POST", "/api/inventory/initialize", { productId: pA.id, warehouseId: wh.id, initialStock: 10 });
  await call("POST", "/api/inventory/initialize", { productId: pB.id, warehouseId: wh.id, initialStock: 10 });
  const line = (p, q, price = 10) => ({ productId: p.id, quantity: q, unitPrice: price });

  // 1. Multi-product order + reservation
  console.log("-- Multi-product order and automatic Kafka publication");
  const o1 = await createOrder(`e2e-${stamp}-1`, [line(pA, 2), line(pB, 3, 5)]);
  let invA = await inventoryOf(pA.id, wh.id);
  let invB = await inventoryOf(pB.id, wh.id);
  check(o1.status === "PENDING_PAYMENT" && Number(o1.totalAmount) === 35, "multi-product order persisted", `order ${o1.id}, total ${o1.totalAmount}`);
  check(invA.availableQty === 8 && invA.reservedQty === 2 && invB.availableQty === 7 && invB.reservedQty === 3, "both lines reserved",
    `A ${invA.availableQty}/${invA.reservedQty}, B ${invB.availableQty}/${invB.reservedQty}`);

  // 2. Automatic outbox publication and consumption (no manual trigger)
  const pub = await waitFor("ORDER_CREATED consumed", async () => {
    const d = await details(o1.id);
    const e = eventOf(d, "ORDER_CREATED");
    return { ok: e && e.publishStatus === "PUBLISHED" && e.publishedVia === "KAFKA" && e.consumerStatus === "PROCESSED", e };
  });
  check(pub.ok, "ORDER_CREATED published to Kafka by the scheduler and consumed",
    pub.e ? `publish=${pub.e.publishStatus}/${pub.e.publishedVia}, consumer=${pub.e.consumerStatus}` : "event missing");

  // 3. Payment failure then success
  console.log("-- Payments");
  const failed = await call("POST", `/api/orders/${o1.id}/payments/simulate`, { outcome: "FAILED" });
  let d1 = await details(o1.id);
  check(failed.status === "FAILED" && d1.status === "PENDING_PAYMENT", "failed payment leaves order PENDING_PAYMENT");
  const paid = await call("POST", `/api/orders/${o1.id}/payments/simulate`, { outcome: "SUCCEEDED" });
  d1 = await details(o1.id);
  check(paid.status === "SUCCEEDED" && d1.status === "CONFIRMED" && d1.reservationStatus === "CONFIRMED", "successful payment confirms order",
    `provider ref ${paid.providerReference}`);
  check(d1.callbacks.length === 2, "provider callbacks recorded for simulated outcomes", `${d1.callbacks.length} callbacks`);

  // 4. Duplicate callback
  const lastCb = d1.callbacks[d1.callbacks.length - 1];
  await call("POST", "/api/payments/callbacks", { callbackId: lastCb.providerCallbackId, paymentId: lastCb.paymentId, result: lastCb.result });
  await call("POST", "/api/payments/callbacks", { callbackId: lastCb.providerCallbackId, paymentId: lastCb.paymentId, result: lastCb.result });
  d1 = await details(o1.id);
  const dupAudits = d1.auditTrail.filter((a) => a.action === "PAYMENT_CALLBACK_DUPLICATE_IGNORED").length;
  check(d1.callbacks.length === 2 && d1.payments.filter((p) => p.status === "SUCCEEDED").length === 1 && dupAudits === 2,
    "duplicate callbacks ignored and audited", `${dupAudits} duplicate audits`);

  // 5. Fulfilment
  await call("POST", `/api/orders/${o1.id}/fulfil`);
  invA = await inventoryOf(pA.id, wh.id);
  check(invA.reservedQty === 0 && invA.fulfilledQty === 2 && invA.availableQty === 8, "fulfilment moves reserved -> fulfilled",
    `A avail ${invA.availableQty}, reserved ${invA.reservedQty}, fulfilled ${invA.fulfilledQty}`);

  // 6. Cancellation releases stock
  console.log("-- Cancellation and late payment");
  const o2 = await createOrder(`e2e-${stamp}-2`, [line(pA, 4), line(pB, 1, 5)]);
  await call("POST", `/api/orders/${o2.id}/cancel`);
  await call("POST", `/api/orders/${o2.id}/cancel`);
  invA = await inventoryOf(pA.id, wh.id);
  invB = await inventoryOf(pB.id, wh.id);
  check(invA.availableQty === 8 && invA.reservedQty === 0 && invB.availableQty === 7, "cancellation (twice) released stock exactly once",
    `A ${invA.availableQty}/${invA.reservedQty}, B ${invB.availableQty}/${invB.reservedQty}`);

  // 7. Timeout, cancel, late success -> reconciliation -> refund
  const o3 = await createOrder(`e2e-${stamp}-3`, [line(pB, 1, 20)]);
  const timedOut = await call("POST", `/api/orders/${o3.id}/payments/simulate`, { outcome: "TIMED_OUT" });
  await call("POST", `/api/orders/${o3.id}/cancel`);
  await call("POST", "/api/payments/callbacks", { callbackId: `e2e-late-${stamp}`, paymentId: timedOut.id, result: "SUCCEEDED" });
  let d3 = await details(o3.id);
  check(d3.status === "CANCELLED" && d3.payments[0].status === "SUCCEEDED", "late success does not resurrect the cancelled order");
  const run = await call("POST", "/api/reconciliation/run");
  d3 = await details(o3.id);
  const mismatch = d3.reconciliation.filter((r) => r.mismatchType === "PAYMENT_SUCCEEDED_ORDER_CANCELLED");
  check(mismatch.length === 1 && mismatch[0].status === "ACTION_REQUIRED", "reconciliation flags PAYMENT_SUCCEEDED_ORDER_CANCELLED once",
    `run ${run.runId}: ${run.ordersChecked} orders, ${run.paymentsChecked} payments, ${run.detectedCount} new`);
  const resolved = await call("POST", `/api/reconciliation/records/${mismatch[0].id}/resolve`, { action: "REFUND_PAYMENT", notes: "e2e refund" });
  d3 = await details(o3.id);
  check(resolved.status === "RESOLVED" && d3.payments[0].status === "REFUNDED", "REFUND_PAYMENT corrective action applied and audited",
    resolved.resolutionNotes);

  // 8. Worker failure -> retry -> recovery
  console.log("-- Retries, dead-lettering and duplicate delivery (Kafka)");
  const o4 = await createOrder(`e2e-${stamp}-4`, [line(pA, 1)]);
  await call("POST", "/api/events/fault-injection", { failures: 2, eventType: "ORDER_CANCELLED", aggregateId: o4.id });
  await call("POST", `/api/orders/${o4.id}/cancel`);
  const rec = await waitFor("recovered", async () => {
    const e = eventOf(await details(o4.id), "ORDER_CANCELLED");
    return { ok: e?.consumerStatus === "PROCESSED_AFTER_RETRY", e };
  }, 45000);
  check(rec.ok, "transient worker failure retried via Kafka redelivery, then processed",
    rec.e ? `${rec.e.consumerStatus}, ${rec.e.consumerAttempts ?? 0} failed attempts` : "event missing");

  // 9. Persistent failure -> dead letter
  const o5 = await createOrder(`e2e-${stamp}-5`, [line(pA, 1)]);
  await call("POST", "/api/events/fault-injection", { failures: 10, eventType: "ORDER_CANCELLED", aggregateId: o5.id });
  await call("POST", `/api/orders/${o5.id}/cancel`);
  const dlt = await waitFor("dead-lettered", async () => {
    const e = eventOf(await details(o5.id), "ORDER_CANCELLED");
    return { ok: e?.consumerStatus === "DEAD_LETTERED", e };
  }, 45000);
  await call("POST", "/api/events/fault-injection", { failures: 0 });
  check(dlt.ok, "persistent worker failure dead-lettered after max retries",
    dlt.e ? `${dlt.e.consumerStatus}, ${dlt.e.consumerAttempts} attempts: ${dlt.e.consumerLastError}` : "event missing");
  const dead = await call("GET", "/api/events/failures/dead-lettered");
  check(dlt.e && dead.some((f) => f.eventId === dlt.e.id), "dead-lettered event listed under Failed Events / DLT");

  // 10. Duplicate event delivery
  const created1 = eventOf(await details(o1.id), "ORDER_CREATED");
  await call("POST", `/api/events/outbox/${created1.id}/redeliver`);
  const dup = await waitFor("duplicate ignored", async () => {
    const d = await details(o1.id);
    return { ok: d.auditTrail.some((a) => a.action === "DUPLICATE_EVENT_IGNORED" && (a.details ?? "").includes(created1.id)), d };
  });
  const consumedOnce = dup.d ? dup.d.auditTrail.filter((a) => a.action === "CONSUMED_ORDER_CREATED").length : -1;
  check(dup.ok && consumedOnce === 1, "re-delivered event detected as duplicate; business effect applied once", `CONSUMED_ORDER_CREATED x${consumedOnce}`);

  console.log(`\n${failures === 0 ? "ALL CHECKS PASSED" : `${failures} CHECK(S) FAILED`}`);
  console.log(`Orders created by this run: search "e2e-${stamp}" on the Orders page.`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((err) => {
  console.error("E2E run aborted:", err.message);
  process.exit(2);
});
