// Tipos da tela de Mesas espelhando TableDto / TableSessionDto do backend. Copiado do frontend.

export type SessionStatus = 'OPEN' | 'BILLING' | 'CLOSED';

export interface TableSession {
  sessionId: string;
  status: SessionStatus;
  openedAt: string;
  billRequestedAt?: string | null;
}

export interface TableDto {
  id: string;
  label: string;
  seats: number;
  sortOrder: number;
  active: boolean;
  session?: TableSession | null;
}

// -- Conta da sessao (subset de OrderResponse do backend) ----------------------
// O backend nao expoe endpoint de pedidos por sessao; a conta e montada no
// cliente via GET /orders?from=<openedAt> filtrando por tableNumber === label.

export type SessionOrderStatus =
  | 'PENDING'
  | 'PREPARING'
  | 'READY'
  | 'DELIVERED'
  | 'CANCELLED';

export interface SessionOrderItem {
  id: string;
  productName: string;
  quantity: number;
  totalPriceCents: number;
}

export interface SessionOrder {
  id: string;
  orderNumber: string;
  status: SessionOrderStatus;
  tableNumber: string | null;
  items: SessionOrderItem[];
  totalCents: number;
  createdAt: string;
}

// Shape minimo do Page<T> do Spring retornado por GET /orders.
export interface PageResponse<T> {
  content: T[];
}
