"use client";

import { useEffect, useRef, useState } from "react";
import type { Product } from "@/types/menu";
import { formatBRL } from "@/types/menu";

interface Props {
  product: Product;
  onClose: () => void;
  onAdd: (quantity: number, notes?: string) => void;
}

export function ProductDetailModal({ product, onClose, onAdd }: Props) {
  const [qty, setQty] = useState(1);
  const [notes, setNotes] = useState("");
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

  function handleAdd() {
    onAdd(qty, notes.trim() || undefined);
    onClose();
  }

  const subtotal = product.effectivePriceCents * qty;

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
