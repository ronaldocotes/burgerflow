// Tipos da area "Minha Loja" (/configuracoes/loja) espelhando os DTOs do backend
// (Fase CONFIG-A, issues #6-#11). Todos os campos monetarios do sistema sao em
// centavos, mas aqui so ha percentuais (feePct) e minutos — nada de dinheiro.

// ── GET/PATCH /config (subconjunto usado por Minha Loja) ─────────────────────────
// A resposta completa de /config e grande (marketing/IA/fidelidade/etc). Tipamos
// apenas os campos que esta area le e escreve.

export interface StoreConfig {
  // Endereco estruturado + pin do mapa (#7)
  postalCode: string | null;
  street: string | null;
  streetNumber: string | null;
  addressComplement: string | null;
  neighborhood: string | null;
  merchantCity: string | null;
  stateUf: string | null;
  restaurantLat: number | null;
  restaurantLng: number | null;
  // Horario de funcionamento (#6): "HH:mm-HH:mm" ou null = fechado
  openingHoursMonday: string | null;
  openingHoursTuesday: string | null;
  openingHoursWednesday: string | null;
  openingHoursThursday: string | null;
  openingHoursFriday: string | null;
  openingHoursSaturday: string | null;
  openingHoursSunday: string | null;
  // Tempo por modalidade em minutos (#9), NOT NULL no backend
  deliveryTimeMinMinutes: number;
  deliveryTimeMaxMinutes: number;
  pickupTimeMinMinutes: number;
  pickupTimeMaxMinutes: number;
  dineinTimeMinMinutes: number;
  dineinTimeMaxMinutes: number;
}

// PATCH /config exige autoAcceptOrders nao-nulo (Jackson/Kotlin). Ele nao pertence
// a esta area, entao os saves precisam reenvia-lo com o valor atual para nao mexer.
// Por isso lemos tambem autoAcceptOrders no GET.
export interface StoreConfigFull extends StoreConfig {
  autoAcceptOrders: boolean;
}

// ── Formas de pagamento (#8) — GET/PUT/DELETE /config/payment-methods ─────────────

export interface PaymentMethodConfig {
  id: string | null;
  method: string; // chave tecnica MAIUSCULA (PIX, CREDIT_CARD, ...)
  label: string;
  enabled: boolean;
  feePct: number;
  passFeeToCustomer: boolean;
  sortOrder: number;
}

// ── Motivos de cancelamento (#10) — GET/POST/PUT/DELETE /config/cancellation-reasons

export interface CancellationReason {
  id: string | null;
  description: string;
  active: boolean;
  sortOrder: number;
}

// ── Links e QR do cardapio (#11) — GET/POST/PUT/DELETE /config/menu-links ─────────

export type MenuLinkVariant = "FULL" | "VIEW_ONLY" | "COUNTER";

export interface MenuLink {
  id: string | null;
  slug: string;
  variant: MenuLinkVariant;
  label: string;
  tableId: string | null;
  active: boolean;
}

// Resolucao publica do link (GET /public/{tenant}/l/{slug}).
export interface PublicMenuLinkResponse {
  variant: MenuLinkVariant;
  orderingEnabled: boolean;
  tableId: string | null;
}
