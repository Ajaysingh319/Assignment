import React, { useCallback, useEffect, useState } from "react";
import { api, ApiError } from "../api/client";
import type { OrderDetails, Payment } from "../types";
import { Badge } from "./Badge";
import { Spinner } from "./Spinner";
import { ErrorBanner } from "./ErrorBanner";

interface Props {
  orderId: string;
  onNotify: (type: "success" | "error" | "info", msg: string) => void;
  onChanged: () => void;
}

const POLL_MS = 3000;

export const fmtTs = (v?: string | null) => (v ? new Date(v).toLocaleString() : "—");
const money = (amount: number, currency: string) =>
  new Intl.NumberFormat(undefined, { style: "currency", currency }).format(Number(amount));
const short = (id?: string | null) => (id ? `${id.substring(0, 8)}…` : "—");

const Section: React.FC<{ title: string; count?: number; children: React.ReactNode }> = ({ title, count, children }) => (
  <section style={{ marginTop: 20 }}>
    <h4 style={{ margin: "0 0 8px" }}>
      {title}
      {count !== undefined && <span className="tab-counter" style={{ marginLeft: 8 }}>{count}</span>}
    </h4>
    {children}
  </section>
);

const Empty: React.FC<{ text: string }> = ({ text }) => <p className="text-secondary text-sm">{text}</p>;

export const OrderDetailPanel: React.FC<Props> = ({ orderId, onNotify, onChanged }) => {
  const [details, setDetails] = useState<OrderDetails | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [errorStatus, setErrorStatus] = useState<number | undefined>();
  const [busy, setBusy] = useState(false);
  const [payOutcome, setPayOutcome] = useState<"SUCCEEDED" | "FAILED" | "TIMED_OUT">("SUCCEEDED");
  const [payAmount, setPayAmount] = useState<string>("");
  const [showAudit, setShowAudit] = useState(false);

  const load = useCallback(async () => {
    try {
      const d = await api.getOrderDetails(orderId);
      setDetails(d);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load order");
      setErrorStatus(err instanceof ApiError ? err.status : undefined);
    }
  }, [orderId]);

  useEffect(() => {
    setDetails(null);
    load();
    const t = window.setInterval(load, POLL_MS);
    return () => window.clearInterval(t);
  }, [load]);

  const run = async (label: string, fn: () => Promise<unknown>) => {
    setBusy(true);
    try {
      await fn();
      onNotify("success", label);
      await load();
      onChanged();
    } catch (err) {
      onNotify("error", err instanceof Error ? err.message : `${label} failed`);
      await load();
    } finally {
      setBusy(false);
    }
  };

  if (error && !details) {
    return <ErrorBanner message={error} statusCode={errorStatus} onRetry={load} />;
  }
  if (!details) {
    return <Spinner size="lg" label="Loading order details…" />;
  }

  const d = details;
  const totalQty = d.lines.reduce((acc, l) => acc + l.quantity, 0);
  const awaitingCallback: Payment | undefined = [...d.payments].reverse().find((p) => p.status === "TIMED_OUT" || p.status === "PENDING");
  const lastCallback = d.callbacks.length > 0 ? d.callbacks[d.callbacks.length - 1] : undefined;

  return (
    <div>
      {error && <ErrorBanner message={`Refresh failed: ${error}`} statusCode={errorStatus} onRetry={load} />}

      <div className="details-grid">
        <div className="detail-item"><span className="detail-label">Order status</span><span className="detail-value"><Badge status={d.status} /></span></div>
        <div className="detail-item"><span className="detail-label">Payment</span><span className="detail-value"><Badge status={d.paymentStatus} /></span></div>
        <div className="detail-item"><span className="detail-label">Inventory reservation</span><span className="detail-value"><Badge status={d.reservationStatus} /></span></div>
        <div className="detail-item"><span className="detail-label">Total</span><span className="detail-value font-medium text-lg">{money(d.totalAmount, d.currency)}</span></div>
        <div className="detail-item"><span className="detail-label">Products / units</span><span className="detail-value">{d.lines.length} / {totalQty}</span></div>
        <div className="detail-item"><span className="detail-label">Idempotency key</span><span className="detail-value font-mono text-xs text-break">{d.idempotencyKey}</span></div>
        <div className="detail-item"><span className="detail-label">Created</span><span className="detail-value">{fmtTs(d.createdAt)}</span></div>
        <div className="detail-item"><span className="detail-label">Last updated</span><span className="detail-value">{fmtTs(d.updatedAt)}</span></div>
        {d.confirmedAt && <div className="detail-item"><span className="detail-label">Confirmed</span><span className="detail-value text-success">{fmtTs(d.confirmedAt)}</span></div>}
        {d.cancelledAt && <div className="detail-item"><span className="detail-label">Cancelled</span><span className="detail-value text-danger">{fmtTs(d.cancelledAt)}</span></div>}
        {d.fulfilledAt && <div className="detail-item"><span className="detail-label">Fulfilled</span><span className="detail-value text-success">{fmtTs(d.fulfilledAt)}</span></div>}
      </div>

      {/* Actions */}
      <div className="card" style={{ marginTop: 16, padding: 12, display: "flex", flexWrap: "wrap", gap: 8, alignItems: "flex-end" }}>
        <div className="form-group" style={{ margin: 0 }}>
          <label htmlFor="detail-pay-outcome" className="text-xs">Simulated payment outcome</label>
          <select id="detail-pay-outcome" value={payOutcome} onChange={(e) => setPayOutcome(e.target.value as typeof payOutcome)} disabled={busy}>
            <option value="SUCCEEDED">SUCCEEDED</option>
            <option value="FAILED">FAILED</option>
            <option value="TIMED_OUT">TIMED_OUT (no provider answer yet)</option>
          </select>
        </div>
        <div className="form-group" style={{ margin: 0, width: 120 }}>
          <label htmlFor="detail-pay-amount" className="text-xs">Amount (blank = order total)</label>
          <input id="detail-pay-amount" type="number" step="0.01" min="0" value={payAmount} placeholder={String(d.totalAmount)}
                 onChange={(e) => setPayAmount(e.target.value)} disabled={busy} />
        </div>
        <button type="button" className="btn btn-sm btn-primary" disabled={busy}
                onClick={() => run(`Payment simulated: ${payOutcome}`, () => api.simulatePayment(d.id, {
                  outcome: payOutcome,
                  amount: payAmount ? Number(payAmount) : undefined,
                  currency: d.currency,
                }))}>
          Simulate payment
        </button>
        {awaitingCallback && (
          <>
            <button type="button" className="btn btn-sm btn-success" disabled={busy}
                    onClick={() => run("Late SUCCEEDED callback delivered", () => api.processPaymentCallback({
                      callbackId: `cb-ui-${Date.now()}`, paymentId: awaitingCallback.id, result: "SUCCEEDED",
                      providerReference: awaitingCallback.providerReference,
                    }))}>
              Deliver late SUCCESS callback
            </button>
            <button type="button" className="btn btn-sm btn-secondary" disabled={busy}
                    onClick={() => run("Late FAILED callback delivered", () => api.processPaymentCallback({
                      callbackId: `cb-ui-${Date.now()}`, paymentId: awaitingCallback.id, result: "FAILED",
                      providerReference: awaitingCallback.providerReference,
                    }))}>
              Deliver late FAILED callback
            </button>
          </>
        )}
        {lastCallback && (
          <button type="button" className="btn btn-sm btn-ghost" disabled={busy}
                  title="Re-sends the most recent callback with the SAME callback id; the backend must ignore it"
                  onClick={() => run(`Duplicate callback ${lastCallback.providerCallbackId} re-sent`, () => api.processPaymentCallback({
                    callbackId: lastCallback.providerCallbackId, paymentId: lastCallback.paymentId, result: lastCallback.result,
                  }))}>
            Re-send last callback (duplicate)
          </button>
        )}
        <span style={{ flex: 1 }} />
        {d.status === "PENDING_PAYMENT" && (
          <>
            <button type="button" className="btn btn-sm btn-danger" disabled={busy}
                    onClick={() => run("Order cancelled; reservations released", () => api.cancelOrder(d.id))}>Cancel</button>
            <button type="button" className="btn btn-sm btn-secondary" disabled={busy}
                    onClick={() => run("Order expired; reservations released", () => api.expireOrder(d.id))}>Expire now</button>
            <button type="button" className="btn btn-sm btn-ghost" disabled={busy}
                    title="Confirms without a payment. Reconciliation will flag MISSING_PAYMENT_CONFIRMED_ORDER."
                    onClick={() => run("Order confirmed manually (no payment)", () => api.confirmOrder(d.id))}>Confirm w/o payment</button>
          </>
        )}
        {d.status === "CONFIRMED" && (
          <button type="button" className="btn btn-sm btn-success" disabled={busy}
                  onClick={() => run("Order fulfilled; reserved stock moved to fulfilled", () => api.fulfilOrder(d.id))}>Fulfil</button>
        )}
      </div>

      <Section title="Products" count={d.lines.length}>
        <table className="data-table">
          <thead><tr><th>Product</th><th>SKU</th><th>Qty</th><th>Unit price</th><th>Line total</th></tr></thead>
          <tbody>
            {d.lines.map((l) => (
              <tr key={l.productId}>
                <td>{l.productName ?? <span className="font-mono text-xs">{l.productId}</span>}</td>
                <td className="font-mono text-xs">{l.sku ?? "—"}</td>
                <td>{l.quantity}</td>
                <td>{money(l.unitPrice, d.currency)}</td>
                <td className="font-medium">{money(l.lineTotal, d.currency)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Section>

      <Section title="Inventory reservations" count={d.reservations.length}>
        {d.reservations.length === 0 ? <Empty text="No reservations recorded for this order." /> : (
          <table className="data-table">
            <thead><tr><th>SKU</th><th>Warehouse</th><th>Qty</th><th>Status</th><th>Reserved</th><th>Released</th><th>Fulfilled</th></tr></thead>
            <tbody>
              {d.reservations.map((r) => (
                <tr key={r.id}>
                  <td className="font-mono text-xs">{r.sku ?? short(r.productId)}</td>
                  <td>{r.warehouseCode ?? short(r.warehouseId)}{r.warehouseName ? <span className="text-secondary text-xs"> ({r.warehouseName})</span> : null}</td>
                  <td>{r.quantity}</td>
                  <td><Badge status={r.status} /></td>
                  <td className="text-xs">{fmtTs(r.createdAt)}</td>
                  <td className="text-xs">{fmtTs(r.releasedAt)}</td>
                  <td className="text-xs">{fmtTs(r.fulfilledAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>

      <Section title="Status history" count={d.statusHistory.length}>
        {d.statusHistory.length === 0 ? <Empty text="No status changes recorded in the audit log." /> : (
          <table className="data-table">
            <thead><tr><th>When</th><th>Status</th><th>Transition</th><th>Actor</th><th>Details</th></tr></thead>
            <tbody>
              {d.statusHistory.map((h, i) => (
                <tr key={`${h.at}-${i}`}>
                  <td className="text-xs">{fmtTs(h.at)}</td>
                  <td><Badge status={h.status} /></td>
                  <td className="font-mono text-xs">{h.action}</td>
                  <td className="text-xs">{h.actor}</td>
                  <td className="text-xs text-break">{h.details}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>

      <Section title="Payments" count={d.payments.length}>
        {d.payments.length === 0 ? <Empty text="No payment attempts yet." /> : (
          <table className="data-table">
            <thead><tr><th>Payment</th><th>Status</th><th>Amount</th><th>Provider reference</th><th>Last callback</th><th>Created</th><th>Succeeded</th></tr></thead>
            <tbody>
              {d.payments.map((p) => (
                <tr key={p.id}>
                  <td className="font-mono text-xs" title={p.id}>{short(p.id)}</td>
                  <td><Badge status={p.status} /></td>
                  <td>{money(p.amount, p.currency)}</td>
                  <td className="font-mono text-xs">{p.providerReference}</td>
                  <td className="font-mono text-xs">{p.lastCallbackId ?? "—"}</td>
                  <td className="text-xs">{fmtTs(p.createdAt)}</td>
                  <td className="text-xs">{fmtTs(p.succeededAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>

      <Section title="Provider callbacks" count={d.callbacks.length}>
        {d.callbacks.length === 0 ? <Empty text="No provider callbacks received. A TIMED_OUT payment has no callback until one is delivered." /> : (
          <table className="data-table">
            <thead><tr><th>Callback id</th><th>Payment</th><th>Result</th><th>Received</th><th>Payload</th></tr></thead>
            <tbody>
              {d.callbacks.map((c) => (
                <tr key={c.id}>
                  <td className="font-mono text-xs">{c.providerCallbackId}</td>
                  <td className="font-mono text-xs">{short(c.paymentId)}</td>
                  <td><Badge status={c.result} /></td>
                  <td className="text-xs">{fmtTs(c.receivedAt)}</td>
                  <td className="text-xs text-break">{c.receivedPayload}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <p className="text-secondary text-xs">Duplicate and ignored callbacks are not stored twice; they appear in the audit trail below as PAYMENT_CALLBACK_DUPLICATE_IGNORED / PAYMENT_CALLBACK_IGNORED.</p>
      </Section>

      <Section title="Kafka events (outbox → topic → consumer)" count={d.events.length}>
        {d.events.length === 0 ? <Empty text="No events recorded." /> : (
          <table className="data-table">
            <thead><tr><th>Event</th><th>Topic</th><th>Created</th><th>Publication</th><th>Published</th><th>Consumer</th><th>Consumed</th><th>Errors</th><th /></tr></thead>
            <tbody>
              {d.events.map((e) => (
                <tr key={e.id}>
                  <td><span className="font-mono text-xs">{e.eventType}</span><br /><span className="text-muted text-xs" title={e.id}>{short(e.id)}</span></td>
                  <td className="font-mono text-xs">{e.topic}</td>
                  <td className="text-xs">{fmtTs(e.createdAt)}</td>
                  <td><Badge status={e.publishStatus} />{e.publishedVia && <div className="text-xs text-secondary">via {e.publishedVia}, {e.publishAttempts} attempt(s)</div>}</td>
                  <td className="text-xs">{fmtTs(e.publishedAt)}</td>
                  <td><Badge status={e.consumerStatus} />{e.consumerAttempts ? <div className="text-xs text-secondary">{e.consumerAttempts} failed attempt(s)</div> : null}</td>
                  <td className="text-xs">{fmtTs(e.consumedAt)}</td>
                  <td className="text-xs text-danger text-break">{e.lastPublishError ?? e.consumerLastError ?? ""}</td>
                  <td>
                    {e.publishStatus === "PUBLISHED" && e.publishedVia === "KAFKA" && (
                      <button type="button" className="btn btn-sm btn-ghost" disabled={busy}
                              title="Send the same event to Kafka again; the consumer must skip it as a duplicate"
                              onClick={() => run(`Event ${e.eventType} re-delivered`, () => api.redeliverEvent(e.id))}>
                        Re-deliver
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>

      <Section title="Reconciliation" count={d.reconciliation.length}>
        {d.reconciliation.length === 0 ? <Empty text="No reconciliation mismatches for this order. Run reconciliation from the Reconciliation page." /> : (
          <table className="data-table">
            <thead><tr><th>Type</th><th>Status</th><th>Detected</th><th>Resolution</th><th>Details</th></tr></thead>
            <tbody>
              {d.reconciliation.map((r) => (
                <tr key={r.id}>
                  <td className="font-mono text-xs">{r.mismatchType}</td>
                  <td><Badge status={r.status} /></td>
                  <td className="text-xs">{fmtTs(r.detectedAt)}</td>
                  <td className="text-xs">{r.resolutionAction ? `${r.resolutionAction}: ${r.resolutionNotes ?? ""}` : "—"}</td>
                  <td className="text-xs text-break">{r.details}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>

      <Section title="Creation requests for this order" count={d.requests.length}>
        {d.requests.length === 0 ? <Empty text="No request history (order created before request tracking was added)." /> : (
          <table className="data-table">
            <thead><tr><th>When</th><th>Outcome</th><th>Idempotency key</th><th>Note</th></tr></thead>
            <tbody>
              {d.requests.map((r) => (
                <tr key={r.id}>
                  <td className="text-xs">{fmtTs(r.createdAt)}</td>
                  <td><Badge status={r.outcome} /></td>
                  <td className="font-mono text-xs">{r.idempotencyKey}</td>
                  <td className="text-xs">{r.reason ?? ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>

      <Section title="Full audit trail" count={d.auditTrail.length}>
        <button type="button" className="btn btn-sm btn-ghost" onClick={() => setShowAudit((s) => !s)}>
          {showAudit ? "Hide" : "Show"} audit entries
        </button>
        {showAudit && (
          <table className="data-table" style={{ marginTop: 8 }}>
            <thead><tr><th>When</th><th>Entity</th><th>Action</th><th>Actor</th><th>Details</th></tr></thead>
            <tbody>
              {d.auditTrail.map((a) => (
                <tr key={a.id}>
                  <td className="text-xs">{fmtTs(a.createdAt)}</td>
                  <td className="text-xs">{a.entityType}</td>
                  <td className="font-mono text-xs">{a.action}</td>
                  <td className="text-xs">{a.actor}</td>
                  <td className="text-xs text-break">{a.details}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Section>
      <p className="text-muted text-xs" style={{ marginTop: 12 }}>Auto-refreshing every {POLL_MS / 1000}s from the backend.</p>
    </div>
  );
};
