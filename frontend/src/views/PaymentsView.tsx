import React, { useState, useEffect, useCallback } from "react";
import { api, ApiError } from "../api/client";
import type { Payment, PaymentCallback, PaymentStatus } from "../types";
import { Badge } from "../components/Badge";
import { Spinner } from "../components/Spinner";
import { ErrorBanner } from "../components/ErrorBanner";
import { EmptyState } from "../components/EmptyState";
import { Modal } from "../components/Modal";

interface PaymentsViewProps {
  onNotify: (type: "success" | "error" | "info", msg: string) => void;
}

const STATUS_FILTERS: { label: string; value: PaymentStatus | "ALL" }[] = [
  { label: "All Payments", value: "ALL" },
  { label: "Succeeded", value: "SUCCEEDED" },
  { label: "Pending", value: "PENDING" },
  { label: "Timed Out", value: "TIMED_OUT" },
  { label: "Failed", value: "FAILED" },
];

export const PaymentsView: React.FC<PaymentsViewProps> = ({ onNotify }) => {
  const [payments, setPayments] = useState<Payment[]>([]);
  const [callbacks, setCallbacks] = useState<PaymentCallback[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [errorStatus, setErrorStatus] = useState<number | undefined>();
  const [filter, setFilter] = useState<PaymentStatus | "ALL">("ALL");

  // Callback Details Modal
  const [selectedCallback, setSelectedCallback] = useState<PaymentCallback | null>(null);

  // Send Callback Simulation Modal
  const [simCallbackModalOpen, setSimCallbackModalOpen] = useState(false);
  const [simPaymentId, setSimPaymentId] = useState("");
  const [simProviderRef, setSimProviderRef] = useState("");
  const [simStatus, setSimStatus] = useState<PaymentStatus>("SUCCEEDED");
  const [simSubmitting, setSimSubmitting] = useState(false);

  const fetchPaymentsAndCallbacks = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const statusParam = filter === "ALL" ? undefined : filter;
      const [paymentsData, callbacksData] = await Promise.all([
        api.getPayments(undefined, statusParam),
        api.getPaymentCallbacks().catch(() => []),
      ]);
      setPayments(paymentsData);
      setCallbacks(callbacksData);
    } catch (err: unknown) {
      if (err instanceof ApiError) {
        setError(err.message);
        setErrorStatus(err.status);
      } else {
        setError(err instanceof Error ? err.message : "Failed to load payments");
      }
    } finally {
      setLoading(false);
    }
  }, [filter]);

  useEffect(() => {
    fetchPaymentsAndCallbacks();
  }, [fetchPaymentsAndCallbacks]);

  const handleSimulateCallback = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!simPaymentId) {
      onNotify("error", "Please select or enter a Payment ID");
      return;
    }
    setSimSubmitting(true);
    const callbackId = `cb-sim-${Date.now()}-${Math.random().toString(36).substring(2, 6)}`;
    try {
      await api.processPaymentCallback({
        callbackId,
        paymentId: simPaymentId,
        providerReference: simProviderRef || undefined,
        status: simStatus,
      });
      onNotify("success", `Payment callback ${callbackId} processed!`);
      setSimCallbackModalOpen(false);
      await fetchPaymentsAndCallbacks();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "Failed to process callback";
      onNotify("error", msg);
    } finally {
      setSimSubmitting(false);
    }
  };

  return (
    <div className="view-container">
      <div className="view-header">
        <div>
          <h2>Payment Operations</h2>
          <p className="view-subtitle">
            Simulated payment authorizations, provider references, and immutable webhook audit log.
          </p>
        </div>
        <div className="view-actions">
          <button
            type="button"
            className="btn btn-secondary"
            onClick={fetchPaymentsAndCallbacks}
            disabled={loading}
          >
            ↻ Refresh
          </button>
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => {
              setSimCallbackModalOpen(true);
            }}
          >
            + Post Webhook Callback
          </button>
        </div>
      </div>

      {/* Filter Tabs */}
      <div className="filter-tabs" role="tablist">
        {STATUS_FILTERS.map((s) => (
          <button
            key={s.value}
            type="button"
            role="tab"
            aria-selected={filter === s.value}
            className={`tab-btn ${filter === s.value ? "active" : ""}`}
            onClick={() => setFilter(s.value)}
          >
            {s.label}
          </button>
        ))}
      </div>

      {error && (
        <ErrorBanner
          message={error}
          statusCode={errorStatus}
          onRetry={fetchPaymentsAndCallbacks}
        />
      )}

      {loading ? (
        <div className="loading-card">
          <Spinner size="lg" label="Loading payments and callback history..." />
        </div>
      ) : payments.length === 0 ? (
        <EmptyState
          title="No payments found"
          description={
            filter === "ALL"
              ? "No payments have been authorized yet. You can simulate payments directly from the Orders view."
              : `There are no payments with status '${filter}'.`
          }
        />
      ) : (
        <div className="card table-card mb-4">
          <table className="data-table" aria-label="Payments table">
            <thead>
              <tr>
                <th>Payment ID</th>
                <th>Order ID</th>
                <th>Provider Ref</th>
                <th>Status</th>
                <th>Amount</th>
                <th>Last Callback ID</th>
                <th>Created At</th>
                <th>Succeeded At</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {payments.map((p) => (
                <tr key={p.id}>
                  <td className="font-mono text-xs" title={p.id}>
                    {p.id.substring(0, 8)}...
                  </td>
                  <td className="font-mono text-xs" title={p.orderId}>
                    {p.orderId ? `${p.orderId.substring(0, 8)}...` : "-"}
                  </td>
                  <td className="font-mono text-xs">{p.providerReference || "-"}</td>
                  <td>
                    <Badge status={p.status} />
                  </td>
                  <td className="font-medium">
                    {new Intl.NumberFormat(undefined, {
                      style: "currency",
                      currency: p.currency,
                    }).format(p.amount)}
                  </td>
                  <td className="font-mono text-xs text-secondary">
                    {p.lastCallbackId || "-"}
                  </td>
                  <td className="text-secondary text-sm">
                    {new Date(p.createdAt).toLocaleString()}
                  </td>
                  <td className="text-secondary text-sm">
                    {p.succeededAt ? (
                      <span className="text-success font-medium">
                        {new Date(p.succeededAt).toLocaleString()}
                      </span>
                    ) : (
                      "-"
                    )}
                  </td>
                  <td>
                    <button
                      type="button"
                      className="btn btn-sm btn-ghost"
                      onClick={() => {
                        setSimPaymentId(p.id);
                        setSimProviderRef(p.providerReference || "");
                        setSimCallbackModalOpen(true);
                      }}
                      title="Post a webhook callback for this payment"
                    >
                      Callback
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Webhook Callback History Section */}
      <div className="mt-4">
        <div className="section-title-row">
          <h3>Webhook Callback Audit Trail</h3>
          <span className="badge badge-neutral">{callbacks.length} events received</span>
        </div>

        {callbacks.length === 0 ? (
          <div className="card empty-card-small">
            <p className="text-secondary text-sm">
              No webhook callbacks recorded yet. You can post a simulated webhook using the button above.
            </p>
          </div>
        ) : (
          <div className="card table-card">
            <table className="data-table" aria-label="Callbacks table">
              <thead>
                <tr>
                  <th>Callback ID</th>
                  <th>Payment ID</th>
                  <th>Result</th>
                  <th>Received At</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {callbacks.map((cb) => (
                  <tr key={cb.id}>
                    <td className="font-mono text-xs">{cb.providerCallbackId}</td>
                    <td className="font-mono text-xs">{cb.paymentId.substring(0, 8)}...</td>
                    <td>
                      <Badge status={cb.result} />
                    </td>
                    <td className="text-secondary text-sm">
                      {new Date(cb.receivedAt).toLocaleString()}
                    </td>
                    <td>
                      <button
                        type="button"
                        className="btn btn-sm btn-ghost"
                        onClick={() => setSelectedCallback(cb)}
                      >
                        Inspect Payload
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Callback Payload Modal */}
      {selectedCallback && (
        <Modal
          isOpen={true}
          onClose={() => setSelectedCallback(null)}
          title={`Callback Payload: ${selectedCallback.providerCallbackId}`}
          maxWidth="md"
        >
          <div className="details-grid mb-3">
            <div className="detail-item">
              <span className="detail-label">Payment ID</span>
              <span className="detail-value font-mono text-xs">{selectedCallback.paymentId}</span>
            </div>
            <div className="detail-item">
              <span className="detail-label">Result Status</span>
              <span className="detail-value">
                <Badge status={selectedCallback.result} />
              </span>
            </div>
            <div className="detail-item">
              <span className="detail-label">Received At</span>
              <span className="detail-value">
                {new Date(selectedCallback.receivedAt).toLocaleString()}
              </span>
            </div>
            <div className="detail-item">
              <span className="detail-label">Processed At</span>
              <span className="detail-value">
                {new Date(selectedCallback.processedAt).toLocaleString()}
              </span>
            </div>
          </div>

          <h4 className="mt-3 mb-1">Payload JSON</h4>
          <pre className="code-block">
            {selectedCallback.receivedPayload || "No payload stored"}
          </pre>
        </Modal>
      )}

      {/* Simulate Callback Modal */}
      <Modal
        isOpen={simCallbackModalOpen}
        onClose={() => setSimCallbackModalOpen(false)}
        title="Simulate Webhook Callback"
        maxWidth="md"
      >
        <form onSubmit={handleSimulateCallback} className="form-container">
          <p className="text-secondary text-sm">
            Simulate an asynchronous provider callback (supports delayed success, timeouts, or race conditions).
          </p>

          <div className="form-group">
            <label htmlFor="sim-payment-id">Target Payment ID</label>
            {payments.length > 0 ? (
              <select
                id="sim-payment-id"
                value={simPaymentId}
                onChange={(e) => {
                  setSimPaymentId(e.target.value);
                  const p = payments.find((item) => item.id === e.target.value);
                  if (p && p.providerReference) {
                    setSimProviderRef(p.providerReference);
                  }
                }}
                required
              >
                <option value="">-- Select a Payment --</option>
                {payments.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.id.substring(0, 8)}... (Order: {p.orderId ? p.orderId.substring(0, 8) : "N/A"}..., Status: {p.status}, {p.currency} {p.amount})
                  </option>
                ))}
              </select>
            ) : (
              <input
                id="sim-payment-id"
                type="text"
                placeholder="Payment UUID"
                value={simPaymentId}
                onChange={(e) => setSimPaymentId(e.target.value)}
                required
              />
            )}
          </div>

          <div className="form-group">
            <label htmlFor="sim-provider-ref">Provider Reference (Optional)</label>
            <input
              id="sim-provider-ref"
              type="text"
              placeholder="e.g. ref-ord-12345"
              value={simProviderRef}
              onChange={(e) => setSimProviderRef(e.target.value)}
            />
          </div>

          <div className="form-group">
            <label htmlFor="sim-status">Callback Result Status</label>
            <select
              id="sim-status"
              value={simStatus}
              onChange={(e) => setSimStatus(e.target.value as PaymentStatus)}
            >
              <option value="SUCCEEDED">SUCCEEDED</option>
              <option value="FAILED">FAILED</option>
              <option value="TIMED_OUT">TIMED_OUT</option>
            </select>
            <span className="text-secondary text-xs mt-1">
              Test delayed payments, timeouts, or late successes arriving after order cancellation.
            </span>
          </div>

          <div className="modal-footer">
            <button
              type="button"
              className="btn btn-secondary"
              onClick={() => setSimCallbackModalOpen(false)}
              disabled={simSubmitting}
            >
              Cancel
            </button>
            <button
              type="submit"
              className="btn btn-primary"
              disabled={simSubmitting}
            >
              {simSubmitting ? "Submitting..." : "Send Callback"}
            </button>
          </div>
        </form>
      </Modal>
    </div>
  );
};
