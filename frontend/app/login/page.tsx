"use client";

import { FormEvent, useState } from "react";
import Image from "next/image";
import { useRouter } from "next/navigation";
import { Eye, EyeOff, UtensilsCrossed } from "lucide-react";
import { login } from "@/lib/auth";
import { ApiError } from "@/lib/api";
import { useRestaurantInfo } from "@/lib/use-restaurant-info";

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
            ? "E-mail, senha ou restaurante invalidos."
            : err.message
          : "Nao foi possivel entrar. Tente novamente.",
      );
    } finally {
      setLoading(false);
    }
  }

  const canSubmit = !loading && !!email && !!password && !!tenant;

  // Nome exibido: nome do tenant no campo (digitado) ou restaurantName da API
  const displayName = restaurantName ?? "MenuFlow";

  return (
    <div className="flex min-h-screen items-center justify-center bg-bg-secondary px-4">
      <div className="w-full max-w-sm animate-fade-in">
        <form
          onSubmit={onSubmit}
          noValidate
          className="rounded-2xl bg-bg-primary p-8 shadow-card"
        >
          {/* Brand: logo da empresa ou badge padrao */}
          <div className="mb-8 flex flex-col items-center gap-3">
            {logoUrl ? (
              <Image
                src={logoUrl}
                alt={displayName}
                width={56}
                height={56}
                className="rounded-2xl object-contain shadow-card"
              />
            ) : (
              <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-primary-700 text-white shadow-card">
                <UtensilsCrossed className="h-7 w-7" aria-hidden="true" />
              </div>
            )}
            <div className="text-center">
              <h1 className="text-2xl font-bold text-text-primary">
                {displayName}
              </h1>
              <p className="mt-0.5 text-sm text-text-muted">Painel de gestao</p>
            </div>
          </div>

          {/* Campo: Restaurante */}
          <div className="mb-4">
            <label htmlFor="tenant" className="form-label">
              Restaurante
            </label>
            <input
              id="tenant"
              className="input-field"
              value={tenant}
              onChange={(e) => setTenant(e.target.value)}
              placeholder="minha-hamburgueria"
              autoComplete="organization"
              autoFocus
              disabled={loading}
              aria-required="true"
              required
            />
            <p className="mt-1 text-xs text-text-muted">
              Identificador do seu restaurante no sistema.
            </p>
          </div>

          {/* Campo: E-mail */}
          <div className="mb-4">
            <label htmlFor="email" className="form-label">
              E-mail
            </label>
            <input
              id="email"
              type="email"
              className="input-field"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="voce@restaurante.com"
              autoComplete="email"
              disabled={loading}
              aria-required="true"
              required
            />
          </div>

          {/* Campo: Senha com toggle */}
          <div className="mb-6">
            <label htmlFor="password" className="form-label">
              Senha
            </label>
            <div className="relative">
              <input
                id="password"
                type={showPwd ? "text" : "password"}
                className="input-field pr-10"
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
                className="absolute right-3 top-1/2 -translate-y-1/2 rounded p-0.5 text-text-muted hover:text-text-secondary"
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
            <p className="form-error mb-4" role="alert">
              {error}
            </p>
          )}

          {/* Submit */}
          <button
            type="submit"
            className="btn-primary w-full"
            disabled={!canSubmit}
          >
            {loading ? "Entrando..." : "Entrar"}
          </button>
        </form>
      </div>
    </div>
  );
}
