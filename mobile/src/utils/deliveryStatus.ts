// Regras de apresentacao e fluxo do status de entrega (app do motoboy).
// Labels em pt-BR; cores SEMPRE via tokens de @/theme/colors.
import { delivery as deliveryColors } from '@/theme/colors';
import type { DeliveryOrder, DeliveryStatus } from '@/types/delivery';

export const STATUS_LABEL: Record<DeliveryStatus, string> = {
  PENDING: 'Aguardando',
  OFFERED: 'Ofertado',
  ACCEPTED: 'Aceito',
  ASSIGNED: 'Atribuido',
  ARRIVED_AT_STORE: 'No restaurante',
  PICKED_UP: 'Coletado',
  OUT_FOR_DELIVERY: 'Entregando',
  ARRIVED_AT_CUSTOMER: 'No cliente',
  DELIVERED: 'Entregue',
  FAILED: 'Falhou',
};

export const STATUS_COLOR: Record<DeliveryStatus, string> = {
  PENDING: deliveryColors.pending,
  OFFERED: deliveryColors.offered,
  ACCEPTED: deliveryColors.accepted,
  ASSIGNED: deliveryColors.accepted,
  ARRIVED_AT_STORE: deliveryColors.arrivedAtStore,
  PICKED_UP: deliveryColors.pickedUp,
  OUT_FOR_DELIVERY: deliveryColors.outForDelivery,
  ARRIVED_AT_CUSTOMER: deliveryColors.arrivedAtCustomer,
  DELIVERED: deliveryColors.delivered,
  FAILED: deliveryColors.failed,
};

/** Status em que o motoboy AINDA esta com a entrega na mao (gera ping de GPS). */
const ACTIVE_STATUSES: readonly DeliveryStatus[] = [
  'ASSIGNED',
  'ACCEPTED',
  'ARRIVED_AT_STORE',
  'PICKED_UP',
  'OUT_FOR_DELIVERY',
  'ARRIVED_AT_CUSTOMER',
];

export function isActiveDelivery(order: DeliveryOrder): boolean {
  return order.deliveryStatus != null && ACTIVE_STATUSES.includes(order.deliveryStatus);
}

export interface NextStep {
  next: DeliveryStatus;
  label: string;
}

/**
 * Proximo passo do fluxo do motoboy: Coletei -> Entregando -> Entregue.
 * null = nao ha acao de avanco (estado terminal ou fora do fluxo do driver).
 */
export function nextStep(status: DeliveryStatus | null): NextStep | null {
  switch (status) {
    case 'ASSIGNED':
    case 'ACCEPTED':
    case 'ARRIVED_AT_STORE':
      return { next: 'PICKED_UP', label: 'COLETEI O PEDIDO' };
    case 'PICKED_UP':
      return { next: 'OUT_FOR_DELIVERY', label: 'SAI PARA ENTREGA' };
    case 'OUT_FOR_DELIVERY':
    case 'ARRIVED_AT_CUSTOMER':
      return { next: 'DELIVERED', label: 'ENTREGUEI' };
    default:
      return null;
  }
}

/** Endereco em uma linha para deep-link de mapas e exibicao compacta. */
export function fullAddress(o: DeliveryOrder): string {
  const parts: string[] = [];
  if (o.deliveryStreet) {
    parts.push(o.deliveryNumber ? `${o.deliveryStreet}, ${o.deliveryNumber}` : o.deliveryStreet);
  }
  if (o.deliveryNeighborhood) parts.push(o.deliveryNeighborhood);
  if (o.deliveryCity) parts.push(o.deliveryCity);
  return parts.join(' - ');
}
