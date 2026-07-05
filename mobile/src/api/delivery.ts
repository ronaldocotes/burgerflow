// Camada de API do dominio de entrega (app do motoboy — Fase 6.2).
// ISOLADA de proposito: os contratos marcados como PREVISTOS estao sendo criados
// pelo backend em paralelo; qualquer ajuste final de rota/shape se resolve
// SOMENTE neste arquivo (as telas consomem os tipos de @/types/delivery).
import { get, post, put } from '@/lib/api';
import type {
  DeliveryOffer,
  DeliveryOrder,
  DeliveryStatus,
  DriverProfile,
  EarningsSummary,
} from '@/types/delivery';

/** EXISTE — perfil do entregador logado (backend resolve o driver pelo token). */
export const getMe = () => get<DriverProfile>('/delivery/me');

/** EXISTE — liga/desliga turno. DRIVER so pode no proprio id (validado no servidor). */
export const setShift = (driverId: string, activeShift: boolean) =>
  post<DriverProfile>(`/delivery/drivers/${driverId}/shift`, { activeShift });

/** EXISTE — posicao GPS do motoboy (backend resolve o driver pelo token). */
export const sendLocation = (body: { lat: number; lng: number; batteryPct?: number }) =>
  post<DriverProfile>('/delivery/location', body);

/** EXISTE — ofertas pendentes do motoboy logado (fallback de polling do STOMP). */
export const getMyOffers = () => get<DeliveryOffer[]>('/delivery/offers/my');

/** EXISTE — aceite de oferta (so o dono; corrida validada no servidor). */
export const acceptOffer = (offerId: string) =>
  post<DeliveryOffer>(`/delivery/offers/${offerId}/accept`, {});

/** EXISTE — recusa de oferta. */
export const rejectOffer = (offerId: string) =>
  post<DeliveryOffer>(`/delivery/offers/${offerId}/reject`, {});

/** EXISTE — entregas ativas atribuidas ao motoboy logado. */
export const getMyOrders = () => get<DeliveryOrder[]>('/delivery/orders/my');

/**
 * Avanco de status pelo motoboy. HOJE existe PUT /delivery/orders/{id}/status
 * (RBAC DRIVER/OPERATOR/ADMIN). O backend esta criando um POST proprio do
 * driver; se o contrato final mudar, ajustar SOMENTE esta funcao.
 */
export const updateOrderStatus = (orderId: string, deliveryStatus: DeliveryStatus) =>
  put<DeliveryOrder>(`/delivery/orders/${orderId}/status`, { deliveryStatus });

/**
 * EXISTE — GET /delivery/earnings/my?from&to (yyyy-MM-dd; sempre o driver do token).
 * O backend consolida por intervalo ({deliveriesCount, deliveryEarningsCents});
 * o app faz duas chamadas (hoje e ultimos 7 dias) e adapta para EarningsSummary.
 */
interface EarningsRangeResponse {
  from: string;
  to: string;
  deliveriesCount: number;
  deliveryEarningsCents: number;
  perDeliveryCents: number | null;
  dailyRateCents: number | null;
  perKmCents: number | null;
  hasConfig: boolean;
}

const localDate = (d: Date) => {
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${m}-${day}`;
};

export const getMyEarnings = async (): Promise<EarningsSummary> => {
  const now = new Date();
  const today = localDate(now);
  const weekStart = localDate(new Date(now.getTime() - 6 * 24 * 60 * 60 * 1000));
  const [day, week] = await Promise.all([
    get<EarningsRangeResponse>(`/delivery/earnings/my?from=${today}&to=${today}`),
    get<EarningsRangeResponse>(`/delivery/earnings/my?from=${weekStart}&to=${today}`),
  ]);
  return {
    todayCents: day.deliveryEarningsCents,
    todayDeliveries: day.deliveriesCount,
    weekCents: week.deliveryEarningsCents,
    weekDeliveries: week.deliveriesCount,
  };
};
