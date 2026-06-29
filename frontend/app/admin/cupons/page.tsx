'use client'

import {
  useCallback,
  useEffect,
  useId,
  useReducer,
  useRef,
  useState,
} from 'react'
import {
  CheckCircle,
  History,
  Pencil,
  Plus,
  Tag,
  XCircle,
} from 'lucide-react'
import { api } from '@/lib/api'
import { useModalA11y } from '@/lib/use-modal-a11y'
import type {
  CouponCreateRequest,
  CouponRedemptionResponse,
  CouponResponse,
} from '@/types/coupon'

// ── Helpers ───────────────────────────────────────────────────────────────────

const formatCents = (cents: number) =>
  (cents / 100).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })

const formatPercent = (value: number) => `${(value / 100).toFixed(0)}%`

const formatDiscount = (c: CouponResponse) =>
  c.discountType === 'FIXED' ? formatCents(c.discountValue) : formatPercent(c.discountValue)

function fmtDate(iso: string) {
  return new Date(iso).toLocaleDateString('pt-BR')
}

function fmtDateTime(iso: string) {
  return new Date(iso).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' })
}

/** Converte ISO 8601 para o formato exigido pelo input datetime-local (YYYY-MM-DDTHH:mm) */
function isoToLocal(iso: string): string {
  if (!iso) return ''
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** Converte datetime-local (YYYY-MM-DDTHH:mm) para ISO 8601 */
function localToIso(local: string): string {
  if (!local) return ''
  return new Date(local).toISOString()
}

type CouponStatus = 'active' | 'inactive' | 'expired'

function getStatus(c: CouponResponse): CouponStatus {
  if (!c.active) return 'inactive'
  if (new Date(c.validUntil) < new Date()) return 'expired'
  return 'active'
}

type Page<T> = {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
}

// ── Estado do formulário ──────────────────────────────────────────────────────

interface FormState {
  code: string
  description: string
  discountType: 'FIXED' | 'PERCENT'
  /** Valor como string para o input (R$ X,XX ou XX%) */
  discountValueStr: string
  minOrderStr: string
  maxUsesStr: string
  maxUsesPerCustomer: string
  validFrom: string
  validUntil: string
  active: boolean
}

const EMPTY_FORM: FormState = {
  code: '',
  description: '',
  discountType: 'PERCENT',
  discountValueStr: '',
  minOrderStr: '',
  maxUsesStr: '',
  maxUsesPerCustomer: '1',
  validFrom: '',
  validUntil: '',
  active: true,
}

function couponToForm(c: CouponResponse): FormState {
  const value =
    c.discountType === 'FIXED'
      ? (c.discountValue / 100).toFixed(2).replace('.', ',')
      : (c.discountValue / 100).toFixed(0)
  return {
    code: c.code,
    description: c.description ?? '',
    discountType: c.discountType,
    discountValueStr: value,
    minOrderStr: c.minOrderCents > 0 ? (c.minOrderCents / 100).toFixed(2).replace('.', ',') : '',
    maxUsesStr: c.maxUses != null ? String(c.maxUses) : '',
    maxUsesPerCustomer: String(c.maxUsesPerCustomer),
    validFrom: isoToLocal(c.validFrom),
    validUntil: isoToLocal(c.validUntil),
    active: c.active,
  }
}

function formToRequest(f: FormState): CouponCreateRequest {
  const rawValue = f.discountValueStr.replace(',', '.')
  const discountValue =
    f.discountType === 'FIXED'
      ? Math.round(parseFloat(rawValue) * 100)
      : Math.round(parseFloat(rawValue) * 100)

  const rawMin = f.minOrderStr.replace(',', '.')
  const minOrderCents = f.minOrderStr.trim()
    ? Math.round(parseFloat(rawMin) * 100)
    : 0

  return {
    code: f.code.trim().toUpperCase(),
    description: f.description.trim() || undefined,
    discountType: f.discountType,
    discountValue,
    minOrderCents,
    maxUses: f.maxUsesStr.trim() ? parseInt(f.maxUsesStr, 10) : undefined,
    maxUsesPerCustomer: parseInt(f.maxUsesPerCustomer, 10) || 1,
    validFrom: localToIso(f.validFrom),
    validUntil: localToIso(f.validUntil),
    active: f.active,
  }
}

function validateForm(f: FormState): string | null {
  if (!f.code.trim()) return 'Informe o codigo do cupom.'
  if (/\s/.test(f.code)) return 'O codigo nao pode conter espacos.'
  const rawValue = f.discountValueStr.replace(',', '.')
  const num = parseFloat(rawValue)
  if (isNaN(num) || num <= 0) return 'Informe um valor de desconto valido (> 0).'
  if (f.discountType === 'PERCENT' && num > 100) return 'Percentual nao pode exceder 100%.'
  if (!f.validFrom) return 'Informe a data/hora de inicio.'
  if (!f.validUntil) return 'Informe a data/hora de termino.'
  if (new Date(f.validFrom) >= new Date(f.validUntil)) return 'A data de inicio deve ser anterior ao termino.'
  return null
}

// ── Sub-componente: Modal de Formulario ───────────────────────────────────────

function CouponFormModal({
  editing,
  onSave,
  onClose,
}: {
  editing: CouponResponse | null
  onSave: () => void
  onClose: () => void
}) {
  const modalRef = useRef<HTMLDivElement>(null)
  useModalA11y(modalRef as React.RefObject<HTMLElement>, onClose)

  const [form, setForm] = useState<FormState>(editing ? couponToForm(editing) : EMPTY_FORM)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const titleId = useId()

  function set(patch: Partial<FormState>) {
    setForm((prev) => ({ ...prev, ...patch }))
  }

  async function handleSave() {
    const validationError = validateForm(form)
    if (validationError) { setError(validationError); return }
    setSaving(true)
    setError(null)
    try {
      const body = formToRequest(form)
      if (editing) {
        await api.put(`/coupons/${editing.id}`, body)
      } else {
        await api.post('/coupons', body)
      }
      onSave()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Erro ao salvar cupom.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4" role="dialog" aria-modal="true" aria-labelledby={titleId}>
      <div className="absolute inset-0 bg-black/50" aria-hidden="true" onClick={onClose} />
      <div
        ref={modalRef}
        tabIndex={-1}
        className="relative bg-bg-primary rounded-2xl w-full max-w-lg p-6 outline-none max-h-[90vh] overflow-y-auto shadow-xl"
      >
        <h2 id={titleId} className="text-lg font-bold text-text-primary mb-5">
          {editing ? 'Editar Cupom' : 'Novo Cupom'}
        </h2>

        {/* Codigo */}
        <div className="form-group">
          <label className="form-label" htmlFor="coupon-code">
            Codigo <span className="text-error" aria-hidden="true">*</span>
          </label>
          {editing ? (
            <p className="input-field bg-bg-secondary text-text-muted font-mono cursor-not-allowed">
              {editing.code}
            </p>
          ) : (
            <input
              id="coupon-code"
              type="text"
              className="input-field font-mono uppercase"
              placeholder="Ex: PROMO10"
              value={form.code}
              onChange={(e) => set({ code: e.target.value })}
              onBlur={(e) => set({ code: e.target.value.trim().toUpperCase().replace(/\s/g, '') })}
              autoComplete="off"
              required
            />
          )}
        </div>

        {/* Descricao */}
        <div className="form-group">
          <label className="form-label" htmlFor="coupon-desc">
            Descricao <span className="text-text-muted text-xs">(opcional)</span>
          </label>
          <input
            id="coupon-desc"
            type="text"
            className="input-field"
            placeholder="Ex: 10% de desconto no primeiro pedido"
            value={form.description}
            onChange={(e) => set({ description: e.target.value })}
          />
        </div>

        {/* Tipo de desconto */}
        <div className="form-group" role="group" aria-labelledby="coupon-type-label">
          <p id="coupon-type-label" className="form-label">
            Tipo de desconto <span className="text-error" aria-hidden="true">*</span>
          </p>
          <div className="flex gap-3">
            {(['FIXED', 'PERCENT'] as const).map((t) => (
              <label
                key={t}
                className={[
                  'flex items-center gap-2 px-4 py-2.5 rounded-lg border cursor-pointer text-sm font-medium transition-colors select-none',
                  form.discountType === t
                    ? 'bg-primary-700 text-white border-primary-700'
                    : 'bg-bg-primary text-text-primary border-border-medium hover:bg-bg-tertiary',
                ].join(' ')}
              >
                <input
                  type="radio"
                  name="discountType"
                  value={t}
                  checked={form.discountType === t}
                  onChange={() => set({ discountType: t, discountValueStr: '' })}
                  className="sr-only"
                />
                {t === 'FIXED' ? 'Valor fixo (R$)' : 'Percentual (%)'}
              </label>
            ))}
          </div>
        </div>

        {/* Valor do desconto */}
        <div className="form-group">
          <label className="form-label" htmlFor="coupon-value">
            {form.discountType === 'FIXED' ? 'Valor do desconto (R$)' : 'Percentual de desconto (%)'}
            <span className="text-error" aria-hidden="true"> *</span>
          </label>
          <div className="relative flex items-center">
            {form.discountType === 'FIXED' && (
              <span className="absolute left-3 text-text-muted text-sm select-none" aria-hidden="true">R$</span>
            )}
            <input
              id="coupon-value"
              type="text"
              inputMode="decimal"
              className={['input-field', form.discountType === 'FIXED' ? 'pl-9' : 'pr-9'].join(' ')}
              placeholder={form.discountType === 'FIXED' ? '5,00' : '15'}
              value={form.discountValueStr}
              onChange={(e) => set({ discountValueStr: e.target.value })}
            />
            {form.discountType === 'PERCENT' && (
              <span className="absolute right-3 text-text-muted text-sm select-none" aria-hidden="true">%</span>
            )}
          </div>
        </div>

        {/* Pedido minimo */}
        <div className="form-group">
          <label className="form-label" htmlFor="coupon-min">
            Pedido minimo (R$) <span className="text-text-muted text-xs">(opcional)</span>
          </label>
          <div className="relative flex items-center">
            <span className="absolute left-3 text-text-muted text-sm select-none" aria-hidden="true">R$</span>
            <input
              id="coupon-min"
              type="text"
              inputMode="decimal"
              className="input-field pl-9"
              placeholder="0,00"
              value={form.minOrderStr}
              onChange={(e) => set({ minOrderStr: e.target.value })}
            />
          </div>
        </div>

        {/* Limites */}
        <div className="grid grid-cols-2 gap-3">
          <div className="form-group">
            <label className="form-label" htmlFor="coupon-maxuses">
              Limite de usos total <span className="text-text-muted text-xs">(vazio = ilimitado)</span>
            </label>
            <input
              id="coupon-maxuses"
              type="number"
              min="1"
              className="input-field"
              placeholder="Ilimitado"
              value={form.maxUsesStr}
              onChange={(e) => set({ maxUsesStr: e.target.value })}
            />
          </div>
          <div className="form-group">
            <label className="form-label" htmlFor="coupon-peruser">
              Limite por cliente
            </label>
            <input
              id="coupon-peruser"
              type="number"
              min="1"
              className="input-field"
              value={form.maxUsesPerCustomer}
              onChange={(e) => set({ maxUsesPerCustomer: e.target.value })}
            />
          </div>
        </div>

        {/* Vigencia */}
        <div className="grid grid-cols-2 gap-3">
          <div className="form-group">
            <label className="form-label" htmlFor="coupon-from">
              Valido de <span className="text-error" aria-hidden="true">*</span>
            </label>
            <input
              id="coupon-from"
              type="datetime-local"
              className="input-field"
              value={form.validFrom}
              onChange={(e) => set({ validFrom: e.target.value })}
            />
          </div>
          <div className="form-group">
            <label className="form-label" htmlFor="coupon-until">
              Valido ate <span className="text-error" aria-hidden="true">*</span>
            </label>
            <input
              id="coupon-until"
              type="datetime-local"
              className="input-field"
              value={form.validUntil}
              onChange={(e) => set({ validUntil: e.target.value })}
            />
          </div>
        </div>

        {/* Status */}
        <div className="form-group">
          <label className="flex items-center gap-3 cursor-pointer select-none">
            <span className="form-label mb-0">Ativo</span>
            <button
              type="button"
              role="switch"
              aria-checked={form.active}
              onClick={() => set({ active: !form.active })}
              className={[
                'relative inline-flex h-6 w-11 flex-shrink-0 rounded-full border-2 border-transparent transition-colors duration-200 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary-700',
                form.active ? 'bg-primary-700' : 'bg-bg-tertiary',
              ].join(' ')}
            >
              <span
                aria-hidden="true"
                className={[
                  'pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow ring-0 transition duration-200 ease-in-out',
                  form.active ? 'translate-x-5' : 'translate-x-0',
                ].join(' ')}
              />
            </button>
          </label>
        </div>

        {/* Erro */}
        {error && (
          <p className="text-error text-sm mb-4" role="alert">
            {error}
          </p>
        )}

        {/* Acoes */}
        <div className="flex gap-3 mt-2">
          <button type="button" onClick={onClose} className="btn-outline flex-1 min-h-[48px]">
            Cancelar
          </button>
          <button
            type="button"
            onClick={() => { void handleSave() }}
            disabled={saving}
            className="btn-primary flex-1 min-h-[48px] flex items-center justify-center gap-2"
          >
            {saving && (
              <svg className="animate-spin h-4 w-4 flex-shrink-0" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z" />
              </svg>
            )}
            {saving ? 'Salvando...' : 'Salvar'}
          </button>
        </div>
      </div>
    </div>
  )
}

// ── Sub-componente: Modal de Historico de Resgates ────────────────────────────

const REDEMPTIONS_PAGE_SIZE = 8

function RedemptionsModal({
  coupon,
  onClose,
}: {
  coupon: CouponResponse
  onClose: () => void
}) {
  const modalRef = useRef<HTMLDivElement>(null)
  useModalA11y(modalRef as React.RefObject<HTMLElement>, onClose)
  const titleId = useId()

  const [redemptions, setRedemptions] = useState<CouponRedemptionResponse[]>([])
  const [totalPages, setTotalPages] = useState(0)
  const [page, setPage] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const loadRedemptions = useCallback(async (p: number) => {
    setLoading(true)
    setError(null)
    try {
      const data = await api.get<Page<CouponRedemptionResponse>>(
        `/coupons/${coupon.id}/redemptions?page=${p}&size=${REDEMPTIONS_PAGE_SIZE}`,
      )
      setRedemptions(data.content)
      setTotalPages(data.totalPages)
      setPage(data.number)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Erro ao carregar historico.')
    } finally {
      setLoading(false)
    }
  }, [coupon.id])

  useEffect(() => { void loadRedemptions(0) }, [loadRedemptions])

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4" role="dialog" aria-modal="true" aria-labelledby={titleId}>
      <div className="absolute inset-0 bg-black/50" aria-hidden="true" onClick={onClose} />
      <div
        ref={modalRef}
        tabIndex={-1}
        className="relative bg-bg-primary rounded-2xl w-full max-w-2xl p-6 outline-none max-h-[90vh] flex flex-col shadow-xl"
      >
        <div className="flex items-center justify-between mb-4">
          <h2 id={titleId} className="text-lg font-bold text-text-primary">
            Historico de Resgates —{' '}
            <span className="font-mono text-primary-700">{coupon.code}</span>
          </h2>
          <button onClick={onClose} aria-label="Fechar historico" className="p-1.5 rounded-lg text-text-muted hover:bg-bg-tertiary transition-colors">
            <XCircle className="h-5 w-5" aria-hidden="true" />
          </button>
        </div>

        {/* Conteudo */}
        <div className="flex-1 overflow-y-auto min-h-0">
          {loading ? (
            <div className="space-y-2">
              {Array.from({ length: 4 }).map((_, i) => (
                <div key={i} className="h-12 rounded-lg bg-bg-tertiary animate-pulse" />
              ))}
            </div>
          ) : error ? (
            <p className="text-error text-sm text-center py-8" role="alert">{error}</p>
          ) : redemptions.length === 0 ? (
            <div className="text-center py-12">
              <History className="h-10 w-10 text-text-muted mx-auto mb-3" aria-hidden="true" />
              <p className="text-text-secondary font-medium">Nenhum resgate ainda</p>
              <p className="text-text-muted text-sm mt-1">Este cupom ainda nao foi utilizado.</p>
            </div>
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border-light">
                  <th className="text-left py-2 px-3 text-text-muted font-medium">Data</th>
                  <th className="text-left py-2 px-3 text-text-muted font-medium">Pedido</th>
                  <th className="text-left py-2 px-3 text-text-muted font-medium">Telefone</th>
                  <th className="text-right py-2 px-3 text-text-muted font-medium">Desconto</th>
                </tr>
              </thead>
              <tbody>
                {redemptions.map((r) => (
                  <tr key={r.id} className="border-b border-border-light hover:bg-bg-secondary">
                    <td className="py-2.5 px-3 text-text-secondary">{fmtDateTime(r.redeemedAt)}</td>
                    <td className="py-2.5 px-3 font-mono text-text-primary text-xs">{r.orderId.slice(0, 8)}</td>
                    <td className="py-2.5 px-3 text-text-secondary">{r.customerPhone ?? '—'}</td>
                    <td className="py-2.5 px-3 text-right font-semibold text-success">{formatCents(r.discountAppliedCents)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        {/* Paginacao */}
        {totalPages > 1 && (
          <div className="flex items-center justify-between pt-4 border-t border-border-light mt-4">
            <button
              onClick={() => { void loadRedemptions(page - 1) }}
              disabled={page === 0 || loading}
              className="btn-outline px-4 py-2 text-sm disabled:opacity-50"
            >
              Anterior
            </button>
            <span className="text-sm text-text-muted">{page + 1} / {totalPages}</span>
            <button
              onClick={() => { void loadRedemptions(page + 1) }}
              disabled={page >= totalPages - 1 || loading}
              className="btn-outline px-4 py-2 text-sm disabled:opacity-50"
            >
              Proximo
            </button>
          </div>
        )}
      </div>
    </div>
  )
}

// ── Skeleton de tabela ────────────────────────────────────────────────────────

function TableSkeleton() {
  return (
    <div className="space-y-2" aria-busy="true" aria-label="Carregando cupons">
      {Array.from({ length: 5 }).map((_, i) => (
        <div key={i} className="h-14 rounded-lg bg-bg-tertiary animate-pulse" />
      ))}
    </div>
  )
}

// ── Badge de status ───────────────────────────────────────────────────────────

function StatusBadge({ status }: { status: CouponStatus }) {
  if (status === 'active') return (
    <span className="inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-semibold bg-green-100 text-green-700">
      <CheckCircle className="h-3 w-3" aria-hidden="true" /> Ativo
    </span>
  )
  if (status === 'expired') return (
    <span className="inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-semibold bg-red-100 text-red-700">
      <XCircle className="h-3 w-3" aria-hidden="true" /> Expirado
    </span>
  )
  return (
    <span className="inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-semibold bg-gray-100 text-gray-600">
      <XCircle className="h-3 w-3" aria-hidden="true" /> Inativo
    </span>
  )
}

// ── Pagina principal ──────────────────────────────────────────────────────────

export default function CuponsPage() {
  const [coupons, setCoupons] = useState<CouponResponse[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [showForm, setShowForm] = useState(false)
  const [editingCoupon, setEditingCoupon] = useState<CouponResponse | null>(null)
  const [redemptionCoupon, setRedemptionCoupon] = useState<CouponResponse | null>(null)

  /** ID do cupom com confirmacao de desativacao pendente */
  const [confirmDeactivateId, setConfirmDeactivateId] = useState<string | null>(null)
  const [deactivating, setDeactivating] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const data = await api.get<Page<CouponResponse>>('/coupons?page=0&size=100')
      setCoupons(data.content)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Erro ao carregar cupons.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load() }, [load])

  function openCreate() {
    setEditingCoupon(null)
    setShowForm(true)
  }

  function openEdit(c: CouponResponse) {
    setEditingCoupon(c)
    setShowForm(true)
  }

  function handleSaved() {
    setShowForm(false)
    void load()
  }

  async function handleDeactivate(id: string) {
    setDeactivating(true)
    try {
      await api.del(`/coupons/${id}`)
      setConfirmDeactivateId(null)
      void load()
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Erro ao desativar cupom.')
    } finally {
      setDeactivating(false)
    }
  }

  return (
    <main className="p-6 max-w-6xl mx-auto">
      {/* Cabecalho */}
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <Tag className="h-6 w-6 text-primary-700" aria-hidden="true" />
          <h1 className="text-2xl font-bold text-text-primary">Cupons &amp; Descontos</h1>
        </div>
        <button onClick={openCreate} className="btn-primary flex items-center gap-2 min-h-[44px] px-4">
          <Plus className="h-4 w-4" aria-hidden="true" />
          Novo Cupom
        </button>
      </div>

      {/* Estados */}
      {loading ? (
        <TableSkeleton />
      ) : error ? (
        <div className="text-center py-16">
          <p className="text-error font-medium mb-4" role="alert">{error}</p>
          <button onClick={() => { void load() }} className="btn-primary">
            Tentar de novo
          </button>
        </div>
      ) : coupons.length === 0 ? (
        <div className="text-center py-16">
          <Tag className="h-12 w-12 text-text-muted mx-auto mb-4" aria-hidden="true" />
          <p className="text-text-secondary font-medium text-lg">Nenhum cupom cadastrado</p>
          <p className="text-text-muted text-sm mt-1 mb-6">Crie o primeiro cupom de desconto para seus clientes.</p>
          <button onClick={openCreate} className="btn-primary flex items-center gap-2 mx-auto min-h-[48px] px-6">
            <Plus className="h-4 w-4" aria-hidden="true" />
            Criar primeiro cupom
          </button>
        </div>
      ) : (
        /* Tabela */
        <div className="bg-bg-primary rounded-xl border border-border-light overflow-hidden shadow-sm">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="bg-bg-secondary border-b border-border-light">
                  <th className="text-left py-3 px-4 text-text-muted font-semibold">Codigo</th>
                  <th className="text-left py-3 px-4 text-text-muted font-semibold">Tipo</th>
                  <th className="text-left py-3 px-4 text-text-muted font-semibold">Desconto</th>
                  <th className="text-left py-3 px-4 text-text-muted font-semibold">Validade</th>
                  <th className="text-left py-3 px-4 text-text-muted font-semibold">Usos (max)</th>
                  <th className="text-left py-3 px-4 text-text-muted font-semibold">Status</th>
                  <th className="text-right py-3 px-4 text-text-muted font-semibold">Acoes</th>
                </tr>
              </thead>
              <tbody>
                {coupons.map((c) => {
                  const status = getStatus(c)
                  const isConfirming = confirmDeactivateId === c.id
                  return (
                    <tr key={c.id} className="border-b border-border-light last:border-0 hover:bg-bg-secondary transition-colors">
                      <td className="py-3 px-4">
                        <span className="font-mono text-sm font-semibold bg-bg-tertiary text-text-primary px-2 py-0.5 rounded">
                          {c.code}
                        </span>
                      </td>
                      <td className="py-3 px-4 text-text-secondary">
                        {c.discountType === 'FIXED' ? 'Valor fixo' : 'Percentual'}
                      </td>
                      <td className="py-3 px-4 font-semibold text-text-primary">
                        {formatDiscount(c)}
                      </td>
                      <td className="py-3 px-4 text-text-secondary whitespace-nowrap">
                        {fmtDate(c.validFrom)} ate {fmtDate(c.validUntil)}
                      </td>
                      <td className="py-3 px-4 text-text-secondary">
                        {c.maxUses != null ? c.maxUses : <span aria-label="Ilimitado">∞</span>}
                      </td>
                      <td className="py-3 px-4">
                        <StatusBadge status={status} />
                      </td>
                      <td className="py-3 px-4">
                        <div className="flex items-center justify-end gap-1 flex-wrap">
                          {isConfirming ? (
                            <>
                              <span className="text-xs text-text-secondary mr-1 whitespace-nowrap">Desativar?</span>
                              <button
                                onClick={() => { void handleDeactivate(c.id) }}
                                disabled={deactivating}
                                className="text-xs font-semibold text-error hover:underline disabled:opacity-50 min-h-[36px] px-2"
                              >
                                {deactivating ? 'Aguarde...' : 'Sim'}
                              </button>
                              <button
                                onClick={() => setConfirmDeactivateId(null)}
                                className="text-xs font-semibold text-text-secondary hover:underline min-h-[36px] px-2"
                              >
                                Nao
                              </button>
                            </>
                          ) : (
                            <>
                              <button
                                onClick={() => setRedemptionCoupon(c)}
                                aria-label={`Ver historico de usos de ${c.code}`}
                                className="p-2 rounded-lg text-text-muted hover:bg-bg-tertiary hover:text-text-primary transition-colors min-h-[36px]"
                              >
                                <History className="h-4 w-4" aria-hidden="true" />
                              </button>
                              <button
                                onClick={() => openEdit(c)}
                                aria-label={`Editar cupom ${c.code}`}
                                className="p-2 rounded-lg text-text-muted hover:bg-bg-tertiary hover:text-text-primary transition-colors min-h-[36px]"
                              >
                                <Pencil className="h-4 w-4" aria-hidden="true" />
                              </button>
                              {c.active && (
                                <button
                                  onClick={() => setConfirmDeactivateId(c.id)}
                                  aria-label={`Desativar cupom ${c.code}`}
                                  className="p-2 rounded-lg text-text-muted hover:bg-red-50 hover:text-error transition-colors min-h-[36px]"
                                >
                                  <XCircle className="h-4 w-4" aria-hidden="true" />
                                </button>
                              )}
                            </>
                          )}
                        </div>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Modais */}
      {showForm && (
        <CouponFormModal
          editing={editingCoupon}
          onSave={handleSaved}
          onClose={() => setShowForm(false)}
        />
      )}

      {redemptionCoupon && (
        <RedemptionsModal
          coupon={redemptionCoupon}
          onClose={() => setRedemptionCoupon(null)}
        />
      )}
    </main>
  )
}
