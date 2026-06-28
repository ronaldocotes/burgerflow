'use client'

import { FormEvent, useState } from 'react'
import { useSearchParams, useRouter } from 'next/navigation'
import { Eye, EyeOff, UtensilsCrossed, CheckCircle, XCircle } from 'lucide-react'
import { api, ApiError } from '@/lib/api'

type ViewState = 'form' | 'loading' | 'success' | 'invalid'

export function AceitarConviteContent() {
  const params   = useSearchParams()
  const router   = useRouter()
  const token    = params.get('token')

  const [firstName,        setFirstName]        = useState('')
  const [lastName,         setLastName]         = useState('')
  const [password,         setPassword]         = useState('')
  const [confirmPassword,  setConfirmPassword]  = useState('')
  const [showPwd,          setShowPwd]          = useState(false)
  const [showConfirm,      setShowConfirm]      = useState(false)
  const [view,             setView]             = useState<ViewState>(token ? 'form' : 'invalid')
  const [error,            setError]            = useState<string | null>(null)

  if (view === 'invalid') {
    return (
      <div className="flex flex-col items-center gap-3 text-center">
        <XCircle className="h-12 w-12 text-error" aria-hidden="true" />
        <h1 className="text-xl font-bold text-text-primary">Link invalido</h1>
        <p className="text-sm text-text-secondary">
          Este link de convite e invalido, expirou ou ja foi utilizado.
        </p>
        <button className="btn-primary mt-2" onClick={() => router.push('/login')}>
          Ir para o login
        </button>
      </div>
    )
  }

  if (view === 'success') {
    return (
      <div className="flex flex-col items-center gap-3 text-center">
        <CheckCircle className="h-12 w-12 text-success" aria-hidden="true" />
        <h1 className="text-xl font-bold text-text-primary">Conta ativada!</h1>
        <p className="text-sm text-text-secondary">
          Sua conta foi criada com sucesso. Faca login para continuar.
        </p>
        <button className="btn-primary mt-2" onClick={() => router.push('/login')}>
          Fazer login
        </button>
      </div>
    )
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    if (password.length < 8) {
      setError('A senha deve ter pelo menos 8 caracteres.')
      return
    }
    if (password !== confirmPassword) {
      setError('As senhas nao coincidem.')
      return
    }
    setView('loading')
    try {
      await api.post('/auth/accept-invite', {
        token,
        firstName: firstName.trim(),
        lastName: lastName.trim(),
        password,
      })
      setView('success')
    } catch (err) {
      const isExpired =
        err instanceof ApiError && (err.status === 409 || err.status === 404)
      setError(
        isExpired
          ? 'Este link expirou ou ja foi utilizado.'
          : err instanceof ApiError
          ? err.message
          : 'Erro ao ativar conta. Tente novamente.',
      )
      setView('form')
    }
  }

  const loading   = view === 'loading'
  const canSubmit = !loading && !!firstName && !!lastName && !!password && !!confirmPassword

  return (
    <>
      <h1 className="mb-1 text-2xl font-bold text-text-primary">Ative sua conta</h1>
      <p className="mb-8 text-sm text-text-muted">Preencha os dados para criar seu acesso.</p>

      <form onSubmit={(e) => void onSubmit(e)} noValidate className="space-y-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <div>
            <label htmlFor="ac-firstname" className="form-label">Nome</label>
            <input
              id="ac-firstname"
              className="input-field w-full"
              value={firstName}
              onChange={(e) => setFirstName(e.target.value)}
              autoComplete="given-name"
              disabled={loading}
              required
              aria-required="true"
            />
          </div>
          <div>
            <label htmlFor="ac-lastname" className="form-label">Sobrenome</label>
            <input
              id="ac-lastname"
              className="input-field w-full"
              value={lastName}
              onChange={(e) => setLastName(e.target.value)}
              autoComplete="family-name"
              disabled={loading}
              required
              aria-required="true"
            />
          </div>
        </div>

        <div>
          <label htmlFor="ac-password" className="form-label">Nova senha</label>
          <div className="relative">
            <input
              id="ac-password"
              type={showPwd ? 'text' : 'password'}
              className="input-field input-with-trailing-action w-full"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="new-password"
              minLength={8}
              disabled={loading}
              required
              aria-required="true"
              aria-describedby="ac-pwd-hint"
            />
            <button
              type="button"
              onClick={() => setShowPwd((v) => !v)}
              disabled={loading}
              aria-label={showPwd ? 'Ocultar senha' : 'Mostrar senha'}
              className="absolute right-2 top-1/2 flex h-9 w-9 -translate-y-1/2 items-center justify-center rounded text-text-muted hover:text-text-secondary"
            >
              {showPwd ? <EyeOff className="h-4 w-4" aria-hidden="true" /> : <Eye className="h-4 w-4" aria-hidden="true" />}
            </button>
          </div>
          <p id="ac-pwd-hint" className="mt-1 text-xs text-text-muted">Minimo de 8 caracteres.</p>
        </div>

        <div>
          <label htmlFor="ac-confirm" className="form-label">Confirmar senha</label>
          <div className="relative">
            <input
              id="ac-confirm"
              type={showConfirm ? 'text' : 'password'}
              className="input-field input-with-trailing-action w-full"
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              autoComplete="new-password"
              disabled={loading}
              required
              aria-required="true"
            />
            <button
              type="button"
              onClick={() => setShowConfirm((v) => !v)}
              disabled={loading}
              aria-label={showConfirm ? 'Ocultar confirmacao' : 'Mostrar confirmacao'}
              className="absolute right-2 top-1/2 flex h-9 w-9 -translate-y-1/2 items-center justify-center rounded text-text-muted hover:text-text-secondary"
            >
              {showConfirm ? <EyeOff className="h-4 w-4" aria-hidden="true" /> : <Eye className="h-4 w-4" aria-hidden="true" />}
            </button>
          </div>
        </div>

        {error && (
          <p role="alert" className="form-error">{error}</p>
        )}

        <button
          type="submit"
          className="btn-primary w-full py-3 text-base font-semibold"
          disabled={!canSubmit}
        >
          {loading ? 'Ativando...' : 'Ativar conta'}
        </button>
      </form>
    </>
  )
}
