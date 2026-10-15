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
  const [createItemProductId, setCreateItemProductId] = useState("");
  const [createItemQty, setCreateItemQty] = useState(1);
  const [createItemPrice, setCreateItemPrice] = useState(25.0);
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
      if (data.length > 0 && !createItemProductId) {
        setCreateItemProductId(data[0].id);
      }
    } catch {
      // Ignore if catalog not populated yet
    }
  }, [createItemProductId]);

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

  const handleCreateOrder = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!createItemProductId) {
      onNotify("error", "Please select a product");
      return;
    }
    setActionLoading(true);
    const key = `dash-key-${Date.now()}-${Math.random().toString(36).substring(2, 7)}`;
    try {
      const newOrder = await api.createOrder(key, {
        currency: createCurrency,
        items: [
          {
            productId: createItemProductId,
            quantity: createItemQty,
            price: createItemPrice,
          },
        ],
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
            onClick={() => setCreateModalOpen(true)}
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
          onAction={() => setCreateModalOpen(true)}
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
        maxWidth="md"
      >
        <form onSubmit={handleCreateOrder} className="form-container">
          <div className="form-group">
            <label htmlFor="create-product-select">Select Product</label>
            {products.length > 0 ? (
              <select
                id="create-product-select"
                value={createItemProductId}
                onChange={(e) => setCreateItemProductId(e.target.value)}
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
                id="create-product-select"
                type="text"
                placeholder="Product UUID"
                value={createItemProductId}
                onChange={(e) => setCreateItemProductId(e.target.value)}
                required
              />
            )}
          </div>

          <div className="form-row">
            <div className="form-group">
              <label htmlFor="create-quantity">Quantity</label>
              <input
                id="create-quantity"
                type="number"
                min="1"
                value={createItemQty}
                onChange={(e) => setCreateItemQty(parseInt(e.target.value) || 1)}
                required
              />
            </div>
            <div className="form-group">
              <label htmlFor="create-unit-price">Unit Price</label>
              <input
                id="create-unit-price"
                type="number"
                step="0.01"
                min="0.01"
                value={createItemPrice}
                onChange={(e) => setCreateItemPrice(parseFloat(e.target.value) || 0)}
                required
              />
            </div>
            <div className="form-group">
              <label htmlFor="create-currency">Currency</label>
              <select
                id="create-currency"
                value={createCurrency}
                onChange={(e) => setCreateCurrency(e.target.value)}
              >
                <option value="USD">USD</option>
                <option value="EUR">EUR</option>
                <option value="GBP">GBP</option>
              </select>
            </div>
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
