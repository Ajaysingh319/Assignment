export type OrderStatus = 'PENDING_PAYMENT' | 'CONFIRMED' | 'CANCELLED' | 'FULFILLED';
export type ReservationStatus = 'PENDING' | 'RESERVED' | 'CONFIRMED' | 'RELEASED' | 'FULFILLED';

export interface ReservationItem {
  id: string;
  orderId: string;
  productId: string;
  warehouseId: string;
  quantity: number;
  status: ReservationStatus;
  createdAt: string;
  updatedAt: string;
  releasedAt?: string | null;
  fulfilledAt?: string | null;
}

export interface OrderItem {
  id: string;
  productId: string;
  quantity: number;
  unitPrice: number;
  lineTotal: number;
  totalPrice?: number;
}

export interface Order {
  id: string;
  status: OrderStatus;
  idempotencyKey: string;
  currency: string;
  totalAmount: number;
  items: OrderItem[];
  reservations?: ReservationItem[];
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
  createdAt?: string;
  updatedAt?: string;
}

export interface Warehouse {
  id: string;
  code: string;
  name: string;
  location?: string | null;
  address?: string | null;
  active: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export type PaymentStatus = 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'TIMED_OUT' | 'REFUNDED';

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
  resolutionAction?: string | null;
  resolutionNotes?: string | null;
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

export type OrderRequestOutcome =
  | 'ACCEPTED'
  | 'IDEMPOTENT_REPLAY'
  | 'REJECTED_INSUFFICIENT_STOCK'
  | 'REJECTED_IDEMPOTENCY_CONFLICT'
  | 'REJECTED_INVALID';

export interface OrderRequestRecord {
  id: string;
  idempotencyKey: string;
  outcome: OrderRequestOutcome;
  orderId?: string | null;
  reason?: string | null;
  itemCount: number;
  totalQuantity: number;
  requestSummary?: string | null;
  createdAt: string;
}

export type OrderRequestSummary = Record<OrderRequestOutcome | 'TOTAL', number>;

export interface OutboxEventRecord {
  id: string;
  aggregateType: string;
  aggregateId: string;
  eventType: string;
  topic: string;
  payload: string;
  createdAt: string;
  publishStatus: 'PENDING' | 'PUBLISH_FAILING' | 'PUBLISHED';
  publishedAt?: string | null;
  publishedVia?: 'KAFKA' | 'SIMULATED' | null;
  publishAttempts: number;
  lastPublishError?: string | null;
  consumerStatus: string;
  consumedAt?: string | null;
  consumerAttempts?: number | null;
  consumerLastError?: string | null;
  consumerFailureId?: string | null;
}

export interface EventPipelineSummary {
  totalOutboxEvents: number;
  pendingPublication: number;
  publishFailing: number;
  publishedViaKafka: number;
  publishedSimulated: number;
  consumerProcessed: number;
  consumerRetrying: number;
  deadLettered: number;
  recoveredOrResolved: number;
  faultInjectionRemaining: number;
  faultInjectionEventType?: string | null;
  kafkaAvailable: boolean;
}

export interface AuditEntry {
  id: string;
  entityType: string;
  entityId: string;
  action: string;
  actor: string;
  details?: string | null;
  createdAt: string;
}

export interface OrderDetails {
  id: string;
  status: OrderStatus;
  idempotencyKey: string;
  currency: string;
  totalAmount: number;
  createdAt: string;
  updatedAt: string;
  confirmedAt?: string | null;
  cancelledAt?: string | null;
  fulfilledAt?: string | null;
  reservationStatus: string;
  paymentStatus: string;
  lines: {
    productId: string;
    sku?: string | null;
    productName?: string | null;
    quantity: number;
    unitPrice: number;
    lineTotal: number;
  }[];
  reservations: {
    id: string;
    productId: string;
    sku?: string | null;
    warehouseId: string;
    warehouseCode?: string | null;
    warehouseName?: string | null;
    quantity: number;
    status: string;
    createdAt: string;
    updatedAt: string;
    releasedAt?: string | null;
    fulfilledAt?: string | null;
  }[];
  statusHistory: { at: string; status: string; action: string; actor: string; details?: string | null }[];
  payments: Payment[];
  callbacks: PaymentCallback[];
  events: OutboxEventRecord[];
  reconciliation: ReconciliationRecord[];
  requests: OrderRequestRecord[];
  auditTrail: AuditEntry[];
}
