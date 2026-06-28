"use client";

import { FormEvent, useState } from "react";
import Image from "next/image";
import { useRouter } from "next/navigation";
import { Eye, EyeOff, UtensilsCrossed, Building2, Mail, Lock, ArrowRight } from "lucide-react";
import { login } from "@/lib/auth";
import { ApiError } from "@/lib/api";
import { useRestaurantInfo } from "@/lib/use-restaurant-info";

// ── Painel esquerdo decorativo ─────────────────────────────────────────────────

function LeftPanel() {
  return (
    <div className="relative hidden lg:flex lg:w-5/12 flex-col overflow-hidden bg-primary-800">
      {/* Círculos decorativos */}
      <div className="pointer-events-none absolute inset-0" aria-hidden="true">
        <div className="absolute -top-24 -left-24 h-96 w-96 rounded-full bg-white/5" />
        <div className="absolute top-1/4 -right-16 h-72 w-72 rounded-full bg-white/5" />
        <div className="absolute bottom-1/4 left-1/4 h-48 w-48 rounded-full bg-white/5" />
        <div className="absolute -bottom-20 -right-8 h-80 w-80 rounded-full bg-white/5" />
        <div className="absolute top-1/2 left-8 h-32 w-32 rounded-full bg-white/5" />
      </div>

      <div className="relative flex flex-1 flex-col items-center justify-center px-12 text-center">
        {/* Ícone */}
        <div className="mb-6 flex h-20 w-20 items-center justify-center rounded-full border-4 border-white/20 bg-white/10">
          <UtensilsCrossed className="h-10 w-10 text-white" aria-hidden="true" />
        </div>

        <h1 className="mb-2 text-4xl font-extrabold tracking-tight text-white">
          MenuFlow
        </h1>
        <p className="mb-1 text-base font-medium text-primary-200">
          Sistema de Gestão para
        </p>
        <p className="text-base font-medium text-primary-200">Restaurantes</p>

        {/* Card missão */}
        <div className="mt-10 w-full rounded-2xl border border-white/10 bg-white/10 px-6 py-5 text-left backdrop-blur-sm">
          <p className="mb-1 text-xs font-semibold uppercase tracking-widest text-primary-300">
            Nossa solução
          </p>
          <p className="text-sm leading-relaxed text-white/90">
            Gerencie pedidos, cardápio e pagamentos de forma simples e integrada,
            em qualquer dispositivo.
          </p>
        </div>
      </div>

      <p className="relative pb-6 text-center text-xs text-white/40">
        MenuFlow · Sistema de Gestão · v1.0 · 2026
      </p>
    </div>
  );
}

// ── Página ─────────────────────────────────────────────────────────────────────

export default function LoginPage() {
  const router = useRouter();
  const { restaurantName, logoUrl } = useRestaurantInfo();
  const [tenant,   setTenant]   = useState("");
  const [email,    setEmail]    = useState("");
  const [password, setPassword] = useState("");
  const [showPwd,  setShowPwd]  = useState(false);
  const [loading,  setLoading]  = useState(false);
  const [error,    setError]    = useState<string | null>(null);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      await login(email.trim(), password, tenant.trim());
      router.push("/pdv");
    } catch (err) {
      setError(
        err instanceof ApiError
          ? err.status === 401
            ? "Restaurante, e-mail ou senha inválidos."
            : err.message
          : "Não foi possível entrar. Tente novamente.",
      );
    } finally {
      setLoading(false);
    }
  }

  const canSubmit = !loading && !!email && !!password && !!tenant;

  return (
    <div className="flex min-h-screen">
      <LeftPanel />

      {/* Painel direito — formulário */}
      <div className="flex flex-1 flex-col items-center justify-center bg-bg-secondary px-6 py-12">
        <div className="w-full max-w-sm">
          {/* Logo / badge */}
          <div className="mb-8 flex justify-center">
            {logoUrl ? (
              <Image
                src={logoUrl}
                alt={restaurantName ?? "MenuFlow"}
                width={56}
                height={56}
                className="rounded-2xl object-contain shadow-card"
              />
            ) : (
              <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-primary-700 text-white shadow-card">
                <UtensilsCrossed className="h-7 w-7" aria-hidden="true" />
              </div>
            )}
          </div>

          <h2 className="mb-1 text-2xl font-bold text-text-primary">
            Acesse sua conta
          </h2>
          <p className="mb-8 text-sm text-text-muted">
            Entre com suas credenciais de acesso
          </p>

          <form onSubmit={onSubmit} noValidate className="space-y-4">
            {/* Restaurante */}
            <div>
              <label htmlFor="tenant" className="form-label">
                Restaurante
              </label>
              <div className="relative">
                <Building2 className="pointer-events-none absolute left-4 top-1/2 h-4 w-4 -translate-y-1/2 text-text-muted" aria-hidden="true" />
                <input
                  id="tenant"
                  className="input-field input-with-leading-icon"
                  value={tenant}
                  onChange={(e) => setTenant(e.target.value)}
                  placeholder="minha-hamburgueria"
                  autoComplete="organization"
                  autoFocus
                  disabled={loading}
                  aria-required="true"
                  required
                />
              </div>
            </div>

            {/* E-mail */}
            <div>
              <label htmlFor="email" className="form-label">
                E-mail
              </label>
              <div className="relative">
                <Mail className="pointer-events-none absolute left-4 top-1/2 h-4 w-4 -translate-y-1/2 text-text-muted" aria-hidden="true" />
                <input
                  id="email"
                  type="email"
                  className="input-field input-with-leading-icon"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="voce@restaurante.com"
                  autoComplete="email"
                  disabled={loading}
                  aria-required="true"
                  required
                />
              </div>
            </div>

            {/* Senha */}
            <div>
              <label htmlFor="password" className="form-label">
                Senha
              </label>
              <div className="relative">
                <Lock className="pointer-events-none absolute left-4 top-1/2 h-4 w-4 -translate-y-1/2 text-text-muted" aria-hidden="true" />
                <input
                  id="password"
                  type={showPwd ? "text" : "password"}
                  className="input-field input-with-leading-icon input-with-trailing-action"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  autoComplete="current-password"
                  disabled={loading}
                  aria-required="true"
                  required
                />
                <button
                  type="button"
                  onClick={() => setShowPwd((v) => !v)}
                  disabled={loading}
                  aria-label={showPwd ? "Ocultar senha" : "Mostrar senha"}
                  className="absolute right-2 top-1/2 flex h-9 w-9 -translate-y-1/2 items-center justify-center rounded text-text-muted hover:text-text-secondary"
                >
                  {showPwd ? (
                    <EyeOff className="h-4 w-4" aria-hidden="true" />
                  ) : (
                    <Eye className="h-4 w-4" aria-hidden="true" />
                  )}
                </button>
              </div>
            </div>

            {/* Erro */}
            {error && (
              <p className="form-error" role="alert">
                {error}
              </p>
            )}

            {/* Submit */}
            <button
              type="submit"
              className="btn-primary flex w-full items-center justify-center gap-2 py-3 text-base font-semibold"
              disabled={!canSubmit}
            >
              {loading ? "Entrando..." : (
                <>Entrar <ArrowRight className="h-4 w-4" aria-hidden="true" /></>
              )}
            </button>
          </form>

          <p className="mt-8 text-center text-xs text-text-muted">
            MenuFlow · Sistema de Gestão · v1.0 · 2026
          </p>
        </div>
      </div>
    </div>
  );
}
