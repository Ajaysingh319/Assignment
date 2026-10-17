import React, { useCallback, useEffect, useState } from "react";
import { api, ApiError } from "../api/client";
import type { EventPipelineSummary, OutboxEventRecord } from "../types";
import { Badge } from "../components/Badge";
import { Spinner } from "../components/Spinner";
import { ErrorBanner } from "../components/ErrorBanner";
import { EmptyState } from "../components/EmptyState";

interface Props {
  onNotify: (type: "success" | "error" | "info", msg: string) => void;
}

const fmt = (v?: string | null) => (v ? new Date(v).toLocaleString() : "—");

/**
 * Transactional outbox → Kafka → idempotent consumer, with real publication and consumption state.
 */
export const EventPipelineView: React.FC<Props> = ({ onNotify }) => {
  const [summary, setSummary] = useState<EventPipelineSummary | null>(null);
  const [events, setEvents] = useState<OutboxEventRecord[]>([]);
  const [statusFilter, setStatusFilter] = useState<"" | "PENDING" | "PUBLISHED">("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [errorStatus, setErrorStatus] = useState<number | undefined>();
  const [busy, setBusy] = useState(false);
  const [faultCount, setFaultCount] = useState("2");
  const [faultType, setFaultType] = useState("");
  const [faultAggregate, setFaultAggregate] = useState("");

  const load = useCallback(async (quiet = false) => {
    if (!quiet) setLoading(true);
    try {
      const [s, e] = await Promise.all([api.getEventPipelineSummary(), api.getOutboxEvents(statusFilter || undefined)]);
      setSummary(s);
      setEvents(e);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to load event pipeline");
      setErrorStatus(err instanceof ApiError ? err.status : undefined);
    } finally {
      if (!quiet) setLoading(false);
    }
  }, [statusFilter]);

  useEffect(() => {
    load();
    const t = window.setInterval(() => load(true), 3000);
    return () => window.clearInterval(t);
  }, [load]);

  const act = async (label: string, fn: () => Promise<unknown>) => {
    setBusy(true);
    try {
      await fn();
      onNotify("success", label);
      await load(true);
    } catch (err) {
      onNotify("error", err instanceof Error ? err.message : `${label} failed`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="view-container">
      <div className="view-header">
        <div>
          <h2>Event Pipeline</h2>
          <p className="view-subtitle">
            Outbox rows are written in the same transaction as the business change, published to Kafka by a scheduled poller,
            and consumed idempotently. Auto-refreshes every 3s.
          </p>
        </div>
        <div className="view-actions">
          <button type="button" className="btn btn-secondary" onClick={() => load()} disabled={loading}>↻ Refresh</button>
          <button type="button" className="btn btn-secondary" disabled={busy}
                  title="Runs one poll immediately; the scheduler also does this every 2 seconds"
                  onClick={() => act("Outbox poll executed", async () => {
                    const r = await api.publishOutbox();
                    onNotify("info", `${r.publishedCount} event(s) published in this poll`);
                  })}>
            Poll outbox now
          </button>
        </div>
      </div>

      {error && <ErrorBanner message={error} statusCode={errorStatus} onRetry={() => load()} />}

      {summary && (
        <>
          {!summary.kafkaAvailable && (
            <div className="notice-banner"><span className="notice-icon">⚠️</span>
              KafkaTemplate is not configured in this backend: events stay pending and are not consumed.</div>
          )}
          <div className="metrics-grid">
            <div className="metric-card"><span className="metric-label">Outbox events</span><span className="metric-value">{summary.totalOutboxEvents}</span><span className="metric-hint">all time</span></div>
            <div className="metric-card"><span className="metric-label">Pending publication</span><span className="metric-value">{summary.pendingPublication}</span><span className="metric-hint">{summary.publishFailing} with failed attempts</span></div>
            <div className="metric-card"><span className="metric-label">Published to Kafka</span><span className="metric-value">{summary.publishedViaKafka}</span><span className="metric-hint">{summary.publishedSimulated} simulated (test only)</span></div>
            <div className="metric-card"><span className="metric-label">Consumed (processed)</span><span className="metric-value">{summary.consumerProcessed}</span><span className="metric-hint">processed_events rows</span></div>
            <div className="metric-card"><span className="metric-label">Retrying</span><span className="metric-value">{summary.consumerRetrying}</span><span className="metric-hint">awaiting redelivery</span></div>
            <div className="metric-card"><span className="metric-label">Dead-lettered</span><span className="metric-value text-danger">{summary.deadLettered}</span><span className="metric-hint">{summary.recoveredOrResolved} recovered / resolved</span></div>
          </div>
        </>
      )}

      <div className="card" style={{ padding: 12, marginBottom: 16 }}>
        <h4 style={{ margin: "0 0 4px" }}>Worker failure simulation</h4>
        <p className="text-secondary text-xs" style={{ margin: "0 0 8px" }}>
          The next N matching consumption attempts throw. With N &lt; 3 the event is redelivered by Kafka and recovers;
          with N ≥ 3 (max retries) it is dead-lettered to &lt;topic&gt;.DLT and appears under Failed Events.
          {summary && summary.faultInjectionRemaining > 0 && (
            <strong> Armed: {summary.faultInjectionRemaining} failure(s) remaining{summary.faultInjectionEventType ? ` for ${summary.faultInjectionEventType}` : ""}.</strong>
          )}
        </p>
        <div style={{ display: "flex", gap: 8, alignItems: "flex-end", flexWrap: "wrap" }}>
          <div className="form-group" style={{ margin: 0, width: 110 }}>
            <label htmlFor="fault-count" className="text-xs">Failures</label>
            <input id="fault-count" type="number" min="0" max="20" value={faultCount} onChange={(e) => setFaultCount(e.target.value)} />
          </div>
          <div className="form-group" style={{ margin: 0 }}>
            <label htmlFor="fault-type" className="text-xs">Event type (optional)</label>
            <select id="fault-type" value={faultType} onChange={(e) => setFaultType(e.target.value)}>
              <option value="">Any</option>
              {["ORDER_CREATED", "ORDER_CONFIRMED", "ORDER_CANCELLED", "ORDER_EXPIRED", "ORDER_FULFILLED", "PAYMENT_SUCCEEDED", "PAYMENT_FAILED", "PAYMENT_TIMED_OUT", "PAYMENT_CALLBACK_PROCESSED"].map((t) => (
                <option key={t} value={t}>{t}</option>
              ))}
            </select>
          </div>
          <div className="form-group" style={{ margin: 0, minWidth: 300 }}>
            <label htmlFor="fault-agg" className="text-xs">Order / payment id (optional)</label>
            <input id="fault-agg" type="text" value={faultAggregate} placeholder="restrict to one aggregate" onChange={(e) => setFaultAggregate(e.target.value)} />
          </div>
          <button type="button" className="btn btn-sm btn-danger" disabled={busy}
                  onClick={() => act(`Fault injection armed (${faultCount})`, () => api.setFaultInjection(Number(faultCount) || 0, faultType, faultAggregate))}>
            Arm
          </button>
          <button type="button" className="btn btn-sm btn-ghost" disabled={busy}
                  onClick={() => act("Fault injection disarmed", () => api.setFaultInjection(0))}>
            Disarm
          </button>
        </div>
      </div>

      <div className="filter-tabs" role="tablist">
        {([["", "All (latest 300)"], ["PENDING", "Pending"], ["PUBLISHED", "Published"]] as const).map(([v, label]) => (
          <button key={v} type="button" role="tab" aria-selected={statusFilter === v}
                  className={`tab-btn ${statusFilter === v ? "active" : ""}`} onClick={() => setStatusFilter(v)}>
            {label}
          </button>
        ))}
      </div>

      {loading ? (
        <Spinner size="lg" label="Loading outbox…" />
      ) : events.length === 0 ? (
        <EmptyState title="No events" description="No outbox events match this filter." />
      ) : (
        <div className="card table-card">
          <table className="data-table" aria-label="Outbox events">
            <thead>
              <tr><th>Event</th><th>Aggregate</th><th>Topic</th><th>Created</th><th>Publication</th><th>Published</th><th>Consumer</th><th>Consumed</th><th>Error</th><th /></tr>
            </thead>
            <tbody>
              {events.map((e) => (
                <tr key={e.id}>
                  <td><span className="font-mono text-xs">{e.eventType}</span><br /><span className="text-muted text-xs" title={e.id}>{e.id.substring(0, 8)}…</span></td>
                  <td className="text-xs"><span className="text-secondary">{e.aggregateType}</span><br /><span className="font-mono" title={e.aggregateId}>{e.aggregateId.substring(0, 8)}…</span></td>
                  <td className="font-mono text-xs">{e.topic}</td>
                  <td className="text-xs">{fmt(e.createdAt)}</td>
                  <td><Badge status={e.publishStatus} /><div className="text-xs text-secondary">{e.publishedVia ? `via ${e.publishedVia}, ` : ""}{e.publishAttempts} attempt(s)</div></td>
                  <td className="text-xs">{fmt(e.publishedAt)}</td>
                  <td><Badge status={e.consumerStatus} />{e.consumerAttempts ? <div className="text-xs text-secondary">{e.consumerAttempts} failed attempt(s)</div> : null}</td>
                  <td className="text-xs">{fmt(e.consumedAt)}</td>
                  <td className="text-xs text-danger text-break">{e.lastPublishError ?? e.consumerLastError ?? ""}</td>
                  <td>
                    {e.publishStatus === "PUBLISHED" && e.publishedVia === "KAFKA" && (
                      <button type="button" className="btn btn-sm btn-ghost" disabled={busy}
                              title="Send this event to Kafka again; the consumer must detect the duplicate"
                              onClick={() => act(`Event ${e.eventType} re-delivered`, () => api.redeliverEvent(e.id))}>
                        Re-deliver
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};
