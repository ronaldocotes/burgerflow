// Tipos do modulo Trafego Pago (Central de Meta Ads) — Fases 8.0/8.1.
// Contrato do backend: /ads/accounts e /ads/accounts/{id}/metrics.
// IMPORTANTE: o backend NUNCA retorna o token nas respostas.

export type AdAccountStatus = 'CONNECTED' | 'EXPIRED' | 'ERROR' | 'DISCONNECTED'

export interface AdAccountResponse {
  id: string
  provider: 'META'
  accountName: string | null
  /** Ultimos 4 digitos do ad account id — exibir como ••••{last4} */
  accountIdLast4: string
  /** Moeda da conta (ex.: 'BRL', 'USD') — usada para formatar spend/cpc */
  currency: string | null
  status: AdAccountStatus
  connectedAt: string
}

export interface AdMetricsResponse {
  /** YYYY-MM-DD */
  date: string
  /** Gasto em CENTAVOS na moeda da conta — dividir por 100 ao exibir */
  spendCents: number
  impressions: number
  reach: number
  clicks: number
  /** CTR% x 1000 — dividir por 1000 ao exibir (ex.: 1500 → 1,5%) */
  ctrMilli: number
  /** CPC em CENTAVOS na moeda da conta */
  cpcCents: number
  /** true = dia corrente, ainda consolidando na Meta */
  isPartial: boolean
}

export interface AdAccountCreateRequest {
  token: string
}

// ── Fase 8.2 — Campanhas de anuncio ──────────────────────────────────────────
// Contrato: POST /ads/campaigns (header Idempotency-Key), GET /ads/campaigns,
// POST /ads/campaigns/{id}/pause|activate, GET /ads/accounts/{id}/pages,
// PUT /ads/accounts/{id}/page.

/** Uma Pagina do Facebook que o token administra (GET /ads/accounts/{id}/pages). */
export interface AdPageResponse {
  id: string
  name: string | null
}

/** Corpo do PUT /ads/accounts/{id}/page. */
export interface SetAdPageRequest {
  pageId: string
  pageName?: string
}

export type AdCampaignStatus = 'DRAFT' | 'PAUSED' | 'ACTIVE' | 'ARCHIVED'

/**
 * Corpo do POST /ads/campaigns. Verba diaria em CENTAVOS na moeda da conta
 * (piso R$10 e teto do tenant validados no backend). Raio 1..80 km (limite Meta).
 */
export interface CreateAdCampaignRequest {
  accountId: string
  name: string
  dailyBudgetCents: number
  geoLat: number
  geoLng: number
  radiusKm: number
  destinationUrl: string
  primaryText: string
  headline?: string
  cta?: string
  /** Produto do catalogo cuja foto vira a imagem do anuncio (opcional). */
  productId?: string
}

export interface AdCampaignResponse {
  id: string
  adAccountId: string
  name: string
  objective: string
  status: AdCampaignStatus
  /** Status espelhado da Meta (pode ser null ate a primeira leitura/ativacao). */
  effectiveStatus: string | null
  /** Verba diaria em CENTAVOS na moeda da conta. */
  dailyBudgetCents: number
  geoLat: number | null
  geoLng: number | null
  radiusKm: number | null
  externalCampaignId: string | null
  createdAt: string
}
