import React, { useState, useEffect, useCallback } from "react";
import { api, ApiError } from "../api/client";
import type { Order, OrderStatus, Product } from "../types";
import { Badge } from "../components/Badge";
import { Spinner } from "../components/Spinner";
import { ErrorBanner } from "../components/ErrorBanner";
import { EmptyState } from "../components/EmptyState";
import { Modal } from "../components/Modal";

interface OrdersViewProps {
  onNotify: (type: "success" | "error" | "info", msg: string) => void;
}

const STATUS_FILTERS: { label: string; value: OrderStatus | "ALL" }[] = [
  { label: "All Orders", value: "ALL" },
  { label: "Pending Payment", value: "PENDING_PAYMENT" },
  { label: "Confirmed", value: "CONFIRMED" },
  { label: "Fulfilled", value: "FULFILLED" },
  { label: "Cancelled", value: "CANCELLED" },
];

export const OrdersView: React.FC<OrdersViewProps> = ({ onNotify }) => {
  const [orders, setOrders] = useState<Order[]>([]);
  const [products, setProducts] = useState<Product[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [errorStatus, setErrorStatus] = useState<number | undefined>();
  const [filter, setFilter] = useState<OrderStatus | "ALL">("ALL");

  // Selected Order for Details Modal
  const [selectedOrder, setSelectedOrder] = useState<Order | null>(null);
  const [actionLoading, setActionLoading] = useState<boolean>(false);

  // Create Order Modal State
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [lineItems, setLineItems] = useState<{ productId: string; quantity: string; unitPrice: string }[]>([
    { productId: "", quantity: "1", unitPrice: "25.00" }
  ]);
  const [createCurrency, setCreateCurrency] = useState("USD");

  // Simulate Payment Modal State
  const [payModalOpen, setPayModalOpen] = useState(false);
  const [payTargetOrder, setPayTargetOrder] = useState<Order | null>(null);
  const [payOutcome, setPayOutcome] = useState<"SUCCEEDED" | "FAILED" | "TIMED_OUT">("SUCCEEDED");

  const fetchOrders = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const statusParam = filter === "ALL" ? undefined : filter;
      const data = await api.getOrders(statusParam);
      setOrders(data);
    } catch (err: unknown) {
      if (err instanceof ApiError) {
        setError(err.message);
        setErrorStatus(err.status);
      } else {
        setError(err instanceof Error ? err.message : "Failed to load orders");
      }
    } finally {
      setLoading(false);
    }
  }, [filter]);

  const fetchProducts = useCallback(async () => {
    try {
      const data = await api.getProducts();
      setProducts(data);
      if (data.length > 0) {
        setLineItems((prev) =>
          prev.map((item) => (item.productId ? item : { ...item, productId: data[0].id }))
        );
      }
    } catch {
      // Ignore if catalog not populated yet
    }
  }, []);

  useEffect(() => {
    fetchOrders();
    fetchProducts();
  }, [fetchOrders, fetchProducts]);

  const handleConfirm = async (orderId: string) => {
    setActionLoading(true);
    try {
      const updated = await api.confirmOrder(orderId);
      onNotify("success", `Order ${orderId.substring(0, 8)} confirmed successfully!`);
      setSelectedOrder(updated);
      await fetchOrders();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "Failed to confirm order";
      onNotify("error", msg);
    } finally {
      setActionLoading(false);
    }
  };

  const handleCancel = async (orderId: string) => {
    setActionLoading(true);
    try {
      const updated = await api.cancelOrder(orderId);
      onNotify("info", `Order ${orderId.substring(0, 8)} cancelled`);
      setSelectedOrder(updated);
      await fetchOrders();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "Failed to cancel order";
      onNotify("error", msg);
    } finally {
      setActionLoading(false);
    }
  };

  const handleFulfil = async (orderId: string) => {
    setActionLoading(true);
    try {
      const updated = await api.fulfilOrder(orderId);
      onNotify("success", `Order ${orderId.substring(0, 8)} marked fulfilled!`);
      setSelectedOrder(updated);
      await fetchOrders();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "Failed to fulfil order";
      onNotify("error", msg);
    } finally {
      setActionLoading(false);
    }
  };

  const handleAddItem = () => {
    setLineItems((prev) => [
      ...prev,
      { productId: products[0]?.id || "", quantity: "1", unitPrice: "25.00" },
    ]);
  };

  const handleRemoveItem = (index: number) => {
    setLineItems((prev) => prev.filter((_, i) => i !== index));
  };

  const handleUpdateItem = (
    index: number,
    field: "productId" | "quantity" | "unitPrice",
    value: string
  ) => {
    setLineItems((prev) =>
      prev.map((item, i) => (i === index ? { ...item, [field]: value } : item))
    );
  };

  const openCreateOrderModal = () => {
    setLineItems([
      { productId: products[0]?.id || "", quantity: "1", unitPrice: "25.00" },
    ]);
    setCreateModalOpen(true);
  };

  const handleCreateOrder = async (e: React.FormEvent) => {
    e.preventDefault();
    if (lineItems.length === 0) {
      onNotify("error", "At least one item is required");
      return;
    }

    for (let i = 0; i < lineItems.length; i++) {
      const it = lineItems[i];
      if (!it.productId) {
        onNotify("error", `Please select a product for line item #${i + 1}`);
        return;
      }
      const qty = parseInt(it.quantity, 10);
      if (isNaN(qty) || qty < 1) {
        onNotify("error", `Quantity for item #${i + 1} must be an integer >= 1`);
        return;
      }
      const pr = parseFloat(it.unitPrice);
      if (isNaN(pr) || pr < 0) {
        onNotify("error", `Unit price for item #${i + 1} must be a valid number >= 0`);
        return;
      }
    }

    setActionLoading(true);
    const key = `dash-key-${Date.now()}-${Math.random().toString(36).substring(2, 7)}`;
    try {
      const newOrder = await api.createOrder(key, {
        currency: createCurrency,
        items: lineItems.map((it) => ({
          productId: it.productId,
          quantity: parseInt(it.quantity, 10),
          price: parseFloat(it.unitPrice),
          unitPrice: parseFloat(it.unitPrice),
        })),
      });
      onNotify("success", `Order created! (ID: ${newOrder.id.substring(0, 8)})`);
      setCreateModalOpen(false);
      await fetchOrders();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "Failed to create order";
      onNotify("error", msg);
    } finally {
      setActionLoading(false);
    }
  };

  const handleSimulatePayment = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!payTargetOrder) return;
    setActionLoading(true);
    try {
      const p = await api.simulatePayment(payTargetOrder.id, {
        status: payOutcome,
        amount: payTargetOrder.totalAmount,
        currency: payTargetOrder.currency,
      });
      onNotify("success", `Payment simulated with status ${p.status}`);
      setPayModalOpen(false);
      await fetchOrders();
      if (selectedOrder && selectedOrder.id === payTargetOrder.id) {
        const refreshed = await api.getOrder(selectedOrder.id);
        setSelectedOrder(refreshed);
      }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "Payment simulation failed";
      onNotify("error", msg);
    } finally {
      setActionLoading(false);
    }
  };

  return (
    <div className="view-container">
      <div className="view-header">
        <div>
          <h2>Order Management</h2>
          <p className="view-subtitle">
            Lifecycle monitoring, real-time transitions, and reservation coordination.
          </p>
        </div>
        <div className="view-actions">
          <button
            type="button"
            className="btn btn-secondary"
            onClick={fetchOrders}
            disabled={loading}
          >
            ↻ Refresh
          </button>
          <button
            type="button"
            className="btn btn-primary"
            onClick={openCreateOrderModal}
          >
            + Create Order
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
          onRetry={fetchOrders}
        />
      )}

      {loading ? (
        <div className="loading-card">
          <Spinner size="lg" label="Loading orders from backend..." />
        </div>
      ) : orders.length === 0 ? (
        <EmptyState
          title="No orders found"
          description={
            filter === "ALL"
              ? "No orders have been recorded in the system yet. Click 'Create Order' to start an order."
              : `There are currently no orders with status '${filter}'.`
          }
          actionLabel="+ Create First Order"
          onAction={openCreateOrderModal}
        />
      ) : (
        <div className="card table-card">
          <table className="data-table" aria-label="Orders table">
            <thead>
              <tr>
                <th>Order ID</th>
                <th>Status</th>
                <th>Currency</th>
                <th>Total Amount</th>
                <th>Items</th>
                <th>Created At</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {orders.map((ord) => (
                <tr key={ord.id}>
                  <td className="font-mono text-xs">
                    <span title={ord.id}>{ord.id.substring(0, 8)}...</span>
                  </td>
                  <td>
                    <Badge status={ord.status} />
                  </td>
                  <td>{ord.currency}</td>
                  <td className="font-medium">
                    {new Intl.NumberFormat(undefined, {
                      style: "currency",
                      currency: ord.currency,
                    }).format(ord.totalAmount)}
                  </td>
                  <td>{ord.items?.length ?? 0} item(s)</td>
                  <td className="text-secondary text-sm">
                    {new Date(ord.createdAt).toLocaleString()}
                  </td>
                  <td>
                    <div className="table-btn-group">
                      <button
                        type="button"
                        className="btn btn-sm btn-ghost"
                        onClick={() => setSelectedOrder(ord)}
                      >
                        Inspect
                      </button>
                      {ord.status === "PENDING_PAYMENT" && (
                        <button
                          type="button"
                          className="btn btn-sm btn-secondary"
                          onClick={() => {
                            setPayTargetOrder(ord);
                            setPayModalOpen(true);
                          }}
                        >
                          Simulate Pay
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

      {/* Order Details Modal */}
      {selectedOrder && (
        <Modal
          isOpen={true}
          onClose={() => setSelectedOrder(null)}
          title={`Order Details: ${selectedOrder.id}`}
          maxWidth="lg"
          footer={
            <div className="modal-actions-bar">
              {selectedOrder.status === "PENDING_PAYMENT" && (
                <>
                  <button
                    type="button"
                    className="btn btn-danger"
                    onClick={() => handleCancel(selectedOrder.id)}
                    disabled={actionLoading}
                  >
                    Cancel Order
                  </button>
                  <button
                    type="button"
                    className="btn btn-secondary"
                    onClick={() => {
                      setPayTargetOrder(selectedOrder);
                      setPayModalOpen(true);
                    }}
                    disabled={actionLoading}
                  >
                    Simulate Payment
                  </button>
                  <button
                    type="button"
                    className="btn btn-primary"
                    onClick={() => handleConfirm(selectedOrder.id)}
                    disabled={actionLoading}
                  >
                    Confirm Order
                  </button>
                </>
              )}
              {selectedOrder.status === "CONFIRMED" && (
                <button
                  type="button"
                  className="btn btn-success"
                  onClick={() => handleFulfil(selectedOrder.id)}
                  disabled={actionLoading}
                >
                  Fulfil Order
                </button>
              )}
              <button
                type="button"
                className="btn btn-secondary"
                onClick={() => setSelectedOrder(null)}
              >
                Close
              </button>
            </div>
          }
        >
          <div className="details-grid">
            <div className="detail-item">
              <span className="detail-label">Status</span>
              <span className="detail-value">
                <Badge status={selectedOrder.status} />
              </span>
            </div>
            <div className="detail-item">
              <span className="detail-label">Total Amount</span>
              <span className="detail-value font-medium text-lg">
                {selectedOrder.currency} {selectedOrder.totalAmount}
              </span>
            </div>
            <div className="detail-item">
              <span className="detail-label">Idempotency Key</span>
              <span className="detail-value font-mono text-xs text-break">
                {selectedOrder.idempotencyKey}
              </span>
            </div>
            <div className="detail-item">
              <span className="detail-label">Created At</span>
              <span className="detail-value">
                {new Date(selectedOrder.createdAt).toLocaleString()}
              </span>
            </div>
            {selectedOrder.confirmedAt && (
              <div className="detail-item">
                <span className="detail-label">Confirmed At</span>
                <span className="detail-value text-success">
                  {new Date(selectedOrder.confirmedAt).toLocaleString()}
                </span>
              </div>
            )}
            {selectedOrder.cancelledAt && (
              <div className="detail-item">
                <span className="detail-label">Cancelled At</span>
                <span className="detail-value text-danger">
                  {new Date(selectedOrder.cancelledAt).toLocaleString()}
                </span>
              </div>
            )}
            {selectedOrder.fulfilledAt && (
              <div className="detail-item">
                <span className="detail-label">Fulfilled At</span>
                <span className="detail-value text-success">
                  {new Date(selectedOrder.fulfilledAt).toLocaleString()}
                </span>
              </div>
            )}
          </div>

          <h4 className="mt-4 mb-2">Line Items</h4>
          <table className="data-table">
            <thead>
              <tr>
                <th>Product ID</th>
                <th>Quantity</th>
                <th>Unit Price</th>
                <th>Total Price</th>
              </tr>
            </thead>
            <tbody>
              {selectedOrder.items?.map((item) => (
                <tr key={item.id}>
                  <td className="font-mono text-xs">{item.productId}</td>
                  <td>{item.quantity}</td>
                  <td>{selectedOrder.currency} {item.unitPrice}</td>
                  <td className="font-medium">{selectedOrder.currency} {item.totalPrice}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Modal>
      )}

      {/* Create Order Modal */}
      <Modal
        isOpen={createModalOpen}
        onClose={() => setCreateModalOpen(false)}
        title="Create New Order"
        maxWidth="lg"
      >
        <form onSubmit={handleCreateOrder} className="form-container">
          <div className="form-row" style={{ alignItems: "flex-end" }}>
            <div className="form-group" style={{ flex: "0 0 160px" }}>
              <label htmlFor="create-currency">Currency</label>
              <select
                id="create-currency"
                value={createCurrency}
                onChange={(e) => setCreateCurrency(e.target.value)}
              >
                <option value="USD">USD ($)</option>
                <option value="EUR">EUR (€)</option>
                <option value="GBP">GBP (£)</option>
              </select>
            </div>
            <div className="form-group" style={{ flex: 1, display: "flex", justifyContent: "flex-end" }}>
              <button
                type="button"
                className="btn btn-secondary btn-sm"
                onClick={handleAddItem}
              >
                + Add Another Product
              </button>
            </div>
          </div>

          <div style={{ display: "flex", flexDirection: "column", gap: "12px", marginTop: "8px" }}>
            <label style={{ fontSize: "0.85rem", fontWeight: 600, color: "var(--color-text-secondary)" }}>
              Line Items ({lineItems.length})
            </label>
            {lineItems.map((item, idx) => (
              <div
                key={idx}
                style={{
                  display: "grid",
                  gridTemplateColumns: "1fr 110px 130px auto",
                  gap: "10px",
                  alignItems: "center",
                  padding: "10px 12px",
                  borderRadius: "8px",
                  background: "rgba(255, 255, 255, 0.02)",
                  border: "1px solid var(--color-border)",
                }}
              >
                <div>
                  <label style={{ fontSize: "0.75rem", color: "var(--color-text-secondary)" }}>
                    Product #{idx + 1}
                  </label>
                  {products.length > 0 ? (
                    <select
                      value={item.productId}
                      onChange={(e) => handleUpdateItem(idx, "productId", e.target.value)}
                      required
                    >
                      {products.map((p) => (
                        <option key={p.id} value={p.id}>
                          {p.name} ({p.sku})
                        </option>
                      ))}
                    </select>
                  ) : (
                    <input
                      type="text"
                      placeholder="Product UUID"
                      value={item.productId}
                      onChange={(e) => handleUpdateItem(idx, "productId", e.target.value)}
                      required
                    />
                  )}
                </div>

                <div>
                  <label style={{ fontSize: "0.75rem", color: "var(--color-text-secondary)" }}>
                    Quantity
                  </label>
                  <input
                    type="number"
                    min="1"
                    placeholder="1"
                    value={item.quantity}
                    onChange={(e) => handleUpdateItem(idx, "quantity", e.target.value)}
                    required
                  />
                </div>

                <div>
                  <label style={{ fontSize: "0.75rem", color: "var(--color-text-secondary)" }}>
                    Unit Price ({createCurrency})
                  </label>
                  <input
                    type="number"
                    step="0.01"
                    min="0"
                    placeholder="0.00"
                    value={item.unitPrice}
                    onChange={(e) => handleUpdateItem(idx, "unitPrice", e.target.value)}
                    required
                  />
                </div>

                <div style={{ paddingTop: "18px" }}>
                  {lineItems.length > 1 ? (
                    <button
                      type="button"
                      className="btn btn-ghost btn-sm"
                      style={{ color: "var(--color-danger, #ef4444)", padding: "6px 8px" }}
                      onClick={() => handleRemoveItem(idx)}
                      title="Remove item"
                    >
                      ✕
                    </button>
                  ) : (
                    <span style={{ display: "inline-block", width: "24px" }} />
                  )}
                </div>
              </div>
            ))}
          </div>

          <div
            style={{
              display: "flex",
              justifyContent: "space-between",
              alignItems: "center",
              padding: "12px 16px",
              background: "rgba(59, 130, 246, 0.08)",
              borderRadius: "8px",
              border: "1px solid rgba(59, 130, 246, 0.2)",
              marginTop: "8px",
            }}
          >
            <span style={{ fontSize: "0.9rem", color: "var(--color-text-secondary)" }}>
              Calculated Total Amount
            </span>
            <span style={{ fontSize: "1.15rem", fontWeight: 700, color: "var(--color-text-primary)" }}>
              {createCurrency}{" "}
              {lineItems
                .reduce((acc, it) => {
                  const q = parseInt(it.quantity, 10);
                  const p = parseFloat(it.unitPrice);
                  return acc + (isNaN(q) ? 0 : q) * (isNaN(p) ? 0 : p);
                }, 0)
                .toFixed(2)}
            </span>
          </div>

          <div className="modal-footer">
            <button
              type="button"
              className="btn btn-secondary"
              onClick={() => setCreateModalOpen(false)}
              disabled={actionLoading}
            >
              Cancel
            </button>
            <button
              type="submit"
              className="btn btn-primary"
              disabled={actionLoading}
            >
              {actionLoading ? "Creating..." : "Submit Order"}
            </button>
          </div>
        </form>
      </Modal>

      {/* Simulate Payment Modal */}
      {payTargetOrder && (
        <Modal
          isOpen={payModalOpen}
          onClose={() => setPayModalOpen(false)}
          title={`Simulate Payment for Order ${payTargetOrder.id.substring(0, 8)}`}
          maxWidth="sm"
        >
          <form onSubmit={handleSimulatePayment} className="form-container">
            <p className="text-secondary text-sm">
              Amount to authorize: <strong>{payTargetOrder.currency} {payTargetOrder.totalAmount}</strong>
            </p>
            <div className="form-group">
              <label htmlFor="pay-outcome-select">Simulated Gateway Outcome</label>
              <select
                id="pay-outcome-select"
                value={payOutcome}
                onChange={(e) =>
                  setPayOutcome(e.target.value as "SUCCEEDED" | "FAILED" | "TIMED_OUT")
                }
              >
                <option value="SUCCEEDED">SUCCEEDED (Payment Confirmed)</option>
                <option value="FAILED">FAILED (Payment Declined)</option>
                <option value="TIMED_OUT">TIMED_OUT (Indeterminate Outcome)</option>
              </select>
            </div>

            <div className="modal-footer">
              <button
                type="button"
                className="btn btn-secondary"
                onClick={() => setPayModalOpen(false)}
                disabled={actionLoading}
              >
                Cancel
              </button>
              <button
                type="submit"
                className="btn btn-primary"
                disabled={actionLoading}
              >
                {actionLoading ? "Processing..." : "Submit Payment"}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </div>
  );
};
