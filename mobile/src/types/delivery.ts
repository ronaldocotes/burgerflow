// Tipos do dominio de entrega (app do motoboy — Fase 6.2).
// Espelham backend/src/main/kotlin/com/menuflow/dto/DeliveryDtos.kt.
// Dinheiro SEMPRE em CENTAVOS (Long no backend, number aqui). Datas em ISO-8601.

export type DeliveryStatus =
  | 'PENDING'
  | 'OFFERED'
  | 'ACCEPTED'
  | 'ASSIGNED'
  | 'ARRIVED_AT_STORE'
  | 'PICKED_UP'
  | 'OUT_FOR_DELIVERY'
  | 'ARRIVED_AT_CUSTOMER'
  | 'DELIVERED'
  | 'FAILED';

export type DeliveryOfferStatus = 'OFFERED' | 'ACCEPTED' | 'REJECTED' | 'EXPIRED';

/** Espelha DriverResponse (tambem o shape PREVISTO de GET /delivery/me). */
export interface DriverProfile {
  id: string;
  name: string;
  phone: string;
  licensePlate: string | null;
  active: boolean;
  activeShift: boolean;
  lastLat: number | null;
  lastLng: number | null;
  lastLocationAt: string | null;
  batteryPct: number | null;
}

/** Espelha DeliveryOfferResponse (REST + payload STOMP em /topic/delivery/{slug}/offers). */
export interface DeliveryOffer {
  id: string;
  orderId: string;
  /** null = oferta de grupo (sem motoboy pre-escolhido). */
  driverId: string | null;
  status: DeliveryOfferStatus;
  feeCents: number;
  distanceKm: number | null;
  offeredAt: string;
  expiresAt: string;
}

/** Espelha DeliveryOrderResponse (REST + payload STOMP em /topic/delivery/{slug}). */
export interface DeliveryOrder {
  orderId: string;
  orderNumber: string;
  driverId: string | null;
  deliveryStatus: DeliveryStatus | null;
  totalCents: number;
  tableNumber: string | null;
  updatedAt: string;
  externalOrigin: string;
  externalDisplayId: string | null;
  deliveryRecipientName: string | null;
  deliveryPhone: string | null;
  deliveryNeighborhood: string | null;
  deliveryCity: string | null;
  deliveryStreet: string | null;
  deliveryNumber: string | null;
  deliveryComplement: string | null;
  deliveryReference: string | null;
  deliveryLat: number | null;
  deliveryLng: number | null;
  deliveryFeeCents: number;
  salesChannel: string;
  paymentStatus: string;
  createdAt: string;
  /**
   * Posicao (1-based) do pedido na rota otimizada do motoboy (issue #4). null quando
   * o pedido nao faz parte de uma rota confirmada. Ordena as paradas na tela de
   * entregas do app.
   */
  deliverySequence: number | null;
}

/** Espelha DriverLocationEvent (payload STOMP em /topic/delivery/{slug}). */
export interface DriverLocationEvent {
  driverId: string;
  lat: number;
  lng: number;
  batteryPct: number | null;
  at: string;
}

/**
 * CONTRATO PREVISTO de GET /delivery/earnings/my (backend em paralelo).
 * Todos os campos tratados como opcionais na renderizacao (?? 0) ate o
 * contrato final assentar.
 */
export interface EarningsSummary {
  todayCents: number;
  todayDeliveries: number;
  weekCents: number;
  weekDeliveries: number;
}
