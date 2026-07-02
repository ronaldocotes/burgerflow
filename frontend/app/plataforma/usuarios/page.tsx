'use client'

// Usuários da plataforma — SUPER_ADMINs (Fase 3).
// Convite por e-mail, revogação com confirmação (nunca a si mesmo) e
// badge de 2FA (obrigatório para SUPER_ADMIN — "Pendente" quando falta).

import { FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import {
  Users,
  UserPlus,
  Trash2,
  RefreshCw,
  AlertTriangle,
  ShieldCheck,
  ShieldAlert,
  X,
} from 'lucide-react'
import { api, ApiError } from '@/lib/api'
import { getToken } from '@/lib/auth'
import { useSuperAdminGuard } from '@/lib/use-super-admin-guard'
import { useModalA11y } from '@/lib/use-modal-a11y'
import { type PlatformUser, formatDate, formatDateTime } from '@/lib/platform'

// Identifica o usuário logado pelo JWT (sub = id; email quando presente)
// para desabilitar a revogação do próprio acesso.
function getCurrentIdentity(): { id: string; email: string } {
  try {
    const token = getToken()
    if (!token) return { id: '', email: '' }
    const parts = token.split('.')
    if (parts.length !== 3) return { id: '', email: '' }
    const b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const payload = JSON.parse(atob(b64)) as { sub?: string; email?: string; userId?: string }
    return {
      id: payload.userId ?? payload.sub ?? '',
      email: (payload.email ?? '').toLowerCase(),
    }
  } catch {
    return { id: '', email: '' }
  }
}

function TwoFaBadge({ has2FA }: { has2FA: boolean }) {
  if (has2FA) {
    return (
      <span className="inline-flex items-center gap-1 rounded-full bg-success-light px-2.5 py-0.5 text-xs font-semibold text-success-dark">
        <ShieldCheck className="h-3.5 w-3.5" aria-hidden="true" />
        Ativo
      </span>
    )
  }
  return (
    <span
      title="2FA é obrigatório para SUPER_ADMIN"
      className="inline-flex items-center gap-1 rounded-full bg-warning-light px-2.5 py-0.5 text-xs font-semibold text-warning-dark"
    >
      <ShieldAlert className="h-3.5 w-3.5" aria-hidden="true" />
      Obrigatório / Pendente
    </span>
  )
}

// ── Modal de convite ─────────────────────────────────────────────────────────

function InviteModal({
  onClose,
  onInvited,
}: {
  onClose: () => void
  onInvited: () => void
}) {
  const ref = useRef<HTMLDivElement>(null)
  useModalA11y(ref as React.RefObject<HTMLElement>, onClose)

  const [email, setEmail] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    const trimmed = email.trim()
    if (!trimmed) return
    setSaving(true)
    setError(null)
    try {
      await api.post('/admin/platform-users/invite', { email: trimmed })
      onInvited()
      onClose()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Erro ao enviar o convite.')
      setSaving(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/40" aria-hidden="true" onClick={onClose} />
      <div
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-labelledby="invite-title"
        className="relative w-full max-w-md rounded-xl border border-border-light bg-bg-primary p-5 shadow-xl"
      >
        <div className="mb-4 flex items-start justify-between gap-2">
          <h2 id="invite-title" className="text-lg font-bold text-text-primary">
            Convidar Super-Admin
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Fechar"
            className="flex h-11 w-11 shrink-0 items-center justify-center rounded-lg text-text-secondary hover:bg-bg-tertiary"
          >
            <X className="h-5 w-5" aria-hidden="true" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label htmlFor="invite-email" className="mb-1 block text-sm font-medium text-text-secondary">
              E-mail
            </label>
            <input
              id="invite-email"
              type="email"
              required
              autoComplete="off"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="pessoa@empresa.com"
              className="input-field w-full"
            />
            <p className="mt-1 text-xs text-text-muted">
              A pessoa recebe um convite por e-mail e precisa ativar 2FA no primeiro acesso.
            </p>
          </div>

          {error && (
            <p role="alert" className="inline-flex items-center gap-1.5 text-sm font-medium text-error-dark">
              <AlertTriangle className="h-4 w-4 shrink-0" aria-hidden="true" />
              {error}
            </p>
          )}

          <div className="flex justify-end gap-2">
            <button type="button" onClick={onClose} className="btn-outline" disabled={saving}>
              Cancelar
            </button>
            <button type="submit" className="btn-primary" disabled={saving || !email.trim()}>
              {saving ? 'Enviando...' : 'Enviar convite'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

// ── Modal de revogação ───────────────────────────────────────────────────────

function RevokeModal({
  user,
  onClose,
  onRevoked,
}: {
  user: PlatformUser
  onClose: () => void
  onRevoked: () => void
}) {
  const ref = useRef<HTMLDivElement>(null)
  useModalA11y(ref as React.RefObject<HTMLElement>, onClose)

  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleRevoke = async () => {
    setSaving(true)
    setError(null)
    try {
      await api.del(`/admin/platform-users/${user.id}`)
      onRevoked()
      onClose()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Erro ao revogar o acesso.')
      setSaving(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/40" aria-hidden="true" onClick={onClose} />
      <div
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-labelledby="revoke-title"
        className="relative w-full max-w-md rounded-xl border border-border-light bg-bg-primary p-5 shadow-xl"
      >
        <h2 id="revoke-title" className="text-lg font-bold text-text-primary">
          Revogar acesso
        </h2>
        <p className="mt-2 text-sm text-text-secondary">
          Revogar o acesso de <strong className="text-text-primary">{user.name}</strong> (
          {user.email})? A pessoa perde imediatamente o acesso ao painel da plataforma. Esta ação
          não pode ser desfeita.
        </p>

        {error && (
          <p role="alert" className="mt-3 inline-flex items-center gap-1.5 text-sm font-medium text-error-dark">
            <AlertTriangle className="h-4 w-4 shrink-0" aria-hidden="true" />
            {error}
          </p>
        )}

        <div className="mt-5 flex justify-end gap-2">
          <button type="button" onClick={onClose} className="btn-outline" disabled={saving}>
            Cancelar
          </button>
          <button
            type="button"
            onClick={() => void handleRevoke()}
            disabled={saving}
            className="inline-flex min-h-11 items-center gap-2 rounded-lg bg-error px-4 py-2 text-sm font-semibold text-white hover:opacity-90 disabled:opacity-50"
          >
            <Trash2 className="h-4 w-4" aria-hidden="true" />
            {saving ? 'Revogando...' : 'Revogar acesso'}
          </button>
        </div>
      </div>
    </div>
  )
}

// ── Skeleton ─────────────────────────────────────────────────────────────────

function TableSkeleton() {
  return (
    <div
      aria-busy="true"
      aria-label="Carregando usuários"
      className="animate-pulse rounded-xl border border-border-light bg-bg-primary p-4"
    >
      <div className="space-y-3">
        {Array.from({ length: 4 }).map((_, i) => (
          <div key={i} className="h-5 w-full rounded bg-bg-tertiary" />
        ))}
      </div>
    </div>
  )
}

// ── Página ───────────────────────────────────────────────────────────────────

export default function UsuariosPage() {
  useSuperAdminGuard()

  const [users, setUsers] = useState<PlatformUser[] | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [inviteOpen, setInviteOpen] = useState(false)
  const [revokeTarget, setRevokeTarget] = useState<PlatformUser | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [me, setMe] = useState({ id: '', email: '' })
  const abortRef = useRef<AbortController | null>(null)

  useEffect(() => {
    setMe(getCurrentIdentity())
  }, [])

  const load = useCallback(async () => {
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller

    setLoading(true)
    setError(null)
    try {
      const res = await api.get<PlatformUser[]>('/admin/platform-users', controller.signal)
      setUsers(res)
    } catch (err) {
      if (controller.signal.aborted) return
      setUsers(null)
      setError(err instanceof ApiError ? err.message : 'Erro ao listar os usuários da plataforma.')
    } finally {
      if (!controller.signal.aborted) setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
    return () => abortRef.current?.abort()
  }, [load])

  const isSelf = (u: PlatformUser) =>
    (me.id !== '' && u.id === me.id) || (me.email !== '' && u.email.toLowerCase() === me.email)

  return (
    <main className="mx-auto max-w-5xl p-4 sm:p-6">
      <div className="mb-6 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-text-primary">Usuários da plataforma</h1>
          <p className="mt-0.5 text-sm text-text-secondary">
            Super-admins com acesso a este painel. 2FA é obrigatório para todos.
          </p>
        </div>
        <button
          type="button"
          onClick={() => setInviteOpen(true)}
          className="btn-primary inline-flex items-center gap-2"
        >
          <UserPlus className="h-4 w-4" aria-hidden="true" />
          Convidar Super-Admin
        </button>
      </div>

      {notice && (
        <p
          role="status"
          className="mb-4 inline-flex items-center gap-1.5 rounded-lg bg-success-light px-3 py-2 text-sm font-medium text-success-dark"
        >
          <ShieldCheck className="h-4 w-4 shrink-0" aria-hidden="true" />
          {notice}
        </p>
      )}

      {loading ? (
        <TableSkeleton />
      ) : error ? (
        <div
          role="alert"
          className="flex flex-wrap items-center gap-3 rounded-xl border border-error/30 bg-error-light px-4 py-3"
        >
          <AlertTriangle className="h-5 w-5 shrink-0 text-error-dark" aria-hidden="true" />
          <p className="min-w-0 flex-1 text-sm font-medium text-error-dark">{error}</p>
          <button type="button" onClick={() => void load()} className="btn-outline inline-flex items-center gap-2">
            <RefreshCw className="h-4 w-4" aria-hidden="true" />
            Tentar novamente
          </button>
        </div>
      ) : !users || users.length === 0 ? (
        <div className="flex flex-col items-center gap-2 rounded-xl border border-border-light bg-bg-primary px-4 py-12 text-center">
          <Users className="h-8 w-8 text-text-muted" aria-hidden="true" />
          <p className="text-sm font-medium text-text-primary">Nenhum super-admin além de você</p>
          <p className="text-sm text-text-secondary">
            Use o botão &quot;Convidar Super-Admin&quot; para dar acesso a outra pessoa.
          </p>
        </div>
      ) : (
        <div className="overflow-x-auto rounded-xl border border-border-light bg-bg-primary">
          <table className="w-full min-w-[720px] text-sm">
            <caption className="sr-only">Super-admins da plataforma</caption>
            <thead>
              <tr className="border-b border-border-light text-left text-xs font-semibold uppercase tracking-wide text-text-secondary">
                <th scope="col" className="px-4 py-3">Nome</th>
                <th scope="col" className="px-4 py-3">E-mail</th>
                <th scope="col" className="px-4 py-3">Criado em</th>
                <th scope="col" className="px-4 py-3">Último login</th>
                <th scope="col" className="px-4 py-3">2FA</th>
                <th scope="col" className="px-4 py-3 text-right">Ações</th>
              </tr>
            </thead>
            <tbody>
              {users.map((u) => {
                const self = isSelf(u)
                return (
                  <tr key={u.id} className="border-b border-border-light last:border-b-0">
                    <td className="px-4 py-3 font-medium text-text-primary">
                      {u.name}
                      {self && (
                        <span className="ml-2 rounded-full bg-bg-tertiary px-2 py-0.5 text-[10px] font-semibold uppercase text-text-secondary">
                          Você
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-text-secondary">{u.email}</td>
                    <td className="px-4 py-3 text-text-secondary">{formatDate(u.createdAt)}</td>
                    <td className="px-4 py-3 text-text-secondary">{formatDateTime(u.lastLoginAt)}</td>
                    <td className="px-4 py-3"><TwoFaBadge has2FA={u.has2FA} /></td>
                    <td className="px-4 py-3 text-right">
                      <button
                        type="button"
                        onClick={() => setRevokeTarget(u)}
                        disabled={self}
                        title={self ? 'Você não pode revogar o próprio acesso' : `Revogar acesso de ${u.name}`}
                        className="inline-flex min-h-11 items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm font-medium text-error-dark hover:bg-error-light disabled:cursor-not-allowed disabled:text-text-muted disabled:hover:bg-transparent"
                      >
                        <Trash2 className="h-4 w-4" aria-hidden="true" />
                        Revogar
                      </button>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      {inviteOpen && (
        <InviteModal
          onClose={() => setInviteOpen(false)}
          onInvited={() => {
            setNotice('Convite enviado com sucesso.')
            void load()
          }}
        />
      )}

      {revokeTarget && (
        <RevokeModal
          user={revokeTarget}
          onClose={() => setRevokeTarget(null)}
          onRevoked={() => {
            setNotice('Acesso revogado.')
            void load()
          }}
        />
      )}
    </main>
  )
}
