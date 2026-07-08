"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { api } from "@/lib/api";
import { ConfigSection, SaveButton, SectionRetry } from "@/components/config/ConfigSection";
import { Toggle } from "@/components/ui/Toggle";
import type { Page, Product } from "@/types/menu";
import { formatBRL } from "@/types/menu";
import type { EntryPopupConfig, PopupDraft } from "@/types/personalization";

// Seção "Pop-up de entrada" (issue #13): toggle ativo, título com contador, busca de
// produto existente (GET /products?size=300, mesmo endpoint do admin/cardápio — filtro
// client-side, padrão da casa), seleção de até 3 com ordem, badge de produto obsoleto.
// Controlado pelo pai (value/onChange) p/ alimentar o preview; o save (PUT atômico) mora aqui.

const MAX_PRODUCTS = 3;
const TITLE_MAX = 120;

interface Props {
  value: PopupDraft;
  onChange: (next: PopupDraft) => void;
  dirty: boolean;
  onSaved: (cfg: EntryPopupConfig) => void;
  showToast: (msg: string, type?: "success" | "error") => void;
  onPreviewPopup: () => void;
}

function normalize(s: string): string {
  return s.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase().trim();
}

export function EntryPopupSection({ value, onChange, dirty, onSaved, showToast, onPreviewPopup }: Props) {
  const [saving, setSaving] = useState(false);
  const [catalog, setCatalog] = useState<Product[]>([]);
  const [catalogState, setCatalogState] = useState<"loading" | "error" | "ok">("loading");
  const [query, setQuery] = useState("");

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
    queueMicrotask(() => void loadCatalog());
  }, [loadCatalog]);

  const byId = useMemo(() => new Map(catalog.map((p) => [p.id, p])), [catalog]);

  // Produtos obsoletos entre os selecionados (desativados depois de entrar no pop-up).
  const obsoleteIds = value.productIds.filter((id) => byId.get(id)?.active === false);
  const hasObsolete = obsoleteIds.length > 0;

  // Lista de busca: só ativos, ainda não selecionados, casando o texto digitado.
  const results = useMemo(() => {
    const q = normalize(query);
    return catalog
      .filter((p) => p.active && !value.productIds.includes(p.id))
      .filter((p) => q === "" || normalize(p.name).includes(q))
      .slice(0, 30);
  }, [catalog, query, value.productIds]);

  const atLimit = value.productIds.length >= MAX_PRODUCTS;

  function addProduct(id: string) {
    if (value.productIds.includes(id) || value.productIds.length >= MAX_PRODUCTS) return;
    onChange({ ...value, productIds: [...value.productIds, id] });
  }

  function removeProduct(id: string) {
    onChange({ ...value, productIds: value.productIds.filter((x) => x !== id) });
  }

  function move(index: number, dir: -1 | 1) {
    const next = [...value.productIds];
    const target = index + dir;
    if (target < 0 || target >= next.length) return;
    [next[index], next[target]] = [next[target], next[index]];
    onChange({ ...value, productIds: next });
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (saving || hasObsolete) return;
    setSaving(true);
    try {
      const updated = await api.put<EntryPopupConfig>("/config/entry-popup", {
        enabled: value.enabled,
        title: value.title.trim() === "" ? null : value.title.trim(),
        productIds: value.productIds,
      });
      onSaved(updated);
      showToast("Pop-up salvo.");
    } catch (err) {
      showToast(err instanceof Error ? err.message : "Falha ao salvar o pop-up.", "error");
    } finally {
      setSaving(false);
    }
  }

  return (
    <ConfigSection
      id="popup"
      title="Pop-up de entrada"
      description="Um destaque que aparece quando o cliente abre seu cardápio. Bom para promoções e carro-chefe."
    >
      <form onSubmit={handleSubmit} className="space-y-5">
        {/* Cabeçalho: toggle ativo com estado em texto */}
        <div className="flex items-center justify-between gap-4">
          <div>
            <p className="text-sm font-medium text-text-primary">Ativo</p>
            <p className="text-xs text-text-muted">
              {value.enabled ? "O cliente vê o pop-up ao abrir o cardápio." : "Desligado — o cliente não vê o pop-up."}
            </p>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-xs text-text-muted" aria-hidden="true">{value.enabled ? "Ativo" : "Desligado"}</span>
            <Toggle
              id="popup-enabled"
              checked={value.enabled}
              onChange={(next) => onChange({ ...value, enabled: next })}
              label={`Pop-up ${value.enabled ? "ativo" : "desligado"}`}
            />
          </div>
        </div>

        {/* Corpo: fica opaco (mas editável) quando desligado — o dono pode montar antes de ligar */}
        <div className={value.enabled ? "" : "opacity-50"}>
          {/* Título */}
          <div>
            <label htmlFor="popup-title" className="block text-sm font-medium text-text-primary">
              Título do pop-up
            </label>
            <input
              id="popup-title"
              type="text"
              value={value.title}
              maxLength={TITLE_MAX}
              onChange={(e) => onChange({ ...value, title: e.target.value })}
              placeholder="Ex.: Destaques da casa"
              aria-describedby="popup-title-count"
              className="input-field mt-1 w-full"
            />
            <p
              id="popup-title-count"
              className="mt-1 text-right text-xs text-text-muted"
              aria-live={value.title.length >= 100 ? "polite" : "off"}
            >
              {value.title.length}/{TITLE_MAX}
            </p>
          </div>

          {/* Seletor de produtos */}
          <div className="mt-4">
            <div className="flex items-center justify-between">
              <label htmlFor="popup-search" className="text-sm font-medium text-text-primary">
                Produtos em destaque
              </label>
              <span className="text-xs text-text-muted" aria-live="polite">
                {value.productIds.length}/{MAX_PRODUCTS} selecionados
              </span>
            </div>
            {atLimit && (
              <p className="mt-1 text-xs text-text-muted">Máximo de {MAX_PRODUCTS} — remova um para trocar.</p>
            )}

            <input
              id="popup-search"
              type="search"
              role="searchbox"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Buscar produto pelo nome"
              className="input-field mt-2 w-full"
            />

            {catalogState === "loading" && (
              <p className="mt-3 text-sm text-text-muted">Carregando produtos…</p>
            )}
            {catalogState === "error" && <SectionRetry onRetry={() => void loadCatalog()} />}
            {catalogState === "ok" && catalog.length === 0 && (
              <p className="mt-3 text-sm text-text-muted">
                Nenhum produto cadastrado — cadastre em Cardápio primeiro.
              </p>
            )}
            {catalogState === "ok" && catalog.length > 0 && (
              <ul role="list" className="mt-2 max-h-64 divide-y divide-border-light overflow-y-auto rounded-xl border border-border-light">
                {results.length === 0 ? (
                  <li className="p-3 text-sm text-text-muted">Nenhum produto encontrado.</li>
                ) : (
                  results.map((p) => {
                    const disabled = atLimit;
                    return (
                      <li key={p.id}>
                        <button
                          type="button"
                          disabled={disabled}
                          onClick={() => addProduct(p.id)}
                          className="flex min-h-11 w-full items-center gap-3 p-2.5 text-left transition-colors hover:bg-bg-secondary disabled:cursor-not-allowed disabled:opacity-50"
                        >
                          {p.imageUrl ? (
                            // eslint-disable-next-line @next/next/no-img-element
                            <img src={p.imageUrl} alt="" className="h-10 w-10 shrink-0 rounded-lg object-cover" />
                          ) : (
                            <div aria-hidden="true" className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-bg-tertiary">🍽️</div>
                          )}
                          <span className="min-w-0 flex-1 truncate text-sm text-text-primary">{p.name}</span>
                          <span className="shrink-0 text-sm text-text-secondary">{formatBRL(p.effectivePriceCents)}</span>
                          <span aria-hidden="true" className="shrink-0 text-lg text-text-muted">＋</span>
                        </button>
                      </li>
                    );
                  })
                )}
              </ul>
            )}
          </div>

          {/* Selecionados (ordem de exibição) */}
          <div className="mt-4">
            <p className="text-sm font-medium text-text-primary">Selecionados (ordem de exibição)</p>
            {value.productIds.length === 0 ? (
              <p className="mt-2 rounded-xl border border-dashed border-border-light p-4 text-sm text-text-muted">
                Escolha até {MAX_PRODUCTS} produtos para destacar quando o cliente abrir o cardápio.
              </p>
            ) : (
              <ul role="list" className="mt-2 flex flex-col gap-2">
                {value.productIds.map((id, index) => {
                  const p = byId.get(id);
                  const obsolete = p?.active === false;
                  return (
                    <li
                      key={id}
                      className="flex items-center gap-2 rounded-xl border border-border-light p-2.5"
                    >
                      <span className="w-5 shrink-0 text-center text-sm font-semibold text-text-muted">{index + 1}.</span>
                      <span className="min-w-0 flex-1 truncate text-sm text-text-primary">
                        {p?.name ?? "Produto"}
                        {obsolete && (
                          <span className="ml-2 inline-flex items-center gap-1 rounded-full bg-warning-light px-2 py-0.5 text-xs font-medium text-warning-dark">
                            <span aria-hidden="true">⚠</span> desativado no cardápio — troque
                          </span>
                        )}
                      </span>
                      <div className="flex shrink-0 items-center gap-1">
                        <button
                          type="button"
                          onClick={() => move(index, -1)}
                          disabled={index === 0}
                          aria-label={`Mover ${p?.name ?? "produto"} para cima`}
                          className="flex h-11 w-11 items-center justify-center rounded-lg text-text-secondary hover:bg-bg-tertiary disabled:opacity-30"
                        >
                          <span aria-hidden="true">↑</span>
                        </button>
                        <button
                          type="button"
                          onClick={() => move(index, 1)}
                          disabled={index === value.productIds.length - 1}
                          aria-label={`Mover ${p?.name ?? "produto"} para baixo`}
                          className="flex h-11 w-11 items-center justify-center rounded-lg text-text-secondary hover:bg-bg-tertiary disabled:opacity-30"
                        >
                          <span aria-hidden="true">↓</span>
                        </button>
                        <button
                          type="button"
                          onClick={() => removeProduct(id)}
                          aria-label={`Remover ${p?.name ?? "produto"}`}
                          className="flex h-11 w-11 items-center justify-center rounded-lg text-text-secondary hover:bg-bg-tertiary"
                        >
                          <span aria-hidden="true">✕</span>
                        </button>
                      </div>
                    </li>
                  );
                })}
              </ul>
            )}
            {hasObsolete && (
              <p className="mt-2 text-sm text-red-600" role="alert">
                Remova o produto desativado antes de salvar.
              </p>
            )}
          </div>
        </div>

        <div className="flex flex-wrap items-center justify-between gap-3">
          <button
            type="button"
            onClick={onPreviewPopup}
            className="text-sm font-medium text-text-secondary underline underline-offset-2 hover:text-text-primary min-h-11"
          >
            <span aria-hidden="true">👁</span> Ver como o cliente vê
          </button>
          <SaveButton saving={saving} label={dirty ? "Salvar pop-up •" : "Salvar pop-up"} disabled={hasObsolete} />
        </div>
      </form>
    </ConfigSection>
  );
}
