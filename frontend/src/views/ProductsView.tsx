import React, { useState, useEffect, useCallback, useMemo } from "react";
import { api, ApiError } from "../api/client";
import type { Product } from "../types";
import { Spinner } from "../components/Spinner";
import { ErrorBanner } from "../components/ErrorBanner";
import { EmptyState } from "../components/EmptyState";
import { Modal } from "../components/Modal";
import { Badge } from "../components/Badge";

interface ProductsViewProps {
  onNotify: (type: "success" | "error" | "info", msg: string) => void;
}

const PAGE_SIZE = 8;

export const ProductsView: React.FC<ProductsViewProps> = ({ onNotify }) => {
  const [products, setProducts] = useState<Product[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [errorStatus, setErrorStatus] = useState<number | undefined>();

  // Filter & Search & Pagination
  const [searchQuery, setSearchQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState<"ALL" | "ACTIVE" | "INACTIVE">("ALL");
  const [currentPage, setCurrentPage] = useState(1);

  // Create Modal State
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [createSku, setCreateSku] = useState("");
  const [createName, setCreateName] = useState("");
  const [createDescription, setCreateDescription] = useState("");
  const [createActive, setCreateActive] = useState(true);
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  // Edit Modal State
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [editingProduct, setEditingProduct] = useState<Product | null>(null);
  const [editName, setEditName] = useState("");
  const [editDescription, setEditDescription] = useState("");
  const [editActive, setEditActive] = useState(true);
  const [updating, setUpdating] = useState(false);
  const [editError, setEditError] = useState<string | null>(null);

  // Action Loading
  const [actionId, setActionId] = useState<string | null>(null);

  const fetchProducts = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.getProducts();
      setProducts(data);
    } catch (err: unknown) {
      if (err instanceof ApiError) {
        setError(err.message);
        setErrorStatus(err.status);
      } else {
        setError(err instanceof Error ? err.message : "Failed to load products");
      }
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchProducts();
  }, [fetchProducts]);

  // Filter & Search
  const filteredProducts = useMemo(() => {
    return products.filter((p) => {
      const matchesSearch =
        p.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
        p.sku.toLowerCase().includes(searchQuery.toLowerCase()) ||
        (p.description && p.description.toLowerCase().includes(searchQuery.toLowerCase()));

      const matchesStatus =
        statusFilter === "ALL" ||
        (statusFilter === "ACTIVE" && p.active) ||
        (statusFilter === "INACTIVE" && !p.active);

      return matchesSearch && matchesStatus;
    });
  }, [products, searchQuery, statusFilter]);

  // Pagination
  const totalPages = Math.max(1, Math.ceil(filteredProducts.length / PAGE_SIZE));
  const paginatedProducts = useMemo(() => {
    const start = (currentPage - 1) * PAGE_SIZE;
    return filteredProducts.slice(start, start + PAGE_SIZE);
  }, [filteredProducts, currentPage]);

  const handleCreateProduct = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!createSku.trim() || !createName.trim()) {
      setCreateError("SKU and Name are required");
      return;
    }
    setCreating(true);
    setCreateError(null);
    try {
      const newProd = await api.createProduct({
        sku: createSku.trim().toUpperCase(),
        name: createName.trim(),
        description: createDescription.trim() || null,
        active: createActive,
      });
      onNotify("success", `Product ${newProd.sku} created successfully!`);
      setCreateModalOpen(false);
      setCreateSku("");
      setCreateName("");
      setCreateDescription("");
      setCreateActive(true);
      await fetchProducts();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "Failed to create product";
      setCreateError(msg);
      onNotify("error", msg);
    } finally {
      setCreating(false);
    }
  };

  const openEditModal = (p: Product) => {
    setEditingProduct(p);
    setEditName(p.name);
    setEditDescription(p.description || "");
    setEditActive(p.active);
    setEditError(null);
    setEditModalOpen(true);
  };

  const handleUpdateProduct = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingProduct) return;
    if (!editName.trim()) {
      setEditError("Name is required");
      return;
    }
    setUpdating(true);
    setEditError(null);
    try {
      const updated = await api.updateProduct(editingProduct.id, {
        name: editName.trim(),
        description: editDescription.trim() || null,
        active: editActive,
      });
      onNotify("success", `Product ${updated.sku} updated successfully!`);
      setEditModalOpen(false);
      setEditingProduct(null);
      await fetchProducts();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "Failed to update product";
      setEditError(msg);
      onNotify("error", msg);
    } finally {
      setUpdating(false);
    }
  };

  const handleToggleActive = async (p: Product) => {
    setActionId(p.id);
    try {
      if (p.active) {
        await api.deactivateProduct(p.id);
        onNotify("info", `Product ${p.sku} deactivated`);
      } else {
        await api.updateProduct(p.id, {
          name: p.name,
          description: p.description,
          active: true,
        });
        onNotify("success", `Product ${p.sku} activated`);
      }
      await fetchProducts();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "Failed to change product status";
      onNotify("error", msg);
    } finally {
      setActionId(null);
    }
  };

  const handleDeleteProduct = async (p: Product) => {
    if (!window.confirm(`Are you sure you want to delete product ${p.sku} (${p.name})?`)) {
      return;
    }
    setActionId(p.id);
    try {
      await api.deleteProduct(p.id);
      onNotify("success", `Product ${p.sku} deleted successfully`);
      await fetchProducts();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "Failed to delete product";
      onNotify("error", msg);
    } finally {
      setActionId(null);
    }
  };

  return (
    <div className="view-container">
      <div className="view-header">
        <div>
          <h2>Product Catalog</h2>
          <p className="view-subtitle">
            Manage product master data, SKUs, active lifecycle status, and inventory links.
          </p>
        </div>
        <div className="view-actions">
          <button
            type="button"
            className="btn btn-secondary"
            onClick={fetchProducts}
            disabled={loading}
          >
            ↻ Refresh
          </button>
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => {
              setCreateError(null);
              setCreateModalOpen(true);
            }}
          >
            + Add Product
          </button>
        </div>
      </div>

      {error && (
        <ErrorBanner
          message={error}
          statusCode={errorStatus}
          onRetry={fetchProducts}
        />
      )}

      {/* Filter and Search Bar */}
      <div className="card mb-4" style={{ padding: "1rem" }}>
        <div className="form-row" style={{ alignItems: "center", gap: "1rem" }}>
          <div className="form-group" style={{ flex: 2, marginBottom: 0 }}>
            <input
              type="text"
              placeholder="Search by SKU, name, or description..."
              value={searchQuery}
              onChange={(e) => {
                setSearchQuery(e.target.value);
                setCurrentPage(1);
              }}
            />
          </div>
          <div className="form-group" style={{ flex: 1, marginBottom: 0 }}>
            <select
              value={statusFilter}
              onChange={(e) => {
                setStatusFilter(e.target.value as "ALL" | "ACTIVE" | "INACTIVE");
                setCurrentPage(1);
              }}
            >
              <option value="ALL">All Statuses</option>
              <option value="ACTIVE">Active Only</option>
              <option value="INACTIVE">Inactive Only</option>
            </select>
          </div>
          <div style={{ color: "var(--color-text-secondary)", fontSize: "0.875rem", whiteSpace: "nowrap" }}>
            Showing {filteredProducts.length} {filteredProducts.length === 1 ? "product" : "products"}
          </div>
        </div>
      </div>

      {loading ? (
        <div className="loading-card">
          <Spinner size="lg" label="Loading product catalog..." />
        </div>
      ) : filteredProducts.length === 0 ? (
        <EmptyState
          title="No products found"
          description={searchQuery || statusFilter !== "ALL" ? "No products match your search criteria." : "No products exist in the catalog yet."}
          actionLabel="+ Add Product"
          onAction={() => setCreateModalOpen(true)}
        />
      ) : (
        <div className="card">
          <table className="data-table">
            <thead>
              <tr>
                <th>SKU</th>
                <th>Product Name</th>
                <th>Description</th>
                <th>Status</th>
                <th>Created</th>
                <th style={{ textAlign: "right" }}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {paginatedProducts.map((p) => (
                <tr key={p.id}>
                  <td className="font-mono text-xs font-bold">{p.sku}</td>
                  <td className="font-medium">{p.name}</td>
                  <td className="text-secondary text-sm" style={{ maxWidth: "250px" }}>
                    {p.description || "—"}
                  </td>
                  <td>
                    <Badge
                      status={p.active ? "ACTIVE" : "INACTIVE"}
                      variant={p.active ? "success" : "neutral"}
                    />
                  </td>
                  <td className="text-secondary text-sm">
                    {p.createdAt ? new Date(p.createdAt).toLocaleDateString() : "—"}
                  </td>
                  <td style={{ textAlign: "right" }}>
                    <div style={{ display: "inline-flex", gap: "0.5rem" }}>
                      <button
                        type="button"
                        className="btn btn-sm btn-secondary"
                        onClick={() => openEditModal(p)}
                        disabled={actionId === p.id}
                      >
                        Edit
                      </button>
                      <button
                        type="button"
                        className={`btn btn-sm ${p.active ? "btn-secondary" : "btn-primary"}`}
                        onClick={() => handleToggleActive(p)}
                        disabled={actionId === p.id}
                      >
                        {p.active ? "Deactivate" : "Activate"}
                      </button>
                      <button
                        type="button"
                        className="btn btn-sm btn-ghost text-danger"
                        onClick={() => handleDeleteProduct(p)}
                        disabled={actionId === p.id}
                        title="Delete product (if unreferenced)"
                      >
                        Delete
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          {/* Pagination Controls */}
          {totalPages > 1 && (
            <div className="modal-footer" style={{ justifyContent: "space-between", marginTop: "1rem", padding: "0.75rem 1rem" }}>
              <span className="text-secondary text-sm">
                Page {currentPage} of {totalPages}
              </span>
              <div style={{ display: "flex", gap: "0.5rem" }}>
                <button
                  type="button"
                  className="btn btn-sm btn-secondary"
                  disabled={currentPage <= 1}
                  onClick={() => setCurrentPage((p) => Math.max(1, p - 1))}
                >
                  ← Previous
                </button>
                <button
                  type="button"
                  className="btn btn-sm btn-secondary"
                  disabled={currentPage >= totalPages}
                  onClick={() => setCurrentPage((p) => Math.min(totalPages, p + 1))}
                >
                  Next →
                </button>
              </div>
            </div>
          )}
        </div>
      )}

      {/* Create Product Modal */}
      <Modal
        isOpen={createModalOpen}
        onClose={() => setCreateModalOpen(false)}
        title="Add New Product"
        maxWidth="md"
      >
        <form onSubmit={handleCreateProduct} className="form-container">
          {createError && <div className="error-banner mb-3" style={{ padding: "0.5rem 0.75rem" }}>{createError}</div>}

          <div className="form-group">
            <label htmlFor="create-sku">SKU (Unique Stock Keeping Unit) *</label>
            <input
              id="create-sku"
              type="text"
              placeholder="e.g. WIDGET-RED or PROD-100"
              value={createSku}
              onChange={(e) => setCreateSku(e.target.value)}
              required
              maxLength={64}
            />
            <span className="text-secondary text-xs mt-1">Must be unique across the catalog.</span>
          </div>

          <div className="form-group">
            <label htmlFor="create-name">Product Name *</label>
            <input
              id="create-name"
              type="text"
              placeholder="e.g. Red Widget Component"
              value={createName}
              onChange={(e) => setCreateName(e.target.value)}
              required
              maxLength={255}
            />
          </div>

          <div className="form-group">
            <label htmlFor="create-desc">Description (Optional)</label>
            <textarea
              id="create-desc"
              rows={3}
              placeholder="Detailed specifications or notes..."
              value={createDescription}
              onChange={(e) => setCreateDescription(e.target.value)}
              maxLength={4000}
            />
          </div>

          <div className="form-group" style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
            <input
              id="create-active"
              type="checkbox"
              checked={createActive}
              onChange={(e) => setCreateActive(e.target.checked)}
              style={{ width: "auto", margin: 0 }}
            />
            <label htmlFor="create-active" style={{ margin: 0, cursor: "pointer" }}>
              Active (eligible for orders and stock allocation)
            </label>
          </div>

          <div className="modal-footer">
            <button
              type="button"
              className="btn btn-secondary"
              onClick={() => setCreateModalOpen(false)}
              disabled={creating}
            >
              Cancel
            </button>
            <button type="submit" className="btn btn-primary" disabled={creating}>
              {creating ? "Creating..." : "Save Product"}
            </button>
          </div>
        </form>
      </Modal>

      {/* Edit Product Modal */}
      <Modal
        isOpen={editModalOpen}
        onClose={() => setEditModalOpen(false)}
        title={`Edit Product: ${editingProduct?.sku || ""}`}
        maxWidth="md"
      >
        <form onSubmit={handleUpdateProduct} className="form-container">
          {editError && <div className="error-banner mb-3" style={{ padding: "0.5rem 0.75rem" }}>{editError}</div>}

          <div className="form-group">
            <label>SKU</label>
            <input
              type="text"
              value={editingProduct?.sku || ""}
              disabled
              style={{ opacity: 0.7, cursor: "not-allowed" }}
            />
            <span className="text-secondary text-xs mt-1">SKU identifier cannot be modified once created.</span>
          </div>

          <div className="form-group">
            <label htmlFor="edit-name">Product Name *</label>
            <input
              id="edit-name"
              type="text"
              value={editName}
              onChange={(e) => setEditName(e.target.value)}
              required
              maxLength={255}
            />
          </div>

          <div className="form-group">
            <label htmlFor="edit-desc">Description</label>
            <textarea
              id="edit-desc"
              rows={3}
              value={editDescription}
              onChange={(e) => setEditDescription(e.target.value)}
              maxLength={4000}
            />
          </div>

          <div className="form-group" style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
            <input
              id="edit-active"
              type="checkbox"
              checked={editActive}
              onChange={(e) => setEditActive(e.target.checked)}
              style={{ width: "auto", margin: 0 }}
            />
            <label htmlFor="edit-active" style={{ margin: 0, cursor: "pointer" }}>
              Active status
            </label>
          </div>

          <div className="modal-footer">
            <button
              type="button"
              className="btn btn-secondary"
              onClick={() => setEditModalOpen(false)}
              disabled={updating}
            >
              Cancel
            </button>
            <button type="submit" className="btn btn-primary" disabled={updating}>
              {updating ? "Saving..." : "Update Product"}
            </button>
          </div>
        </form>
      </Modal>
    </div>
  );
};
