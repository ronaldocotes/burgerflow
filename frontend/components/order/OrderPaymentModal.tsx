"use client";

// Modal de pagamento: escolhe a forma, calcula troco, registra o pedido
// (POST /orders + Idempotency-Key) e, se PIX, encadeia o PixPaymentModal.
// Movido de app/pdv/page.tsx (PaymentModal → OrderPaymentModal, extração
// behavior-preserving, fatia 1).

import { useMemo, useRef, useState } from "react";
import Link from "next/link";
import { api, ApiError } from "@/lib/api";
import { useModalA11y } from "@/lib/use-modal-a11y";
import { formatBRL } from "@/types/menu";
import {
  CartLine,
  OrderCreateInput,
  OrderItemInput,
  OrderType,
  PaymentMethod,
  QuoteResponse,
} from "@/types/cart";
import { OrderCreatedResponse, PaymentIntentResponse } from "./types";
import { PixPaymentModal } from "./PixPaymentModal";

const PAYMENT_LABELS: Record<PaymentMethod, string> = {
  CASH: "Dinheiro",
  CREDIT_CARD: "Crédito",
  DEBIT_CARD: "Débito",
  PIX: "Pix",
  OTHER: "Outro",
};

export function OrderPaymentModal({
  quote,
  orderType,
  items,
  cart,
  couponCode,
  couponDiscountCents,
  hasCashSession,
  loyaltyEnabled,
  notes,
  initialPhone,
  onClose,
  onUnauthorized,
  onConfirmed,
}: {
  quote: QuoteResponse;
  orderType: OrderType;
  items: OrderItemInput[];
  cart: CartLine[];
  couponCode: string | null;
  couponDiscountCents: number;
  hasCashSession: boolean;
  loyaltyEnabled: boolean;
  /** Observação do pedido inteiro (ex.: vinda do Novo Pedido em /pedidos); opcional. */
  notes?: string;
  /** Telefone já digitado antes de abrir o pagamento (evita re-pedir o dado — WCAG 3.3.7). */
  initialPhone?: string;
  onClose: () => void;
  onUnauthorized: () => void;
  /** customerPhone = telefone enviado no pedido (digitos), ou null se nao informado.
   *  orderId = id do pedido criado (POST /orders), para quem precisa selecioná-lo depois. */
  onConfirmed: (customerPhone: string | null, orderId: string) => void;
}) {
  const [method, setMethod] = useState<PaymentMethod>("CASH");
  const [received, setReceived] = useState(""); // valor recebido em reais (texto)
  // Telefone do cliente (opcional): avisos WhatsApp (2.4) + fidelidade (3.3).
  // So vai no pedido com DDD completo (>= 10 digitos); menos que isso e ruido.
  const [phone, setPhone] = useState(initialPhone ?? "");
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  // Quando PIX: guarda a intenção de pagamento gerada após criar o pedido
  const [pixIntent, setPixIntent] = useState<PaymentIntentResponse | null>(null);

  const dialogRef = useRef<HTMLDivElement>(null);
  useModalA11y(dialogRef, onClose);

  const receivedCents = useMemo(() => {
    const normalized = received.replace(/\./g, "").replace(",", ".");
    const value = Number.parseFloat(normalized);
    return Number.isFinite(value) ? Math.round(value * 100) : null;
  }, [received]);

  // Total devido na tela: quote menos o cupom pre-validado. O valor final
  // continua sendo o do servidor (que revalida o cupom ao criar o pedido).
  const dueCents = Math.max(0, quote.totalCents - couponDiscountCents);

  const changeCents =
    method === "CASH" && receivedCents != null
      ? receivedCents - dueCents
      : null;

  const insufficientCash =
    method === "CASH" && receivedCents != null && receivedCents < dueCents;

  const sentPhone = (() => {
    const digits = phone.replace(/\D/g, "");
    return digits.length >= 10 && digits.length <= 13 ? digits : null;
  })();

  async function confirm() {
    if (submitting) return;
    setErr(null);
    setSubmitting(true);
    try {
      const body: OrderCreateInput = {
        orderType,
        items,
        paymentMethod: method,
        couponCode: couponCode ?? undefined,
        customerPhone: sentPhone ?? undefined,
        notes: notes?.trim() ? notes.trim() : undefined,
      };
      const created = await api.post<OrderCreatedResponse>("/orders", body, {
        "Idempotency-Key": crypto.randomUUID(),
      });

      if (method === "PIX" && created?.id) {
        // Fluxo PIX: buscar QR e abrir modal dedicado — não chamar onConfirmed() aqui.
        const intent = await api.post<PaymentIntentResponse>("/payments/pix-qr", {
          orderId: created.id,
        });
        setPixIntent(intent);
        setSubmitting(false);
      } else {
        // CASH, CARD, OTHER: encerra normalmente.
        onConfirmed(sentPhone, created.id);
      }
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        onUnauthorized();
        return;
      }
      setErr(e instanceof ApiError ? e.message : "Falha ao registrar o pedido.");
      setSubmitting(false);
    }
  }

  return (
    <>
    <div
      className="fixed inset-0 z-50 flex items-end sm:items-center justify-center bg-black/50 p-0 sm:p-4"
      onClick={onClose}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-label="Pagamento"
        className="bg-bg-primary w-full sm:max-w-md rounded-t-2xl sm:rounded-2xl p-5 space-y-4"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-bold text-text-primary">Pagamento</h2>
          <span className="text-lg font-bold text-primary-700">
            {formatBRL(dueCents)}
          </span>
        </div>

        {/* Resumo dos itens */}
        <div className="rounded-lg border border-border-light bg-bg-secondary divide-y divide-border-light max-h-36 overflow-y-auto">
          {cart.map((line, idx) => {
            const q = quote.items[idx];
            return (
              <div key={line.lineId} className="flex items-center justify-between gap-2 px-3 py-2">
                <div className="min-w-0">
                  <p className="text-sm font-medium text-text-primary truncate">
                    {line.quantity > 1 && <span className="text-primary-700 font-bold mr-1">{line.quantity}×</span>}
                    {line.productName}
                  </p>
                  {line.label && <p className="text-xs text-text-muted truncate">{line.label}</p>}
                </div>
                <span className="shrink-0 text-sm font-semibold text-text-primary">
                  {q ? formatBRL(q.totalPriceCents) : "—"}
                </span>
              </div>
            );
          })}
          {couponCode && couponDiscountCents > 0 && (
            <div className="flex items-center justify-between gap-2 px-3 py-2">
              <p className="text-sm font-medium text-success">
                Cupom {couponCode}
              </p>
              <span className="shrink-0 text-sm font-semibold text-success">
                - {formatBRL(couponDiscountCents)}
              </span>
            </div>
          )}
        </div>

        <div
          role="group"
          aria-label="Forma de pagamento"
          className="grid grid-cols-3 gap-2"
        >
          {(Object.keys(PAYMENT_LABELS) as PaymentMethod[]).map((m) => (
            <button
              key={m}
              type="button"
              onClick={() => setMethod(m)}
              aria-pressed={method === m}
              className={`text-sm py-2 rounded-md border transition-colors ${
                method === m
                  ? "bg-primary-700 text-white border-primary-700"
                  : "bg-bg-secondary text-text-secondary border-border-light hover:bg-bg-tertiary"
              }`}
            >
              {PAYMENT_LABELS[m]}
            </button>
          ))}
        </div>

        <div className="space-y-1">
          <label
            htmlFor="customer-phone"
            className="block text-sm font-medium text-text-secondary"
          >
            Telefone do cliente (opcional)
          </label>
          <input
            id="customer-phone"
            type="tel"
            inputMode="tel"
            autoComplete="off"
            maxLength={16}
            value={phone}
            onChange={(e) => setPhone(e.target.value)}
            placeholder="(96) 99999-9999"
            className="input-field w-full"
          />
          <p className="text-xs text-text-muted">
            {loyaltyEnabled
              ? "Pontos de fidelidade e avisos do pedido por WhatsApp."
              : "Avisos do pedido por WhatsApp."}
          </p>
        </div>

        {method === "CASH" && !hasCashSession && (
          <div className="flex items-start gap-2 rounded-lg border border-warning/40 bg-warning/10 p-3 text-sm">
            <span className="mt-0.5 shrink-0 text-warning" aria-hidden="true">&#9888;</span>
            <span className="text-text-primary">
              Abra o caixa antes de registrar vendas em dinheiro.{" "}
              <Link href="/caixa" className="font-semibold underline hover:text-primary-700">
                Ir para Caixa
              </Link>
            </span>
          </div>
        )}

        {method === "CASH" && hasCashSession && (
          <div className="space-y-2">
            <label
              htmlFor="received"
              className="block text-sm font-medium text-text-secondary"
            >
              Valor recebido (R$)
            </label>
            <input
              id="received"
              type="text"
              inputMode="decimal"
              value={received}
              onChange={(e) => setReceived(e.target.value)}
              placeholder="0,00"
              className="input-field w-full"
            />
            <div className="flex items-center justify-between text-sm">
              <span className="text-text-secondary">Troco</span>
              <span
                className={`font-semibold ${
                  insufficientCash ? "text-error" : "text-text-primary"
                }`}
              >
                {changeCents == null
                  ? "—"
                  : insufficientCash
                    ? "Valor insuficiente"
                    : formatBRL(changeCents)}
              </span>
            </div>
          </div>
        )}

        {err && (
          <p className="text-sm text-error" role="alert">
            {err}
          </p>
        )}

        <div className="flex gap-2 pt-1">
          <button
            type="button"
            className="btn-outline flex-1"
            onClick={onClose}
            disabled={submitting}
          >
            Cancelar
          </button>
          <button
            type="button"
            className="btn-primary flex-1"
            onClick={() => void confirm()}
            disabled={submitting || insufficientCash || (method === "CASH" && !hasCashSession)}
          >
            {submitting ? "Registrando…" : "Confirmar pedido"}
          </button>
        </div>
      </div>
    </div>

    {/* Modal PIX — aparece sobre o OrderPaymentModal quando método = PIX */}
    {pixIntent && (
      <PixPaymentModal
        intent={pixIntent}
        onPaid={() => onConfirmed(sentPhone, pixIntent.orderId)}
        onCancel={() => setPixIntent(null)}
        onUnauthorized={onUnauthorized}
      />
    )}
    </>
  );
}
