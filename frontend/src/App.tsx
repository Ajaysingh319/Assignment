import { useState } from "react";
import { API_BASE_URL } from "./api/client";
import { OrdersView } from "./views/OrdersView";
import { ProductsView } from "./views/ProductsView";
import { WarehousesView } from "./views/WarehousesView";
import { InventoryView } from "./views/InventoryView";
import { PaymentsView } from "./views/PaymentsView";
import { ReconciliationView } from "./views/ReconciliationView";
import { FailedEventsView } from "./views/FailedEventsView";
import { OrderRequestsView } from "./views/OrderRequestsView";
import { EventPipelineView } from "./views/EventPipelineView";
import { ToastContainer, type ToastMessage } from "./components/Toast";

const NAV_ITEMS = [
  { id: "orders", label: "Orders", icon: "📦" },
  { id: "requests", label: "Order Requests", icon: "📨" },
  { id: "products", label: "Products", icon: "🏷️" },
  { id: "warehouses", label: "Warehouses", icon: "🏭" },
  { id: "inventory", label: "Inventory", icon: "🏢" },
  { id: "payments", label: "Payments", icon: "💳" },
  { id: "reconciliation", label: "Reconciliation", icon: "⚖️" },
  { id: "pipeline", label: "Event Pipeline", icon: "🔁" },
  { id: "events", label: "Failed Events / DLT", icon: "⚠️" },
] as const;

type SectionId = (typeof NAV_ITEMS)[number]["id"];

export default function App() {
  const [section, setSection] = useState<SectionId>("orders");
  const [menuOpen, setMenuOpen] = useState(false);
  const [toasts, setToasts] = useState<ToastMessage[]>([]);

  const addToast = (type: "success" | "error" | "info", message: string) => {
    const id = `toast-${Date.now()}-${Math.random().toString(36).substring(2, 6)}`;
    setToasts((prev) => [...prev, { id, type, message }]);
  };

  const removeToast = (id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  };

  return (
    <div className="app-shell">
      {/* Toast Notification Container */}
      <ToastContainer toasts={toasts} onDismiss={removeToast} />

      {/* Top Application Bar */}
      <header className="top-bar">
        <div className="brand-group">
          <div className="brand-logo" aria-hidden="true">
            ⚡
          </div>
          <div>
            <p className="eyebrow">Enterprise Fulfilment & Payments</p>
            <h1>Operations Console</h1>
          </div>
        </div>

        <div className="top-bar-right">
          <div className="endpoint-badge" title="Active Backend API Base URL">
            <span className="live-dot" />
            <span className="endpoint-label">API:</span>
            <code className="endpoint-code">{API_BASE_URL}</code>
          </div>

          <button
            className="menu-toggle"
            type="button"
            aria-expanded={menuOpen}
            aria-label="Toggle navigation menu"
            onClick={() => setMenuOpen((open) => !open)}
          >
            ☰ Menu
          </button>
        </div>
      </header>

      <div className="layout">
        {/* Sidebar Navigation */}
        <nav className={`side-nav ${menuOpen ? "open" : ""}`} aria-label="Main Navigation">
          <div className="nav-header">Navigation</div>
          {NAV_ITEMS.map((item) => {
            const isActive = section === item.id;
            return (
              <button
                key={item.id}
                type="button"
                className={`nav-button ${isActive ? "active" : ""}`}
                onClick={() => {
                  setSection(item.id);
                  setMenuOpen(false);
                }}
              >
                <span className="nav-icon" aria-hidden="true">{item.icon}</span>
                <span className="nav-label">{item.label}</span>
                {isActive && <span className="nav-active-pill" />}
              </button>
            );
          })}

          <div className="nav-footer">
            <div className="platform-meta">
              <span>PostgreSQL • Kafka • Outbox</span>
              <span className="text-secondary text-xs">Simulated Gateway & Reconciliation</span>
            </div>
          </div>
        </nav>

        {/* Main Content Area */}
        <main className="content" id="main-content">
          {section === "orders" && <OrdersView onNotify={addToast} />}
          {section === "products" && <ProductsView onNotify={addToast} />}
          {section === "warehouses" && <WarehousesView onNotify={addToast} />}
          {section === "inventory" && <InventoryView onNotify={addToast} />}
          {section === "payments" && <PaymentsView onNotify={addToast} />}
          {section === "reconciliation" && <ReconciliationView onNotify={addToast} />}
          {section === "requests" && <OrderRequestsView onNotify={addToast} />}
          {section === "pipeline" && <EventPipelineView onNotify={addToast} />}
          {section === "events" && <FailedEventsView onNotify={addToast} />}
        </main>
      </div>
    </div>
  );
}
