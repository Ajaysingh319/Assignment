import React, { useCallback, useEffect, useState } from "react";
import { api, ApiError } from "../api/client";
import type { OrderRequestOutcome, OrderRequestRecord, OrderRequestSummary } from "../types";
import { Badge } from "../components/Badge";
import { Spinner } from "../components/Spinner";
import { ErrorBanner } from "../components/ErrorBanner";
import { EmptyState } from "../components/EmptyState";

interface Props {
  onNotify: (type: "success" | "error" | "info", msg: string) => void;
}

const OUTCOMES: { value: OrderRequestOutcome; label: string; hint: string }[] = [
  { value: "ACCEPTED", label: "Accepted", hint: "Created an order and reserved stock" },
  { value: "REJECTED_INSUFFICIENT_STOCK", label: "Rejected: stock", hint: "No order created, nothing reserved" },
  { value: "IDEMPOTENT_REPLAY", label: "Idempotent replay", hint: "Retried key: existing order returned" },
  { value: "REJECTED_IDEMPOTENCY_CONFLICT", label: "Key conflict", hint: "Key reused with a different payload" },
  { value: "REJECTED_INVALID", label: "Invalid", hint: "Unknown/inactive product etc." },
];

/**
 * Operation history of POST /api/orders. Rejected purchase attempts never become orders, so they are shown
 * here rather than on the Orders page.
 */
export const OrderRequestsView: React.FC<Props> = () => {
  const [summary, setSummary] = useState<OrderRequestSummary | null>(null);
  const [rows, setRows] = useState<OrderRequestRecord[]>([]);
  const [outcome, setOutcome] = useState<OrderRequestOutcome | "">("");
  const [keyPrefix, setKeyPrefix] = useState("");
  const [appliedPrefix, setAppliedPrefix] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [errorStatus, setErrorStatus] = useState<number | undefined>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [s, r] = await Promise.all([
        api.getOrderRequestSummary(appliedPrefix || undefined),
        api.getOrderRequests({ outcome: outcome || undefined, keyPrefix: appliedPrefix || undefined, limit: 500 }),
      ]);
      setSummary(s);
      setRows(r);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load order requests");
      setErrorStatus(err instanceof ApiError ? err.status : undefined);
    } finally {
      setLoading(false);
    }
  }, [outcome, appliedPrefix]);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <div className="view-container">
      <div className="view-header">
        <div>
          <h2>Order Requests</h2>
          <p className="view-subtitle">
            Every order-creation request and its outcome. A request rejected for insufficient stock never becomes an order;
            this is where those attempts are recorded. Filter by idempotency-key prefix to isolate one concurrency demo run.
          </p>
        </div>
        <div className="view-actions">
          <button type="button" className="btn btn-secondary" onClick={load} disabled={loading}>↻ Refresh</button>
        </div>
      </div>

      <form
        onSubmit={(e) => { e.preventDefault(); setAppliedPrefix(keyPrefix.trim()); }}
        style={{ display: "flex", gap: 8, alignItems: "center", marginBottom: 12 }}
      >
        <input
          type="text"
          placeholder="Idempotency-key prefix, e.g. demo-20261017-1530-"
          value={keyPrefix}
          onChange={(e) => setKeyPrefix(e.target.value)}
          style={{ maxWidth: 380 }}
          aria-label="Idempotency key prefix"
        />
        <button type="submit" className="btn btn-sm btn-primary">Apply</button>
        {appliedPrefix && (
          <button type="button" className="btn btn-sm btn-ghost" onClick={() => { setKeyPrefix(""); setAppliedPrefix(""); }}>
            Clear ({appliedPrefix})
          </button>
        )}
      </form>

      {error && <ErrorBanner message={error} statusCode={errorStatus} onRetry={load} />}

      {summary && (
        <div className="metrics-grid">
          <div className="metric-card">
            <span className="metric-label">Total requests</span>
            <span className="metric-value">{summary.TOTAL}</span>
            <span className="metric-hint">{appliedPrefix ? `key prefix "${appliedPrefix}"` : "all time"}</span>
          </div>
          {OUTCOMES.map((o) => (
            <button
              key={o.value}
              type="button"
              className="metric-card"
              style={{ textAlign: "left", cursor: "pointer", outline: outcome === o.value ? "2px solid var(--color-primary, #3b82f6)" : undefined }}
              onClick={() => setOutcome(outcome === o.value ? "" : o.value)}
              title="Click to filter the table"
            >
              <span className="metric-label">{o.label}</span>
              <span className="metric-value">{summary[o.value] ?? 0}</span>
              <span className="metric-hint">{o.hint}</span>
            </button>
          ))}
        </div>
      )}

      {loading ? (
        <Spinner size="lg" label="Loading request history…" />
      ) : rows.length === 0 ? (
        <EmptyState title="No requests" description="No order-creation requests match the current filter." />
      ) : (
        <div className="card table-card">
          <table className="data-table" aria-label="Order requests">
            <thead>
              <tr><th>When</th><th>Outcome</th><th>Idempotency key</th><th>Order</th><th>Lines / units</th><th>Reason</th></tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td className="text-xs">{new Date(r.createdAt).toLocaleString()}</td>
                  <td><Badge status={r.outcome} /></td>
                  <td className="font-mono text-xs text-break">{r.idempotencyKey}</td>
                  <td className="font-mono text-xs" title={r.orderId ?? ""}>{r.orderId ? `${r.orderId.substring(0, 8)}…` : "— (no order)"}</td>
                  <td>{r.itemCount} / {r.totalQuantity}</td>
                  <td className="text-xs text-break">{r.reason ?? ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {rows.length >= 500 && <p className="text-muted text-xs">Showing the most recent 500 rows.</p>}
        </div>
      )}
    </div>
  );
};
