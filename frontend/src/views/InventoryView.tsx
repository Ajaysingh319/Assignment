import React, { useState, useEffect, useCallback } from "react";
import { api, ApiError } from "../api/client";
import type { ProductInventory, Product, Warehouse } from "../types";
import { Spinner } from "../components/Spinner";
import { ErrorBanner } from "../components/ErrorBanner";
import { EmptyState } from "../components/EmptyState";
import { Modal } from "../components/Modal";

interface InventoryViewProps {
  onNotify: (type: "success" | "error" | "info", msg: string) => void;
}

export const InventoryView: React.FC<InventoryViewProps> = ({ onNotify }) => {
  const [inventoryItems, setInventoryItems] = useState<ProductInventory[]>([]);
  const [products, setProducts] = useState<Product[]>([]);
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [errorStatus, setErrorStatus] = useState<number | undefined>();

  // Adjustment Modal State
  const [adjustModalOpen, setAdjustModalOpen] = useState(false);
  const [selectedProductId, setSelectedProductId] = useState("");
  const [selectedWarehouseId, setSelectedWarehouseId] = useState("");
  const [adjustQuantity, setAdjustQuantity] = useState<string>("10");
  const [adjusting, setAdjusting] = useState(false);

  // Initialization Modal State
  const [initModalOpen, setInitModalOpen] = useState(false);
  const [initStock, setInitStock] = useState<string>("20");
  const [initializing, setInitializing] = useState(false);

  const fetchInventory = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [invData, prodData, whData] = await Promise.all([
        api.getInventory(),
        api.getProducts().catch(() => []),
        api.getWarehouses().catch(() => []),
      ]);
      setInventoryItems(invData.items || []);
      setProducts(prodData);
      setWarehouses(whData);

      if (prodData.length > 0 && !selectedProductId) {
        setSelectedProductId(prodData[0].id);
      }
      if (whData.length > 0 && !selectedWarehouseId) {
        setSelectedWarehouseId(whData[0].id);
      }
    } catch (err: unknown) {
      if (err instanceof ApiError) {
        setError(err.message);
        setErrorStatus(err.status);
      } else {
        setError(err instanceof Error ? err.message : "Failed to load inventory");
      }
    } finally {
      setLoading(false);
    }
  }, [selectedProductId, selectedWarehouseId]);

  useEffect(() => {
    fetchInventory();
  }, [fetchInventory]);

  const handleAdjustStock = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedProductId || !selectedWarehouseId) {
      onNotify("error", "Please select both product and warehouse");
      return;
    }
    const qty = parseInt(adjustQuantity, 10);
    if (isNaN(qty) || qty === 0) {
      onNotify("error", "Quantity adjustment must be a non-zero integer");
      return;
    }
    setAdjusting(true);
    try {
      const res = await api.adjustInventory(selectedProductId, selectedWarehouseId, qty);
      setInventoryItems(res.items || []);
      onNotify("success", `Inventory adjusted by ${qty} units`);
      setAdjustModalOpen(false);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "Failed to adjust stock";
      onNotify("error", msg);
    } finally {
      setAdjusting(false);
    }
  };

  const handleInitStock = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedProductId || !selectedWarehouseId) {
      onNotify("error", "Please select both product and warehouse");
      return;
    }
    const qty = parseInt(initStock, 10);
    if (isNaN(qty) || qty < 0) {
      onNotify("error", "Initial quantity must be a non-negative integer");
      return;
    }
    setInitializing(true);
    try {
      const res = await api.initializeInventory(selectedProductId, selectedWarehouseId, qty);
      setInventoryItems(res.items || []);
      onNotify("success", `Inventory record initialized with ${qty} units`);
      setInitModalOpen(false);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "Failed to initialize inventory";
      onNotify("error", msg);
    } finally {
      setInitializing(false);
    }
  };

  // Aggregate totals
  const totalAvailable = inventoryItems.reduce(
    (acc, p) => acc + p.warehouses.reduce((wAcc, w) => wAcc + w.availableQty, 0),
    0
  );
  const totalReserved = inventoryItems.reduce(
    (acc, p) => acc + p.warehouses.reduce((wAcc, w) => wAcc + w.reservedQty, 0),
    0
  );
  const totalFulfilled = inventoryItems.reduce(
    (acc, p) => acc + p.warehouses.reduce((wAcc, w) => wAcc + w.fulfilledQty, 0),
    0
  );

  return (
    <div className="view-container">
      <div className="view-header">
        <div>
          <h2>Inventory & Warehouse Allocation</h2>
          <p className="view-subtitle">
            Atomic reservation tracking, available stock, and audited adjustments.
          </p>
        </div>
        <div className="view-actions">
          <button
            type="button"
            className="btn btn-secondary"
            onClick={fetchInventory}
            disabled={loading}
          >
            ↻ Refresh
          </button>
          <button
            type="button"
            className="btn btn-secondary"
            onClick={() => {
              if (products.length > 0 && !selectedProductId) setSelectedProductId(products[0].id);
              if (warehouses.length > 0 && !selectedWarehouseId) setSelectedWarehouseId(warehouses[0].id);
              setInitStock("20");
              setInitModalOpen(true);
            }}
          >
            + Initialize Stock
          </button>
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => setAdjustModalOpen(true)}
          >
            ± Adjust Stock
          </button>
        </div>
      </div>

      {/* Metric Cards */}
      <div className="metrics-grid">
        <div className="metric-card">
          <span className="metric-label">Available Stock</span>
          <span className="metric-value text-success">{totalAvailable}</span>
          <span className="metric-hint">Ready for new reservations</span>
        </div>
        <div className="metric-card">
          <span className="metric-label">Reserved Units</span>
          <span className="metric-value text-info">{totalReserved}</span>
          <span className="metric-hint">Allocated to pending orders</span>
        </div>
        <div className="metric-card">
          <span className="metric-label">Fulfilled Units</span>
          <span className="metric-value text-neutral">{totalFulfilled}</span>
          <span className="metric-hint">Shipped to customers</span>
        </div>
      </div>

      {error && (
        <ErrorBanner
          message={error}
          statusCode={errorStatus}
          onRetry={fetchInventory}
        />
      )}

      {loading ? (
        <div className="loading-card">
          <Spinner size="lg" label="Loading inventory positions..." />
        </div>
      ) : inventoryItems.length === 0 ? (
        <EmptyState
          title="No inventory records"
          description="There are currently no products or stock allocations configured."
          actionLabel="± Add Stock"
          onAction={() => setAdjustModalOpen(true)}
        />
      ) : (
        <div className="inventory-list">
          {inventoryItems.map((prod) => (
            <div key={prod.productId} className="card product-inventory-card">
              <div className="product-card-header">
                <div>
                  <h3 className="product-title">{prod.productName}</h3>
                  <span className="product-sku">SKU: {prod.sku}</span>
                </div>
                <div style={{ display: "flex", gap: "0.5rem" }}>
                  <button
                    type="button"
                    className="btn btn-sm btn-secondary"
                    onClick={() => {
                      setSelectedProductId(prod.productId);
                      if (warehouses.length > 0 && !selectedWarehouseId) setSelectedWarehouseId(warehouses[0].id);
                      setInitStock("20");
                      setInitModalOpen(true);
                    }}
                  >
                    + Initialize Stock
                  </button>
                  <button
                    type="button"
                    className="btn btn-sm btn-ghost"
                    onClick={() => {
                      setSelectedProductId(prod.productId);
                      setAdjustModalOpen(true);
                    }}
                  >
                    Adjust Stock
                  </button>
                </div>
              </div>

              {prod.warehouses.length === 0 ? (
                <div style={{ padding: "1.25rem", textAlign: "center", background: "var(--color-bg-secondary)", borderRadius: "var(--radius-md)", marginTop: "0.5rem" }}>
                  <p className="text-secondary text-sm mb-2">No warehouse inventory initialized for this product yet.</p>
                  <button
                    type="button"
                    className="btn btn-sm btn-secondary"
                    onClick={() => {
                      setSelectedProductId(prod.productId);
                      if (warehouses.length > 0) setSelectedWarehouseId(warehouses[0].id);
                      setInitStock("20");
                      setInitModalOpen(true);
                    }}
                  >
                    + Initialize Warehouse Allocation
                  </button>
                </div>
              ) : (

              <table className="data-table mt-2">
                <thead>
                  <tr>
                    <th>Warehouse</th>
                    <th>Code</th>
                    <th>Available</th>
                    <th>Reserved</th>
                    <th>Fulfilled</th>
                    <th>Tracked Total</th>
                    <th>Stock Allocation</th>
                    <th>Last Updated</th>
                    <th>Action</th>
                  </tr>
                </thead>
                <tbody>
                  {prod.warehouses.map((wh) => {
                    const total = wh.trackedTotal > 0 ? wh.trackedTotal : 1;
                    const availPct = Math.round((wh.availableQty / total) * 100);
                    const resPct = Math.round((wh.reservedQty / total) * 100);
                    const fulPct = Math.round((wh.fulfilledQty / total) * 100);
                    const invariantValid =
                      wh.availableQty + wh.reservedQty + wh.fulfilledQty === wh.trackedTotal;

                    return (
                      <tr key={wh.warehouseId}>
                        <td className="font-medium">{wh.warehouseName}</td>
                        <td className="font-mono text-xs">{wh.warehouseCode}</td>
                        <td className="font-bold text-success">{wh.availableQty}</td>
                        <td className="font-bold text-info">{wh.reservedQty}</td>
                        <td className="font-bold text-neutral">{wh.fulfilledQty}</td>
                        <td>
                          <span
                            className={`badge ${invariantValid ? "badge-success" : "badge-danger"}`}
                            title={invariantValid ? "Invariant Valid" : "Invariant Broken!"}
                          >
                            {wh.trackedTotal}
                          </span>
                        </td>
                        <td style={{ minWidth: "160px" }}>
                          <div className="progress-stacked" title={`Available: ${availPct}%, Reserved: ${resPct}%, Fulfilled: ${fulPct}%`}>
                            <div className="bar bar-success" style={{ width: `${availPct}%` }} />
                            <div className="bar bar-info" style={{ width: `${resPct}%` }} />
                            <div className="bar bar-neutral" style={{ width: `${fulPct}%` }} />
                          </div>
                        </td>
                        <td className="text-secondary text-sm">
                          {new Date(wh.updatedAt).toLocaleTimeString()}
                        </td>
                        <td>
                          <button
                            type="button"
                            className="btn btn-sm btn-ghost"
                            onClick={() => {
                              setSelectedProductId(prod.productId);
                              setSelectedWarehouseId(wh.warehouseId);
                              setAdjustQuantity("10");
                              setAdjustModalOpen(true);
                            }}
                          >
                            Adjust
                          </button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
              )}
            </div>
          ))}
        </div>
      )}

      {/* Adjust Stock Modal */}
      <Modal
        isOpen={adjustModalOpen}
        onClose={() => setAdjustModalOpen(false)}
        title="Adjust Warehouse Stock"
        maxWidth="md"
      >
        <form onSubmit={handleAdjustStock} className="form-container">
          <div className="form-group">
            <label htmlFor="adjust-product">Product</label>
            {products.length > 0 ? (
              <select
                id="adjust-product"
                value={selectedProductId}
                onChange={(e) => setSelectedProductId(e.target.value)}
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
                id="adjust-product"
                type="text"
                placeholder="Product UUID"
                value={selectedProductId}
                onChange={(e) => setSelectedProductId(e.target.value)}
                required
              />
            )}
          </div>

          <div className="form-group">
            <label htmlFor="adjust-warehouse">Warehouse</label>
            {warehouses.length > 0 ? (
              <select
                id="adjust-warehouse"
                value={selectedWarehouseId}
                onChange={(e) => setSelectedWarehouseId(e.target.value)}
                required
              >
                {warehouses.map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.name} ({w.code})
                  </option>
                ))}
              </select>
            ) : (
              <input
                id="adjust-warehouse"
                type="text"
                placeholder="Warehouse UUID"
                value={selectedWarehouseId}
                onChange={(e) => setSelectedWarehouseId(e.target.value)}
                required
              />
            )}
          </div>

          <div className="form-group">
            <label htmlFor="adjust-quantity">Quantity Adjustment (± Units)</label>
            <input
              id="adjust-quantity"
              type="number"
              value={adjustQuantity}
              onChange={(e) => setAdjustQuantity(e.target.value)}
              placeholder="e.g. 10 or -5"
              required
            />
            <span className="text-secondary text-xs mt-1">
              Positive values add stock; negative values safely remove available stock.
            </span>
          </div>

          <div className="modal-footer">
            <button
              type="button"
              className="btn btn-secondary"
              onClick={() => setAdjustModalOpen(false)}
              disabled={adjusting}
            >
              Cancel
            </button>
            <button
              type="submit"
              className="btn btn-primary"
              disabled={adjusting}
            >
              {adjusting ? "Adjusting..." : "Apply Adjustment"}
            </button>
          </div>
        </form>
      </Modal>

      {/* Initialize Stock Modal */}
      <Modal
        isOpen={initModalOpen}
        onClose={() => setInitModalOpen(false)}
        title="Initialize Warehouse Stock"
        maxWidth="md"
      >
        <form onSubmit={handleInitStock} className="form-container">
          <div className="form-group">
            <label htmlFor="init-product">Product *</label>
            {products.length > 0 ? (
              <select
                id="init-product"
                value={selectedProductId}
                onChange={(e) => setSelectedProductId(e.target.value)}
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
                id="init-product"
                type="text"
                placeholder="Product UUID"
                value={selectedProductId}
                onChange={(e) => setSelectedProductId(e.target.value)}
                required
              />
            )}
          </div>

          <div className="form-group">
            <label htmlFor="init-warehouse">Warehouse Facility *</label>
            {warehouses.length > 0 ? (
              <select
                id="init-warehouse"
                value={selectedWarehouseId}
                onChange={(e) => setSelectedWarehouseId(e.target.value)}
                required
              >
                {warehouses.map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.name} ({w.code})
                  </option>
                ))}
              </select>
            ) : (
              <input
                id="init-warehouse"
                type="text"
                placeholder="Warehouse UUID"
                value={selectedWarehouseId}
                onChange={(e) => setSelectedWarehouseId(e.target.value)}
                required
              />
            )}
          </div>

          <div className="form-group">
            <label htmlFor="init-quantity">Initial Stock Quantity</label>
            <input
              id="init-quantity"
              type="number"
              min="0"
              value={initStock}
              onChange={(e) => setInitStock(e.target.value)}
              placeholder="e.g. 20"
              required
            />
            <span className="text-secondary text-xs mt-1">
              Initial available stock count for this product-warehouse combination.
            </span>
          </div>

          <div className="modal-footer">
            <button
              type="button"
              className="btn btn-secondary"
              onClick={() => setInitModalOpen(false)}
              disabled={initializing}
            >
              Cancel
            </button>
            <button
              type="submit"
              className="btn btn-primary"
              disabled={initializing}
            >
              {initializing ? "Initializing..." : "Initialize Allocation"}
            </button>
          </div>
        </form>
      </Modal>
    </div>
  );
};
