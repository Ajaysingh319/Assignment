import { useState } from "react";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

const NAV_ITEMS = [
  { id: "orders", label: "Orders" },
  { id: "inventory", label: "Inventory" },
  { id: "payments", label: "Payments" },
  { id: "reconciliation", label: "Reconciliation" },
  { id: "events", label: "Failed events" },
] as const;

type SectionId = (typeof NAV_ITEMS)[number]["id"];

export default function App() {
  const [section, setSection] = useState<SectionId>("orders");
  const [menuOpen, setMenuOpen] = useState(false);

  return (
    <div className="app-shell">
      <header className="top-bar">
        <div>
          <p className="eyebrow">Operations</p>
          <h1>Order fulfilment</h1>
        </div>
        <button
          className="menu-toggle"
          type="button"
          aria-expanded={menuOpen}
          onClick={() => setMenuOpen((open) => !open)}
        >
          Menu
        </button>
      </header>

      <div className="layout">
        <nav className={`side-nav ${menuOpen ? "open" : ""}`} aria-label="Primary">
          {NAV_ITEMS.map((item) => (
            <button
              key={item.id}
              type="button"
              className={section === item.id ? "nav-button active" : "nav-button"}
              onClick={() => {
                setSection(item.id);
                setMenuOpen(false);
              }}
            >
              {item.label}
            </button>
          ))}
        </nav>

        <main className="content">
          <section className="panel">
            <h2>{NAV_ITEMS.find((item) => item.id === section)?.label}</h2>
            <p>
              Dashboard views will load live data from{" "}
              <code>{API_BASE_URL}</code>. Domain APIs are not implemented in this
              foundation slice.
            </p>
          </section>
        </main>
      </div>
    </div>
  );
}
