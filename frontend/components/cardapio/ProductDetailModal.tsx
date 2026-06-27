"use client";

import { useEffect, useRef, useState } from "react";
import type { Product } from "@/types/menu";
import { formatBRL } from "@/types/menu";
import type { CartLineOption } from "./types";

interface Props {
  product: Product;
  onClose: () => void;
  onAdd: (quantity: number, notes?: string, options?: CartLineOption[]) => void;
}

export function ProductDetailModal({ product, onClose, onAdd }: Props) {
  const [qty, setQty] = useState(1);
  const [notes, setNotes] = useState("");
  const [selectedOptions, setSelectedOptions] = useState<Record<string, string[]>>({});
  const [validationError, setValidationError] = useState<string | null>(null);
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const handleKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    document.addEventListener("keydown", handleKey);
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", handleKey);
      document.body.style.overflow = "";
    };
  }, [onClose]);

  useEffect(() => { closeRef.current?.focus(); }, []);

  function toggleOption(groupId: string, optionId: string, maxSelect: number) {
    setValidationError(null);
    setSelectedOptions(prev => {
      const current = prev[groupId] ?? [];
      if (current.includes(optionId)) {
        return { ...prev, [groupId]: current.filter(id => id !== optionId) };
      }
      if (maxSelect === 1) {
        return { ...prev, [groupId]: [optionId] };
      }
      if (current.length >= maxSelect) return prev;
      return { ...prev, [groupId]: [...current, optionId] };
    });
  }

  function handleAdd() {
    const missing = (product.optionGroups ?? []).filter(g =>
      g.required && (selectedOptions[g.id]?.length ?? 0) === 0
    );
    if (missing.length > 0) {
      setValidationError(`Selecione: ${missing.map(g => g.name).join(", ")}`);
      return;
    }
    const options: CartLineOption[] = (product.optionGroups ?? []).flatMap(g =>
      (selectedOptions[g.id] ?? []).map(optId => {
        const opt = g.options.find(o => o.id === optId)!;
        return { optionId: opt.id, groupName: g.name, optionName: opt.name, priceCents: opt.priceCents };
      })
    );
    onAdd(qty, notes.trim() || undefined, options.length > 0 ? options : undefined);
    onClose();
  }

  const extrasCents = Object.entries(selectedOptions).flatMap(([groupId, optIds]) => {
    const group = (product.optionGroups ?? []).find(g => g.id === groupId);
    return optIds.map(optId => group?.options.find(o => o.id === optId)?.priceCents ?? 0);
  }).reduce((s, v) => s + v, 0);

  const subtotal = (product.effectivePriceCents + extrasCents) * qty;

  return (
    <>
      <div className="fixed inset-0 bg-black/50 z-50" onClick={onClose} aria-hidden="true" />
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="pdm-title"
        className="fixed inset-x-0 bottom-0 z-[60] bg-bg-primary rounded-t-2xl max-h-[90vh] flex flex-col overflow-hidden"
      >
        {/* Handle */}
        <div className="flex justify-center pt-3 pb-1">
          <div className="w-10 h-1.5 bg-border-medium rounded-full" aria-hidden="true" />
        </div>

        <div className="overflow-y-auto flex-1">
          {/* Foto */}
          {product.imageUrl ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={product.imageUrl} alt={product.name} className="w-full h-48 object-cover" />
          ) : (
            <div className="w-full h-48 bg-bg-tertiary flex items-center justify-center text-6xl" aria-hidden="true">
              {"\u{1F37D}️"}
            </div>
          )}

          <div className="p-4">
            {/* Badges */}
            <div className="flex flex-wrap gap-1 mb-2">
              {product.onPromo && (
                <span className="text-xs font-bold px-2 py-0.5 rounded-full bg-amber-100 text-amber-800 border border-amber-300">PROMO</span>
              )}
              {product.isFeatured && (
                <span className="text-xs font-bold px-2 py-0.5 rounded-full bg-primary-100 text-primary-800 border border-primary-300">DESTAQUE</span>
              )}
            </div>

            <h2 id="pdm-title" className="text-xl font-bold text-text-primary">{product.name}</h2>

            {product.description && (
              <p className="text-text-secondary mt-1 text-sm leading-relaxed">{product.description}</p>
            )}

            <div className="mt-2 flex items-baseline gap-2">
              <span className="text-2xl font-bold text-primary-600">{formatBRL(product.effectivePriceCents)}</span>
              {product.onPromo && (
                <span className="text-sm text-text-muted line-through">{formatBRL(product.priceCents)}</span>
              )}
            </div>

            {/* Grupos de complementos */}
            {(product.optionGroups ?? []).filter(g => g.options.length > 0).map(group => (
              <div key={group.id} className="mt-4">
                <div className="flex items-center justify-between mb-2">
                  <p className="text-sm font-semibold text-text-primary">{group.name}</p>
                  <span className={`text-xs px-2 py-0.5 rounded-full ${
                    group.required
                      ? "bg-red-100 text-red-700"
                      : "bg-bg-tertiary text-text-muted"
                  }`}>
                    {group.required ? "Obrigatorio" : "Opcional"}
                    {group.maxSelect > 1 ? ` (ate ${group.maxSelect})` : ""}
                  </span>
                </div>
                <div className="space-y-1.5">
                  {group.options.map(opt => {
                    const checked = (selectedOptions[group.id] ?? []).includes(opt.id);
                    const atLimit = !checked &&
                      group.maxSelect > 1 &&
                      (selectedOptions[group.id]?.length ?? 0) >= group.maxSelect;
                    return (
                      <button
                        key={opt.id}
                        type="button"
                        disabled={atLimit}
                        onClick={() => toggleOption(group.id, opt.id, group.maxSelect)}
                        className={[
                          "w-full flex items-center justify-between px-3 py-2.5 rounded-lg border transition-colors text-left",
                          checked
                            ? "border-primary-700 bg-primary-50"
                            : atLimit
                            ? "border-border-light bg-bg-secondary opacity-50 cursor-not-allowed"
                            : "border-border-light bg-bg-secondary hover:border-border-medium",
                        ].join(" ")}
                      >
                        <div className="flex items-center gap-2.5">
                          <div className={[
                            "w-4 h-4 flex-shrink-0 flex items-center justify-center",
                            group.maxSelect === 1
                              ? "rounded-full border-2 " + (checked ? "border-primary-700 bg-primary-700" : "border-border-medium")
                              : "rounded " + (checked ? "bg-primary-700 border-primary-700 border-2" : "border-2 border-border-medium"),
                          ].join(" ")}>
                            {checked && (
                              <span className="text-white text-[10px] font-bold leading-none">
                                {group.maxSelect === 1 ? "●" : "✓"}
                              </span>
                            )}
                          </div>
                          <span className="text-sm text-text-primary">{opt.name}</span>
                        </div>
                        {opt.priceCents > 0 && (
                          <span className="text-sm text-text-secondary shrink-0">
                            +{formatBRL(opt.priceCents)}
                          </span>
                        )}
                      </button>
                    );
                  })}
                </div>
                {group.required && (selectedOptions[group.id]?.length ?? 0) === 0 && validationError && (
                  <p className="text-xs text-red-600 mt-1">Selecione ao menos uma opcao</p>
                )}
              </div>
            ))}

            {/* Observacoes por item */}
            <div className="mt-4">
              <label htmlFor="pdm-notes" className="block text-sm font-medium text-text-primary mb-1">
                Alguma observacao?
              </label>
              <textarea
                id="pdm-notes"
                value={notes}
                onChange={(e) => setNotes(e.target.value)}
                placeholder="Ex.: sem cebola, bem passado..."
                maxLength={200}
                rows={2}
                className="w-full rounded-lg border border-border-medium bg-bg-secondary px-3 py-2 text-sm text-text-primary placeholder:text-text-muted resize-none focus:outline-none focus:ring-2 focus:ring-primary-500"
              />
            </div>

            {/* Stepper de quantidade */}
            <div className="mt-4 flex items-center justify-between">
              <span className="text-sm font-medium text-text-primary">Quantidade</span>
              <div className="flex items-center gap-3">
                <button
                  onClick={() => setQty((q) => Math.max(1, q - 1))}
                  className="w-10 h-10 rounded-full bg-bg-tertiary text-text-primary flex items-center justify-center font-bold text-xl hover:bg-border-light"
                  aria-label="Diminuir quantidade"
                >
                  {"−"}
                </button>
                <span className="w-8 text-center font-bold text-text-primary text-lg select-none">{qty}</span>
                <button
                  onClick={() => setQty((q) => Math.min(99, q + 1))}
                  className="w-10 h-10 rounded-full bg-primary-700 text-white flex items-center justify-center font-bold text-xl hover:bg-primary-800"
                  aria-label="Aumentar quantidade"
                >
                  +
                </button>
              </div>
            </div>
          </div>
        </div>

        {/* Footer fixo */}
        <div className="p-4 border-t border-border-light bg-bg-primary">
          {validationError && (
            <p className="text-xs text-red-600 mb-2 text-center" role="alert">
              {validationError}
            </p>
          )}
          <button
            ref={closeRef}
            onClick={handleAdd}
            className="btn-primary w-full py-3 text-base min-h-[48px]"
          >
            Adicionar {"·"} {formatBRL(subtotal)}
          </button>
        </div>
      </div>
    </>
  );
}
