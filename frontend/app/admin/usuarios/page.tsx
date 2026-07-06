'use client'

import { FormEvent, useCallback, useEffect, useId, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import {
  CheckCircle,
  Copy,
  Pencil,
  UserPlus,
  Users,
  XCircle,
  type LucideIcon,
} from 'lucide-react'
import { api, ApiError } from '@/lib/api'
import { getToken } from '@/lib/auth'
import { useModalA11y } from '@/lib/use-modal-a11y'

// ── Tipos ────────────────────────────────────────────────────────────────────

type UserResponse = {
  id: string
  firstName: string | null
  lastName: string | null
  email: string
  role: string
  isActive: boolean
  lastLoginAt: string | null
}

type InviteResponse = {
  id: string
  email: string
  role: string
  status: string
  expiresAt: string
  inviteLink: string
}

type Page<T> = {
  content: T[]
  totalElements: number
}

type PageOrList<T> = Page<T> | T[]

// ── Constantes ───────────────────────────────────────────────────────────────

const ROLES = ['ADMIN', 'MANAGER', 'CASHIER', 'STAFF', 'KITCHEN', 'WAITER', 'DRIVER'] as const
type Role = (typeof ROLES)[number]

const ROLE_LABELS: Record<Role, string> = {
  ADMIN:   'Administrador',
  MANAGER: 'Gerente',
  CASHIER: 'Caixa',
  STAFF:   'Colaborador',
  KITCHEN: 'Cozinheiro',
  WAITER:  'Garcom',
  DRIVER:  'Entregador (app)',
}

const ROLE_DESC: Record<Role, string> = {
  ADMIN:   'Acesso total ao sistema',
  MANAGER: 'Gerencia cardapio, relatorios e equipe',
  CASHIER: 'PDV e caixa',
  STAFF:   'Colaborador geral',
  KITCHEN: 'Cozinha e KDS',
  WAITER:  'Atendimento de mesas',
  DRIVER:  'Acesso ao app de entregas',
}

const ROLE_BADGE: Record<Role, string> = {
  ADMIN:   'bg-primary-100 text-primary-700',
  MANAGER: 'bg-blue-100 text-blue-700',
  CASHIER: 'bg-amber-100 text-amber-700',
  STAFF:   'bg-gray-100 text-gray-700',
  KITCHEN: 'bg-orange-100 text-orange-700',
  WAITER:  'bg-purple-100 text-purple-700',
  DRIVER:  'bg-teal-100 text-teal-700',
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function decodeRole(token: string | null): string | null {
  if (!token) return null
  try {
    const parts = token.split('.')
    if (parts.length !== 3) return null
    const b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const payload = JSON.parse(atob(b64)) as { role?: string; roles?: string[]; sub?: string }
    return payload.role ?? ((Array.isArray(payload.roles) && payload.roles[0]) || null)
  } catch {
    return null
  }
}

function decodeUserId(token: string | null): string | null {
  if (!token) return null
  try {
    const parts = token.split('.')
    if (parts.length !== 3) return null
    const b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const payload = JSON.parse(atob(b64)) as { userId?: string; sub?: string }
    return payload.userId ?? payload.sub ?? null
  } catch {
    return null
  }
}

function fullName(u: UserResponse): string {
  const parts = [u.firstName, u.lastName].filter(Boolean)
  return parts.length > 0 ? parts.join(' ') : '—'
}

function formatDate(iso: string | null): string {
  if (!iso) return 'Nunca'
  return new Date(iso).toLocaleString('pt-BR', {
    day: '2-digit', month: '2-digit', year: 'numeric',
    hour: '2-digit', minute: '2-digit',
  })
}

function hoursUntil(iso: string): string {
  const diff = Math.max(0, new Date(iso).getTime() - Date.now())
  const hours = Math.floor(diff / 3_600_000)
  const mins  = Math.floor((diff % 3_600_000) / 60_000)
  if (hours > 0) return `${hours}h`
  return `${mins}min`
}

function roleBadge(role: string) {
  const cls = ROLE_BADGE[role as Role] ?? 'bg-gray-100 text-gray-700'
  const label = ROLE_LABELS[role as Role] ?? role
  return (
    <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ${cls}`}>
      {label}
    </span>
  )
}

// ── Skeleton ─────────────────────────────────────────────────────────────────

function SkeletonRows({ rows = 5 }: { rows?: number }) {
  return (
    <tbody className="divide-y divide-border-light">
      {Array.from({ length: rows }).map((_, i) => (
        <tr key={i} className="animate-pulse">
          <td className="px-4 py-3"><div className="h-4 w-32 rounded bg-bg-tertiary" /></td>
          <td className="px-4 py-3"><div className="h-4 w-40 rounded bg-bg-tertiary" /></td>
          <td className="px-4 py-3"><div className="h-4 w-20 rounded bg-bg-tertiary" /></td>
          <td className="px-4 py-3"><div className="h-4 w-14 rounded bg-bg-tertiary" /></td>
          <td className="px-4 py-3"><div className="h-4 w-24 rounded bg-bg-tertiary" /></td>
          <td className="px-4 py-3"><div className="h-4 w-16 rounded bg-bg-tertiary" /></td>
        </tr>
      ))}
    </tbody>
  )
}

// ── Modal Convidar ────────────────────────────────────────────────────────────

type InviteState = 'form' | 'loading' | 'done'

function ModalConvidar({ onClose, onInvited }: { onClose: () => void; onInvited: () => void }) {
  const ref = useRef<HTMLDivElement>(null)
  useModalA11y(ref as React.RefObject<HTMLElement>, onClose)

  const titleId = useId()
  const [email, setEmail]     = useState('')
  const [role,  setRole]      = useState<Role>('CASHIER')
  const [state, setState]     = useState<InviteState>('form')
  const [error, setError]     = useState<string | null>(null)
  const [link,  setLink]      = useState('')
  const [copied, setCopied]   = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setState('loading')
    setError(null)
    try {
      const res = await api.post<InviteResponse>('/users/invite', { email: email.trim(), role })
      setLink(res.inviteLink)
      setState('done')
      onInvited()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Erro ao convidar usuario.')
      setState('form')
    }
  }

  async function copyLink() {
    await navigator.clipboard.writeText(link)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="w-full max-w-md rounded-2xl bg-bg-primary p-6 shadow-xl"
      >
        <h2 id={titleId} className="mb-5 text-lg font-bold text-text-primary">
          Convidar usuario
        </h2>

        {state !== 'done' ? (
          <form onSubmit={(e) => void submit(e)} className="space-y-4" noValidate>
            <div>
              <label htmlFor="invite-email" className="form-label">E-mail</label>
              <input
                id="invite-email"
                type="email"
                className="input-field w-full"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="colaborador@restaurante.com"
                autoComplete="email"
                required
                disabled={state === 'loading'}
                aria-required="true"
              />
            </div>

            <div>
              <label htmlFor="invite-role" className="form-label">Papel</label>
              <select
                id="invite-role"
                className="input-field w-full"
                value={role}
                onChange={(e) => setRole(e.target.value as Role)}
                disabled={state === 'loading'}
              >
                {ROLES.map((r) => (
                  <option key={r} value={r}>{r} — {ROLE_LABELS[r]}</option>
                ))}
              </select>
              <p className="mt-1 text-xs text-text-muted">{ROLE_DESC[role]}</p>
            </div>

            {error && (
              <p role="alert" className="form-error">{error}</p>
            )}

            <div className="flex gap-3 pt-1">
              <button type="button" className="btn-outline flex-1" onClick={onClose} disabled={state === 'loading'}>
                Cancelar
              </button>
              <button
                type="submit"
                className="btn-primary flex flex-1 items-center justify-center gap-2"
                disabled={state === 'loading' || !email}
              >
                <UserPlus className="h-4 w-4" aria-hidden="true" />
                {state === 'loading' ? 'Convidando...' : 'Convidar'}
              </button>
            </div>
          </form>
        ) : (
          <div className="space-y-4">
            <div className="flex items-center gap-2 rounded-lg bg-success/10 px-4 py-3 text-sm text-success">
              <CheckCircle className="h-4 w-4 shrink-0" aria-hidden="true" />
              Convite gerado. Compartilhe o link abaixo com o usuario.
            </div>
            <div>
              <p className="form-label mb-1">Link do convite</p>
              <div className="flex gap-2">
                <input
                  readOnly
                  value={link}
                  className="input-field min-w-0 flex-1 text-xs"
                  aria-label="Link do convite"
                  onClick={(e) => (e.target as HTMLInputElement).select()}
                />
                <button
                  type="button"
                  onClick={() => void copyLink()}
                  className="btn-outline inline-flex shrink-0 items-center gap-1.5"
                >
                  <Copy className="h-4 w-4" aria-hidden="true" />
                  {copied ? 'Copiado!' : 'Copiar'}
                </button>
              </div>
            </div>
            <button type="button" className="btn-primary w-full" onClick={onClose}>
              Fechar
            </button>
          </div>
        )}
      </div>
    </div>
  )
}

// ── Modal Editar Papel ────────────────────────────────────────────────────────

function ModalEditarPapel({
  user,
  onClose,
  onSaved,
  isOnlyAdmin,
}: {
  user: UserResponse
  onClose: () => void
  onSaved: () => void
  isOnlyAdmin: boolean
}) {
  const ref = useRef<HTMLDivElement>(null)
  useModalA11y(ref as React.RefObject<HTMLElement>, onClose)

  const titleId  = useId()
  const [role, setRole] = useState<Role>(
    ROLES.includes(user.role as Role) ? (user.role as Role) : 'STAFF',
  )
  const [saving, setSaving] = useState(false)
  const [error,  setError]  = useState<string | null>(null)

  const isDowngrade = user.role === 'ADMIN' && role !== 'ADMIN'

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (isOnlyAdmin && isDowngrade) {
      setError('Nao e possivel rebaixar o unico administrador ativo.')
      return
    }
    setSaving(true)
    setError(null)
    try {
      await api.patch(`/users/${user.id}/role`, { role })
      onSaved()
      onClose()
    } catch (err) {
      setError(
        err instanceof ApiError
          ? err.status === 409
            ? 'Nao e possivel rebaixar o unico administrador ativo.'
            : err.message
          : 'Erro ao alterar papel.',
      )
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="w-full max-w-sm rounded-2xl bg-bg-primary p-6 shadow-xl"
      >
        <h2 id={titleId} className="mb-1 text-lg font-bold text-text-primary">Editar papel</h2>
        <p className="mb-5 text-sm text-text-secondary">
          {fullName(user)} &middot; {user.email}
        </p>

        <form onSubmit={(e) => void submit(e)} className="space-y-4" noValidate>
          <div>
            <label htmlFor="edit-role" className="form-label">Novo papel</label>
            <select
              id="edit-role"
              className="input-field w-full"
              value={role}
              onChange={(e) => setRole(e.target.value as Role)}
              disabled={saving}
            >
              {ROLES.map((r) => (
                <option key={r} value={r}>{r} — {ROLE_LABELS[r]}</option>
              ))}
            </select>
            <p className="mt-1 text-xs text-text-muted">{ROLE_DESC[role]}</p>
          </div>

          {isOnlyAdmin && isDowngrade && (
            <p role="alert" className="form-error">
              Nao e possivel rebaixar o unico administrador ativo.
            </p>
          )}

          {error && !isOnlyAdmin && (
            <p role="alert" className="form-error">{error}</p>
          )}

          <div className="flex gap-3 pt-1">
            <button type="button" className="btn-outline flex-1" onClick={onClose} disabled={saving}>
              Cancelar
            </button>
            <button
              type="submit"
              className="btn-primary flex-1"
              disabled={saving || role === user.role || (isOnlyAdmin && isDowngrade)}
            >
              {saving ? 'Salvando...' : 'Salvar'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

// ── Pagina principal ──────────────────────────────────────────────────────────

type Notice = { type: 'success' | 'error'; message: string } | null

function pageContent<T>(value: PageOrList<T>): T[] {
  return Array.isArray(value) ? value : value.content
}

function UserMobileCard({
  user,
  isMe,
  isAdmin,
  onlyAdmin,
  toggling,
  onEdit,
  onToggle,
}: {
  user: UserResponse
  isMe: boolean
  isAdmin: boolean
  onlyAdmin: boolean
  toggling: boolean
  onEdit: () => void
  onToggle: () => void
}) {
  const disableToggle = onlyAdmin && user.isActive

  return (
    <article className="rounded-lg border border-border-light bg-bg-primary p-4">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 className="truncate text-sm font-semibold text-text-primary">
            {fullName(user)}
            {isMe && <span className="ml-1 text-sm font-normal text-text-muted">(voce)</span>}
          </h3>
          <p className="truncate text-sm text-text-muted">{user.email}</p>
        </div>
        <div className="shrink-0">{roleBadge(user.role)}</div>
      </div>

      <dl className="mt-4 grid gap-3 text-sm">
        <div className="flex items-center justify-between gap-3">
          <dt className="text-sm font-semibold uppercase text-text-muted">Status</dt>
          <dd>
            {user.isActive ? (
              <span className="inline-flex items-center gap-1 text-success">
                <CheckCircle className="h-3.5 w-3.5" aria-hidden="true" />
                Ativo
              </span>
            ) : (
              <span className="inline-flex items-center gap-1 text-text-muted">
                <XCircle className="h-3.5 w-3.5" aria-hidden="true" />
                Inativo
              </span>
            )}
          </dd>
        </div>
        <div className="flex items-center justify-between gap-3">
          <dt className="text-sm font-semibold uppercase text-text-muted">Ultimo acesso</dt>
          <dd className="text-right text-text-secondary">{formatDate(user.lastLoginAt)}</dd>
        </div>
      </dl>

      {isAdmin && (
        <div className="mt-4 flex items-center justify-between gap-3">
          <button
            className="btn-outline inline-flex min-h-11 flex-1 items-center justify-center gap-2 text-sm"
            onClick={onEdit}
          >
            <Pencil className="h-4 w-4" aria-hidden="true" />
            Editar papel
          </button>
          <button
            className={[
              'relative inline-flex h-11 w-16 shrink-0 items-center rounded-full px-1 transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary-700',
              user.isActive ? 'bg-primary-700' : 'bg-bg-tertiary',
              disableToggle ? 'cursor-not-allowed opacity-40' : '',
            ].join(' ')}
            aria-checked={user.isActive}
            aria-label={
              disableToggle
                ? 'Unico administrador — nao pode ser inativado'
                : user.isActive
                ? `Inativar ${fullName(user)}`
                : `Ativar ${fullName(user)}`
            }
            role="switch"
            disabled={disableToggle || toggling}
            onClick={onToggle}
            title={disableToggle ? 'Unico administrador' : undefined}
          >
            <span
              className={[
                'inline-block h-8 w-8 transform rounded-full bg-white shadow transition-transform',
                user.isActive ? 'translate-x-6' : 'translate-x-0',
              ].join(' ')}
              aria-hidden="true"
            />
          </button>
        </div>
      )}
    </article>
  )
}

export default function UsuariosPage() {
  const router = useRouter()

  const [users,        setUsers]        = useState<UserResponse[]>([])
  const [invites,      setInvites]      = useState<InviteResponse[]>([])
  const [loadingUsers, setLoadingUsers] = useState(true)
  const [loadingInvites, setLoadingInvites] = useState(true)
  const [notice,       setNotice]       = useState<Notice>(null)
  const [togglingId,   setTogglingId]   = useState<string | null>(null)
  const [revokingId,   setRevokingId]   = useState<string | null>(null)
  const [copied,       setCopied]       = useState<string | null>(null)
  const [showInviteModal,  setShowInviteModal]  = useState(false)
  const [editTarget,       setEditTarget]       = useState<UserResponse | null>(null)
  const [userError,        setUserError]        = useState<string | null>(null)
  const [inviteError,      setInviteError]      = useState<string | null>(null)
  const [auth,             setAuth]             = useState({
    ready: false,
    hasToken: false,
    role: null as string | null,
    userId: null as string | null,
  })

  const myRole   = auth.role
  const myUserId = auth.userId
  const isAdmin  = myRole === 'ADMIN'

  const activeAdmins = users.filter((u) => u.role === 'ADMIN' && u.isActive)
  const isOnlyAdmin  = (u: UserResponse) => u.role === 'ADMIN' && activeAdmins.length <= 1

  const loadUsers = useCallback(async () => {
    setLoadingUsers(true)
    setUserError(null)
    try {
      const res = await api.get<PageOrList<UserResponse>>('/users?size=200')
      setUsers(pageContent(res))
    } catch (err) {
      setUserError(err instanceof ApiError ? err.message : 'Erro ao carregar usuarios.')
    } finally {
      setLoadingUsers(false)
    }
  }, [])

  const loadInvites = useCallback(async () => {
    setLoadingInvites(true)
    setInviteError(null)
    try {
      const res = await api.get<PageOrList<InviteResponse>>('/invitations?size=100')
      setInvites(pageContent(res).filter((i) => i.status === 'PENDING'))
    } catch (err) {
      setInviteError(err instanceof ApiError ? err.message : 'Erro ao carregar convites.')
    } finally {
      setLoadingInvites(false)
    }
  }, [])

  useEffect(() => {
    queueMicrotask(() => {
      const token = getToken()
      if (!token) {
        router.replace('/login')
        return
      }
      setAuth({
        ready: true,
        hasToken: true,
        role: decodeRole(token),
        userId: decodeUserId(token),
      })
    })
  }, [router])

  useEffect(() => {
    if (!auth.ready || !auth.hasToken) return
    queueMicrotask(() => {
      void loadUsers()
      if (auth.role === 'ADMIN') void loadInvites()
      else setLoadingInvites(false)
    })
  }, [auth.ready, auth.hasToken, auth.role, loadUsers, loadInvites])

  async function toggleStatus(user: UserResponse) {
    if (isOnlyAdmin(user) && user.isActive) return
    setTogglingId(user.id)
    setNotice(null)
    try {
      await api.patch(`/users/${user.id}/status`, { active: !user.isActive })
      setNotice({ type: 'success', message: `Usuario ${user.isActive ? 'inativado' : 'ativado'}.` })
      await loadUsers()
    } catch (err) {
      setNotice({ type: 'error', message: err instanceof ApiError ? err.message : 'Erro ao alterar status.' })
    } finally {
      setTogglingId(null)
    }
  }

  async function revokeInvite(id: string) {
    setRevokingId(id)
    setNotice(null)
    try {
      await api.post(`/invitations/${id}/revoke`, {})
      setNotice({ type: 'success', message: 'Convite revogado.' })
      await loadInvites()
    } catch (err) {
      setNotice({ type: 'error', message: err instanceof ApiError ? err.message : 'Erro ao revogar convite.' })
    } finally {
      setRevokingId(null)
    }
  }

  async function copyInviteLink(id: string, link: string) {
    await navigator.clipboard.writeText(link)
    setCopied(id)
    setTimeout(() => setCopied(null), 2000)
  }

  return (
    <div className="min-h-screen bg-bg-secondary">
      <main className="mx-auto flex w-full max-w-5xl flex-col gap-5 px-4 py-6">

        {/* Cabecalho */}
        <header className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h1 className="text-2xl font-bold text-text-primary">Usuarios</h1>
            <p className="mt-1 text-sm text-text-secondary">
              Gerencie os colaboradores e acessos do restaurante.
            </p>
          </div>
          {isAdmin && (
            <button
              className="btn-primary inline-flex items-center gap-2"
              onClick={() => setShowInviteModal(true)}
            >
              <UserPlus className="h-4 w-4" aria-hidden="true" />
              Convidar usuario
            </button>
          )}
        </header>

        {/* Banner de aviso */}
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

        {/* Tabela de usuarios */}
        <section className="overflow-hidden rounded-lg bg-bg-primary shadow-card">
          <div className="flex items-center gap-2 border-b border-border-light px-4 py-3">
            <Users className="h-4 w-4 text-text-muted" aria-hidden="true" />
            <h2 className="text-sm font-semibold text-text-primary">Colaboradores</h2>
          </div>

          {userError ? (
            <div role="alert" className="flex flex-col items-center gap-2 px-4 py-10 text-center">
              <XCircle className="h-8 w-8 text-error" aria-hidden="true" />
              <p className="text-sm text-error">{userError}</p>
              <button className="btn-outline mt-2" onClick={() => void loadUsers()}>Tentar novamente</button>
            </div>
          ) : (
            <>
              <div className="grid gap-3 p-4 lg:hidden">
                {loadingUsers ? (
                  Array.from({ length: 3 }).map((_, i) => (
                    <div key={i} className="h-40 animate-pulse rounded-lg bg-bg-tertiary" />
                  ))
                ) : users.length === 0 ? (
                  <p className="py-8 text-center text-sm text-text-muted">
                    Nenhum usuario cadastrado.
                  </p>
                ) : (
                  users.map((user) => {
                    const onlyAdmin = isOnlyAdmin(user)
                    const isMe = user.id === myUserId
                    const toggling = togglingId === user.id
                    return (
                      <UserMobileCard
                        key={user.id}
                        user={user}
                        isMe={isMe}
                        isAdmin={isAdmin}
                        onlyAdmin={onlyAdmin}
                        toggling={toggling}
                        onEdit={() => setEditTarget(user)}
                        onToggle={() => void toggleStatus(user)}
                      />
                    )
                  })
                )}
              </div>

              <div className="hidden overflow-x-auto lg:block">
                <table className="min-w-full text-sm" aria-label="Lista de usuarios">
                <thead className="bg-bg-secondary text-left text-xs uppercase text-text-muted">
                  <tr>
                    <th scope="col" className="px-4 py-3">Nome</th>
                    <th scope="col" className="px-4 py-3">E-mail</th>
                    <th scope="col" className="px-4 py-3">Papel</th>
                    <th scope="col" className="px-4 py-3">Status</th>
                    <th scope="col" className="px-4 py-3">Ultimo acesso</th>
                    {isAdmin && <th scope="col" className="px-4 py-3 text-right">Acoes</th>}
                  </tr>
                </thead>
                {loadingUsers ? (
                  <SkeletonRows rows={5} />
                ) : users.length === 0 ? (
                  <tbody>
                    <tr>
                      <td colSpan={isAdmin ? 6 : 5} className="px-4 py-10 text-center text-sm text-text-muted">
                        Nenhum usuario cadastrado.
                      </td>
                    </tr>
                  </tbody>
                ) : (
                  <tbody className="divide-y divide-border-light">
                    {users.map((user) => {
                      const onlyAdmin   = isOnlyAdmin(user)
                      const isMe        = user.id === myUserId
                      const disableToggle = onlyAdmin && user.isActive
                      const toggling    = togglingId === user.id
                      return (
                        <tr key={user.id} className="hover:bg-bg-secondary/70">
                          <td className="px-4 py-3 font-medium text-text-primary">
                            {fullName(user)}
                            {isMe && (
                              <span className="ml-2 text-xs text-text-muted">(voce)</span>
                            )}
                          </td>
                          <td className="px-4 py-3 text-text-secondary">{user.email}</td>
                          <td className="px-4 py-3">{roleBadge(user.role)}</td>
                          <td className="px-4 py-3">
                            {user.isActive ? (
                              <span className="inline-flex items-center gap-1 text-success">
                                <CheckCircle className="h-3.5 w-3.5" aria-hidden="true" />
                                Ativo
                              </span>
                            ) : (
                              <span className="inline-flex items-center gap-1 text-text-muted">
                                <XCircle className="h-3.5 w-3.5" aria-hidden="true" />
                                Inativo
                              </span>
                            )}
                          </td>
                          <td className="px-4 py-3 text-text-secondary">
                            {formatDate(user.lastLoginAt)}
                          </td>
                          {isAdmin && (
                            <td className="px-4 py-3 text-right">
                              <div className="inline-flex items-center gap-2">
                                {/* Editar papel */}
                                <button
                                  className="icon-button"
                                  aria-label={`Editar papel de ${fullName(user)}`}
                                  onClick={() => setEditTarget(user)}
                                >
                                  <Pencil className="h-4 w-4" aria-hidden="true" />
                                </button>
                                {/* Toggle ativo/inativo */}
                                <button
	                                  className={[
	                                    'relative inline-flex h-11 w-16 items-center rounded-full px-1 transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary-700',
	                                    user.isActive ? 'bg-primary-700' : 'bg-bg-tertiary',
	                                    disableToggle ? 'cursor-not-allowed opacity-40' : '',
                                  ].join(' ')}
                                  aria-checked={user.isActive}
                                  aria-label={
                                    disableToggle
                                      ? `Unico administrador — nao pode ser inativado`
                                      : user.isActive
                                      ? `Inativar ${fullName(user)}`
                                      : `Ativar ${fullName(user)}`
                                  }
                                  role="switch"
                                  disabled={disableToggle || toggling}
                                  onClick={() => void toggleStatus(user)}
                                  title={disableToggle ? 'Unico administrador' : undefined}
                                >
	                                  <span
	                                    className={[
	                                      'inline-block h-8 w-8 transform rounded-full bg-white shadow transition-transform',
	                                      user.isActive ? 'translate-x-6' : 'translate-x-0',
	                                    ].join(' ')}
                                    aria-hidden="true"
                                  />
                                </button>
                              </div>
                            </td>
                          )}
                        </tr>
                      )
                    })}
                  </tbody>
                )}
                </table>
              </div>
            </>
          )}
        </section>

        {/* Secao de convites (ADMIN only) */}
        {isAdmin && (
          <section className="overflow-hidden rounded-lg bg-bg-primary shadow-card">
            <div className="border-b border-border-light px-4 py-3">
              <h2 className="text-sm font-semibold text-text-primary">Convites pendentes</h2>
            </div>

            {inviteError ? (
              <div role="alert" className="flex flex-col items-center gap-2 px-4 py-8 text-center">
                <XCircle className="h-7 w-7 text-error" aria-hidden="true" />
                <p className="text-sm text-error">{inviteError}</p>
                <button className="btn-outline mt-2" onClick={() => void loadInvites()}>Tentar novamente</button>
              </div>
            ) : loadingInvites ? (
              <div className="space-y-2 p-4">
                {Array.from({ length: 3 }).map((_, i) => (
                  <div key={i} className="h-10 animate-pulse rounded bg-bg-tertiary" />
                ))}
              </div>
            ) : invites.length === 0 ? (
              <p className="px-4 py-8 text-center text-sm text-text-muted">
                Nenhum convite pendente.
              </p>
            ) : (
              <ul role="list" className="divide-y divide-border-light">
                {invites.map((invite) => (
                  <li key={invite.id} className="flex flex-col gap-2 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
                    <div className="min-w-0">
                      <p className="truncate text-sm font-medium text-text-primary">{invite.email}</p>
                      <p className="text-xs text-text-muted">
                        {roleBadge(invite.role)}
                        <span className="ml-2">Expira em {hoursUntil(invite.expiresAt)}</span>
                      </p>
                    </div>
                    <div className="flex shrink-0 gap-2">
                      <button
                        className="btn-outline inline-flex items-center gap-1.5 text-xs"
                        onClick={() => void copyInviteLink(invite.id, invite.inviteLink)}
                      >
                        <Copy className="h-3.5 w-3.5" aria-hidden="true" />
                        {copied === invite.id ? 'Copiado!' : 'Copiar link'}
                      </button>
                      <button
                        className="inline-flex items-center gap-1.5 rounded-lg border border-error px-3 py-1.5 text-xs font-medium text-error transition-colors hover:bg-error/10 disabled:opacity-50"
                        onClick={() => void revokeInvite(invite.id)}
                        disabled={revokingId === invite.id}
                        aria-label={`Revogar convite de ${invite.email}`}
                      >
                        {revokingId === invite.id ? 'Revogando...' : 'Revogar'}
                      </button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>
        )}
      </main>

      {/* Modais */}
      {showInviteModal && (
        <ModalConvidar
          onClose={() => setShowInviteModal(false)}
          onInvited={() => void loadInvites()}
        />
      )}

      {editTarget && (
        <ModalEditarPapel
          user={editTarget}
          isOnlyAdmin={isOnlyAdmin(editTarget)}
          onClose={() => setEditTarget(null)}
          onSaved={() => void loadUsers()}
        />
      )}
    </div>
  )
}
