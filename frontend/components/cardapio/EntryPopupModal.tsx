"use client";

import { useRef } from "react";
import type { Product } from "@/types/menu";
import { formatBRL } from "@/types/menu";
import { useModalA11y } from "@/lib/use-modal-a11y";

// Pop-up de entrada do cardápio público (issue #13). Aparece 1x por sessão (o
// controle de sessionStorage fica no /cardapio, que decide QUANDO montar isto).
// Cada card é clicável inteiro → fecha o pop-up e abre o ProductDetailModal do
// produto (o pop-up vende, não só exibe). A cor de realce usa as CSS vars do tema
// (--mf-primary / --mf-on-primary) herdadas do <main> do cardápio.

interface Props {
  title: string | null;
  products: Product[];
  /** Respeita o toggle de exibição de preços do tema (§ decisão do dono). */
  showPrices: boolean;
  onSelectProduct: (product: Product) => void;
  onClose: () => void;
}

export function EntryPopupModal({ title, products, showPrices, onSelectProduct, onClose }: Props) {
  const panelRef = useRef<HTMLDivElement>(null);
  useModalA11y(panelRef, onClose);

  return (
    <div className="fixed inset-0 z-[70] flex items-end sm:items-center justify-center">
      {/* Backdrop: clique fecha (saída óbvia para quem não quer o destaque). */}
      <div className="absolute inset-0 bg-black/60" aria-hidden="true" onClick={onClose} />

      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="mf-popup-title"
        className="relative z-[71] w-full sm:max-w-md max-h-[85vh] overflow-y-auto rounded-t-2xl sm:rounded-2xl bg-bg-primary p-5 shadow-xl animate-slide-up"
      >
        <div className="mb-4 flex items-start justify-between gap-3">
          <h2 id="mf-popup-title" className="text-lg font-bold text-text-primary">
            {title?.trim() ? title : "Destaques"}
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Fechar destaques"
            className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full text-text-muted hover:bg-bg-tertiary"
          >
            <span aria-hidden="true" className="text-xl leading-none">×</span>
          </button>
        </div>

        <ul role="list" className="flex flex-col gap-3">
          {products.map((p) => (
            <li key={p.id}>
              <button
                type="button"
                onClick={() => onSelectProduct(p)}
                className="flex w-full items-center gap-3 rounded-xl border border-border-light p-3 text-left transition-colors hover:border-border-medium hover:bg-bg-secondary min-h-11"
              >
                {p.imageUrl ? (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img
                    src={p.imageUrl}
                    alt={p.name}
                    className="h-14 w-14 shrink-0 rounded-lg object-cover"
                  />
                ) : (
                  <div
                    aria-hidden="true"
                    className="flex h-14 w-14 shrink-0 items-center justify-center rounded-lg bg-bg-tertiary text-2xl"
                  >
                    {"\u{1F37D}️"}
                  </div>
                )}
                <div className="min-w-0 flex-1">
                  <p className="truncate font-semibold text-text-primary">{p.name}</p>
                  {showPrices && (
                    <p className="mt-0.5 text-sm font-bold text-text-primary">
                      {formatBRL(p.effectivePriceCents)}
                      {p.onPromo && (
                        <span className="ml-1.5 text-xs font-normal text-text-muted line-through">
                          {formatBRL(p.priceCents)}
                        </span>
                      )}
                    </p>
                  )}
                </div>
                <span
                  aria-hidden="true"
                  className="shrink-0 rounded-full px-3 py-1.5 text-sm font-semibold bg-[var(--mf-primary)] text-[var(--mf-on-primary)]"
                >
                  Ver
                </span>
              </button>
            </li>
          ))}
        </ul>

        <button
          type="button"
          onClick={onClose}
          className="mt-4 w-full rounded-xl border border-border-medium py-2.5 text-sm font-medium text-text-secondary hover:bg-bg-secondary min-h-11"
        >
          Ver cardápio completo
        </button>
      </div>
    </div>
  );
}
