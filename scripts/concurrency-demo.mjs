#!/usr/bin/env node
// API-level concurrency demonstration against the RUNNING backend (real HTTP, real PostgreSQL).
//
//   node scripts/concurrency-demo.mjs                      # 10 units, 50 concurrent purchases, 5 retried keys
//   node scripts/concurrency-demo.mjs --stock 25 --requests 100 --replays 10
//
// Each purchase attempt is a distinct POST /api/orders with its own Idempotency-Key and quantity 1.
// After the burst, some accepted keys are re-sent concurrently to prove retries never create a second order.
// Results are verified through the API (orders, order-request history, inventory) and printed.

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, cur, i, all) => {
    if (cur.startsWith("--")) acc.push([cur.slice(2), all[i + 1]]);
    return acc;
  }, [])
);
const BASE = process.env.API_BASE_URL ?? "http://localhost:8080";
const STOCK = Number(args.stock ?? 10);
const REQUESTS = Number(args.requests ?? 50);
const REPLAYS = Number(args.replays ?? 5);
const PRICE = 12.5;

const stamp = new Date().toISOString().replace(/[-:T]/g, "").slice(0, 14);
const runId = `demo-${stamp}`;

async function call(method, path, body, headers = {}) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { "Content-Type": "application/json", Accept: "application/json", ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { json = text; }
  return { status: res.status, body: json };
}

async function must(method, path, body) {
  const r = await call(method, path, body);
  if (r.status >= 300) throw new Error(`${method} ${path} -> HTTP ${r.status}: ${JSON.stringify(r.body)}`);
  return r.body;
}

function line(label, value) {
  console.log(`${label.padEnd(38)}: ${value}`);
}

async function main() {
  console.log(`\nConcurrency demo ${runId} against ${BASE}`);

  const health = await call("GET", "/actuator/health");
  if (health.status !== 200) throw new Error(`Backend not healthy at ${BASE}: HTTP ${health.status}`);

  // 1. Isolated product + warehouse + stock
  const product = await must("POST", "/api/products", {
    sku: `DEMO-CONC-${stamp}`, name: `Concurrency Demo Product ${stamp}`, description: "API-level concurrency demo", active: true,
  });
  const warehouse = await must("POST", "/api/warehouses", {
    code: `WH-CONC-${stamp}`, name: `Concurrency Demo Warehouse ${stamp}`, location: "Demo", active: true,
  });
  await must("POST", "/api/inventory/initialize", { productId: product.id, warehouseId: warehouse.id, initialStock: STOCK });
  line("Product", `${product.sku} (${product.id})`);
  line("Warehouse", `${warehouse.code} (${warehouse.id})`);
  line("Initial stock", STOCK);

  // 2. Concurrent burst: REQUESTS distinct purchase attempts, 1 unit each
  const payload = { currency: "USD", items: [{ productId: product.id, quantity: 1, unitPrice: PRICE }] };
  const keys = Array.from({ length: REQUESTS }, (_, i) => `${runId}-req-${String(i).padStart(3, "0")}`);
  const started = Date.now();
  const results = await Promise.all(
    keys.map(async (key) => ({ key, ...(await call("POST", "/api/orders", payload, { "Idempotency-Key": key })) }))
  );
  const burstMs = Date.now() - started;

  const created = results.filter((r) => r.status === 201);
  const stockRejected = results.filter((r) => r.status === 409 && String(r.body?.message ?? "").includes("Insufficient stock"));
  const unexpected = results.filter((r) => !created.includes(r) && !stockRejected.includes(r));

  // 3. Retries: re-send some accepted keys concurrently with the same payload
  const replayKeys = created.slice(0, REPLAYS).map((r) => r.key);
  const replays = await Promise.all(
    replayKeys.map(async (key) => ({ key, ...(await call("POST", "/api/orders", payload, { "Idempotency-Key": key })) }))
  );
  const originalIdByKey = Object.fromEntries(created.map((r) => [r.key, r.body.id]));
  const replaySameOrder = replays.filter((r) => r.status < 300 && r.body?.id === originalIdByKey[r.key]).length;

  // 4. Verify persisted state through the API
  const inventory = await must("GET", "/api/inventory");
  const inv = inventory.items.find((p) => p.productId === product.id)?.warehouses.find((w) => w.warehouseId === warehouse.id);
  const orders = (await must("GET", "/api/orders")).filter((o) => o.idempotencyKey.startsWith(`${runId}-`));
  const summary = await must("GET", `/api/orders/requests/summary?keyPrefix=${encodeURIComponent(runId + "-")}`);
  const expectedAccepted = Math.min(STOCK, REQUESTS);

  console.log("\n========== API-LEVEL CONCURRENCY RESULT ==========");
  line("Concurrent purchase requests", `${REQUESTS} (burst took ${burstMs} ms)`);
  line("HTTP 201 order created", created.length);
  line("HTTP 409 insufficient stock", stockRejected.length);
  line("Unexpected responses", unexpected.length);
  line("Persisted orders for this run", orders.length);
  line("Order statuses", JSON.stringify(orders.reduce((a, o) => ({ ...a, [o.status]: (a[o.status] ?? 0) + 1 }), {})));
  line("Retried keys -> same order returned", `${replaySameOrder} / ${replayKeys.length}`);
  line("order_requests ACCEPTED", summary.ACCEPTED);
  line("order_requests REJECTED_INSUFFICIENT_STOCK", summary.REJECTED_INSUFFICIENT_STOCK);
  line("order_requests IDEMPOTENT_REPLAY", summary.IDEMPOTENT_REPLAY);
  line("Final available", inv?.availableQty);
  line("Final reserved", inv?.reservedQty);
  line("Final fulfilled", inv?.fulfilledQty);
  console.log("==================================================");

  const checks = [
    [created.length === expectedAccepted, `exactly ${expectedAccepted} orders created`],
    [stockRejected.length === REQUESTS - expectedAccepted, `exactly ${REQUESTS - expectedAccepted} stock rejections`],
    [unexpected.length === 0, "no unexpected responses"],
    [orders.length === expectedAccepted, "persisted orders == accepted requests (no ghost or duplicate orders)"],
    [replaySameOrder === replayKeys.length, "retried keys returned the original order"],
    [summary.ACCEPTED === expectedAccepted && summary.REJECTED_INSUFFICIENT_STOCK === REQUESTS - expectedAccepted, "order_requests history matches"],
    [inv && inv.availableQty === STOCK - expectedAccepted && inv.reservedQty === expectedAccepted, "inventory available/reserved consistent"],
    [inv && inv.availableQty >= 0, "no negative stock"],
  ];
  for (const [ok, label] of checks) console.log(`${ok ? "PASS" : "FAIL"}  ${label}`);
  if (unexpected.length) console.log("Unexpected sample:", JSON.stringify(unexpected.slice(0, 3), null, 2));

  console.log(`\nDashboard: Orders -> search "${runId}" ; Order Requests -> key prefix "${runId}-" ; Inventory -> ${product.sku}`);
  process.exit(checks.every(([ok]) => ok) ? 0 : 1);
}

main().catch((err) => {
  console.error("Demo failed:", err.message);
  process.exit(2);
});
