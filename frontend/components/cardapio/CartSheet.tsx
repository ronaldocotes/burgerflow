"use client";

import { useEffect, useRef } from "react";
import { formatBRL } from "@/types/menu";
import type { CartLine, CartAction } from "./types";

interface Props {
  cart: CartLine[];
  dispatch: (action: CartAction) => void;
  onClose: () => void;
  onCheckout: () => void;
}

export function CartSheet({ cart, dispatch, onClose, onCheckout }: Props) {
  const total = cart.reduce(
    (sum, l) => sum + l.product.effectivePriceCents * l.quantity,
    0,
  );
  const sheetRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", handleKey);
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", handleKey);
      document.body.style.overflow = "";
    };
  }, [onClose]);

  useEffect(() => {
    sheetRef.current?.focus();
  }, []);

  return (
    <>
      {/* Overlay */}
      <div
        className="fixed inset-0 bg-black/40 z-40"
        onClick={onClose}
        aria-hidden="true"
      />

      {/* Sheet */}
      <div
        ref={sheetRef}
        role="dialog"
        aria-modal="true"
        aria-label="Carrinho de compras"
        tabIndex={-1}
        className="fixed bottom-0 left-0 right-0 z-50 bg-bg-primary rounded-t-2xl max-h-[85vh] flex flex-col outline-none"
      >
        {/* Handle visual */}
        <div className="flex justify-center pt-3 pb-1">
          <div className="w-10 h-1.5 bg-border-medium rounded-full" aria-hidden="true" />
        </div>

        {/* Header */}
        <div className="flex items-center justify-between px-4 pb-3 border-b border-border-light">
          <h2 className="text-lg font-bold text-text-primary">Carrinho</h2>
          <button
            onClick={onClose}
            className="min-h-[48px] min-w-[48px] flex items-center justify-center rounded-full hover:bg-bg-tertiary text-text-secondary"
            aria-label="Fechar carrinho"
          >
            {'✕'}
          </button>
        </div>

        {/* Lines */}
        <div className="flex-1 overflow-y-auto px-4 py-3 space-y-2">
          {cart.length === 0 ? (
            <div className="empty-state">
              <p className="empty-state-title">Carrinho vazio</p>
              <p className="empty-state-description">Adicione produtos para continuar.</p>
            </div>
          ) : (
            cart.map((line) => (
              <div
                key={line.lineId}
                className="flex items-center gap-3 py-2 border-b border-border-light last:border-0"
              >
                <div className="flex-1 min-w-0">
                  <p className="font-medium text-text-primary truncate">{line.product.name}</p>
                  {line.notes && (
                    <p className="text-xs text-text-muted mt-0.5 italic">{line.notes}</p>
                  )}
                  <p className="text-sm text-text-muted">
                    {formatBRL(line.product.effectivePriceCents)} un.
                  </p>
                </div>

                {/* Quantity controls */}
                <div className="flex items-center gap-2 flex-shrink-0">
                  <button
                    onClick={() =>
                      dispatch({ type: "DECREMENT_LINE", lineId: line.lineId })
                    }
                    className="min-h-[48px] min-w-[48px] rounded-full bg-bg-tertiary text-text-primary flex items-center justify-center font-bold text-lg hover:bg-border-light"
                    aria-label={`Remover um ${line.product.name}`}
                  >
                    {'−'}
                  </button>
                  <span className="w-6 text-center font-semibold text-text-primary select-none">
                    {line.quantity}
                  </span>
                  <button
                    onClick={() =>
                      dispatch({ type: "INCREMENT_LINE", lineId: line.lineId })
                    }
                    className="min-h-[48px] min-w-[48px] rounded-full bg-primary-700 text-white flex items-center justify-center font-bold text-lg hover:bg-primary-800"
                    aria-label={`Adicionar mais ${line.product.name}`}
                  >
                    +
                  </button>
                </div>

                {/* Subtotal */}
                <div className="w-20 text-right flex-shrink-0">
                  <p className="font-semibold text-text-primary">
                    {formatBRL(line.product.effectivePriceCents * line.quantity)}
                  </p>
                </div>
              </div>
            ))
          )}
        </div>

        {/* Footer */}
        {cart.length > 0 && (
          <div className="px-4 py-4 border-t border-border-light bg-bg-primary">
            <div className="flex justify-between items-center mb-4">
              <span className="text-text-secondary font-medium">Total</span>
              <span className="text-xl font-bold text-text-primary">{formatBRL(total)}</span>
            </div>
            <button
              onClick={onCheckout}
              className="btn-primary w-full py-3 text-base min-h-[48px]"
            >
              Finalizar Pedido
            </button>
          </div>
        )}
      </div>
    </>
  );
}
