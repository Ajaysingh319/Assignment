import React, { useState, useEffect, useCallback, useMemo } from "react";
import { api, ApiError } from "../api/client";
import type { Warehouse } from "../types";
import { Spinner } from "../components/Spinner";
import { ErrorBanner } from "../components/ErrorBanner";
import { EmptyState } from "../components/EmptyState";
import { Modal } from "../components/Modal";
import { Badge } from "../components/Badge";

interface WarehousesViewProps {
  onNotify: (type: "success" | "error" | "info", msg: string) => void;
}

const PAGE_SIZE = 8;

export const WarehousesView: React.FC<WarehousesViewProps> = ({ onNotify }) => {
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [errorStatus, setErrorStatus] = useState<number | undefined>();

  // Filter & Search & Pagination
  const [searchQuery, setSearchQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState<"ALL" | "ACTIVE" | "INACTIVE">("ALL");
  const [currentPage, setCurrentPage] = useState(1);

  // Create Modal State
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [createCode, setCreateCode] = useState("");
  const [createName, setCreateName] = useState("");
  const [createLocation, setCreateLocation] = useState("");
  const [createActive, setCreateActive] = useState(true);
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);

  // Edit Modal State
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [editingWarehouse, setEditingWarehouse] = useState<Warehouse | null>(null);
  const [editName, setEditName] = useState("");
  const [editLocation, setEditLocation] = useState("");
  const [editActive, setEditActive] = useState(true);
  const [updating, setUpdating] = useState(false);
  const [editError, setEditError] = useState<string | null>(null);

  // Action Loading
  const [actionId, setActionId] = useState<string | null>(null);

  const fetchWarehouses = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.getWarehouses();
      setWarehouses(data);
    } catch (err: unknown) {
      if (err instanceof ApiError) {
        setError(err.message);
        setErrorStatus(err.status);
      } else {
        setError(err instanceof Error ? err.message : "Failed to load warehouses");
      }
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchWarehouses();
  }, [fetchWarehouses]);

  // Filter & Search
  const filteredWarehouses = useMemo(() => {
    return warehouses.filter((w) => {
      const loc = w.location || w.address || "";
      const matchesSearch =
        w.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
        w.code.toLowerCase().includes(searchQuery.toLowerCase()) ||
        loc.toLowerCase().includes(searchQuery.toLowerCase());

      const matchesStatus =
        statusFilter === "ALL" ||
        (statusFilter === "ACTIVE" && w.active) ||
        (statusFilter === "INACTIVE" && !w.active);

      return matchesSearch && matchesStatus;
    });
  }, [warehouses, searchQuery, statusFilter]);

  // Pagination
  const totalPages = Math.max(1, Math.ceil(filteredWarehouses.length / PAGE_SIZE));
  const paginatedWarehouses = useMemo(() => {
    const start = (currentPage - 1) * PAGE_SIZE;
    return filteredWarehouses.slice(start, start + PAGE_SIZE);
  }, [filteredWarehouses, currentPage]);

  const handleCreateWarehouse = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!createCode.trim() || !createName.trim()) {
      setCreateError("Warehouse Code and Name are required");
      return;
    }
    setCreating(true);
    setCreateError(null);
    try {
      const newWh = await api.createWarehouse({
        code: createCode.trim().toUpperCase(),
        name: createName.trim(),
        location: createLocation.trim() || null,
        active: createActive,
      });
      onNotify("success", `Warehouse ${newWh.code} created successfully!`);
      setCreateModalOpen(false);
      setCreateCode("");
      setCreateName("");
      setCreateLocation("");
      setCreateActive(true);
      await fetchWarehouses();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "Failed to create warehouse";
      setCreateError(msg);
      onNotify("error", msg);
    } finally {
      setCreating(false);
    }
  };

  const openEditModal = (w: Warehouse) => {
    setEditingWarehouse(w);
    setEditName(w.name);
    setEditLocation(w.location || w.address || "");
    setEditActive(w.active);
    setEditError(null);
    setEditModalOpen(true);
  };

  const handleUpdateWarehouse = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingWarehouse) return;
    if (!editName.trim()) {
      setEditError("Name is required");
      return;
    }
    setUpdating(true);
    setEditError(null);
    try {
      const updated = await api.updateWarehouse(editingWarehouse.id, {
        name: editName.trim(),
        location: editLocation.trim() || null,
        active: editActive,
      });
      onNotify("success", `Warehouse ${updated.code} updated successfully!`);
      setEditModalOpen(false);
      setEditingWarehouse(null);
      await fetchWarehouses();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "Failed to update warehouse";
      setEditError(msg);
      onNotify("error", msg);
    } finally {
      setUpdating(false);
    }
  };

  const handleToggleActive = async (w: Warehouse) => {
    setActionId(w.id);
    try {
      if (w.active) {
        await api.deactivateWarehouse(w.id);
        onNotify("info", `Warehouse ${w.code} deactivated`);
      } else {
        await api.updateWarehouse(w.id, {
          name: w.name,
          location: w.location || w.address,
          active: true,
        });
        onNotify("success", `Warehouse ${w.code} activated`);
      }
      await fetchWarehouses();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "Failed to change warehouse status";
      onNotify("error", msg);
    } finally {
      setActionId(null);
    }
  };

  const handleDeleteWarehouse = async (w: Warehouse) => {
    if (!window.confirm(`Are you sure you want to delete warehouse ${w.code} (${w.name})?`)) {
      return;
    }
    setActionId(w.id);
    try {
      await api.deleteWarehouse(w.id);
      onNotify("success", `Warehouse ${w.code} deleted successfully`);
      await fetchWarehouses();
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : "Failed to delete warehouse";
      onNotify("error", msg);
    } finally {
      setActionId(null);
    }
  };

  return (
    <div className="view-container">
      <div className="view-header">
        <div>
          <h2>Warehouse Facilities</h2>
          <p className="view-subtitle">
            Manage distribution centers, physical warehouse codes, locations, and fulfillment status.
          </p>
        </div>
        <div className="view-actions">
          <button
            type="button"
            className="btn btn-secondary"
            onClick={fetchWarehouses}
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
            + Add Warehouse
          </button>
        </div>
      </div>

      {error && (
        <ErrorBanner
          message={error}
          statusCode={errorStatus}
          onRetry={fetchWarehouses}
        />
      )}

      {/* Filter and Search Bar */}
      <div className="card mb-4" style={{ padding: "1rem" }}>
        <div className="form-row" style={{ alignItems: "center", gap: "1rem" }}>
          <div className="form-group" style={{ flex: 2, marginBottom: 0 }}>
            <input
              type="text"
              placeholder="Search by code, name, or location..."
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
            Showing {filteredWarehouses.length} {filteredWarehouses.length === 1 ? "warehouse" : "warehouses"}
          </div>
        </div>
      </div>

      {loading ? (
        <div className="loading-card">
          <Spinner size="lg" label="Loading warehouses..." />
        </div>
      ) : filteredWarehouses.length === 0 ? (
        <EmptyState
          title="No warehouses found"
          description={searchQuery || statusFilter !== "ALL" ? "No warehouses match your search criteria." : "No warehouses have been registered yet."}
          actionLabel="+ Add Warehouse"
          onAction={() => setCreateModalOpen(true)}
        />
      ) : (
        <div className="card">
          <table className="data-table">
            <thead>
              <tr>
                <th>Code</th>
                <th>Warehouse Name</th>
                <th>Location / Address</th>
                <th>Status</th>
                <th>Created</th>
                <th style={{ textAlign: "right" }}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {paginatedWarehouses.map((w) => (
                <tr key={w.id}>
                  <td className="font-mono text-xs font-bold">{w.code}</td>
                  <td className="font-medium">{w.name}</td>
                  <td className="text-secondary text-sm">
                    {w.location || w.address || "—"}
                  </td>
                  <td>
                    <Badge
                      status={w.active ? "ACTIVE" : "INACTIVE"}
                      variant={w.active ? "success" : "neutral"}
                    />
                  </td>
                  <td className="text-secondary text-sm">
                    {w.createdAt ? new Date(w.createdAt).toLocaleDateString() : "—"}
                  </td>
                  <td style={{ textAlign: "right" }}>
                    <div style={{ display: "inline-flex", gap: "0.5rem" }}>
                      <button
                        type="button"
                        className="btn btn-sm btn-secondary"
                        onClick={() => openEditModal(w)}
                        disabled={actionId === w.id}
                      >
                        Edit
                      </button>
                      <button
                        type="button"
                        className={`btn btn-sm ${w.active ? "btn-secondary" : "btn-primary"}`}
                        onClick={() => handleToggleActive(w)}
                        disabled={actionId === w.id}
                      >
                        {w.active ? "Deactivate" : "Activate"}
                      </button>
                      <button
                        type="button"
                        className="btn btn-sm btn-ghost text-danger"
                        onClick={() => handleDeleteWarehouse(w)}
                        disabled={actionId === w.id}
                        title="Delete warehouse (if unreferenced)"
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

      {/* Create Warehouse Modal */}
      <Modal
        isOpen={createModalOpen}
        onClose={() => setCreateModalOpen(false)}
        title="Register New Warehouse"
        maxWidth="md"
      >
        <form onSubmit={handleCreateWarehouse} className="form-container">
          {createError && <div className="error-banner mb-3" style={{ padding: "0.5rem 0.75rem" }}>{createError}</div>}

          <div className="form-group">
            <label htmlFor="create-code">Warehouse Code (Unique) *</label>
            <input
              id="create-code"
              type="text"
              placeholder="e.g. WH-EAST or WH-US-NY"
              value={createCode}
              onChange={(e) => setCreateCode(e.target.value)}
              required
              maxLength={64}
            />
            <span className="text-secondary text-xs mt-1">Short alphanumeric identifier.</span>
          </div>

          <div className="form-group">
            <label htmlFor="create-wh-name">Warehouse Facility Name *</label>
            <input
              id="create-wh-name"
              type="text"
              placeholder="e.g. East Coast Distribution Hub"
              value={createName}
              onChange={(e) => setCreateName(e.target.value)}
              required
              maxLength={255}
            />
          </div>

          <div className="form-group">
            <label htmlFor="create-location">Location / Address (Optional)</label>
            <input
              id="create-location"
              type="text"
              placeholder="e.g. 100 Logistics Blvd, Newark, NJ"
              value={createLocation}
              onChange={(e) => setCreateLocation(e.target.value)}
              maxLength={255}
            />
          </div>

          <div className="form-group" style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
            <input
              id="create-wh-active"
              type="checkbox"
              checked={createActive}
              onChange={(e) => setCreateActive(e.target.checked)}
              style={{ width: "auto", margin: 0 }}
            />
            <label htmlFor="create-wh-active" style={{ margin: 0, cursor: "pointer" }}>
              Active (eligible for inventory allocations and fulfillment)
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
              {creating ? "Creating..." : "Save Warehouse"}
            </button>
          </div>
        </form>
      </Modal>

      {/* Edit Warehouse Modal */}
      <Modal
        isOpen={editModalOpen}
        onClose={() => setEditModalOpen(false)}
        title={`Edit Warehouse: ${editingWarehouse?.code || ""}`}
        maxWidth="md"
      >
        <form onSubmit={handleUpdateWarehouse} className="form-container">
          {editError && <div className="error-banner mb-3" style={{ padding: "0.5rem 0.75rem" }}>{editError}</div>}

          <div className="form-group">
            <label>Warehouse Code</label>
            <input
              type="text"
              value={editingWarehouse?.code || ""}
              disabled
              style={{ opacity: 0.7, cursor: "not-allowed" }}
            />
            <span className="text-secondary text-xs mt-1">Warehouse code cannot be modified once created.</span>
          </div>

          <div className="form-group">
            <label htmlFor="edit-wh-name">Warehouse Name *</label>
            <input
              id="edit-wh-name"
              type="text"
              value={editName}
              onChange={(e) => setEditName(e.target.value)}
              required
              maxLength={255}
            />
          </div>

          <div className="form-group">
            <label htmlFor="edit-location">Location / Address</label>
            <input
              id="edit-location"
              type="text"
              value={editLocation}
              onChange={(e) => setEditLocation(e.target.value)}
              maxLength={255}
            />
          </div>

          <div className="form-group" style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
            <input
              id="edit-wh-active"
              type="checkbox"
              checked={editActive}
              onChange={(e) => setEditActive(e.target.checked)}
              style={{ width: "auto", margin: 0 }}
            />
            <label htmlFor="edit-wh-active" style={{ margin: 0, cursor: "pointer" }}>
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
              {updating ? "Saving..." : "Update Warehouse"}
            </button>
          </div>
        </form>
      </Modal>
    </div>
  );
};
