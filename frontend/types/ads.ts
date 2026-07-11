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
