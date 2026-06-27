// Tipos do KDS espelhando KdsOrderView / KdsDtos do backend.

export type OrderStatus =
  | "PENDING"
  | "PREPARING"
  | "READY"
  | "DELIVERED"
  | "CANCELLED";

export type OrderType = "DINE_IN" | "TAKEAWAY" | "DELIVERY";

export interface KdsItem {
  productName: string;
  quantity: number;
  notes?: string | null;
}

export interface KdsOrder {
  orderId: string;
  orderNumber: string;
  status: OrderStatus;
  orderType: OrderType;
  tableNumber: string | null;
  items: KdsItem[];
  estimatedPrepTimeMinutes: number | null;
  createdAt: string;
}

// Evento STOMP publicado em /topic/kds/{tenantSlug} após updateStatus.
export interface KdsEvent {
  orderId: string;
  orderNumber: string;
  status: OrderStatus;
  items: KdsItem[];
  changedAt: string;
}

export type FeedStatus = "connecting" | "live" | "reconnecting" | "polling";
