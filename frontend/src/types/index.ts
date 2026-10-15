export type OrderStatus = 'PENDING_PAYMENT' | 'CONFIRMED' | 'CANCELLED' | 'FULFILLED';

export interface OrderItem {
  id: string;
  productId: string;
  quantity: number;
  unitPrice: number;
  totalPrice: number;
}

export interface Order {
  id: string;
  status: OrderStatus;
  idempotencyKey: string;
  currency: string;
  totalAmount: number;
  items: OrderItem[];
  createdAt: string;
  updatedAt: string;
  confirmedAt?: string | null;
  cancelledAt?: string | null;
  fulfilledAt?: string | null;
}

export interface WarehouseInventory {
  warehouseId: string;
  warehouseCode: string;
  warehouseName: string;
  availableQty: number;
  reservedQty: number;
  fulfilledQty: number;
  trackedTotal: number;
  updatedAt: string;
}

export interface ProductInventory {
  productId: string;
  sku: string;
  productName: string;
  warehouses: WarehouseInventory[];
}

export interface InventoryListResponse {
  items: ProductInventory[];
}

export interface Product {
  id: string;
  sku: string;
  name: string;
  description?: string | null;
  active: boolean;
}

export interface Warehouse {
  id: string;
  code: string;
  name: string;
  address?: string | null;
  active: boolean;
}

export type PaymentStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'TIMED_OUT';

export interface Payment {
  id: string;
  orderId: string;
  providerReference: string;
  status: PaymentStatus;
  amount: number;
  currency: string;
  lastCallbackId?: string | null;
  createdAt: string;
  updatedAt: string;
  succeededAt?: string | null;
}

export interface PaymentCallback {
  id: string;
  providerCallbackId: string;
  paymentId: string;
  result: string;
  receivedPayload?: string | null;
  receivedAt: string;
  processedAt: string;
}

export type ReconciliationStatus = 'OPEN' | 'ACTION_REQUIRED' | 'RESOLVED' | 'DISMISSED';

export interface ReconciliationRecord {
  id: string;
  mismatchKey: string;
  orderId?: string | null;
  paymentId?: string | null;
  mismatchType: string;
  status: ReconciliationStatus;
  details: string;
  detectedAt: string;
  updatedAt: string;
  resolvedAt?: string | null;
}

export interface ReconciliationRunResponse {
  detectedCount: number;
  existingCount: number;
  records: ReconciliationRecord[];
}

export type EventFailureStatus = 'RETRYING' | 'DEAD_LETTERED' | 'REPLAYED' | 'RESOLVED';

export interface EventFailure {
  id: string;
  eventId: string;
  topic: string;
  partitionNumber?: number | null;
  offsetNumber?: number | null;
  consumerName: string;
  attemptCount: number;
  lastError?: string | null;
  status: EventFailureStatus;
  payload?: string | null;
  firstFailedAt: string;
  lastFailedAt: string;
  resolvedAt?: string | null;
  eligibleForRetry: boolean;
}

export interface EventFailureSummary {
  totalFailures: number;
  deadLetteredCount: number;
  retryingCount: number;
  resolvedCount: number;
  replayedCount: number;
}
