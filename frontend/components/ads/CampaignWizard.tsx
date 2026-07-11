"use client";

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import dynamic from "next/dynamic";
import { AlertTriangle, ChevronLeft, ChevronRight, PauseCircle } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import { getTenant } from "@/lib/auth";
import { useModalA11y } from "@/lib/use-modal-a11y";
import type {
  AdAccountResponse,
  AdCampaignResponse,
  AdPageResponse,
  CreateAdCampaignRequest,
} from "@/types/ads";
import type { Page, Product } from "@/types/menu";
import { fmtAdsMoney } from "./ads-format";

// Wizard "Criar campanha" (Fase 8.2). 5 passos: Pagina do Facebook (pre-requisito do
// backend — criar campanha sem Pagina vinculada da 400), Nome & Verba, Publico (mapa
// Leaflet ja usado no Minha Loja: pin + raio), Criativo & Destino, Revisao.
//
// Seguranca de gasto:
// - Idempotency-Key: UUID gerado UMA vez na ABERTURA do wizard e reusado no submit —
//   reenvio do mesmo formulario (retry/double-click) nao cria 2 campanhas;
// - a campanha SEMPRE nasce PAUSED (o backend forca); a revisao deixa isso explicito.
//
// Nota de contrato: AdAccountResponse NAO expoe pageId, entao o wizard nao tem como
// saber se a conta ja tem Pagina — o passo 1 sempre pede para escolher/confirmar
// (o PUT e idempotente e regrava a escolha).

const GeoRadiusMap = dynamic(() => import("./GeoRadiusMap"), {
  ssr: false,
  loading: () => (
    <div className="h-64 w-full animate-pulse rounded-xl bg-bg-tertiary" aria-hidden="true" />
  ),
});

const MIN_BUDGET_CENTS = 1000; // piso R$10 — o backend revalida (e valida tambem o teto do tenant)
const MAX_BUDGET_CENTS = 99_999_999;
const RADIUS_MIN = 1;
const RADIUS_MAX = 80; // limite da Meta para custom_locations
/** Centro do Brasil — fallback do mapa quando nao ha pin nem endereco da loja. */
const BRAZIL_CENTER: [number, number] = [-14.235, -51.9253];

const STEPS = ["Pagina", "Nome e verba", "Publico", "Criativo", "Revisao"] as const;

/** So os digitos viram centavos: "R$ 25,00" -> 2500 (mascara de moeda estilo caixa). */
function centsFromInput(raw: string): number {
  const digits = raw.replace(/\D/g, "");
  if (!digits) return 0;
  return Math.min(parseInt(digits, 10), MAX_BUDGET_CENTS);
}

/** Aceita "lat, lng" colado do Google Maps (ex.: "-0.03889, -51.06639"). */
function parseCoords(raw: string): { lat: number; lng: number } | null {
  const parts = raw.trim().split(",");
  if (parts.length !== 2) return null;
  const lat = Number(parts[0].trim());
  const lng = Number(parts[1].trim());
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return null;
  if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return null;
  return { lat, lng };
}

function isValidUrl(raw: string): boolean {
  const v = raw.trim();
  return /^https?:\/\/\S+$/.test(v) && v.length <= 500;
}

interface Props {
  /** Contas CONNECTED (a criacao exige conta conectada). */
  accounts: AdAccountResponse[];
  onClose: () => void;
  onCreated: (campaign: AdCampaignResponse) => void;
}

export function CampaignWizard({ accounts, onClose, onCreated }: Props) {
  const ref = useRef<HTMLDivElement>(null);
  useModalA11y(ref as React.RefObject<HTMLElement | null>, onClose);
  const titleId = useId();
  const ids = {
    account: useId(),
    page: useId(),
    name: useId(),
    budget: useId(),
    coords: useId(),
    radius: useId(),
    primaryText: useId(),
    headline: useId(),
    cta: useId(),
    productSearch: useId(),
    destination: useId(),
  };

  // Idempotency-Key: gerada na ABERTURA do wizard, reusada em todo submit.
  const idempotencyKey = useRef<string>(crypto.randomUUID());

  const [step, setStep] = useState(0);
  const [accountId, setAccountId] = useState<string>(accounts[0]?.id ?? "");
  const account = accounts.find((a) => a.id === accountId) ?? null;
  const currency = account?.currency ?? "BRL";

  // Passo 1 — Pagina do Facebook
  const [pages, setPages] = useState<AdPageResponse[]>([]);
  const [pagesState, setPagesState] = useState<"loading" | "error" | "empty" | "ok">("loading");
  const [pagesError, setPagesError] = useState<string | null>(null);
  const [pageId, setPageId] = useState<string>("");
  const [savingPage, setSavingPage] = useState(false);
  const [savePageError, setSavePageError] = useState<string | null>(null);

  // Passo 2 — Nome e verba
  const [name, setName] = useState("");
  const [budgetCents, setBudgetCents] = useState(0);
  const [budgetTouched, setBudgetTouched] = useState(false);

  // Passo 3 — Publico (geo)
  const [lat, setLat] = useState<number | null>(null);
  const [lng, setLng] = useState<number | null>(null);
  const [radiusKm, setRadiusKm] = useState(5);
  const [coordsText, setCoordsText] = useState("");
  const [coordsError, setCoordsError] = useState<string | null>(null);
  const [fallbackCenter, setFallbackCenter] = useState<[number, number]>(BRAZIL_CENTER);

  // Passo 4 — Criativo e destino
  const [primaryText, setPrimaryText] = useState("");
  const [headline, setHeadline] = useState("");
  const [cta, setCta] = useState("");
  const [productId, setProductId] = useState<string | null>(null);
  const [catalog, setCatalog] = useState<Product[]>([]);
  const [catalogState, setCatalogState] = useState<"loading" | "error" | "ok">("loading");
  const [productQuery, setProductQuery] = useState("");
  const [destinationUrl, setDestinationUrl] = useState(() => {
    const appUrl = process.env.NEXT_PUBLIC_APP_URL ?? "http://localhost:3000";
    const tenant = getTenant();
    return tenant ? `${appUrl}/cardapio?tenant=${encodeURIComponent(tenant)}` : `${appUrl}/cardapio`;
  });

  // Submit
  const [saving, setSaving] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  // Pre-preenche o publico com o endereco do restaurante (Minha Loja), se existir.
  useEffect(() => {
    let cancelled = false;
    api
      .get<{ restaurantLat: number | null; restaurantLng: number | null }>("/config")
      .then((cfg) => {
        if (cancelled || cfg.restaurantLat == null || cfg.restaurantLng == null) return;
        setFallbackCenter([cfg.restaurantLat, cfg.restaurantLng]);
        setLat((prev) => prev ?? cfg.restaurantLat);
        setLng((prev) => prev ?? cfg.restaurantLng);
      })
      .catch(() => {
        // Sem endereco da loja: o mapa abre no Brasil e o dono clica/cola coordenadas.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // Paginas do Facebook da conta selecionada.
  const loadPages = useCallback(async () => {
    if (!accountId) return;
    setPagesState("loading");
    setPagesError(null);
    setPageId("");
    try {
      const data = await api.get<AdPageResponse[]>(`/ads/accounts/${accountId}/pages`);
      setPages(data);
      if (data.length === 0) {
        setPagesState("empty");
      } else {
        setPagesState("ok");
        if (data.length === 1) setPageId(data[0].id);
      }
    } catch (err) {
      setPagesError(err instanceof ApiError ? err.message : "Erro ao buscar as Paginas do Facebook.");
      setPagesState("error");
    }
  }, [accountId]);

  useEffect(() => {
    void loadPages();
  }, [loadPages]);

  // Catalogo para o seletor de produto (mesmo endpoint do admin/cardapio).
  const loadCatalog = useCallback(async () => {
    setCatalogState("loading");
    try {
      const page = await api.get<Page<Product>>("/products?size=300");
      setCatalog(page.content);
      setCatalogState("ok");
    } catch {
      setCatalogState("error");
    }
  }, []);

  useEffect(() => {
    void loadCatalog();
  }, [loadCatalog]);

  // A foto do produto vira a imagem do anuncio — so produtos ativos COM foto entram.
  const productResults = useMemo(() => {
    const q = productQuery.trim().toLowerCase();
    return catalog
      .filter((p) => p.active && p.imageUrl)
      .filter((p) => q === "" || p.name.toLowerCase().includes(q))
      .slice(0, 20);
  }, [catalog, productQuery]);

  const selectedProduct = productId ? (catalog.find((p) => p.id === productId) ?? null) : null;
  const selectedPage = pages.find((p) => p.id === pageId) ?? null;

  // ── Validacao por passo ────────────────────────────────────────────────────
  const nameOk = name.trim().length > 0 && name.trim().length <= 200;
  const budgetOk = budgetCents >= MIN_BUDGET_CENTS;
  const geoOk = lat != null && lng != null && radiusKm >= RADIUS_MIN && radiusKm <= RADIUS_MAX;
  const textOk = primaryText.trim().length > 0 && primaryText.trim().length <= 2000;
  const urlOk = isValidUrl(destinationUrl);
  const canAdvance = [
    pagesState === "ok" && pageId !== "",
    nameOk && budgetOk,
    geoOk,
    textOk && urlOk,
    true,
  ][step];

  async function advanceFromPageStep() {
    if (!accountId || !pageId || savingPage) return;
    setSavingPage(true);
    setSavePageError(null);
    try {
      // Grava a Pagina na conta ANTES de seguir: se falhar, o dono ve o erro aqui,
      // nao la no submit final.
      await api.put(`/ads/accounts/${accountId}/page`, {
        pageId,
        ...(selectedPage?.name ? { pageName: selectedPage.name } : {}),
      });
      setStep(1);
    } catch (err) {
      setSavePageError(err instanceof ApiError ? err.message : "Erro ao vincular a Pagina.");
    } finally {
      setSavingPage(false);
    }
  }

  function handleNext() {
    if (!canAdvance) return;
    if (step === 0) {
      void advanceFromPageStep();
    } else if (step < STEPS.length - 1) {
      setStep(step + 1);
    }
  }

  async function handleCreate() {
    if (saving || !accountId || lat == null || lng == null) return;
    setSaving(true);
    setSubmitError(null);
    const body: CreateAdCampaignRequest = {
      accountId,
      name: name.trim(),
      dailyBudgetCents: budgetCents,
      geoLat: lat,
      geoLng: lng,
      radiusKm,
      destinationUrl: destinationUrl.trim(),
      primaryText: primaryText.trim(),
      ...(headline.trim() ? { headline: headline.trim() } : {}),
      ...(cta.trim() ? { cta: cta.trim() } : {}),
      ...(productId ? { productId } : {}),
    };
    try {
      const created = await api.post<AdCampaignResponse>("/ads/campaigns", body, {
        "Idempotency-Key": idempotencyKey.current,
      });
      onCreated(created);
    } catch (err) {
      // 400 do backend ja vem com mensagem clara (piso/teto de verba, sem Pagina,
      // produto sem foto...). 403 = modulo off / sem permissao.
      setSubmitError(err instanceof ApiError ? err.message : "Erro ao criar a campanha.");
      setSaving(false);
    }
  }

  function applyCoords() {
    const parsed = parseCoords(coordsText);
    if (!parsed) {
      setCoordsError('Nao entendi as coordenadas. Cole no formato "latitude, longitude" (ex.: -0.03889, -51.06639).');
      return;
    }
    setCoordsError(null);
    setLat(parsed.lat);
    setLng(parsed.lng);
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby={titleId}
    >
      <div className="absolute inset-0 bg-black/50" aria-hidden="true" onClick={onClose} />
      <div
        ref={ref}
        className="relative z-10 flex max-h-[92vh] w-full max-w-xl flex-col rounded-2xl bg-bg-primary shadow-dropdown"
      >
        {/* Header */}
        <div className="flex shrink-0 items-center justify-between border-b border-border-light px-6 py-4">
          <div>
            <h2 id={titleId} className="text-base font-semibold text-text-primary">
              Criar campanha de anuncio
            </h2>
            <p className="mt-0.5 text-xs text-text-muted" aria-live="polite">
              Passo {step + 1} de {STEPS.length} &mdash; {STEPS[step]}
            </p>
          </div>
          <button
            onClick={onClose}
            aria-label="Fechar"
            className="rounded-lg p-1 text-text-muted hover:bg-bg-tertiary"
          >
            <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        {/* Indicador de passos */}
        <ol className="flex shrink-0 gap-1.5 px-6 pt-4" aria-label="Progresso do wizard">
          {STEPS.map((label, i) => (
            <li
              key={label}
              aria-current={i === step ? "step" : undefined}
              className={[
                "h-1.5 flex-1 rounded-full",
                i < step ? "bg-primary-700" : i === step ? "bg-primary-700/60" : "bg-bg-tertiary",
              ].join(" ")}
            >
              <span className="sr-only">{`${label}${i < step ? " (concluido)" : i === step ? " (atual)" : ""}`}</span>
            </li>
          ))}
        </ol>

        {/* Corpo */}
        <div className="flex-1 space-y-5 overflow-y-auto px-6 py-5">
          {/* ── Passo 1: Pagina do Facebook ─────────────────────────────── */}
          {step === 0 && (
            <>
              {accounts.length > 1 && (
                <div>
                  <label htmlFor={ids.account} className="mb-1 block text-sm font-medium text-text-primary">
                    Conta de anuncio
                  </label>
                  <select
                    id={ids.account}
                    value={accountId}
                    onChange={(e) => setAccountId(e.target.value)}
                    className="input-field w-full"
                  >
                    {accounts.map((a) => (
                      <option key={a.id} value={a.id}>
                        {a.accountName ?? `Conta ••••${a.accountIdLast4}`}
                        {a.currency ? ` (${a.currency})` : ""}
                      </option>
                    ))}
                  </select>
                </div>
              )}

              <div>
                <p className="mb-1 text-sm font-medium text-text-primary">Pagina do Facebook</p>
                <p className="mb-3 text-sm text-text-muted">
                  O anuncio e publicado em nome de uma Pagina. Escolha qual Pagina assina os seus
                  anuncios (obrigatorio para criar campanha).
                </p>

                {pagesState === "loading" && (
                  <div className="space-y-2" aria-busy="true" aria-label="Carregando Paginas...">
                    {Array.from({ length: 2 }).map((_, i) => (
                      <div key={i} className="h-12 animate-pulse rounded-xl bg-bg-tertiary" aria-hidden="true" />
                    ))}
                  </div>
                )}

                {pagesState === "error" && (
                  <div role="alert" className="rounded-xl bg-red-50 px-4 py-3 text-sm text-red-700">
                    <p>{pagesError}</p>
                    <button type="button" onClick={() => void loadPages()} className="mt-2 font-medium underline">
                      Tentar novamente
                    </button>
                  </div>
                )}

                {pagesState === "empty" && (
                  <div className="rounded-xl border border-border-light bg-bg-secondary p-4">
                    <p className="flex items-start gap-2 text-sm text-text-primary">
                      <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-yellow-600" aria-hidden="true" />
                      <span>
                        Nenhuma Pagina encontrada para este token. Conecte uma Pagina do Facebook ao
                        seu Business Manager (Configuracoes do negocio &rarr; Contas &rarr; Paginas) e
                        de acesso ao Usuario do Sistema. Depois volte aqui.
                      </span>
                    </p>
                    <button type="button" onClick={() => void loadPages()} className="btn-outline mt-3 text-sm">
                      Ja conectei &mdash; buscar de novo
                    </button>
                  </div>
                )}

                {pagesState === "ok" && (
                  <div role="radiogroup" aria-label="Escolha a Pagina do Facebook" className="space-y-2">
                    {pages.map((p) => (
                      <label
                        key={p.id}
                        className={[
                          "flex min-h-11 cursor-pointer items-center gap-3 rounded-xl border p-3 transition-colors",
                          pageId === p.id
                            ? "border-primary-700 bg-bg-secondary ring-1 ring-primary-700"
                            : "border-border-light hover:bg-bg-secondary",
                        ].join(" ")}
                      >
                        <input
                          type="radio"
                          name="fb-page"
                          value={p.id}
                          checked={pageId === p.id}
                          onChange={() => setPageId(p.id)}
                          className="h-4 w-4 accent-primary-700"
                        />
                        <span className="min-w-0 flex-1">
                          <span className="block truncate text-sm font-medium text-text-primary">
                            {p.name ?? "Pagina sem nome"}
                          </span>
                          <span className="block font-mono text-xs text-text-muted">ID {p.id}</span>
                        </span>
                      </label>
                    ))}
                  </div>
                )}

                {savePageError && (
                  <p role="alert" className="mt-3 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">
                    {savePageError}
                  </p>
                )}
              </div>
            </>
          )}

          {/* ── Passo 2: Nome e verba ───────────────────────────────────── */}
          {step === 1 && (
            <>
              <div>
                <label htmlFor={ids.name} className="mb-1 block text-sm font-medium text-text-primary">
                  Nome da campanha
                </label>
                <input
                  id={ids.name}
                  type="text"
                  required
                  maxLength={200}
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  className="input-field w-full"
                  placeholder="Ex: Promo de lancamento — 5 km"
                />
              </div>

              <div>
                <label htmlFor={ids.budget} className="mb-1 block text-sm font-medium text-text-primary">
                  Verba diaria
                </label>
                <input
                  id={ids.budget}
                  type="text"
                  inputMode="numeric"
                  required
                  value={fmtAdsMoney(budgetCents, currency)}
                  onChange={(e) => setBudgetCents(centsFromInput(e.target.value))}
                  onBlur={() => setBudgetTouched(true)}
                  aria-describedby={`${ids.budget}-hint`}
                  aria-invalid={budgetTouched && !budgetOk ? true : undefined}
                  className={[
                    "input-field w-full",
                    budgetTouched && !budgetOk ? "border-red-500" : "",
                  ].join(" ")}
                />
                <p id={`${ids.budget}-hint`} className="mt-1.5 text-sm text-text-muted">
                  Quanto a campanha pode gastar POR DIA, na moeda da conta ({currency}). Minimo{" "}
                  {fmtAdsMoney(MIN_BUDGET_CENTS, currency)}. Seu plano tambem tem um teto &mdash; se
                  passar, avisamos aqui.
                </p>
                {budgetTouched && !budgetOk && (
                  <p role="alert" className="mt-1 text-sm text-red-600">
                    A verba minima e {fmtAdsMoney(MIN_BUDGET_CENTS, currency)} por dia.
                  </p>
                )}
              </div>

              <div className="rounded-xl border border-border-light bg-bg-secondary p-3">
                <p className="text-xs font-semibold uppercase tracking-wider text-text-muted">Objetivo</p>
                <p className="mt-1 text-sm text-text-primary">
                  Trafego para o seu cardapio (fixo nesta versao) &mdash; a Meta mostra o anuncio
                  para quem tem mais chance de clicar no link.
                </p>
              </div>
            </>
          )}

          {/* ── Passo 3: Publico (geo) ──────────────────────────────────── */}
          {step === 2 && (
            <>
              <p className="text-sm text-text-secondary">
                O anuncio aparece para pessoas dentro do raio abaixo. Clique no mapa (ou arraste o
                pin) para marcar o centro &mdash; normalmente o seu restaurante.
              </p>

              <GeoRadiusMap
                lat={lat}
                lng={lng}
                radiusKm={radiusKm}
                fallbackCenter={fallbackCenter}
                onChange={(la, ln) => {
                  setLat(la);
                  setLng(ln);
                  setCoordsError(null);
                }}
              />

              <div className="grid gap-4 sm:grid-cols-2">
                <div>
                  <label htmlFor={ids.coords} className="mb-1 block text-sm font-medium text-text-primary">
                    Ou cole as coordenadas
                  </label>
                  <div className="flex gap-2">
                    <input
                      id={ids.coords}
                      type="text"
                      value={coordsText}
                      onChange={(e) => setCoordsText(e.target.value)}
                      aria-describedby={`${ids.coords}-hint`}
                      aria-invalid={coordsError ? true : undefined}
                      className={["input-field w-full", coordsError ? "border-red-500" : ""].join(" ")}
                      placeholder="-0.03889, -51.06639"
                    />
                    <button type="button" onClick={applyCoords} className="btn-outline shrink-0 text-sm">
                      Usar
                    </button>
                  </div>
                  <p id={`${ids.coords}-hint`} className="mt-1 text-xs text-text-muted">
                    No Google Maps: clique com o botao direito no local e copie os numeros.
                  </p>
                  {coordsError && (
                    <p role="alert" className="mt-1 text-sm text-red-600">
                      {coordsError}
                    </p>
                  )}
                </div>

                <div>
                  <label htmlFor={ids.radius} className="mb-1 block text-sm font-medium text-text-primary">
                    Raio (km)
                  </label>
                  <input
                    id={ids.radius}
                    type="number"
                    min={RADIUS_MIN}
                    max={RADIUS_MAX}
                    step={1}
                    value={radiusKm}
                    onChange={(e) => {
                      const n = Math.round(Number(e.target.value));
                      setRadiusKm(Number.isFinite(n) ? Math.min(Math.max(n, RADIUS_MIN), RADIUS_MAX) : RADIUS_MIN);
                    }}
                    aria-describedby={`${ids.radius}-hint`}
                    className="input-field w-full"
                  />
                  <p id={`${ids.radius}-hint`} className="mt-1 text-xs text-text-muted">
                    De {RADIUS_MIN} a {RADIUS_MAX} km (limite da Meta). Para delivery, 3 a 8 km
                    costuma render mais.
                  </p>
                </div>
              </div>

              {lat != null && lng != null && (
                <p className="text-xs text-text-muted" aria-live="polite">
                  Centro: {lat.toFixed(5)}, {lng.toFixed(5)} &middot; raio {radiusKm} km
                </p>
              )}
              {(lat == null || lng == null) && (
                <p className="text-sm text-yellow-700">
                  Marque o centro do publico no mapa (ou cole as coordenadas) para continuar.
                </p>
              )}
            </>
          )}

          {/* ── Passo 4: Criativo e destino ─────────────────────────────── */}
          {step === 3 && (
            <>
              <div>
                <label htmlFor={ids.primaryText} className="mb-1 block text-sm font-medium text-text-primary">
                  Texto principal do anuncio
                </label>
                <textarea
                  id={ids.primaryText}
                  required
                  rows={3}
                  maxLength={2000}
                  value={primaryText}
                  onChange={(e) => setPrimaryText(e.target.value)}
                  className="input-field w-full resize-none"
                  placeholder="Ex: Fome de hamburguer artesanal? Peca agora e receba quentinho em casa."
                />
              </div>

              <div className="grid gap-4 sm:grid-cols-2">
                <div>
                  <label htmlFor={ids.headline} className="mb-1 block text-sm font-medium text-text-primary">
                    Titulo <span className="font-normal text-text-muted">(opcional)</span>
                  </label>
                  <input
                    id={ids.headline}
                    type="text"
                    maxLength={200}
                    value={headline}
                    onChange={(e) => setHeadline(e.target.value)}
                    className="input-field w-full"
                    placeholder="Ex: Peca pelo cardapio digital"
                  />
                </div>
                <div>
                  <label htmlFor={ids.cta} className="mb-1 block text-sm font-medium text-text-primary">
                    Botao (CTA) <span className="font-normal text-text-muted">(opcional)</span>
                  </label>
                  <input
                    id={ids.cta}
                    type="text"
                    maxLength={40}
                    value={cta}
                    onChange={(e) => setCta(e.target.value)}
                    className="input-field w-full"
                    placeholder="Ex: Pedir agora"
                  />
                </div>
              </div>

              {/* Produto (foto do anuncio) */}
              <div>
                <div className="flex items-center justify-between">
                  <label htmlFor={ids.productSearch} className="text-sm font-medium text-text-primary">
                    Foto do anuncio <span className="font-normal text-text-muted">(opcional)</span>
                  </label>
                  {selectedProduct && (
                    <button
                      type="button"
                      onClick={() => setProductId(null)}
                      className="text-xs font-medium text-text-secondary underline underline-offset-2 hover:text-text-primary"
                    >
                      Remover foto
                    </button>
                  )}
                </div>
                <p className="mb-2 mt-0.5 text-xs text-text-muted">
                  Escolha um produto do cardapio: a foto dele vira a imagem do anuncio. Sem produto,
                  o anuncio sai so com o link.
                </p>

                {selectedProduct ? (
                  <div className="flex items-center gap-3 rounded-xl border border-primary-700 bg-bg-secondary p-3 ring-1 ring-primary-700">
                    {selectedProduct.imageUrl && (
                      // eslint-disable-next-line @next/next/no-img-element
                      <img
                        src={selectedProduct.imageUrl}
                        alt=""
                        className="h-12 w-12 shrink-0 rounded-lg object-cover"
                      />
                    )}
                    <span className="min-w-0 flex-1 truncate text-sm font-medium text-text-primary">
                      {selectedProduct.name}
                    </span>
                  </div>
                ) : (
                  <>
                    <input
                      id={ids.productSearch}
                      type="search"
                      value={productQuery}
                      onChange={(e) => setProductQuery(e.target.value)}
                      className="input-field w-full"
                      placeholder="Buscar produto pelo nome"
                    />
                    {catalogState === "loading" && (
                      <p className="mt-2 text-sm text-text-muted">Carregando produtos&hellip;</p>
                    )}
                    {catalogState === "error" && (
                      <p className="mt-2 text-sm text-text-muted">
                        Nao deu para carregar o cardapio.{" "}
                        <button type="button" onClick={() => void loadCatalog()} className="font-medium underline">
                          Tentar de novo
                        </button>{" "}
                        &mdash; ou siga sem foto.
                      </p>
                    )}
                    {catalogState === "ok" && productResults.length === 0 && (
                      <p className="mt-2 text-sm text-text-muted">
                        Nenhum produto ativo com foto encontrado &mdash; o anuncio sai so com o link.
                      </p>
                    )}
                    {catalogState === "ok" && productResults.length > 0 && (
                      <ul
                        role="list"
                        className="mt-2 max-h-48 divide-y divide-border-light overflow-y-auto rounded-xl border border-border-light"
                      >
                        {productResults.map((p) => (
                          <li key={p.id}>
                            <button
                              type="button"
                              onClick={() => setProductId(p.id)}
                              className="flex min-h-11 w-full items-center gap-3 p-2.5 text-left transition-colors hover:bg-bg-secondary"
                            >
                              {p.imageUrl && (
                                // eslint-disable-next-line @next/next/no-img-element
                                <img src={p.imageUrl} alt="" className="h-10 w-10 shrink-0 rounded-lg object-cover" />
                              )}
                              <span className="min-w-0 flex-1 truncate text-sm text-text-primary">{p.name}</span>
                            </button>
                          </li>
                        ))}
                      </ul>
                    )}
                  </>
                )}
              </div>

              {/* Destino */}
              <div>
                <label htmlFor={ids.destination} className="mb-1 block text-sm font-medium text-text-primary">
                  Para onde o clique leva
                </label>
                <input
                  id={ids.destination}
                  type="url"
                  required
                  maxLength={500}
                  value={destinationUrl}
                  onChange={(e) => setDestinationUrl(e.target.value)}
                  aria-describedby={`${ids.destination}-hint`}
                  aria-invalid={destinationUrl !== "" && !urlOk ? true : undefined}
                  className={[
                    "input-field w-full",
                    destinationUrl !== "" && !urlOk ? "border-red-500" : "",
                  ].join(" ")}
                />
                <p id={`${ids.destination}-hint`} className="mt-1 text-xs text-text-muted">
                  Ja sugerimos o seu cardapio digital. Pode trocar pelo link do WhatsApp
                  (https://wa.me/55...).
                </p>
                {destinationUrl !== "" && !urlOk && (
                  <p role="alert" className="mt-1 text-sm text-red-600">
                    Informe um link valido comecando com http:// ou https:// (ate 500 caracteres).
                  </p>
                )}
              </div>
            </>
          )}

          {/* ── Passo 5: Revisao ────────────────────────────────────────── */}
          {step === 4 && (
            <>
              <dl className="divide-y divide-border-light rounded-xl border border-border-light">
                {[
                  ["Conta", account ? (account.accountName ?? `••••${account.accountIdLast4}`) : "—"],
                  ["Pagina", selectedPage ? (selectedPage.name ?? selectedPage.id) : "—"],
                  ["Nome", name.trim()],
                  ["Verba diaria", fmtAdsMoney(budgetCents, currency)],
                  [
                    "Publico",
                    lat != null && lng != null
                      ? `Raio de ${radiusKm} km em torno de ${lat.toFixed(5)}, ${lng.toFixed(5)}`
                      : "—",
                  ],
                  ["Texto", primaryText.trim()],
                  ...(headline.trim() ? [["Titulo", headline.trim()] as [string, string]] : []),
                  ...(cta.trim() ? [["Botao", cta.trim()] as [string, string]] : []),
                  ["Foto", selectedProduct ? selectedProduct.name : "Sem foto (anuncio so com link)"],
                  ["Destino", destinationUrl.trim()],
                ].map(([label, value]) => (
                  <div key={label} className="flex gap-3 px-4 py-2.5">
                    <dt className="w-28 shrink-0 text-sm font-medium text-text-muted">{label}</dt>
                    <dd className="min-w-0 flex-1 break-words text-sm text-text-primary">{value}</dd>
                  </div>
                ))}
              </dl>

              <div className="flex items-start gap-2 rounded-xl border border-border-light bg-bg-secondary p-3">
                <PauseCircle className="mt-0.5 h-5 w-5 shrink-0 text-yellow-600" aria-hidden="true" />
                <p className="text-sm text-text-secondary">
                  <span className="font-semibold text-text-primary">
                    A campanha e criada PAUSADA e nao gasta nada ainda.
                  </span>{" "}
                  Depois de criar, revise na lista e clique em <span className="font-medium">Ativar</span>{" "}
                  quando quiser que ela comece a veicular (e gastar a verba diaria).
                </p>
              </div>

              {submitError && (
                <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">
                  {submitError}
                </p>
              )}
            </>
          )}
        </div>

        {/* Footer de navegacao */}
        <div className="flex shrink-0 items-center justify-between gap-3 border-t border-border-light px-6 py-4">
          <button
            type="button"
            onClick={() => (step === 0 ? onClose() : setStep(step - 1))}
            className="btn-outline flex items-center gap-1.5"
          >
            <ChevronLeft className="h-4 w-4" aria-hidden="true" />
            {step === 0 ? "Cancelar" : "Voltar"}
          </button>

          {step < STEPS.length - 1 ? (
            <button
              type="button"
              onClick={handleNext}
              disabled={!canAdvance || savingPage}
              className="btn-primary flex items-center gap-1.5 disabled:opacity-50"
            >
              {savingPage && (
                <span
                  className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent"
                  aria-hidden="true"
                />
              )}
              {savingPage ? "Salvando..." : "Continuar"}
              {!savingPage && <ChevronRight className="h-4 w-4" aria-hidden="true" />}
            </button>
          ) : (
            <button
              type="button"
              onClick={() => void handleCreate()}
              disabled={saving}
              className="btn-primary flex items-center gap-2 disabled:opacity-50"
            >
              {saving && (
                <span
                  className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent"
                  aria-hidden="true"
                />
              )}
              {saving ? "Criando..." : "Criar campanha (pausada)"}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
