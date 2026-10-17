import React, { useState, useEffect, useCallback, useMemo } from "react";
import { api, ApiError } from "../api/client";
import type { Order, OrderStatus, Payment, Product } from "../types";
import { Badge } from "../components/Badge";
import { Spinner } from "../components/Spinner";
import { ErrorBanner } from "../components/ErrorBanner";
import { EmptyState } from "../components/EmptyState";
import { Modal } from "../components/Modal";
import { OrderDetailPanel } from "../components/OrderDetailPanel";

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

/** SUCCEEDED wins; otherwise the latest attempt. Mirrors the backend order-details aggregation. */
function paymentStatusFor(payments: Payment[] | undefined): string {
  if (!payments || payments.length === 0) return "NONE";
  const succeeded = payments.filter((p) => p.status === "SUCCEEDED").length;
  if (succeeded > 1) return "SUCCEEDED_MULTIPLE";
  if (succeeded === 1) return "SUCCEEDED";
  const latest = [...payments].sort((a, b) => a.createdAt.localeCompare(b.createdAt))[payments.length - 1];
  return latest.status;
}

function reservationStatusFor(order: Order): string {
  const statuses = new Set((order.reservations ?? []).map((r) => r.status));
  if (statuses.size === 0) return "NONE";
  return statuses.size === 1 ? [...statuses][0] : "MIXED";
}

export const OrdersView: React.FC<OrdersViewProps> = ({ onNotify }) => {
  const [orders, setOrders] = useState<Order[]>([]);
  const [paymentsByOrder, setPaymentsByOrder] = useState<Record<string, Payment[]>>({});
  const [products, setProducts] = useState<Product[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [errorStatus, setErrorStatus] = useState<number | undefined>();
  const [filter, setFilter] = useState<OrderStatus | "ALL">("ALL");
  const [search, setSearch] = useState("");
  const [lastLoadedAt, setLastLoadedAt] = useState<Date | null>(null);

  const [selectedOrderId, setSelectedOrderId] = useState<string | null>(null);
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

  const fetchOrders = useCallback(async (quiet = false) => {
    if (!quiet) setLoading(true);
    try {
      const [orderData, paymentData] = await Promise.all([api.getOrders(), api.getPayments()]);
      const grouped: Record<string, Payment[]> = {};
      for (const p of paymentData) {
        (grouped[p.orderId] ??= []).push(p);
      }
      setOrders(orderData);
      setPaymentsByOrder(grouped);
      setError(null);
      setLastLoadedAt(new Date());
    } catch (err: unknown) {
      if (err instanceof ApiError) {
        setError(err.message);
        setErrorStatus(err.status);
      } else {
        setError(err instanceof Error ? err.message : "Failed to load orders");
      }
    } finally {
      if (!quiet) setLoading(false);
    }
  }, []);

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
      // Catalog may be empty; the create form falls back to a raw product id input
    }
  }, []);

  useEffect(() => {
    fetchOrders();
    fetchProducts();
  }, [fetchOrders, fetchProducts]);

  // Keep the list fresh so expiry, payments and concurrent demo runs show up without a manual refresh
  useEffect(() => {
    const t = window.setInterval(() => fetchOrders(true), 10000);
    return () => window.clearInterval(t);
  }, [fetchOrders]);

  const counts = useMemo(() => {
    const c: Record<string, number> = { ALL: orders.length };
    for (const o of orders) c[o.status] = (c[o.status] ?? 0) + 1;
    return c;
  }, [orders]);

  const productNames = useMemo(() => Object.fromEntries(products.map((p) => [p.id, p.sku])), [products]);

  const visibleOrders = useMemo(() => {
    const term = search.trim().toLowerCase();
    return orders.filter((o) => {
      if (filter !== "ALL" && o.status !== filter) return false;
      if (!term) return true;
      return (
        o.id.toLowerCase().includes(term) ||
        o.idempotencyKey.toLowerCase().includes(term) ||
        o.items.some((i) => (productNames[i.productId] ?? "").toLowerCase().includes(term))
      );
    });
  }, [orders, filter, search, productNames]);

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
          unitPrice: parseFloat(it.unitPrice),
        })),
      });
      onNotify("success", `Order created! (ID: ${newOrder.id.substring(0, 8)})`);
      setCreateModalOpen(false);
      await fetchOrders();
      setSelectedOrderId(newOrder.id);
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
        outcome: payOutcome,
        amount: payTargetOrder.totalAmount,
        currency: payTargetOrder.currency,
      });
      onNotify("success", `Payment simulated with status ${p.status}`);
      setPayModalOpen(false);
      await fetchOrders();
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
            Persisted orders from PostgreSQL, grouped by status. Open an order to see its products,
            reservations, lifecycle, payments, Kafka events and reconciliation history.
          </p>
        </div>
        <div className="view-actions">
          <button
            type="button"
            className="btn btn-secondary"
            onClick={() => fetchOrders()}
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

      {/* Status groups (counts from the persisted orders) */}
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
            <span className="tab-counter">{counts[s.value] ?? 0}</span>
          </button>
        ))}
      </div>

      <div style={{ display: "flex", gap: 12, alignItems: "center", margin: "8px 0 12px" }}>
        <input
          type="search"
          placeholder="Search by order id, idempotency key or SKU…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          style={{ maxWidth: 420 }}
          aria-label="Search orders"
        />
        <span className="text-muted text-xs">
          Showing {visibleOrders.length} of {orders.length} orders
          {lastLoadedAt ? ` · loaded ${lastLoadedAt.toLocaleTimeString()}` : ""} · rejected purchase attempts are listed under "Order Requests"
        </span>
      </div>

      {error && (
        <ErrorBanner
          message={error}
          statusCode={errorStatus}
          onRetry={() => fetchOrders()}
        />
      )}

      {loading ? (
        <div className="loading-card">
          <Spinner size="lg" label="Loading orders from backend..." />
        </div>
      ) : visibleOrders.length === 0 ? (
        <EmptyState
          title="No orders found"
          description={
            orders.length === 0
              ? "No orders have been recorded in the system yet. Click 'Create Order' to start an order."
              : "No orders match the current status filter or search."
          }
          actionLabel="+ Create Order"
          onAction={openCreateOrderModal}
        />
      ) : (
        <div className="card table-card">
          <table className="data-table" aria-label="Orders table">
            <thead>
              <tr>
                <th>Order ID</th>
                <th>Created</th>
                <th>Products / Units</th>
                <th>Total</th>
                <th>Status</th>
                <th>Reservation</th>
                <th>Payment</th>
                <th>Last updated</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {visibleOrders.map((ord) => {
                const units = ord.items.reduce((acc, i) => acc + Number(i.quantity), 0);
                const distinct = new Set(ord.items.map((i) => i.productId)).size;
                return (
                  <tr key={ord.id}>
                    <td className="font-mono text-xs">
                      <span title={ord.id}>{ord.id.substring(0, 8)}…</span>
                    </td>
                    <td className="text-secondary text-xs">{new Date(ord.createdAt).toLocaleString()}</td>
                    <td title={ord.items.map((i) => `${productNames[i.productId] ?? i.productId} × ${i.quantity}`).join("\n")}>
                      {distinct} / {units}
                    </td>
                    <td className="font-medium">
                      {new Intl.NumberFormat(undefined, { style: "currency", currency: ord.currency }).format(ord.totalAmount)}
                    </td>
                    <td><Badge status={ord.status} /></td>
                    <td><Badge status={reservationStatusFor(ord)} /></td>
                    <td><Badge status={paymentStatusFor(paymentsByOrder[ord.id])} /></td>
                    <td className="text-secondary text-xs">{new Date(ord.updatedAt).toLocaleString()}</td>
                    <td>
                      <div className="table-btn-group">
                        <button type="button" className="btn btn-sm btn-ghost" onClick={() => setSelectedOrderId(ord.id)}>
                          Open
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
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {/* Order Details */}
      {selectedOrderId && (
        <Modal
          isOpen={true}
          onClose={() => setSelectedOrderId(null)}
          title={`Order ${selectedOrderId}`}
          maxWidth="xl"
        >
          <OrderDetailPanel orderId={selectedOrderId} onNotify={onNotify} onChanged={() => fetchOrders(true)} />
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
