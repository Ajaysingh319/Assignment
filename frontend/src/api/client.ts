import type {
  EventFailure,
  EventFailureStatus,
  EventFailureSummary,
  InventoryListResponse,
  Order,
  OrderStatus,
  Payment,
  PaymentCallback,
  PaymentStatus,
  Product,
  ReconciliationRecord,
  ReconciliationRunResponse,
  ReconciliationStatus,
  Warehouse,
} from "../types";

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

export class ApiError extends Error {
  status: number;
  data: unknown;

  constructor(status: number, message: string, data?: unknown) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.data = data;
  }
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const url = `${API_BASE_URL}${path}`;
  const headers = new Headers(options.headers || {});

  if (!headers.has("Accept")) {
    headers.set("Accept", "application/json");
  }

  if (options.body && typeof options.body === "string" && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  let response: Response;
  try {
    response = await fetch(url, { ...options, headers });
  } catch (err: unknown) {
    const errorMsg = err instanceof Error ? err.message : "Network error";
    throw new ApiError(0, `Cannot connect to backend (${url}): ${errorMsg}`);
  }

  let responseData: unknown = null;
  const contentType = response.headers.get("content-type") || "";
  if (contentType.includes("application/json")) {
    try {
      responseData = await response.json();
    } catch {
      responseData = null;
    }
  } else {
    try {
      responseData = await response.text();
    } catch {
      responseData = null;
    }
  }

  if (!response.ok) {
    let errorDetail = `HTTP ${response.status} ${response.statusText}`;
    if (responseData && typeof responseData === "object") {
      const obj = responseData as Record<string, unknown>;
      if (typeof obj.message === "string") {
        errorDetail = obj.message;
      } else if (typeof obj.error === "string") {
        errorDetail = obj.error;
      }
    } else if (typeof responseData === "string" && responseData.trim()) {
      errorDetail = responseData;
    }
    throw new ApiError(response.status, errorDetail, responseData);
  }

  return responseData as T;
}

export const api = {
  // Orders
  getOrders: (status?: OrderStatus) =>
    request<Order[]>(`/api/orders${status ? `?status=${encodeURIComponent(status)}` : ""}`),

  getOrder: (id: string) =>
    request<Order>(`/api/orders/${encodeURIComponent(id)}`),

  createOrder: (idempotencyKey: string, payload: { currency: string; items: { productId: string; quantity: number; unitPrice?: number; price?: number }[] }) =>
    request<Order>("/api/orders", {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify({
        currency: payload.currency,
        items: payload.items.map((it) => ({
          productId: it.productId,
          quantity: it.quantity,
          unitPrice: it.unitPrice ?? it.price,
        })),
      }),
    }),

  confirmOrder: (id: string) =>
    request<Order>(`/api/orders/${encodeURIComponent(id)}/confirm`, { method: "POST" }),

  cancelOrder: (id: string) =>
    request<Order>(`/api/orders/${encodeURIComponent(id)}/cancel`, { method: "POST" }),

  fulfilOrder: (id: string) =>
    request<Order>(`/api/orders/${encodeURIComponent(id)}/fulfil`, { method: "POST" }),

  // Inventory & Catalog
  getInventory: () =>
    request<InventoryListResponse>("/api/inventory"),

  adjustInventory: (productId: string, warehouseId: string, availableDelta: number) =>
    request<InventoryListResponse>("/api/inventory/adjustments", {
      method: "POST",
      body: JSON.stringify({ productId, warehouseId, availableDelta }),
    }),

  getProducts: () =>
    request<Product[]>("/api/products"),

  getWarehouses: () =>
    request<Warehouse[]>("/api/warehouses"),

  // Payments
  getPayments: (orderId?: string, status?: PaymentStatus) => {
    const params = new URLSearchParams();
    if (orderId) params.set("orderId", orderId);
    if (status) params.set("status", status);
    const query = params.toString();
    return request<Payment[]>(`/api/payments${query ? `?${query}` : ""}`);
  },

  getPayment: (id: string) =>
    request<Payment>(`/api/payments/${encodeURIComponent(id)}`),

  simulatePayment: (orderId: string, payload: { providerReference?: string; outcome?: string; status?: PaymentStatus; amount?: number; currency?: string }) =>
    request<Payment>(`/api/orders/${encodeURIComponent(orderId)}/payments/simulate`, {
      method: "POST",
      body: JSON.stringify({
        outcome: payload.outcome ?? payload.status,
        amount: payload.amount,
        currency: payload.currency,
      }),
    }),

  processPaymentCallback: (payload: { callbackId: string; paymentId: string; providerReference?: string; status?: PaymentStatus; result?: string }) =>
    request<Payment>("/api/payments/callbacks", {
      method: "POST",
      body: JSON.stringify({
        callbackId: payload.callbackId,
        paymentId: payload.paymentId,
        providerReference: payload.providerReference,
        result: payload.result ?? payload.status,
      }),
    }),

  getPaymentCallbacks: (paymentId?: string) =>
    request<PaymentCallback[]>(`/api/payments/callbacks${paymentId ? `?paymentId=${encodeURIComponent(paymentId)}` : ""}`),

  // Reconciliation
  runReconciliation: () =>
    request<ReconciliationRunResponse>("/api/reconciliation/run", { method: "POST" }),

  getReconciliationRecords: (status?: ReconciliationStatus, mismatchType?: string) => {
    const params = new URLSearchParams();
    if (status) params.set("status", status);
    if (mismatchType) params.set("mismatchType", mismatchType);
    const query = params.toString();
    return request<ReconciliationRecord[]>(`/api/reconciliation/records${query ? `?${query}` : ""}`);
  },

  getReconciliationRecord: (id: string) =>
    request<ReconciliationRecord>(`/api/reconciliation/records/${encodeURIComponent(id)}`),

  resolveReconciliation: (id: string, action: "RESOLVE" | "DISMISS", notes?: string) =>
    request<ReconciliationRecord>(`/api/reconciliation/records/${encodeURIComponent(id)}/resolve`, {
      method: "POST",
      body: JSON.stringify({ action, notes }),
    }),

  // Events / DLQ
  getFailures: (status?: EventFailureStatus) =>
    request<EventFailure[]>(`/api/events/failures${status ? `?status=${encodeURIComponent(status)}` : ""}`),

  getDeadLetteredFailures: () =>
    request<EventFailure[]>("/api/events/failures/dead-lettered"),

  getFailureSummary: () =>
    request<EventFailureSummary>("/api/events/failures/summary"),

  getFailure: (id: string) =>
    request<EventFailure>(`/api/events/failures/${encodeURIComponent(id)}`),

  retryFailure: (id: string) =>
    request<EventFailure>(`/api/events/failures/${encodeURIComponent(id)}/retry`, { method: "POST" }),

  retryEligibleFailures: () =>
    request<EventFailure[]>("/api/events/failures/retry-eligible", { method: "POST" }),

  publishOutbox: () =>
    request<{ publishedCount: number }>("/api/events/outbox/publish", { method: "POST" }),
};
