"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { getToken } from "@/lib/auth";

// Tipos

interface TenantConfig {
  autoAcceptOrders: boolean;
}

// Toast simples (estado local, sem biblioteca)

type ToastType = "success" | "error";

interface ToastState {
  id: number;
  message: string;
  type: ToastType;
}

function useToast() {
  const [toasts, setToasts] = useState<ToastState[]>([]);
  const counter = useRef(0);

  const show = useCallback((message: string, type: ToastType = "success") => {
    const id = ++counter.current;
    setToasts((prev) => [...prev, { id, message, type }]);
    setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id));
    }, 3000);
  }, []);

  return { toasts, show };
}

function ToastContainer({ toasts }: { toasts: ToastState[] }) {
  if (toasts.length === 0) return null;
  return (
    <div
      className="fixed bottom-4 right-4 z-50 flex flex-col gap-2"
      aria-live="polite"
      aria-atomic="false"
    >
      {toasts.map((t) => (
        <div
          key={t.id}
          role="status"
          className={[
            "rounded-xl px-4 py-3 text-sm font-medium shadow-dropdown animate-slide-up",
            t.type === "success"
              ? "bg-success text-white"
              : "bg-error text-white",
          ].join(" ")}
        >
          {t.message}
        </div>
      ))}
    </div>
  );
}

// Skeleton de 1 card

function ConfigSkeleton() {
  return (
    <div
      className="animate-pulse rounded-2xl bg-bg-primary p-6 shadow-card"
      aria-busy="true"
      aria-label="Carregando configurações..."
    >
      <div className="mb-4 h-5 w-1/3 rounded bg-bg-tertiary" aria-hidden="true" />
      <div className="flex items-center justify-between gap-4">
        <div className="flex-1">
          <div className="mb-2 h-4 w-1/2 rounded bg-bg-tertiary" aria-hidden="true" />
          <div className="h-3 w-3/4 rounded bg-bg-tertiary" aria-hidden="true" />
        </div>
        <div className="h-7 w-12 rounded-full bg-bg-tertiary" aria-hidden="true" />
      </div>
    </div>
  );
}

// Toggle pill

interface ToggleProps {
  id: string;
  checked: boolean;
  disabled?: boolean;
  onChange: (next: boolean) => void;
  label: string;
}

function Toggle({ id, checked, disabled, onChange, label }: ToggleProps) {
  return (
    <button
      id={id}
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={[
        "relative inline-flex h-7 w-12 shrink-0 cursor-pointer items-center rounded-full",
        "transition-colors duration-200 focus-visible:outline-none",
        "focus-visible:ring-2 focus-visible:ring-primary-700 focus-visible:ring-offset-2",
        "disabled:cursor-not-allowed disabled:opacity-50",
        checked ? "bg-primary-700" : "bg-bg-tertiary border border-border-medium",
      ].join(" ")}
    >
      <span
        className={[
          "inline-block h-5 w-5 rounded-full bg-white shadow-card",
          "transform transition-transform duration-200",
          checked ? "translate-x-6" : "translate-x-1",
        ].join(" ")}
        aria-hidden="true"
      />
    </button>
  );
}

// Pagina principal

export default function ConfiguracoesPage() {
  const router = useRouter();
  const { toasts, show: showToast } = useToast();

  const [config, setConfig] = useState<TenantConfig | null>(null);
  const [loadState, setLoadState] = useState<"loading" | "error" | "ok">(
    "loading",
  );
  const [saving, setSaving] = useState(false);
  const isAuthenticated = typeof window === "undefined" || !!getToken();

  const load = useCallback(async () => {
    setLoadState("loading");
    try {
      const data = await api.get<TenantConfig>("/config");
      setConfig(data);
      setLoadState("ok");
    } catch {
      setLoadState("error");
    }
  }, []);

  useEffect(() => {
    if (!isAuthenticated) {
      router.replace("/login");
      return;
    }
    queueMicrotask(() => {
      void load();
    });
  }, [isAuthenticated, load, router]);

  if (!isAuthenticated) return null;

  async function handleToggle(next: boolean) {
    if (saving || !config) return;
    setSaving(true);
    setConfig((prev) => (prev ? { ...prev, autoAcceptOrders: next } : prev));
    try {
      await api.patch<TenantConfig>("/config", { autoAcceptOrders: next });
      showToast("Configuração salva", "success");
    } catch (err) {
      setConfig((prev) => (prev ? { ...prev, autoAcceptOrders: !next } : prev));
      const msg =
        err instanceof ApiError ? err.message : "Erro ao salvar configuração.";
      showToast(msg, "error");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="flex min-h-screen flex-col bg-bg-secondary">
      <main className="mx-auto w-full max-w-2xl flex-1 px-4 py-8">
        <h2 className="mb-6 text-2xl font-bold text-text-primary">Configurações</h2>

        {/* 1. Estado: carregando */}
        {loadState === "loading" && <ConfigSkeleton />}

        {/* 2. Estado: erro */}
        {loadState === "error" && (
          <div
            role="alert"
            className="flex flex-col items-center gap-4 rounded-2xl bg-bg-primary p-8 text-center shadow-card"
          >
            <p className="text-base font-medium text-text-primary">
              Não foi possível carregar as configurações.
            </p>
            <button className="btn-primary" onClick={() => void load()}>
              Tentar novamente
            </button>
          </div>
        )}

        {/* 3. Estado: ok */}
        {loadState === "ok" && config && (
          <section aria-labelledby="secao-pedidos">
            <h3
              id="secao-pedidos"
              className="mb-3 text-sm font-semibold uppercase tracking-wider text-text-secondary"
            >
              Pedidos
            </h3>

            <div className="rounded-2xl bg-bg-primary p-6 shadow-card">
              <div className="flex items-center justify-between gap-4">
                <div className="flex-1">
                  <p className="text-sm font-semibold text-text-primary">
                    Aceite automático
                  </p>
                  <p className="mt-0.5 text-sm text-text-secondary">
                    Pedidos vão direto para a cozinha sem ação manual.
                  </p>
                </div>

                <div className="flex items-center gap-3">
                  {saving && (
                    <span
                      className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-primary-700 border-t-transparent"
                      aria-hidden="true"
                    />
                  )}
                  <Toggle
                    id="toggle-auto-accept"
                    checked={config.autoAcceptOrders}
                    disabled={saving}
                    onChange={(next) => void handleToggle(next)}
                    label={
                      config.autoAcceptOrders
                        ? "Aceite automático ativado"
                        : "Aceite automático desativado"
                    }
                  />
                </div>
              </div>
            </div>
          </section>
        )}
      </main>

      <ToastContainer toasts={toasts} />
    </div>
  );
}
