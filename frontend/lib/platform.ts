// Tipos e helpers compartilhados do painel super-admin /plataforma.
// Espelham os DTOs de /api/v1/admin/tenants (backend Fase 1).

export type TenantPlan = 'BASIC' | 'PRO' | 'ENTERPRISE'

export interface TenantSummary {
  id: string
  slug: string
  displayName: string
  plan: TenantPlan
  isActive: boolean
  expiresAt: string | null
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

export const SLUG_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/
