// Tipos e helpers compartilhados do painel super-admin /plataforma.
// Espelham os DTOs de /api/v1/admin/tenants (backend Fase 1) e
// /api/v1/admin/integrations|usage (backend Fase 2).

export type TenantPlan = 'BASIC' | 'PRO' | 'ENTERPRISE'

export interface TenantSummary {
  id: string
  slug: string
  displayName: string
  plan: TenantPlan
  isActive: boolean
  expiresAt: string | null
  /** pedidos no mês corrente — só vem quando o backend Fase 2 expõe */
  ordersThisMonth?: number
}

export interface TenantCreated {
  id: string
  slug: string
  displayName: string
  plan: TenantPlan
  inviteLink: string
  adminEmail: string
}

export interface ModuleStatus {
  key: string
  label: string
  enabled: boolean
  isOverride: boolean
}

export interface MigrationStatus {
  tenant: string
  currentVersion: string | null
  expectedVersion: string
  status: string
}

// ── Fase 2: integrações e uso ────────────────────────────────────────────────

export type IntegrationStatus = 'OK' | 'DEGRADED' | 'DOWN'

export interface IntegrationCard {
  name: string
  status: IntegrationStatus
  detail?: string
}

export interface IntegrationsHealthResponse {
  updatedAt: string // ISO
  cards: IntegrationCard[]
}

export interface TenantUsageResponse {
  ordersThisMonth: number
  dbSizeMb: number
  lastLoginAt?: string
  snapshotDate?: string
}

export const INTEGRATION_STATUS_LABELS: Record<IntegrationStatus, string> = {
  OK: 'Operacional',
  DEGRADED: 'Degradado',
  DOWN: 'Indisponível',
}

export const PLANS: TenantPlan[] = ['BASIC', 'PRO', 'ENTERPRISE']

export const PLAN_LABELS: Record<TenantPlan, string> = {
  BASIC: 'Basic',
  PRO: 'Pro',
  ENTERPRISE: 'Enterprise',
}

// Contraste conferido: primary-700 (#047857) sobre claro = 5.48:1 (AA ok);
// ENTERPRISE usa text-info-dark (#1e40af) porque text-info (#3b82f6) sobre
// bg-info-light (#dbeafe) dá ~2.8:1 e reprova AA para texto pequeno.
export const PLAN_BADGE: Record<TenantPlan, string> = {
  BASIC: 'bg-bg-tertiary text-text-secondary',
  PRO: 'bg-primary-700/10 text-primary-700',
  ENTERPRISE: 'bg-info-light text-info-dark',
}

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  return d.toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit', year: 'numeric' })
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  return d.toLocaleString('pt-BR', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

export const SLUG_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/

// ── Fase 3: uso de IA e usuários da plataforma ───────────────────────────────

export interface AiUsageEntry {
  tenantSlug: string
  model: string
  inputTokens: number
  outputTokens: number
  estimatedCostUsdMicros: number
  callCount: number
}

export interface AiUsageResponse {
  month: string
  entries: AiUsageEntry[]
  totalCostUsdMicros: number
  totalCalls: number
}

export interface PlatformUser {
  id: string
  name: string
  email: string
  createdAt: string
  lastLoginAt?: string
  has2FA: boolean
}

/** micros de USD → "$X.XXXX". O custo é sempre ESTIMADO (tabela de preços). */
export function formatUsdMicros(micros: number): string {
  return `$${(micros / 1_000_000).toFixed(4)}`
}

// ── Chaves de API da plataforma (super-admin) ────────────────────────────────
// Contrato WRITE-ONLY: o backend NUNCA devolve o valor da chave, só o estado
// mascarado. Ver PlatformApiKeyResponse/PlatformApiKeyTestResponse (backend #53).

export type ApiKeyStatus = 'DEFINED' | 'ABSENT'
export type ApiKeySource = 'DB' | 'ENV' | 'NONE'

export interface PlatformApiKeyResponse {
  provider: string
  status: ApiKeyStatus
  /** 4 primeiros + 4 últimos chars (ex.: "AIza…gUms"); null quando ABSENT */
  masked: string | null
  source: ApiKeySource
  /** vem da linha do banco; null quando a chave vigente vem só da ENV */
  keyVersion: number | null
  updatedAt: string | null
  /** UUID do super-admin que gravou; null quando a chave vem só da ENV */
  updatedBy: string | null
}

export interface PlatformApiKeyTestResponse {
  ok: boolean
  latencyMs: number
  source: ApiKeySource
  message: string
}
