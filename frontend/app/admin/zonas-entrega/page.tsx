'use client'

import { useCallback, useEffect, useId, useState } from 'react'
import dynamic from 'next/dynamic'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { AlertTriangle, MapPin, Plus, Trash2, XCircle } from 'lucide-react'
import { api, ApiError } from '@/lib/api'
import { getToken } from '@/lib/auth'
import type { ZoneRing } from '@/components/delivery/DeliveryZonesMap'

// Configuração das zonas de entrega por raio (issue #2) — ADMIN/MANAGER.
// O admin desenha anéis concêntricos centrados no restaurante: cada anel tem raio (km),
// preço do frete, ETA (min–máx) e opção de frete grátis; além do limiar global
// "frete grátis acima de R$ X". O PUT /delivery/zones substitui o conjunto INTEIRO
// (idempotente) — validamos no cliente as mesmas regras do backend (raios estritamente
// crescentes, fee 0..R$1.000, etaMin<=etaMax<=600, raio 0<r<=100) antes de enviar.

const DeliveryZonesMap = dynamic(() => import('@/components/delivery/DeliveryZonesMap'), {
  ssr: false,
  loading: () => (
    <div className="h-80 w-full animate-pulse rounded-xl bg-bg-tertiary lg:h-[28rem]" aria-hidden="true" />
  ),
})

// ── Tipos (contrato real do backend — DeliveryZoneDtos.kt) ────────────────────

type DeliveryZoneView = {
  id: string | null
  name: string | null
  maxRadiusKm: number
  feeCents: number
  etaMinMinutes: number
  etaMaxMinutes: number
  isFree: boolean
  displayOrder: number
  active: boolean
}

type DeliveryZonesResponse = {
  zones: DeliveryZoneView[]
  freeDeliveryMinOrderCents: number | null
}

type DeliveryZoneUpsert = {
  name?: string
  maxRadiusKm: number
  feeCents: number
  etaMinMinutes: number
  etaMaxMinutes: number
  isFree?: boolean
}

// ── Limites (espelham DeliveryZoneLimits do backend) ──────────────────────────

const MAX_FEE_CENTS = 100_000 // R$ 1.000,00 por zona
const MAX_ETA_MINUTES = 600
const MAX_RADIUS_KM = 100
const MAX_FREE_MIN_ORDER_CENTS = 100_000_000 // R$ 1.000.000,00

// Cores dos anéis no mapa (hex — o Leaflet não lê classes Tailwind; mesmo padrão
// do GeoRadiusMap). Paleta com contraste entre anéis vizinhos.
const RING_COLORS = ['#047857', '#2563eb', '#d97706', '#7c3aed', '#db2777', '#0891b2', '#65a30d', '#b91c1c']

// ── Helpers monetários (dinheiro sempre em centavos inteiros, nunca float) ────

/** Converte centavos → "R$ 1.234,56" */
function centsToDisplay(cents: number): string {
  return (cents / 100).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })
}

/** Aplica máscara moeda BR ao digitar: só os dígitos viram centavos ("1234" → "12,34"). */
function maskCurrency(raw: string): string {
  const digits = raw.replace(/\D/g, '')
  if (digits === '') return ''
  const num = parseInt(digits, 10) / 100
  return num.toLocaleString('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

/** Converte string mascarada "1.234,56" → centavos inteiros. Vazio → null. */
function maskedToCents(masked: string): number | null {
  const digits = masked.replace(/\D/g, '')
  if (digits === '') return null
  return parseInt(digits, 10)
}

/** Raio digitado ("1,5" ou "1.5") → número. Null quando não parseável. */
function parseRadius(raw: string): number | null {
  const v = raw.trim().replace(',', '.')
  if (v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/** Inteiro de minutos digitado → número. Null quando não parseável. */
function parseMinutes(raw: string): number | null {
  const v = raw.trim()
  if (!/^\d+$/.test(v)) return null
  return parseInt(v, 10)
}

function formatRadius(km: number): string {
  return km.toLocaleString('pt-BR', { maximumFractionDigits: 2 })
}

// ── Rascunho editável de um anel ──────────────────────────────────────────────

type ZoneDraft = {
  key: string
  name: string
  radiusText: string
  feeMasked: string
  etaMinText: string
  etaMaxText: string
  isFree: boolean
}

type ZoneErrors = {
  radius?: string
  fee?: string
  eta?: string
}

let draftSeq = 0
function nextKey(): string {
  draftSeq += 1
  return `zone-${draftSeq}`
}

function draftFromView(z: DeliveryZoneView): ZoneDraft {
  return {
    key: nextKey(),
    name: z.name ?? '',
    radiusText: formatRadius(z.maxRadiusKm),
    feeMasked: maskCurrency(String(z.feeCents)),
    etaMinText: String(z.etaMinMinutes),
    etaMaxText: String(z.etaMaxMinutes),
    isFree: z.isFree,
  }
}

/** Valida um anel com as regras do backend; prevRadius = raio do anel anterior. */
function validateZone(draft: ZoneDraft, prevRadius: number | null): ZoneErrors {
  const errors: ZoneErrors = {}

  const radius = parseRadius(draft.radiusText)
  if (radius == null || radius <= 0 || radius > MAX_RADIUS_KM) {
    errors.radius = `Informe um raio maior que 0 e até ${MAX_RADIUS_KM} km.`
  } else if (prevRadius != null && radius <= prevRadius) {
    errors.radius = `O raio deve ser maior que o da zona anterior (${formatRadius(prevRadius)} km) — os anéis não podem se sobrepor.`
  }

  if (!draft.isFree) {
    const fee = maskedToCents(draft.feeMasked)
    if (fee == null || fee > MAX_FEE_CENTS) {
      errors.fee = 'Informe um frete entre R$ 0,00 e R$ 1.000,00.'
    }
  }

  const etaMin = parseMinutes(draft.etaMinText)
  const etaMax = parseMinutes(draft.etaMaxText)
  if (etaMin == null || etaMax == null || etaMin > MAX_ETA_MINUTES || etaMax > MAX_ETA_MINUTES) {
    errors.eta = `Informe os tempos em minutos (0 a ${MAX_ETA_MINUTES}).`
  } else if (etaMin > etaMax) {
    errors.eta = 'O tempo mínimo não pode ser maior que o máximo.'
  }

  return errors
}

/** Erros de todos os anéis, na ordem da lista (raio anterior = último raio válido). */
function validateAll(drafts: ZoneDraft[]): ZoneErrors[] {
  let prev: number | null = null
  return drafts.map((d) => {
    const errors = validateZone(d, prev)
    const r = parseRadius(d.radiusText)
    if (r != null && r > 0) prev = r
    return errors
  })
}

type Notice = { type: 'success' | 'error'; message: string } | null

// ── Editor de um anel ─────────────────────────────────────────────────────────

function ZoneEditor({
  draft,
  index,
  color,
  errors,
  showErrors,
  disabled,
  onChange,
  onRemove,
}: {
  draft: ZoneDraft
  index: number
  color: string
  errors: ZoneErrors
  showErrors: boolean
  disabled: boolean
  onChange: (patch: Partial<ZoneDraft>) => void
  onRemove: () => void
}) {
  const ids = {
    name: useId(),
    radius: useId(),
    fee: useId(),
    etaMin: useId(),
    etaMax: useId(),
    free: useId(),
    radiusErr: useId(),
    feeErr: useId(),
    etaErr: useId(),
  }
  const zoneTitle = draft.name.trim() || `Zona ${index + 1}`

  return (
    <fieldset
      className="rounded-xl border border-border-light bg-bg-primary p-4"
      disabled={disabled}
    >
      <legend className="sr-only">{`Anel ${index + 1}: ${zoneTitle}`}</legend>

      <div className="flex items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-2">
          {/* Cor do anel correspondente no mapa (hex inline — cor do Leaflet) */}
          <span
            className="h-3.5 w-3.5 shrink-0 rounded-full border border-black/10"
            style={{ backgroundColor: color }}
            aria-hidden="true"
          />
          <h3 className="truncate text-sm font-semibold text-text-primary">{zoneTitle}</h3>
        </div>
        <button
          type="button"
          className="flex h-11 w-11 shrink-0 items-center justify-center rounded-lg text-text-muted transition-colors hover:bg-error/10 hover:text-error"
          aria-label={`Remover ${zoneTitle}`}
          onClick={onRemove}
        >
          <Trash2 className="h-4 w-4" aria-hidden="true" />
        </button>
      </div>

      <div className="mt-3 grid grid-cols-2 gap-3">
        <div>
          <label htmlFor={ids.name} className="form-label">Nome (opcional)</label>
          <input
            id={ids.name}
            type="text"
            maxLength={100}
            className="input-field w-full"
            value={draft.name}
            onChange={(e) => onChange({ name: e.target.value })}
            placeholder={`Zona ${index + 1}`}
          />
        </div>
        <div>
          <label htmlFor={ids.radius} className="form-label">Raio até (km)</label>
          <input
            id={ids.radius}
            type="text"
            inputMode="decimal"
            className="input-field w-full"
            value={draft.radiusText}
            onChange={(e) => onChange({ radiusText: e.target.value })}
            placeholder="2"
            aria-required="true"
            aria-invalid={showErrors && !!errors.radius}
            aria-describedby={showErrors && errors.radius ? ids.radiusErr : undefined}
          />
          {showErrors && errors.radius && (
            <p id={ids.radiusErr} role="alert" className="form-error mt-1">{errors.radius}</p>
          )}
        </div>

        <div>
          <label htmlFor={ids.fee} className="form-label">Frete (R$)</label>
          <div className="relative">
            <span className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-sm text-text-muted">R$</span>
            <input
              id={ids.fee}
              type="text"
              inputMode="numeric"
              className="input-field w-full pl-9 disabled:opacity-50"
              value={draft.isFree ? '0,00' : draft.feeMasked}
              onChange={(e) => onChange({ feeMasked: maskCurrency(e.target.value) })}
              placeholder="0,00"
              disabled={draft.isFree}
              aria-invalid={showErrors && !!errors.fee}
              aria-describedby={showErrors && errors.fee ? ids.feeErr : undefined}
            />
          </div>
          {showErrors && errors.fee && (
            <p id={ids.feeErr} role="alert" className="form-error mt-1">{errors.fee}</p>
          )}
          <div className="mt-2 flex items-center gap-2">
            <input
              id={ids.free}
              type="checkbox"
              className="h-4 w-4 accent-primary-700"
              checked={draft.isFree}
              onChange={(e) => onChange({ isFree: e.target.checked })}
            />
            <label htmlFor={ids.free} className="text-xs font-medium text-text-secondary">
              Frete grátis nesta zona
            </label>
          </div>
        </div>

        <div>
          <span className="form-label" id={ids.etaErr + '-label'}>Tempo de entrega (min)</span>
          <div className="flex items-center gap-2">
            <label htmlFor={ids.etaMin} className="sr-only">{`Tempo mínimo em minutos da ${zoneTitle}`}</label>
            <input
              id={ids.etaMin}
              type="text"
              inputMode="numeric"
              className="input-field w-full"
              value={draft.etaMinText}
              onChange={(e) => onChange({ etaMinText: e.target.value.replace(/\D/g, '') })}
              placeholder="20"
              aria-required="true"
              aria-invalid={showErrors && !!errors.eta}
              aria-describedby={showErrors && errors.eta ? ids.etaErr : undefined}
            />
            <span className="text-sm text-text-muted" aria-hidden="true">–</span>
            <label htmlFor={ids.etaMax} className="sr-only">{`Tempo máximo em minutos da ${zoneTitle}`}</label>
            <input
              id={ids.etaMax}
              type="text"
              inputMode="numeric"
              className="input-field w-full"
              value={draft.etaMaxText}
              onChange={(e) => onChange({ etaMaxText: e.target.value.replace(/\D/g, '') })}
              placeholder="40"
              aria-required="true"
              aria-invalid={showErrors && !!errors.eta}
              aria-describedby={showErrors && errors.eta ? ids.etaErr : undefined}
            />
          </div>
          {showErrors && errors.eta && (
            <p id={ids.etaErr} role="alert" className="form-error mt-1">{errors.eta}</p>
          )}
        </div>
      </div>
    </fieldset>
  )
}

// ── Página ────────────────────────────────────────────────────────────────────

export default function ZonasEntregaPage() {
  const router = useRouter()
  const freeMinId = useId()

  const [pageState, setPageState] = useState<'loading' | 'error' | 'ready'>('loading')
  const [loadError, setLoadError] = useState<string | null>(null)

  const [drafts, setDrafts] = useState<ZoneDraft[]>([])
  const [freeMinMasked, setFreeMinMasked] = useState('')
  const [center, setCenter] = useState<[number, number] | null>(null)

  const [showErrors, setShowErrors] = useState(false)
  const [saving, setSaving] = useState(false)
  const [notice, setNotice] = useState<Notice>(null)

  const load = useCallback(async () => {
    setPageState('loading')
    setLoadError(null)
    try {
      // Zonas + centro do mapa (localização da loja em Minha Loja) em paralelo.
      const [zonesRes, cfgRes] = await Promise.allSettled([
        api.get<DeliveryZonesResponse>('/delivery/zones'),
        api.get<{ restaurantLat: number | null; restaurantLng: number | null }>('/config'),
      ])
      if (zonesRes.status === 'rejected') {
        const err: unknown = zonesRes.reason
        throw err instanceof Error ? err : new Error('Erro ao carregar as zonas.')
      }
      setDrafts(zonesRes.value.zones.map(draftFromView))
      setFreeMinMasked(
        zonesRes.value.freeDeliveryMinOrderCents != null
          ? maskCurrency(String(zonesRes.value.freeDeliveryMinOrderCents))
          : '',
      )
      // Sem localização da loja: a página funciona, só o mapa fica desabilitado.
      if (
        cfgRes.status === 'fulfilled' &&
        cfgRes.value.restaurantLat != null &&
        cfgRes.value.restaurantLng != null
      ) {
        setCenter([cfgRes.value.restaurantLat, cfgRes.value.restaurantLng])
      } else {
        setCenter(null)
      }
      setPageState('ready')
    } catch (err) {
      setLoadError(err instanceof ApiError ? err.message : 'Erro ao carregar as zonas de entrega.')
      setPageState('error')
    }
  }, [])

  useEffect(() => {
    if (!getToken()) { router.replace('/login'); return }
    queueMicrotask(() => { void load() })
  }, [load, router])

  const errorsList = validateAll(drafts)
  const hasZoneErrors = errorsList.some((e) => e.radius || e.fee || e.eta)

  const freeMinCents = maskedToCents(freeMinMasked)
  const freeMinError =
    freeMinCents != null && freeMinCents > MAX_FREE_MIN_ORDER_CENTS
      ? 'O valor mínimo do pedido deve ser de até R$ 1.000.000,00.'
      : null

  // Anéis do mapa: refletem ao vivo os raios válidos digitados no editor.
  const rings: ZoneRing[] = drafts.flatMap((d, i) => {
    const r = parseRadius(d.radiusText)
    if (r == null || r <= 0 || r > MAX_RADIUS_KM) return []
    const fee = d.isFree ? 'Frete grátis' : centsToDisplay(maskedToCents(d.feeMasked) ?? 0)
    const name = d.name.trim() || `Até ${formatRadius(r)} km`
    return [{ radiusKm: r, color: RING_COLORS[i % RING_COLORS.length], label: `${name} · ${fee}` }]
  })

  function patchDraft(key: string, patch: Partial<ZoneDraft>) {
    setDrafts((prev) => prev.map((d) => (d.key === key ? { ...d, ...patch } : d)))
  }

  function addZone() {
    setNotice(null)
    setDrafts((prev) => {
      const last = prev[prev.length - 1]
      const lastRadius = last ? parseRadius(last.radiusText) : null
      const suggested = lastRadius != null && lastRadius > 0 ? Math.min(lastRadius + 1, MAX_RADIUS_KM) : 1
      const lastEtaMax = last ? parseMinutes(last.etaMaxText) : null
      return [
        ...prev,
        {
          key: nextKey(),
          name: '',
          radiusText: formatRadius(suggested),
          feeMasked: '',
          etaMinText: lastEtaMax != null ? String(Math.min(lastEtaMax, MAX_ETA_MINUTES)) : '20',
          etaMaxText: lastEtaMax != null ? String(Math.min(lastEtaMax + 15, MAX_ETA_MINUTES)) : '40',
          isFree: false,
        },
      ]
    })
  }

  function removeZone(key: string) {
    setNotice(null)
    setDrafts((prev) => prev.filter((d) => d.key !== key))
  }

  async function save() {
    setNotice(null)
    if (hasZoneErrors || freeMinError) {
      setShowErrors(true)
      setNotice({ type: 'error', message: 'Corrija os campos destacados antes de salvar.' })
      return
    }
    const zones: DeliveryZoneUpsert[] = drafts.map((d) => ({
      name: d.name.trim() || undefined,
      maxRadiusKm: parseRadius(d.radiusText) as number,
      feeCents: d.isFree ? 0 : (maskedToCents(d.feeMasked) ?? 0),
      etaMinMinutes: parseMinutes(d.etaMinText) as number,
      etaMaxMinutes: parseMinutes(d.etaMaxText) as number,
      isFree: d.isFree,
    }))
    setSaving(true)
    try {
      const res = await api.put<DeliveryZonesResponse>('/delivery/zones', {
        zones,
        freeDeliveryMinOrderCents: freeMinCents,
      })
      // Renormaliza com o que o backend gravou (ids, ordem).
      setDrafts(res.zones.map(draftFromView))
      setFreeMinMasked(
        res.freeDeliveryMinOrderCents != null ? maskCurrency(String(res.freeDeliveryMinOrderCents)) : '',
      )
      setShowErrors(false)
      setNotice({ type: 'success', message: 'Zonas de entrega salvas com sucesso.' })
    } catch (err) {
      // 400 do backend (sobreposição, bounds) chega aqui com a mensagem dele.
      setNotice({
        type: 'error',
        message: err instanceof ApiError ? err.message : 'Erro ao salvar as zonas de entrega.',
      })
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="min-h-screen bg-bg-secondary">
      <main className="mx-auto flex w-full max-w-6xl flex-col gap-5 px-4 py-6">

        {/* Cabeçalho */}
        <header className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h1 className="text-2xl font-bold text-text-primary">Zonas de entrega</h1>
            <p className="mt-1 text-sm text-text-secondary">
              Anéis de cobertura centrados na sua loja: cada anel define preço do frete e tempo de entrega.
            </p>
          </div>
          <button
            type="button"
            className="btn-primary min-h-11 shrink-0 disabled:opacity-50"
            onClick={() => void save()}
            disabled={saving || pageState !== 'ready'}
          >
            {saving ? 'Salvando...' : 'Salvar zonas'}
          </button>
        </header>

        {/* Banner de sucesso/erro */}
        {notice && (
          <div
            role={notice.type === 'error' ? 'alert' : 'status'}
            className={[
              'rounded-lg px-4 py-3 text-sm font-medium',
              notice.type === 'success' ? 'bg-success/10 text-success' : 'bg-error/10 text-error',
            ].join(' ')}
          >
            {notice.message}
          </div>
        )}

        {pageState === 'loading' ? (
          <div className="grid gap-5 lg:grid-cols-2" aria-busy="true" aria-label="Carregando zonas de entrega...">
            <div className="h-96 animate-pulse rounded-xl bg-bg-tertiary" />
            <div className="flex flex-col gap-3">
              {Array.from({ length: 3 }).map((_, i) => (
                <div key={i} className="h-40 animate-pulse rounded-xl bg-bg-tertiary" />
              ))}
            </div>
          </div>
        ) : pageState === 'error' ? (
          <div role="alert" className="flex flex-col items-center gap-2 rounded-xl bg-bg-primary px-4 py-10 text-center shadow-card">
            <XCircle className="h-8 w-8 text-error" aria-hidden="true" />
            <p className="text-sm text-error">{loadError}</p>
            <button className="btn-outline mt-2" onClick={() => void load()}>
              Tentar novamente
            </button>
          </div>
        ) : (
          <div className="grid items-start gap-5 lg:grid-cols-2">

            {/* ── Mapa ─────────────────────────────────────────────────────── */}
            <section className="rounded-xl bg-bg-primary p-4 shadow-card" aria-label="Mapa das zonas">
              <div className="mb-3 flex items-center gap-2">
                <MapPin className="h-4 w-4 text-text-muted" aria-hidden="true" />
                <h2 className="text-sm font-semibold text-text-primary">Área de cobertura</h2>
              </div>

              {center ? (
                <>
                  <DeliveryZonesMap center={center} rings={rings} />
                  {rings.length > 0 && (
                    <ul className="mt-3 flex flex-wrap gap-x-4 gap-y-1" aria-label="Legenda das zonas">
                      {rings.map((ring, i) => (
                        <li key={i} className="flex items-center gap-1.5 text-xs text-text-secondary">
                          <span
                            className="h-2.5 w-2.5 rounded-full border border-black/10"
                            style={{ backgroundColor: ring.color }}
                            aria-hidden="true"
                          />
                          {ring.label}
                        </li>
                      ))}
                    </ul>
                  )}
                </>
              ) : (
                <div className="flex h-80 flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-border-medium bg-bg-secondary px-6 text-center lg:h-[28rem]">
                  <AlertTriangle className="h-8 w-8 text-warning" aria-hidden="true" />
                  <p className="text-sm font-medium text-text-primary">
                    A loja ainda não tem localização definida.
                  </p>
                  <p className="text-sm text-text-secondary">
                    Defina o endereço e o pin da loja em Configurações → Minha Loja para
                    visualizar as zonas no mapa. Você ainda pode configurar os anéis ao lado.
                  </p>
                  <Link href="/configuracoes/loja" className="btn-outline min-h-11">
                    Abrir Minha Loja
                  </Link>
                </div>
              )}
            </section>

            {/* ── Editor de anéis ──────────────────────────────────────────── */}
            <section className="flex flex-col gap-4" aria-label="Anéis de entrega">
              {drafts.length === 0 ? (
                <div className="flex flex-col items-center gap-3 rounded-xl bg-bg-primary px-6 py-10 text-center shadow-card">
                  <MapPin className="h-8 w-8 text-text-muted" aria-hidden="true" />
                  <p className="text-sm font-medium text-text-primary">Nenhuma zona de entrega configurada.</p>
                  <p className="text-sm text-text-secondary">
                    Crie anéis de raio crescente — por exemplo: até 1 km por R$ 5,00 e até 2 km por R$ 8,00.
                    Pedidos fora do maior anel ficam fora da área de entrega.
                  </p>
                  <button type="button" className="btn-primary mt-1 min-h-11" onClick={addZone}>
                    <Plus className="mr-1.5 inline h-4 w-4" aria-hidden="true" />
                    Adicionar primeira zona
                  </button>
                </div>
              ) : (
                <>
                  <ol className="flex flex-col gap-3" aria-label="Lista de anéis, do menor para o maior raio">
                    {drafts.map((draft, i) => (
                      <li key={draft.key}>
                        <ZoneEditor
                          draft={draft}
                          index={i}
                          color={RING_COLORS[i % RING_COLORS.length]}
                          errors={errorsList[i]}
                          showErrors={showErrors}
                          disabled={saving}
                          onChange={(patch) => patchDraft(draft.key, patch)}
                          onRemove={() => removeZone(draft.key)}
                        />
                      </li>
                    ))}
                  </ol>
                  <button
                    type="button"
                    className="btn-outline min-h-11 self-start disabled:opacity-50"
                    onClick={addZone}
                    disabled={saving}
                  >
                    <Plus className="mr-1.5 inline h-4 w-4" aria-hidden="true" />
                    Adicionar zona
                  </button>
                </>
              )}

              {/* ── Frete grátis global por valor de pedido ─────────────────── */}
              <div className="rounded-xl bg-bg-primary p-4 shadow-card">
                <h2 className="text-sm font-semibold text-text-primary">Frete grátis por valor do pedido</h2>
                <p className="mt-1 text-xs text-text-secondary">
                  Pedidos com subtotal a partir deste valor têm frete grátis em qualquer zona.
                  Deixe vazio para não oferecer.
                </p>
                <div className="mt-3 max-w-xs">
                  <label htmlFor={freeMinId} className="form-label">Frete grátis acima de (R$)</label>
                  <div className="relative">
                    <span className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-sm text-text-muted">R$</span>
                    <input
                      id={freeMinId}
                      type="text"
                      inputMode="numeric"
                      className="input-field w-full pl-9"
                      value={freeMinMasked}
                      onChange={(e) => setFreeMinMasked(maskCurrency(e.target.value))}
                      placeholder="Ex.: 80,00"
                      disabled={saving}
                      aria-invalid={!!freeMinError}
                    />
                  </div>
                  {freeMinError && (
                    <p role="alert" className="form-error mt-1">{freeMinError}</p>
                  )}
                </div>
              </div>
            </section>
          </div>
        )}
      </main>
    </div>
  )
}
