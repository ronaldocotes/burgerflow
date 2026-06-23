// Order Types

export enum OrderType {
  DINE_IN = 'DINE_IN',
  TAKEAWAY = 'TAKEAWAY',
  DELIVERY = 'DELIVERY',
}

export enum OrderStatus {
  PENDING = 'PENDING',
  IN_PREPARATION = 'IN_PREPARATION',
  READY_FOR_DELIVERY = 'READY_FOR_DELIVERY',
  IN_DELIVERY = 'IN_DELIVERY',
  COMPLETED = 'COMPLETED',
  CANCELLED = 'CANCELLED',
}

export enum PaymentMethod {
  CASH = 'CASH',
  CREDIT_CARD = 'CREDIT_CARD',
  DEBIT_CARD = 'DEBIT_CARD',
  PIX = 'PIX',
  MERCADO_PAGO = 'MERCADO_PAGO',
  OTHER = 'OTHER',
}

export enum PaymentStatus {
  PENDING = 'PENDING',
  PAID = 'PAID',
  FAILED = 'FAILED',
  REFUNDED = 'REFUNDED',
  PARTIALLY_REFUNDED = 'PARTIALLY_REFUNDED',
}

export enum OrderPriority {
  LOW = 'LOW',
  NORMAL = 'NORMAL',
  HIGH = 'HIGH',
  URGENT = 'URGENT',
}

// Order interfaces
export interface Order {
  id: string;
  orderNumber: string;
  tenantId: string;
  customerId?: string;
  customerName?: string;
  customerPhone?: string;
  userId?: string;
  userName?: string;
  orderType: OrderType;
  status: OrderStatus;
  tableNumber?: string;
  items: OrderItem[];
  subtotal: number;
  discount: number;
  deliveryFee: number;
  taxAmount: number;
  total: number;
  paymentMethod?: PaymentMethod;
  paymentStatus: PaymentStatus;
  paymentReference?: string;
  isTakeaway: boolean;
  priority: OrderPriority;
  estimatedPrepTimeMinutes: number;
  notes?: string;
  idempotencyKey?: string;
  createdAt: string;
  updatedAt: string;
  completedAt?: string;
  cancelledAt?: string;
  cancelledReason?: string;
}

export interface OrderItem {
  id: string;
  orderId: string;
  productId: string;
  productSku: string;
  productName: string;
  quantity: number;
  unitPrice: number;
  totalPrice: number;
  notes?: string;
  status: OrderItemStatus;
  preparationStartedAt?: string;
  preparationCompletedAt?: string;
  displayOrder: number;
  isCombo: boolean;
  parentItemId?: string;
  customizations?: ProductCustomization[];
}

export enum OrderItemStatus {
  PENDING = 'PENDING',
  IN_PREPARATION = 'IN_PREPARATION',
  READY = 'READY',
  COMPLETED = 'COMPLETED',
  CANCELLED = 'CANCELLED',
}

export interface ProductCustomization {
  ingredientId: string;
  ingredientName: string;
  action: CustomizationAction;
  quantity?: number;
  extraPrice?: number;
}

export enum CustomizationAction {
  ADD = 'ADD',
  REMOVE = 'REMOVE',
  REPLACE = 'REPLACE',
}

// Order Request types
export interface CreateOrderRequest {
  tenantId: string;
  customerId?: string;
  orderType: OrderType;
  tableNumber?: string;
  items: CreateOrderItemRequest[];
  notes?: string;
  paymentMethod?: PaymentMethod;
  isTakeaway: boolean;
  priority?: OrderPriority;
  idempotencyKey?: string;
}

export interface CreateOrderItemRequest {
  productId: string;
  quantity: number;
  notes?: string;
  customizations?: CreateProductCustomizationRequest[];
}

export interface CreateProductCustomizationRequest {
  ingredientId: string;
  action: CustomizationAction;
  quantity?: number;
}

// Order Response types
export interface OrderResponse extends Order {}

export interface OrderSummary {
  id: string;
  orderNumber: string;
  tenantId: string;
  customerName?: string;
  orderType: OrderType;
  status: OrderStatus;
  total: number;
  paymentStatus: PaymentStatus;
  createdAt: string;
  tableNumber?: string;
}

export interface OrderListResponse {
  content: OrderSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
}

// Order filters
export interface OrderFilters {
  tenantId: string;
  status?: OrderStatus[];
  orderType?: OrderType[];
  paymentStatus?: PaymentStatus[];
  startDate?: string;
  endDate?: string;
  customerId?: string;
  page?: number;
  size?: number;
  sortBy?: string;
  sortDirection?: 'ASC' | 'DESC';
}

// Order metrics
export interface OrderMetrics {
  totalOrders: number;
  totalRevenue: number;
  averageOrderValue: number;
  completedOrders: number;
  pendingOrders: number;
  cancelledOrders: number;
  ordersByStatus: Record<OrderStatus, number>;
  ordersByType: Record<OrderType, number>;
  revenueByDay: Record<string, number>;
}
