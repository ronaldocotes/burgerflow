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

/** PREVISTO — perfil do entregador logado (backend resolve o driver pelo token). */
export const getMe = () => get<DriverProfile>('/delivery/me');

/** EXISTE — liga/desliga turno. DRIVER so pode no proprio id (validado no servidor). */
export const setShift = (driverId: string, activeShift: boolean) =>
  post<DriverProfile>(`/delivery/drivers/${driverId}/shift`, { activeShift });

/** EXISTE — posicao GPS do motoboy (backend resolve o driver pelo token). */
export const sendLocation = (body: { lat: number; lng: number; batteryPct?: number }) =>
  post<DriverProfile>('/delivery/location', body);

/** PREVISTO — ofertas pendentes do motoboy logado (fallback de polling do STOMP). */
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

/** PREVISTO — resumo de ganhos (dia/semana) do motoboy logado. Centavos. */
export const getMyEarnings = () => get<EarningsSummary>('/delivery/earnings/my');
