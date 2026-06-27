"use client";

import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import { login } from "@/lib/auth";
import { ApiError } from "@/lib/api";

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [tenant, setTenant] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      await login(email.trim(), password, tenant.trim());
      router.push("/cardapio");
    } catch (err) {
      setError(
        err instanceof ApiError
          ? err.status === 401
            ? "E-mail, senha ou restaurante inválidos."
            : err.message
          : "Não foi possível entrar. Tente novamente.",
      );
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-bg-secondary px-4">
      <div className="w-full max-w-sm bg-bg-primary rounded-xl shadow-card p-8 animate-fade-in">
        <div className="mb-6 text-center">
          <h1 className="text-2xl font-bold text-text-primary">
            <span aria-hidden="true">🍔</span> MenuFlow
          </h1>
          <p className="text-text-secondary text-sm mt-1">Entrar no painel</p>
        </div>

        <form onSubmit={onSubmit} noValidate>
          <div className="form-group">
            <label className="form-label" htmlFor="tenant">
              Restaurante
            </label>
            <input
              id="tenant"
              className="input-field"
              value={tenant}
              onChange={(e) => setTenant(e.target.value)}
              placeholder="ex.: minha-hamburgueria"
              autoComplete="organization"
              aria-required="true"
              required
            />
            <p className="text-xs text-text-muted mt-1">
              O identificador do seu restaurante no sistema.
            </p>
          </div>

          <div className="form-group">
            <label className="form-label" htmlFor="email">
              E-mail
            </label>
            <input
              id="email"
              type="email"
              className="input-field"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="você@restaurante.com"
              autoComplete="email"
              aria-required="true"
              required
            />
          </div>

          <div className="form-group">
            <label className="form-label" htmlFor="password">
              Senha
            </label>
            <input
              id="password"
              type="password"
              className="input-field"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
              aria-required="true"
              required
            />
          </div>

          {error && (
            <p className="form-error mb-3" role="alert">
              {error}
            </p>
          )}

          <button
            type="submit"
            className="btn-primary w-full"
            disabled={loading || !email || !password || !tenant}
          >
            {loading ? "Entrando..." : "Entrar"}
          </button>
        </form>
      </div>
    </div>
  );
}
