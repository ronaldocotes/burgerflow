"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { getToken } from "@/lib/auth";

// ── Tipos ─────────────────────────────────────────────────────────────────────

interface TenantConfig {
  autoAcceptOrders: boolean;
  marketplaceFeePct?: number;
  cardFeePct?: number;
  taxPct?: number;
  wahaPrimaryPhone?: string;
  wahaFallbackPhone?: string;
  campaignDailyLimit?: number;
  campaignDelayMinSeconds?: number;
  campaignDelayMaxSeconds?: number;
  aiEnabled?: boolean;
  aiDailyLimit?: number;
  aiSystemPrompt?: string;
}

// ── Toast simples ─────────────────────────────────────────────────────────────

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

// ── Skeleton ──────────────────────────────────────────────────────────────────

function ConfigSkeleton() {
  return (
    <div
      className="animate-pulse rounded-2xl bg-bg-primary p-6 shadow-card"
      aria-busy="true"
      aria-label="Carregando configuracoes..."
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

// ── Toggle pill ───────────────────────────────────────────────────────────────

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

// ── Campo numerico de percentual ──────────────────────────────────────────────

interface PctFieldProps {
  id: string;
  label: string;
  description: string;
  value: string;
  onChange: (v: string) => void;
  disabled?: boolean;
}

function PctField({ id, label, description, value, onChange, disabled }: PctFieldProps) {
  return (
    <div className="flex items-center justify-between gap-4">
      <div className="flex-1">
        <label htmlFor={id} className="text-sm font-semibold text-text-primary">
          {label}
        </label>
        <p className="mt-0.5 text-sm text-text-secondary">{description}</p>
      </div>
      <div className="flex items-center gap-1">
        <input
          id={id}
          type="number"
          min="0"
          max="100"
          step="0.1"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          disabled={disabled}
          className="input-field w-24 text-right disabled:opacity-50"
          aria-label={label}
        />
        <span className="text-sm text-text-secondary">%</span>
      </div>
    </div>
  );
}

// ── Pagina principal ──────────────────────────────────────────────────────────

export default function ConfiguracoesPage() {
  const router = useRouter();
  const { toasts, show: showToast } = useToast();

  const [config, setConfig] = useState<TenantConfig | null>(null);
  const [loadState, setLoadState] = useState<"loading" | "error" | "ok">(
    "loading",
  );
  const [saving, setSaving] = useState(false);
  const [savingTaxes, setSavingTaxes] = useState(false);

  // Campos de taxa (string para input controlado)
  const [marketplaceFeePct, setMarketplaceFeePct] = useState("");
  const [cardFeePct, setCardFeePct] = useState("");
  const [taxPct, setTaxPct] = useState("");

  // Campos WhatsApp Marketing
  const [wahaPrimaryPhone,       setWahaPrimaryPhone]       = useState("");
  const [wahaFallbackPhone,      setWahaFallbackPhone]      = useState("");
  const [campaignDailyLimit,     setCampaignDailyLimit]     = useState("");
  const [campaignDelayMin,       setCampaignDelayMin]       = useState("");
  const [campaignDelayMax,       setCampaignDelayMax]       = useState("");
  const [savingWaha,             setSavingWaha]             = useState(false);

  // Campos Copiloto IA
  const [aiEnabled,      setAiEnabled]      = useState(false);
  const [aiDailyLimit,   setAiDailyLimit]   = useState("50000");
  const [aiSystemPrompt, setAiSystemPrompt] = useState("");
  const [savingAi,       setSavingAi]       = useState(false);

  const isAuthenticated = typeof window === "undefined" || !!getToken();

  const load = useCallback(async () => {
    setLoadState("loading");
    try {
      const data = await api.get<TenantConfig>("/config");
      setConfig(data);
      setMarketplaceFeePct(data.marketplaceFeePct?.toString() ?? "0");
      setCardFeePct(data.cardFeePct?.toString() ?? "0");
      setTaxPct(data.taxPct?.toString() ?? "0");
      setWahaPrimaryPhone(data.wahaPrimaryPhone ?? "");
      setWahaFallbackPhone(data.wahaFallbackPhone ?? "");
      setCampaignDailyLimit(data.campaignDailyLimit?.toString() ?? "100");
      setCampaignDelayMin(data.campaignDelayMinSeconds?.toString() ?? "5");
      setCampaignDelayMax(data.campaignDelayMaxSeconds?.toString() ?? "30");
      setAiEnabled(data.aiEnabled ?? false);
      setAiDailyLimit(data.aiDailyLimit?.toString() ?? "50000");
      setAiSystemPrompt(data.aiSystemPrompt ?? "");
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
      showToast("Configuracao salva", "success");
    } catch (err) {
      setConfig((prev) => (prev ? { ...prev, autoAcceptOrders: !next } : prev));
      const msg =
        err instanceof ApiError ? err.message : "Erro ao salvar configuracao.";
      showToast(msg, "error");
    } finally {
      setSaving(false);
    }
  }

  async function handleSaveWaha(e: React.FormEvent) {
    e.preventDefault();
    if (savingWaha) return;
    const body = {
      wahaPrimaryPhone:       wahaPrimaryPhone.trim() || undefined,
      wahaFallbackPhone:      wahaFallbackPhone.trim() || undefined,
      campaignDailyLimit:     Number.parseInt(campaignDailyLimit) || 100,
      campaignDelayMinSeconds: Number.parseInt(campaignDelayMin) || 5,
      campaignDelayMaxSeconds: Number.parseInt(campaignDelayMax) || 30,
    };
    setSavingWaha(true);
    try {
      await api.patch<TenantConfig>("/config", body);
      showToast("WhatsApp salvo", "success");
    } catch (err) {
      const msg = err instanceof ApiError ? err.message : "Erro ao salvar configuracoes WhatsApp.";
      showToast(msg, "error");
    } finally {
      setSavingWaha(false);
    }
  }

  async function handleSaveTaxes(e: React.FormEvent) {
    e.preventDefault();
    if (savingTaxes) return;
    const body = {
      marketplaceFeePct: Number.parseFloat(marketplaceFeePct) || 0,
      cardFeePct: Number.parseFloat(cardFeePct) || 0,
      taxPct: Number.parseFloat(taxPct) || 0,
    };
    setSavingTaxes(true);
    try {
      await api.patch<TenantConfig>("/config", body);
      showToast("Taxas salvas", "success");
    } catch (err) {
      const msg =
        err instanceof ApiError ? err.message : "Erro ao salvar taxas.";
      showToast(msg, "error");
    } finally {
      setSavingTaxes(false);
    }
  }

  async function handleSaveAi(e: React.FormEvent) {
    e.preventDefault();
    if (savingAi) return;
    const body = {
      aiEnabled,
      aiDailyLimit: Number.parseInt(aiDailyLimit) || 50000,
      aiSystemPrompt: aiSystemPrompt.trim() || undefined,
    };
    setSavingAi(true);
    try {
      await api.patch<TenantConfig>("/config", body);
      showToast("Copiloto IA salvo", "success");
    } catch (err) {
      const msg =
        err instanceof ApiError ? err.message : "Erro ao salvar configuracoes de IA.";
      showToast(msg, "error");
    } finally {
      setSavingAi(false);
    }
  }

  return (
    <div className="flex min-h-screen flex-col bg-bg-secondary">
      <main className="mx-auto w-full max-w-2xl flex-1 px-4 py-8">
        <h2 className="mb-6 text-2xl font-bold text-text-primary">Configuracoes</h2>

        {/* 1. Estado: carregando */}
        {loadState === "loading" && <ConfigSkeleton />}

        {/* 2. Estado: erro */}
        {loadState === "error" && (
          <div
            role="alert"
            className="flex flex-col items-center gap-4 rounded-2xl bg-bg-primary p-8 text-center shadow-card"
          >
            <p className="text-base font-medium text-text-primary">
              Nao foi possivel carregar as configuracoes.
            </p>
            <button className="btn-primary" onClick={() => void load()}>
              Tentar novamente
            </button>
          </div>
        )}

        {/* 3. Estado: ok */}
        {loadState === "ok" && config && (
          <div className="space-y-8">
            {/* Secao: Pedidos */}
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
                      Aceite automatico
                    </p>
                    <p className="mt-0.5 text-sm text-text-secondary">
                      Pedidos vao direto para a cozinha sem acao manual.
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
                          ? "Aceite automatico ativado"
                          : "Aceite automatico desativado"
                      }
                    />
                  </div>
                </div>
              </div>
            </section>

            {/* Secao: Taxas e Impostos */}
            <section aria-labelledby="secao-taxas">
              <h3
                id="secao-taxas"
                className="mb-3 text-sm font-semibold uppercase tracking-wider text-text-secondary"
              >
                Taxas e Impostos
              </h3>

              <div className="rounded-2xl bg-bg-primary p-6 shadow-card">
                <p className="mb-5 text-sm text-text-secondary">
                  Percentuais usados no calculo do DRE. Aplique sobre a receita bruta.
                </p>

                <form onSubmit={(e) => void handleSaveTaxes(e)} className="space-y-5">
                  <PctField
                    id="marketplace-fee"
                    label="Taxa marketplace"
                    description="Comissao cobrada por plataformas (ex: iFood, Rappi)."
                    value={marketplaceFeePct}
                    onChange={setMarketplaceFeePct}
                    disabled={savingTaxes}
                  />

                  <div className="border-t border-border-light" role="separator" />

                  <PctField
                    id="card-fee"
                    label="Taxa cartao"
                    description="Taxa media cobrada pelas maquininhas de cartao."
                    value={cardFeePct}
                    onChange={setCardFeePct}
                    disabled={savingTaxes}
                  />

                  <div className="border-t border-border-light" role="separator" />

                  <PctField
                    id="tax-pct"
                    label="Imposto"
                    description="Simples Nacional ou aliquota media aplicavel."
                    value={taxPct}
                    onChange={setTaxPct}
                    disabled={savingTaxes}
                  />

                  <div className="flex justify-end pt-2">
                    <button
                      type="submit"
                      disabled={savingTaxes}
                      className="btn-primary flex items-center gap-2 disabled:opacity-50"
                    >
                      {savingTaxes && (
                        <span
                          className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent"
                          aria-hidden="true"
                        />
                      )}
                      {savingTaxes ? "Salvando..." : "Salvar taxas"}
                    </button>
                  </div>
                </form>
              </div>
            </section>

            {/* Secao: WhatsApp Marketing */}
            <section aria-labelledby="secao-whatsapp">
              <h3
                id="secao-whatsapp"
                className="mb-3 text-sm font-semibold uppercase tracking-wider text-text-secondary"
              >
                WhatsApp Marketing
              </h3>

              <div className="rounded-2xl bg-bg-primary p-6 shadow-card">
                <p className="mb-5 text-sm text-text-secondary">
                  Configuracoes do canal WhatsApp usado nas campanhas de marketing.
                </p>

                <form onSubmit={(e) => void handleSaveWaha(e)} className="space-y-5">
                  <div className="flex items-center justify-between gap-4">
                    <div className="flex-1">
                      <label htmlFor="waha-primary" className="text-sm font-semibold text-text-primary">
                        Numero principal
                      </label>
                      <p className="mt-0.5 text-sm text-text-secondary">Numero usado para enviar as campanhas.</p>
                    </div>
                    <input
                      id="waha-primary"
                      type="tel"
                      value={wahaPrimaryPhone}
                      onChange={(e) => setWahaPrimaryPhone(e.target.value)}
                      disabled={savingWaha}
                      placeholder="+55 (91) 99999-9999"
                      className="input-field w-52 disabled:opacity-50"
                    />
                  </div>

                  <div className="border-t border-border-light" role="separator" />

                  <div className="flex items-center justify-between gap-4">
                    <div className="flex-1">
                      <label htmlFor="waha-fallback" className="text-sm font-semibold text-text-primary">
                        Numero reserva <span className="font-normal text-text-muted">(opcional)</span>
                      </label>
                      <p className="mt-0.5 text-sm text-text-secondary">Usado caso o principal falhe.</p>
                    </div>
                    <input
                      id="waha-fallback"
                      type="tel"
                      value={wahaFallbackPhone}
                      onChange={(e) => setWahaFallbackPhone(e.target.value)}
                      disabled={savingWaha}
                      placeholder="+55 (91) 99999-9999"
                      className="input-field w-52 disabled:opacity-50"
                    />
                  </div>

                  <div className="border-t border-border-light" role="separator" />

                  <div className="flex items-center justify-between gap-4">
                    <div className="flex-1">
                      <label htmlFor="campaign-daily-limit" className="text-sm font-semibold text-text-primary">
                        Limite diario de envios
                      </label>
                      <p className="mt-0.5 text-sm text-text-secondary">Maximo de mensagens por dia.</p>
                    </div>
                    <input
                      id="campaign-daily-limit"
                      type="number"
                      min="1"
                      max="10000"
                      value={campaignDailyLimit}
                      onChange={(e) => setCampaignDailyLimit(e.target.value)}
                      disabled={savingWaha}
                      className="input-field w-24 text-right disabled:opacity-50"
                    />
                  </div>

                  <div className="border-t border-border-light" role="separator" />

                  <div className="flex items-center justify-between gap-4">
                    <div className="flex-1">
                      <label className="text-sm font-semibold text-text-primary">
                        Delay entre mensagens (seg)
                      </label>
                      <p className="mt-0.5 text-sm text-text-secondary">
                        Intervalo minimo e maximo entre cada envio.
                      </p>
                    </div>
                    <div className="flex items-center gap-2">
                      <input
                        id="campaign-delay-min"
                        type="number"
                        min="1"
                        max="300"
                        value={campaignDelayMin}
                        onChange={(e) => setCampaignDelayMin(e.target.value)}
                        disabled={savingWaha}
                        aria-label="Delay minimo em segundos"
                        className="input-field w-20 text-right disabled:opacity-50"
                      />
                      <span className="text-sm text-text-muted">a</span>
                      <input
                        id="campaign-delay-max"
                        type="number"
                        min="1"
                        max="300"
                        value={campaignDelayMax}
                        onChange={(e) => setCampaignDelayMax(e.target.value)}
                        disabled={savingWaha}
                        aria-label="Delay maximo em segundos"
                        className="input-field w-20 text-right disabled:opacity-50"
                      />
                    </div>
                  </div>

                  <div className="flex justify-end pt-2">
                    <button
                      type="submit"
                      disabled={savingWaha}
                      className="btn-primary flex items-center gap-2 disabled:opacity-50"
                    >
                      {savingWaha && (
                        <span
                          className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent"
                          aria-hidden="true"
                        />
                      )}
                      {savingWaha ? "Salvando..." : "Salvar WhatsApp"}
                    </button>
                  </div>
                </form>
              </div>
            </section>

            {/* Secao: Copiloto IA */}
            <section aria-labelledby="secao-ia">
              <h3
                id="secao-ia"
                className="mb-3 text-sm font-semibold uppercase tracking-wider text-text-secondary"
              >
                Copiloto IA
              </h3>

              <div className="rounded-2xl bg-bg-primary p-6 shadow-card">
                <p className="mb-5 text-sm text-text-secondary">
                  Configure o assistente de IA integrado ao painel de gestao.
                </p>

                <form onSubmit={(e) => void handleSaveAi(e)} className="space-y-5">
                  {/* Toggle ativar */}
                  <div className="flex items-center justify-between gap-4">
                    <div className="flex-1">
                      <p className="text-sm font-semibold text-text-primary">
                        Ativar Copiloto IA
                      </p>
                      <p className="mt-0.5 text-sm text-text-secondary">
                        Habilita o chat flutuante de IA no painel administrativo.
                      </p>
                    </div>
                    <Toggle
                      id="toggle-ai-enabled"
                      checked={aiEnabled}
                      disabled={savingAi}
                      onChange={setAiEnabled}
                      label={aiEnabled ? "Copiloto IA ativado" : "Copiloto IA desativado"}
                    />
                  </div>

                  <div className="border-t border-border-light" role="separator" />

                  {/* Limite diario de tokens */}
                  <div className="flex items-center justify-between gap-4">
                    <div className="flex-1">
                      <label
                        htmlFor="ai-daily-limit"
                        className="text-sm font-semibold text-text-primary"
                      >
                        Limite diario de tokens
                      </label>
                      <p className="mt-0.5 text-sm text-text-secondary">
                        Padrao: 50.000 tokens/dia.
                      </p>
                    </div>
                    <input
                      id="ai-daily-limit"
                      type="number"
                      min="1000"
                      max="1000000"
                      step="1000"
                      value={aiDailyLimit}
                      onChange={(e) => setAiDailyLimit(e.target.value)}
                      disabled={savingAi}
                      className="input-field w-32 text-right disabled:opacity-50"
                    />
                  </div>

                  <div className="border-t border-border-light" role="separator" />

                  {/* Instrucao personalizada */}
                  <div className="flex flex-col gap-2">
                    <label
                      htmlFor="ai-system-prompt"
                      className="text-sm font-semibold text-text-primary"
                    >
                      Instrucao personalizada
                    </label>
                    <p className="text-sm text-text-secondary">
                      Define o comportamento do assistente. Ex: Responda sempre de forma resumida.
                    </p>
                    <textarea
                      id="ai-system-prompt"
                      rows={3}
                      value={aiSystemPrompt}
                      onChange={(e) => setAiSystemPrompt(e.target.value)}
                      disabled={savingAi}
                      placeholder="Ex: Responda sempre de forma resumida e em portugues."
                      className="input-field resize-none disabled:opacity-50"
                    />
                  </div>

                  <div className="flex justify-end pt-2">
                    <button
                      type="submit"
                      disabled={savingAi}
                      className="btn-primary flex items-center gap-2 disabled:opacity-50"
                    >
                      {savingAi && (
                        <span
                          className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent"
                          aria-hidden="true"
                        />
                      )}
                      {savingAi ? "Salvando..." : "Salvar IA"}
                    </button>
                  </div>
                </form>
              </div>
            </section>
          </div>
        )}
      </main>

      <ToastContainer toasts={toasts} />
    </div>
  );
}
