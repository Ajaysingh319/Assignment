import React, { useState, useEffect, useCallback } from "react";
import { api, ApiError } from "../api/client";
import type { AuditEntry, ReconciliationRecord, ReconciliationStatus } from "../types";

/** Corrective actions that change real business state, by mismatch type (validated again by the backend). */
const CORRECTIVE_ACTIONS: Record<string, { value: string; label: string }[]> = {
  PAYMENT_SUCCEEDED_ORDER_CANCELLED: [{ value: "REFUND_PAYMENT", label: "REFUND_PAYMENT (simulated refund; payment → REFUNDED)" }],
  DUPLICATE_SUCCESSFUL_PAYMENT: [{ value: "REFUND_PAYMENT", label: "REFUND_PAYMENT (refund the latest duplicate charge)" }],
  PAYMENT_AMOUNT_MISMATCH: [{ value: "REFUND_PAYMENT", label: "REFUND_PAYMENT (refund the mismatched payment)" }],
  STALE_PENDING_ORDER: [{ value: "EXPIRE_ORDER", label: "EXPIRE_ORDER (cancel order and release its reservations)" }],
};
import { Badge } from "../components/Badge";
import { Spinner } from "../components/Spinner";
import { ErrorBanner } from "../components/ErrorBanner";
import { EmptyState } from "../components/EmptyState";
import { Modal } from "../components/Modal";

interface ReconciliationViewProps {
  onNotify: (type: "success" | "error" | "info", msg: string) => void;
}

const STATUS_FILTERS: { label: string; value: ReconciliationStatus | "ALL" }[] = [
  { label: "All Records", value: "ALL" },
  { label: "Action Required", value: "ACTION_REQUIRED" },
  { label: "Open", value: "OPEN" },
  { label: "Resolved", value: "RESOLVED" },
  { label: "Dismissed", value: "DISMISSED" },
];

export const ReconciliationView: React.FC<ReconciliationViewProps> = ({ onNotify }) => {
  const [records, setRecords] = useState<ReconciliationRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [errorStatus, setErrorStatus] = useState<number | undefined>();
  const [statusFilter, setStatusFilter] = useState<ReconciliationStatus | "ALL">("ALL");

  // Inspection / Resolution Modal
  const [selectedRecord, setSelectedRecord] = useState<ReconciliationRecord | null>(null);
  const [resolveAction, setResolveAction] = useState<string>("RESOLVE");
  const [auditEntries, setAuditEntries] = useState<AuditEntry[]>([]);
  const [resolveNotes, setResolveNotes] = useState("");
  const [resolving, setResolving] = useState(false);

  const fetchRecords = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const statusParam = statusFilter === "ALL" ? undefined : statusFilter;
      const data = await api.getReconciliationRecords(statusParam);
      setRecords(data);
    } catch (err: unknown) {
      if (err instanceof ApiError) {
        setError(err.message);
        setErrorStatus(err.status);
      } else {
        setError(err instanceof Error ? err.message : "Failed to load reconciliation records");
      }
    } finally {
      setLoading(false);
    }
  }, [statusFilter]);

  const fetchAudit = useCallback(async () => {
    try {
      const [runs, decisions] = await Promise.all([api.getAudit("reconciliation_run", 20), api.getAudit("reconciliation", 50)]);
      setAuditEntries([...runs, ...decisions].sort((a, b) => b.createdAt.localeCompare(a.createdAt)));
    } catch {
      // The audit panel is supplementary; the records table shows its own error state
    }
  }, []);

  useEffect(() => {
    fetchRecords();
    fetchAudit();
  }, [fetchRecords, fetchAudit]);

  const handleRunReconciliation = async () => {
    setRunning(true);
    try {
      const result = await api.runReconciliation();
      onNotify(
        "success",
        `Reconciliation run complete! ${result.detectedCount} new mismatch(es) detected, ${result.existingCount} existing.`
      );
      await Promise.all([fetchRecords(), fetchAudit()]);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "Reconciliation run failed";
      onNotify("error", msg);
    } finally {
      setRunning(false);
    }
  };

  const handleResolveRecord = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedRecord) return;
    setResolving(true);
    try {
      const updated = await api.resolveReconciliationWithAction(
        selectedRecord.id,
        resolveAction,
        resolveNotes || undefined
      );
      onNotify("success", `Record ${updated.status} via ${updated.resolutionAction ?? resolveAction}`);
      setSelectedRecord(null);
      setResolveNotes("");
      await Promise.all([fetchRecords(), fetchAudit()]);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "Failed to resolve record";
      onNotify("error", msg);
    } finally {
      setResolving(false);
    }
  };

  const parseDetails = (detailsStr: string) => {
    try {
      return JSON.parse(detailsStr) as Record<string, unknown>;
    } catch {
      return { raw: detailsStr };
    }
  };

  const actionRequiredCount = records.filter((r) => r.status === "ACTION_REQUIRED").length;

  return (
    <div className="view-container">
      <div className="view-header">
        <div>
          <h2>Payment & Order Reconciliation</h2>
          <p className="view-subtitle">
            Automated detection of contradictory business states, stale pending transactions, and simulated corrective actions.
          </p>
        </div>
        <div className="view-actions">
          <button
            type="button"
            className="btn btn-secondary"
            onClick={fetchRecords}
            disabled={loading}
          >
            ↻ Refresh
          </button>
          <button
            type="button"
            className="btn btn-primary"
            onClick={handleRunReconciliation}
            disabled={running}
          >
            {running ? "Scanning Database..." : "⚡ Run Reconciliation"}
          </button>
        </div>
      </div>

      {/* Safety Notice */}
      <div className="notice-banner">
        <span className="notice-icon">🛡</span>
        <div className="notice-body">
          <strong>Simulated Corrective Actions Only:</strong> Actions such as{" "}
          <code>REFUND_REVIEW_REQUIRED</code> or <code>PAYMENT_AUDIT_REQUIRED</code> are simulated
          advisory workflows. The platform will never move real money or execute automated external refunds.
        </div>
      </div>

      {/* Status Filter Tabs */}
      <div className="filter-tabs mt-3" role="tablist">
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
            {s.value === "ACTION_REQUIRED" && actionRequiredCount > 0 && (
              <span className="tab-counter">{actionRequiredCount}</span>
            )}
          </button>
        ))}
      </div>

      {error && (
        <ErrorBanner
          message={error}
          statusCode={errorStatus}
          onRetry={fetchRecords}
        />
      )}

      {loading ? (
        <div className="loading-card">
          <Spinner size="lg" label="Analyzing reconciliation state..." />
        </div>
      ) : records.length === 0 ? (
        <EmptyState
          title="All states balanced"
          description={
            statusFilter === "ALL"
              ? "No reconciliation mismatches detected in the system. Click 'Run Reconciliation' to perform an on-demand audit."
              : `No mismatch records with status '${statusFilter}'.`
          }
          actionLabel="Run Audit Now"
          onAction={handleRunReconciliation}
        />
      ) : (
        <div className="card table-card">
          <table className="data-table" aria-label="Reconciliation table">
            <thead>
              <tr>
                <th>Mismatch Type</th>
                <th>Status</th>
                <th>Simulated Action</th>
                <th>Related Order</th>
                <th>Related Payment</th>
                <th>Detected At</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {records.map((r) => {
                const detailsObj = parseDetails(r.details);
                const simAction = (detailsObj.action as string) || "REVIEW_REQUIRED";

                return (
                  <tr key={r.id}>
                    <td>
                      <span className="font-semibold text-sm">
                        {r.mismatchType.replace(/_/g, " ")}
                      </span>
                    </td>
                    <td>
                      <Badge status={r.status} />
                    </td>
                    <td>
                      <code className="text-xs font-semibold text-warning">
                        {simAction}
                      </code>
                    </td>
                    <td className="font-mono text-xs">
                      {r.orderId ? `${r.orderId.substring(0, 8)}...` : "-"}
                    </td>
                    <td className="font-mono text-xs">
                      {r.paymentId ? `${r.paymentId.substring(0, 8)}...` : "-"}
                    </td>
                    <td className="text-secondary text-sm">
                      {new Date(r.detectedAt).toLocaleString()}
                    </td>
                    <td>
                      <button
                        type="button"
                        className="btn btn-sm btn-ghost"
                        onClick={() => {
                          setSelectedRecord(r);
                          setResolveAction(CORRECTIVE_ACTIONS[r.mismatchType]?.[0]?.value ?? "RESOLVE");
                        }}
                      >
                        Inspect & Resolve
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {/* Reconciliation audit trail: every run and every operator decision */}
      <div className="card table-card" style={{ marginTop: 20 }}>
        <h4 style={{ margin: "12px 12px 8px" }}>Reconciliation audit trail</h4>
        {auditEntries.length === 0 ? (
          <p className="text-secondary text-sm" style={{ margin: "0 12px 12px" }}>No reconciliation runs or decisions recorded yet.</p>
        ) : (
          <table className="data-table" aria-label="Reconciliation audit trail">
            <thead><tr><th>When</th><th>Type</th><th>Action</th><th>Actor</th><th>Details</th></tr></thead>
            <tbody>
              {auditEntries.map((a) => (
                <tr key={a.id}>
                  <td className="text-xs">{new Date(a.createdAt).toLocaleString()}</td>
                  <td className="text-xs">{a.entityType === "reconciliation_run" ? "Run" : "Record"}</td>
                  <td className="font-mono text-xs">{a.action}</td>
                  <td className="text-xs">{a.actor}</td>
                  <td className="text-xs text-break">{a.details}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* Inspect & Resolve Modal */}
      {selectedRecord && (
        <Modal
          isOpen={true}
          onClose={() => setSelectedRecord(null)}
          title={`Reconciliation Record: ${selectedRecord.mismatchType}`}
          maxWidth="lg"
        >
          <div className="details-grid mb-3">
            <div className="detail-item">
              <span className="detail-label">Status</span>
              <span className="detail-value">
                <Badge status={selectedRecord.status} />
              </span>
            </div>
            <div className="detail-item">
              <span className="detail-label">Unique Mismatch Key</span>
              <span className="detail-value font-mono text-xs text-break">
                {selectedRecord.mismatchKey}
              </span>
            </div>
            <div className="detail-item">
              <span className="detail-label">Order ID</span>
              <span className="detail-value font-mono text-xs">
                {selectedRecord.orderId || "N/A"}
              </span>
            </div>
            <div className="detail-item">
              <span className="detail-label">Payment ID</span>
              <span className="detail-value font-mono text-xs">
                {selectedRecord.paymentId || "N/A"}
              </span>
            </div>
            <div className="detail-item">
              <span className="detail-label">Detected At</span>
              <span className="detail-value">
                {new Date(selectedRecord.detectedAt).toLocaleString()}
              </span>
            </div>
            {selectedRecord.resolvedAt && (
              <div className="detail-item">
                <span className="detail-label">Resolved At</span>
                <span className="detail-value text-success">
                  {new Date(selectedRecord.resolvedAt).toLocaleString()}
                </span>
              </div>
            )}
          </div>

          <h4 className="mt-3 mb-1">Evidence & Evidence Details</h4>
          <pre className="code-block mb-3">
            {JSON.stringify(parseDetails(selectedRecord.details), null, 2)}
          </pre>

          {selectedRecord.status !== "RESOLVED" && selectedRecord.status !== "DISMISSED" ? (
            <form onSubmit={handleResolveRecord} className="form-container mt-4 border-top pt-3">
              <h4 className="mb-2">Simulated Corrective Action</h4>
              <div className="form-group">
                <label htmlFor="resolve-action-select">Resolution Type</label>
                <select
                  id="resolve-action-select"
                  value={resolveAction}
                  onChange={(e) => setResolveAction(e.target.value)}
                >
                  {(CORRECTIVE_ACTIONS[selectedRecord.mismatchType] ?? []).map((a) => (
                    <option key={a.value} value={a.value}>{a.label}</option>
                  ))}
                  <option value="RESOLVE">RESOLVE (mark resolved; no business state change)</option>
                  <option value="DISMISS">DISMISS (acknowledge as expected exception)</option>
                </select>
              </div>

              <div className="form-group">
                <label htmlFor="resolve-notes">Operator Audit Notes</label>
                <textarea
                  id="resolve-notes"
                  rows={2}
                  placeholder="e.g. Verified customer was refunded via external support portal"
                  value={resolveNotes}
                  onChange={(e) => setResolveNotes(e.target.value)}
                  required
                />
              </div>

              <div className="modal-footer">
                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={() => setSelectedRecord(null)}
                  disabled={resolving}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="btn btn-primary"
                  disabled={resolving}
                >
                  {resolving ? "Saving..." : "Apply Resolution"}
                </button>
              </div>
            </form>
          ) : (
            <>
              <h4 className="mt-3 mb-1">Resolution</h4>
              <p className="text-sm">
                <strong>{selectedRecord.resolutionAction ?? selectedRecord.status}</strong>
                {selectedRecord.resolutionNotes ? ` — ${selectedRecord.resolutionNotes}` : ""}
              </p>
              <div className="modal-footer">
                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={() => setSelectedRecord(null)}
                >
                  Close
                </button>
              </div>
            </>
          )}
        </Modal>
      )}
    </div>
  );
};
