"use client";

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { Megaphone, Pause, PauseCircle, Play, Plus } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import { useModalA11y } from "@/lib/use-modal-a11y";
import type { AdAccountResponse, AdCampaignResponse } from "@/types/ads";
import { CampaignWizard } from "./CampaignWizard";
import { fmtAdsMoney } from "./ads-format";

// Secao "Campanhas" da tela de Trafego Pago (Fase 8.2): lista GET /ads/campaigns,
// wizard de criacao e pausar/ativar. ATIVAR = comecar a gastar dinheiro de verdade,
// entao tem modal de confirmacao forte com a verba diaria; pausar e a acao segura
// (para o gasto) e roda direto.

const CAMPAIGN_STATUS_META: Record<string, { label: string; cls: string }> = {
  DRAFT: { label: "Rascunho", cls: "bg-bg-tertiary text-text-secondary" },
  PAUSED: { label: "Pausada", cls: "bg-yellow-100 text-yellow-800" },
  ACTIVE: { label: "Ativa", cls: "bg-green-100 text-green-700" },
  ARCHIVED: { label: "Arquivada", cls: "bg-bg-tertiary text-text-muted" },
};

/** Traducao dos effective_status mais comuns da Meta (o resto sai cru). */
const EFFECTIVE_LABELS: Record<string, string> = {
  DISAPPROVED: "Reprovada na Meta",
  WITH_ISSUES: "Com problemas na Meta",
  PENDING_REVIEW: "Em analise na Meta",
  IN_PROCESS: "Processando na Meta",
  CAMPAIGN_PAUSED: "Campanha pausada na Meta",
  ADSET_PAUSED: "Conjunto pausado na Meta",
};

function CampaignStatusBadge({ campaign }: { campaign: AdCampaignResponse }) {
  const meta = CAMPAIGN_STATUS_META[campaign.status] ?? CAMPAIGN_STATUS_META.DRAFT;
  const eff = campaign.effectiveStatus;
  // So mostra o espelho da Meta quando ele acrescenta informacao (difere do status local).
  const showEff = eff != null && eff !== campaign.status;
  const effBad = eff === "DISAPPROVED" || eff === "WITH_ISSUES";
  return (
    <span className="inline-flex flex-wrap items-center gap-1.5">
      <span className={["inline-flex rounded-full px-2.5 py-0.5 text-xs font-medium", meta.cls].join(" ")}>
        {meta.label}
      </span>
      {showEff && (
        <span
          className={[
            "inline-flex rounded-full px-2.5 py-0.5 text-xs font-medium",
            effBad ? "bg-red-100 text-red-700" : "bg-bg-tertiary text-text-secondary",
          ].join(" ")}
          title="Status reportado pela Meta"
        >
          {EFFECTIVE_LABELS[eff] ?? eff}
        </span>
      )}
    </span>
  );
}

function fmtDateTime(iso: string): string {
  return new Date(iso).toLocaleString("pt-BR", { dateStyle: "short", timeStyle: "short" });
}

// ── Modal: confirmar ativacao (comeca a GASTAR) ───────────────────────────────

interface ActivateModalProps {
  campaign: AdCampaignResponse;
  currency: string | null;
  onClose: () => void;
  onActivated: (updated: AdCampaignResponse) => void;
}

function ActivateModal({ campaign, currency, onClose, onActivated }: ActivateModalProps) {
  const ref = useRef<HTMLDivElement>(null);
  useModalA11y(ref as React.RefObject<HTMLElement | null>, onClose);
  const titleId = useId();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handle() {
    if (loading) return;
    setLoading(true);
    setError(null);
    try {
      const updated = await api.post<AdCampaignResponse>(`/ads/campaigns/${campaign.id}/activate`, {});
      onActivated(updated);
    } catch (err) {
      // O backend REVALIDA o teto de verba na ativacao — a mensagem do 400 e clara.
      setError(err instanceof ApiError ? err.message : "Erro ao ativar a campanha.");
      setLoading(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby={titleId}
    >
      <div className="absolute inset-0 bg-black/50" aria-hidden="true" onClick={onClose} />
      <div ref={ref} className="relative z-10 w-full max-w-sm rounded-2xl bg-bg-primary p-6 shadow-dropdown">
        <h2 id={titleId} className="mb-2 text-base font-semibold text-text-primary">
          Ativar campanha?
        </h2>
        <p className="mb-5 text-sm text-text-secondary">
          Ativar faz <span className="font-semibold text-text-primary">{campaign.name}</span> comecar
          a veicular e a{" "}
          <span className="font-semibold text-text-primary">
            gastar a verba diaria de {fmtAdsMoney(campaign.dailyBudgetCents, currency)}
          </span>{" "}
          na sua conta da Meta. Confirmar?
        </p>
        {error && (
          <p role="alert" className="mb-4 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">
            {error}
          </p>
        )}
        <div className="flex justify-end gap-3">
          <button onClick={onClose} className="btn-outline">
            Cancelar
          </button>
          <button
            onClick={() => void handle()}
            disabled={loading}
            className="btn-primary flex items-center gap-2 disabled:opacity-50"
          >
            {loading && (
              <span
                className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent"
                aria-hidden="true"
              />
            )}
            {loading ? "Ativando..." : "Sim, ativar e gastar"}
          </button>
        </div>
      </div>
    </div>
  );
}

// ── Skeleton ──────────────────────────────────────────────────────────────────

function CampaignsSkeleton() {
  return (
    <div
      className="animate-pulse space-y-3 rounded-2xl bg-bg-primary p-5 shadow-card"
      aria-busy="true"
      aria-label="Carregando campanhas..."
    >
      {Array.from({ length: 3 }).map((_, i) => (
        <div key={i} className="h-10 rounded bg-bg-tertiary" aria-hidden="true" />
      ))}
    </div>
  );
}

// ── Secao ─────────────────────────────────────────────────────────────────────

interface Props {
  accounts: AdAccountResponse[];
}

export function CampaignsSection({ accounts }: Props) {
  const connected = useMemo(() => accounts.filter((a) => a.status === "CONNECTED"), [accounts]);
  const accountById = useMemo(() => new Map(accounts.map((a) => [a.id, a])), [accounts]);

  const [campaigns, setCampaigns] = useState<AdCampaignResponse[]>([]);
  const [loadState, setLoadState] = useState<"loading" | "error" | "empty" | "ok">("loading");
  const [wizardOpen, setWizardOpen] = useState(false);
  const [activateTarget, setActivateTarget] = useState<AdCampaignResponse | null>(null);
  const [pausingId, setPausingId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [createdName, setCreatedName] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoadState("loading");
    try {
      const data = await api.get<AdCampaignResponse[]>("/ads/campaigns");
      setCampaigns(data);
      setLoadState(data.length === 0 ? "empty" : "ok");
    } catch {
      setLoadState("error");
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  function currencyOf(c: AdCampaignResponse): string | null {
    return accountById.get(c.adAccountId)?.currency ?? null;
  }

  async function handlePause(c: AdCampaignResponse) {
    if (pausingId) return;
    setPausingId(c.id);
    setActionError(null);
    try {
      const updated = await api.post<AdCampaignResponse>(`/ads/campaigns/${c.id}/pause`, {});
      setCampaigns((prev) => prev.map((x) => (x.id === updated.id ? updated : x)));
    } catch (err) {
      setActionError(err instanceof ApiError ? err.message : "Erro ao pausar a campanha.");
    } finally {
      setPausingId(null);
    }
  }

  const canCreate = connected.length > 0;

  return (
    <section aria-label="Campanhas de anuncio" className="mt-10">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <Megaphone className="h-5 w-5 text-primary-700" aria-hidden="true" />
          <h3 className="text-lg font-semibold text-text-primary">Campanhas de anuncio</h3>
        </div>
        {loadState === "ok" && (
          <button
            onClick={() => setWizardOpen(true)}
            disabled={!canCreate}
            className="btn-primary flex items-center gap-2 disabled:opacity-50"
            title={canCreate ? undefined : "Conecte uma conta da Meta primeiro"}
          >
            <Plus className="h-4 w-4" aria-hidden="true" />
            Criar campanha
          </button>
        )}
      </div>

      {/* Banner pos-criacao: comunica o PAUSED intencional */}
      {createdName && (
        <div
          role="status"
          className="mb-4 flex items-start gap-2 rounded-xl border border-border-light bg-bg-primary px-4 py-3 shadow-card"
        >
          <PauseCircle className="mt-0.5 h-5 w-5 shrink-0 text-yellow-600" aria-hidden="true" />
          <p className="flex-1 text-sm text-text-secondary">
            <span className="font-semibold text-text-primary">{createdName}</span> foi criada{" "}
            <span className="font-semibold text-text-primary">pausada</span> &mdash; ela ainda nao
            gasta nada. Revise e clique em <span className="font-medium">Ativar</span> quando quiser
            que ela comece a veicular.
          </p>
          <button
            onClick={() => setCreatedName(null)}
            aria-label="Fechar aviso"
            className="rounded-lg p-1 text-text-muted hover:bg-bg-tertiary"
          >
            <svg className="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>
      )}

      {actionError && (
        <div role="alert" className="mb-4 rounded-xl bg-red-50 px-4 py-3 text-sm text-red-700">
          {actionError}
        </div>
      )}

      {/* 1. Carregando */}
      {loadState === "loading" && <CampaignsSkeleton />}

      {/* 2. Erro */}
      {loadState === "error" && (
        <div
          role="alert"
          className="flex flex-col items-center gap-4 rounded-2xl bg-bg-primary p-10 text-center shadow-card"
        >
          <p className="text-base font-medium text-text-primary">
            Nao foi possivel carregar as campanhas.
          </p>
          <button className="btn-primary" onClick={() => void load()}>
            Tentar novamente
          </button>
        </div>
      )}

      {/* 3. Vazio */}
      {loadState === "empty" && (
        <div className="flex flex-col items-center gap-4 rounded-2xl bg-bg-primary p-12 text-center shadow-card">
          <Megaphone className="h-12 w-12 text-text-muted" aria-hidden="true" />
          <p className="text-base font-medium text-text-primary">Nenhuma campanha ainda</p>
          <p className="max-w-md text-sm text-text-muted">
            Crie um anuncio no Facebook e Instagram para quem esta perto do seu restaurante: voce
            define a verba diaria, o raio e o texto &mdash; a campanha nasce pausada e so gasta
            quando voce ativar.
          </p>
          <button
            onClick={() => setWizardOpen(true)}
            disabled={!canCreate}
            className="btn-primary flex items-center gap-2 disabled:opacity-50"
          >
            <Plus className="h-4 w-4" aria-hidden="true" />
            Criar 1&ordf; campanha
          </button>
          {!canCreate && (
            <p className="text-xs text-text-muted">
              Conecte uma conta da Meta (acima) para poder criar campanhas.
            </p>
          )}
        </div>
      )}

      {/* 4. Lista */}
      {loadState === "ok" && (
        <div className="overflow-hidden rounded-2xl bg-bg-primary shadow-card">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border-light">
                  {["Nome", "Status", "Verba diaria", "Raio", "Criada em", "Acoes"].map((h) => (
                    <th
                      key={h}
                      className="whitespace-nowrap px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-text-muted"
                    >
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {campaigns.map((c) => (
                  <tr key={c.id} className="border-b border-border-light hover:bg-bg-secondary">
                    <td className="max-w-[220px] truncate px-4 py-3 font-medium text-text-primary" title={c.name}>
                      {c.name}
                    </td>
                    <td className="px-4 py-3">
                      <CampaignStatusBadge campaign={c} />
                    </td>
                    <td className="whitespace-nowrap px-4 py-3 text-text-secondary">
                      {fmtAdsMoney(c.dailyBudgetCents, currencyOf(c))}/dia
                    </td>
                    <td className="whitespace-nowrap px-4 py-3 text-text-secondary">
                      {c.radiusKm != null ? `${c.radiusKm} km` : "—"}
                    </td>
                    <td className="whitespace-nowrap px-4 py-3 text-text-secondary">
                      {fmtDateTime(c.createdAt)}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        {(c.status === "PAUSED" || c.status === "DRAFT") && (
                          <button
                            onClick={() => setActivateTarget(c)}
                            aria-label={`Ativar campanha ${c.name}`}
                            className="flex min-h-10 items-center gap-1 rounded-lg border border-primary-700 px-2.5 py-1 text-xs font-medium text-primary-700 transition-colors hover:bg-primary-700 hover:text-white"
                          >
                            <Play className="h-3.5 w-3.5" aria-hidden="true" />
                            Ativar
                          </button>
                        )}
                        {c.status === "ACTIVE" && (
                          <button
                            onClick={() => void handlePause(c)}
                            disabled={pausingId === c.id}
                            aria-label={`Pausar campanha ${c.name}`}
                            className="flex min-h-10 items-center gap-1 rounded-lg border border-yellow-600 px-2.5 py-1 text-xs font-medium text-yellow-700 transition-colors hover:bg-yellow-600 hover:text-white disabled:opacity-50"
                          >
                            {pausingId === c.id ? (
                              <span
                                className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-yellow-700 border-t-transparent"
                                aria-hidden="true"
                              />
                            ) : (
                              <Pause className="h-3.5 w-3.5" aria-hidden="true" />
                            )}
                            Pausar
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {wizardOpen && (
        <CampaignWizard
          accounts={connected}
          onClose={() => setWizardOpen(false)}
          onCreated={(campaign) => {
            setWizardOpen(false);
            setCreatedName(campaign.name);
            void load();
          }}
        />
      )}
      {activateTarget && (
        <ActivateModal
          campaign={activateTarget}
          currency={currencyOf(activateTarget)}
          onClose={() => setActivateTarget(null)}
          onActivated={(updated) => {
            setActivateTarget(null);
            setCampaigns((prev) => prev.map((x) => (x.id === updated.id ? updated : x)));
            setCreatedName(null);
          }}
        />
      )}
    </section>
  );
}
