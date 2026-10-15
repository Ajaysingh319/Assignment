import React, { useState, useEffect, useCallback } from "react";
import { api, ApiError } from "../api/client";
import type { EventFailure, EventFailureStatus, EventFailureSummary } from "../types";
import { Badge } from "../components/Badge";
import { Spinner } from "../components/Spinner";
import { ErrorBanner } from "../components/ErrorBanner";
import { EmptyState } from "../components/EmptyState";
import { Modal } from "../components/Modal";

interface FailedEventsViewProps {
  onNotify: (type: "success" | "error" | "info", msg: string) => void;
}

const STATUS_FILTERS: { label: string; value: EventFailureStatus | "ALL" }[] = [
  { label: "All Events", value: "ALL" },
  { label: "Dead-Lettered", value: "DEAD_LETTERED" },
  { label: "Retrying", value: "RETRYING" },
  { label: "Resolved", value: "RESOLVED" },
];

export const FailedEventsView: React.FC<FailedEventsViewProps> = ({ onNotify }) => {
  const [failures, setFailures] = useState<EventFailure[]>([]);
  const [summary, setSummary] = useState<EventFailureSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [errorStatus, setErrorStatus] = useState<number | undefined>();
  const [statusFilter, setStatusFilter] = useState<EventFailureStatus | "ALL">("ALL");

  // Inspection Modal
  const [selectedFailure, setSelectedFailure] = useState<EventFailure | null>(null);
  const [retryingId, setRetryingId] = useState<string | null>(null);
  const [batchRetrying, setBatchRetrying] = useState(false);
  const [publishingOutbox, setPublishingOutbox] = useState(false);

  const fetchFailuresAndSummary = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const statusParam = statusFilter === "ALL" ? undefined : statusFilter;
      const [failuresData, summaryData] = await Promise.all([
        api.getFailures(statusParam),
        api.getFailureSummary().catch(() => null),
      ]);
      setFailures(failuresData);
      setSummary(summaryData);
    } catch (err: unknown) {
      if (err instanceof ApiError) {
        setError(err.message);
        setErrorStatus(err.status);
      } else {
        setError(err instanceof Error ? err.message : "Failed to load failed events");
      }
    } finally {
      setLoading(false);
    }
  }, [statusFilter]);

  useEffect(() => {
    fetchFailuresAndSummary();
  }, [fetchFailuresAndSummary]);

  const handleRetrySingle = async (failureId: string) => {
    setRetryingId(failureId);
    try {
      const result = await api.retryFailure(failureId);
      onNotify("success", `Event ${result.eventId} replayed. Status: ${result.status}`);
      await fetchFailuresAndSummary();
      if (selectedFailure && selectedFailure.id === failureId) {
        setSelectedFailure(result);
      }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "Retry attempt failed";
      onNotify("error", msg);
    } finally {
      setRetryingId(null);
    }
  };

  const handleRetryAllEligible = async () => {
    setBatchRetrying(true);
    try {
      const results = await api.retryEligibleFailures();
      onNotify("success", `Batch retry finished for ${results.length} eligible failure(s).`);
      await fetchFailuresAndSummary();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "Batch retry failed";
      onNotify("error", msg);
    } finally {
      setBatchRetrying(false);
    }
  };

  const handlePublishOutbox = async () => {
    setPublishingOutbox(true);
    try {
      const res = await api.publishOutbox();
      onNotify("info", `Outbox publisher triggered: ${res.publishedCount} event(s) published to Kafka.`);
      await fetchFailuresAndSummary();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "Outbox publish trigger failed";
      onNotify("error", msg);
    } finally {
      setPublishingOutbox(false);
    }
  };

  return (
    <div className="view-container">
      <div className="view-header">
        <div>
          <h2>Dead-Letter Queue & Failed Events</h2>
          <p className="view-subtitle">
            Bounded consumer retries, failure diagnostics, audited operator replaying, and outbox synchronization.
          </p>
        </div>
        <div className="view-actions">
          <button
            type="button"
            className="btn btn-secondary"
            onClick={fetchFailuresAndSummary}
            disabled={loading}
          >
            ↻ Refresh
          </button>
          <button
            type="button"
            className="btn btn-secondary"
            onClick={handlePublishOutbox}
            disabled={publishingOutbox}
          >
            {publishingOutbox ? "Publishing..." : "⚡ Publish Outbox"}
          </button>
          <button
            type="button"
            className="btn btn-primary"
            onClick={handleRetryAllEligible}
            disabled={batchRetrying || (summary?.deadLetteredCount === 0 && summary?.retryingCount === 0)}
          >
            {batchRetrying ? "Retrying..." : "↻ Retry All Eligible"}
          </button>
        </div>
      </div>

      {/* Summary KPI Cards */}
      {summary && (
        <div className="metrics-grid">
          <div className="metric-card">
            <span className="metric-label">Dead-Lettered</span>
            <span className="metric-value text-danger">{summary.deadLetteredCount}</span>
            <span className="metric-hint">Retries exhausted; requires intervention</span>
          </div>
          <div className="metric-card">
            <span className="metric-label">In Retry Loop</span>
            <span className="metric-value text-info">{summary.retryingCount}</span>
            <span className="metric-hint">Attempt count &lt; bounded threshold</span>
          </div>
          <div className="metric-card">
            <span className="metric-label">Resolved / Replayed</span>
            <span className="metric-value text-success">{summary.resolvedCount + summary.replayedCount}</span>
            <span className="metric-hint">Successfully reprocessed</span>
          </div>
          <div className="metric-card">
            <span className="metric-label">Total Failures Logged</span>
            <span className="metric-value text-neutral">{summary.totalFailures}</span>
            <span className="metric-hint">Preserved historical audit records</span>
          </div>
        </div>
      )}

      {/* Status Filter Tabs */}
      <div className="filter-tabs" role="tablist">
        {STATUS_FILTERS.map((s) => (
          <button
            key={s.value}
            type="button"
            role="tab"
            aria-selected={statusFilter === s.value}
            className={`tab-btn ${statusFilter === s.value ? "active" : ""}`}
            onClick={() => setStatusFilter(s.value)}
          >
            {s.label}
          </button>
        ))}
      </div>

      {error && (
        <ErrorBanner
          message={error}
          statusCode={errorStatus}
          onRetry={fetchFailuresAndSummary}
        />
      )}

      {loading ? (
        <div className="loading-card">
          <Spinner size="lg" label="Loading failure records and dead-letter queues..." />
        </div>
      ) : failures.length === 0 ? (
        <EmptyState
          title="No failed events"
          description={
            statusFilter === "ALL"
              ? "All consumer event queues are operating normally with zero recorded failures."
              : `No events found matching status '${statusFilter}'.`
          }
        />
      ) : (
        <div className="card table-card">
          <table className="data-table" aria-label="Failed events table">
            <thead>
              <tr>
                <th>Event ID</th>
                <th>Topic</th>
                <th>Consumer</th>
                <th>Attempts</th>
                <th>Status</th>
                <th>Last Error</th>
                <th>First Failed</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {failures.map((f) => (
                <tr key={f.id}>
                  <td className="font-mono text-xs">
                    <span title={f.eventId}>{f.eventId.substring(0, 8)}...</span>
                  </td>
                  <td>
                    <code className="text-xs">{f.topic}</code>
                  </td>
                  <td className="text-sm font-medium">{f.consumerName}</td>
                  <td>
                    <span
                      className={`badge ${
                        f.attemptCount >= 3 ? "badge-danger" : "badge-info"
                      }`}
                    >
                      {f.attemptCount} / 3
                    </span>
                  </td>
                  <td>
                    <Badge status={f.status} />
                  </td>
                  <td className="text-xs text-danger text-truncate" style={{ maxWidth: "220px" }}>
                    {f.lastError || "Unknown error"}
                  </td>
                  <td className="text-secondary text-sm">
                    {new Date(f.firstFailedAt).toLocaleTimeString()}
                  </td>
                  <td>
                    <div className="table-btn-group">
                      <button
                        type="button"
                        className="btn btn-sm btn-ghost"
                        onClick={() => setSelectedFailure(f)}
                      >
                        Inspect
                      </button>
                      {f.eligibleForRetry && (
                        <button
                          type="button"
                          className="btn btn-sm btn-primary"
                          onClick={() => handleRetrySingle(f.id)}
                          disabled={retryingId === f.id}
                        >
                          {retryingId === f.id ? "Retrying..." : "↻ Retry"}
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Inspect Failure Modal */}
      {selectedFailure && (
        <Modal
          isOpen={true}
          onClose={() => setSelectedFailure(null)}
          title={`Failed Event: ${selectedFailure.eventId}`}
          maxWidth="lg"
          footer={
            <div className="modal-actions-bar">
              {selectedFailure.eligibleForRetry && (
                <button
                  type="button"
                  className="btn btn-primary"
                  onClick={() => handleRetrySingle(selectedFailure.id)}
                  disabled={retryingId === selectedFailure.id}
                >
                  {retryingId === selectedFailure.id ? "Replaying..." : "↻ Replay Event"}
                </button>
              )}
              <button
                type="button"
                className="btn btn-secondary"
                onClick={() => setSelectedFailure(null)}
              >
                Close
              </button>
            </div>
          }
        >
          <div className="details-grid mb-3">
            <div className="detail-item">
              <span className="detail-label">Status</span>
              <span className="detail-value">
                <Badge status={selectedFailure.status} />
              </span>
            </div>
            <div className="detail-item">
              <span className="detail-label">Topic</span>
              <span className="detail-value font-mono text-xs">{selectedFailure.topic}</span>
            </div>
            <div className="detail-item">
              <span className="detail-label">Consumer Name</span>
              <span className="detail-value font-mono text-xs">{selectedFailure.consumerName}</span>
            </div>
            <div className="detail-item">
              <span className="detail-label">Attempt Count</span>
              <span className="detail-value font-bold">{selectedFailure.attemptCount}</span>
            </div>
            <div className="detail-item">
              <span className="detail-label">First Failed At</span>
              <span className="detail-value">
                {new Date(selectedFailure.firstFailedAt).toLocaleString()}
              </span>
            </div>
            <div className="detail-item">
              <span className="detail-label">Last Failed At</span>
              <span className="detail-value">
                {new Date(selectedFailure.lastFailedAt).toLocaleString()}
              </span>
            </div>
            {selectedFailure.resolvedAt && (
              <div className="detail-item">
                <span className="detail-label">Resolved At</span>
                <span className="detail-value text-success">
                  {new Date(selectedFailure.resolvedAt).toLocaleString()}
                </span>
              </div>
            )}
          </div>

          <h4 className="mt-3 mb-1">Last Failure Error</h4>
          <div className="error-box mb-3">
            <code>{selectedFailure.lastError || "No message logged"}</code>
          </div>

          <h4 className="mt-3 mb-1">Event Payload</h4>
          <pre className="code-block">
            {selectedFailure.payload || "No payload stored"}
          </pre>
        </Modal>
      )}
    </div>
  );
};
