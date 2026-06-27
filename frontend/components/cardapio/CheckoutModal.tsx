"use client";

import { useState, useEffect, useRef } from "react";
import { formatBRL } from "@/types/menu";
import { API_BASE } from "@/lib/api";
import type { CartItem } from "./types";

type PaymentMethod = "CASH" | "PIX" | "CREDIT_CARD" | "DEBIT_CARD";

interface Props {
  cart: CartItem[];
  pixKey: string | null;
  tableLabel: string | null;
  tenantSlug: string;
  onClose: () => void;
  onNewOrder: () => void;
}

const PAYMENT_OPTIONS: { value: PaymentMethod; emoji: string; label: string }[] = [
  { value: "CASH",        emoji: "💵", label: "Dinheiro" },
  { value: "PIX",         emoji: "⚡",      label: "PIX" },
  { value: "DEBIT_CARD",  emoji: "💳", label: "Débito" },
  { value: "CREDIT_CARD", emoji: "💳", label: "Crédito" },
];

export function CheckoutModal({
  cart,
  pixKey,
  tableLabel,
  tenantSlug,
  onClose,
  onNewOrder,
}: Props) {
  const [nome, setNome] = useState("");
  const [obs, setObs] = useState("");
  const [payment, setPayment] = useState<PaymentMethod | null>(null);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [successOrderId, setSuccessOrderId] = useState<string | null>(null);
  const modalRef = useRef<HTMLDivElement>(null);

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
    modalRef.current?.focus();
  }, []);

  const total = cart.reduce(
    (sum, i) => sum + i.product.effectivePriceCents * i.quantity,
    0,
  );

  const canSubmit = nome.trim().length > 0 && payment !== null && !sending;

  async function handleSubmit() {
    if (!canSubmit || !payment) return;
    setSending(true);
    setError(null);
    try {
      const res = await fetch(`${API_BASE}/public/${tenantSlug}/orders`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          customerName: nome.trim(),
          paymentMethod: payment,
          tableLabel: tableLabel ?? undefined,
          observations: obs.trim() || undefined,
          items: cart.map((i) => ({ productId: i.product.id, quantity: i.quantity })),
        }),
      });

      if (!res.ok) {
        const data = (await res.json().catch(() => ({}))) as Record<string, unknown>;
        throw new Error(
          typeof data.message === "string" ? data.message : `Erro ${res.status}`,
        );
      }

      const data = (await res.json()) as { id?: string };
      setSuccessOrderId(data.id ?? "");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Erro ao enviar pedido.");
    } finally {
      setSending(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-end sm:items-center justify-center"
      role="dialog"
      aria-modal="true"
      aria-label="Finalizar pedido"
    >
      {/* Overlay */}
      <div className="absolute inset-0 bg-black/50" onClick={onClose} aria-hidden="true" />

      {/* Card */}
      <div
        ref={modalRef}
        tabIndex={-1}
        className="relative bg-bg-primary rounded-t-2xl sm:rounded-2xl w-full sm:max-w-md p-6 outline-none max-h-[90vh] overflow-y-auto"
      >
        {successOrderId !== null ? (
          /* Estado de sucesso */
          <div className="text-center py-4">
            <div className="text-5xl mb-4" aria-hidden="true">✅</div>
            <h2 className="text-xl font-bold text-text-primary mb-2">Pedido enviado!</h2>
            {successOrderId && (
              <p className="text-sm text-text-muted mb-2">Pedido #{successOrderId}</p>
            )}
            <p className="text-text-secondary mb-6">
              Seu pedido foi enviado! Aguarde a preparação.
            </p>
            <button onClick={onNewOrder} className="btn-primary w-full min-h-[48px]">
              Novo Pedido
            </button>
          </div>
        ) : (
          /* Formulario */
          <>
            <h2 className="text-xl font-bold text-text-primary mb-5">Finalizar Pedido</h2>

            {/* Total resumo */}
            <div className="bg-bg-secondary rounded-lg p-3 mb-4 flex justify-between items-center">
              <span className="text-text-secondary">Total</span>
              <span className="text-lg font-bold text-text-primary">{formatBRL(total)}</span>
            </div>

            {/* Nome */}
            <div className="form-group">
              <label className="form-label" htmlFor="checkout-name">
                Seu nome{" "}
                <span className="text-error" aria-hidden="true">*</span>
              </label>
              <input
                id="checkout-name"
                type="text"
                className="input-field"
                placeholder="Seu nome"
                value={nome}
                onChange={(e) => setNome(e.target.value)}
                autoComplete="given-name"
                required
              />
            </div>

            {/* Observacoes */}
            <div className="form-group">
              <label className="form-label" htmlFor="checkout-obs">
                Observações{" "}
                <span className="text-text-muted text-xs">(opcional)</span>
              </label>
              <textarea
                id="checkout-obs"
                className="input-field resize-none"
                placeholder="Ex: sem cebola, bem passado…"
                rows={2}
                value={obs}
                onChange={(e) => setObs(e.target.value)}
              />
            </div>

            {/* Pagamento */}
            <div className="form-group">
              <label className="form-label">
                Pagamento{" "}
                <span className="text-error" aria-hidden="true">*</span>
              </label>
              <div className="grid grid-cols-2 gap-2">
                {PAYMENT_OPTIONS.map((opt) => (
                  <button
                    key={opt.value}
                    type="button"
                    onClick={() => setPayment(opt.value)}
                    aria-pressed={payment === opt.value}
                    className={[
                      "flex items-center gap-2 px-3 py-2 rounded-lg border font-medium text-sm transition-colors min-h-[48px]",
                      payment === opt.value
                        ? "bg-primary-700 text-white border-primary-700"
                        : "bg-bg-primary text-text-primary border-border-medium hover:bg-bg-tertiary",
                    ].join(" ")}
                  >
                    <span aria-hidden="true">{opt.emoji}</span>
                    {opt.label}
                  </button>
                ))}
              </div>
            </div>

            {/* Chave PIX */}
            {payment === "PIX" && pixKey && (
              <div
                className="bg-bg-secondary rounded-lg p-3 text-sm mb-4"
                role="note"
                aria-label="Chave PIX para pagamento"
              >
                <p className="font-medium text-text-primary mb-1">Chave PIX:</p>
                <p className="text-text-secondary break-all font-mono">{pixKey}</p>
                <p className="text-text-muted mt-2">
                  Realize o pagamento e confirme abaixo.
                </p>
              </div>
            )}

            {/* Erro */}
            {error && (
              <p className="text-error text-sm mb-4" role="alert">
                {error}
              </p>
            )}

            {/* Acoes */}
            <div className="flex gap-3">
              <button
                type="button"
                onClick={onClose}
                className="btn-outline flex-1 min-h-[48px]"
              >
                Cancelar
              </button>
              <button
                type="button"
                onClick={() => { void handleSubmit(); }}
                disabled={!canSubmit}
                className="btn-primary flex-1 min-h-[48px] flex items-center justify-center gap-2"
              >
                {sending && (
                  <svg
                    className="animate-spin h-4 w-4 flex-shrink-0"
                    viewBox="0 0 24 24"
                    fill="none"
                    aria-hidden="true"
                  >
                    <circle
                      className="opacity-25"
                      cx="12"
                      cy="12"
                      r="10"
                      stroke="currentColor"
                      strokeWidth="4"
                    />
                    <path
                      className="opacity-75"
                      fill="currentColor"
                      d="M4 12a8 8 0 018-8v8H4z"
                    />
                  </svg>
                )}
                {sending ? "Enviando..." : "Confirmar Pedido"}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
