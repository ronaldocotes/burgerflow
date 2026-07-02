"use client";

import { useState, useEffect, useRef } from "react";
import { Tag, CheckCircle, XCircle } from "lucide-react";
import { formatBRL } from "@/types/menu";
import { API_BASE } from "@/lib/api";
import type { CartLine } from "./types";
import type { ApplyCouponResponse } from "@/types/coupon";
import {
  DeliveryAddressForm,
  EMPTY_DELIVERY_ADDRESS,
} from "./DeliveryAddressForm";
import type { DeliveryAddress, DeliveryFieldErrors } from "./DeliveryAddressForm";

type PaymentMethod = "CASH" | "PIX" | "CREDIT_CARD" | "DEBIT_CARD";
type OrderType = "TAKEAWAY" | "DELIVERY";

interface Props {
  cart: CartLine[];
  pixKey: string | null;
  tableLabel: string | null;
  tenantSlug: string;
  onClose: () => void;
  onNewOrder: () => void;
}

const PAYMENT_OPTIONS: { value: PaymentMethod; emoji: string; label: string }[] = [
  { value: "CASH",        emoji: "💵", label: "Dinheiro" },
  { value: "PIX",         emoji: "⚡",  label: "PIX" },
  { value: "DEBIT_CARD",  emoji: "💳", label: "Debito" },
  { value: "CREDIT_CARD", emoji: "💳", label: "Credito" },
];

const ORDER_TYPES: { value: OrderType; emoji: string; label: string }[] = [
  { value: "TAKEAWAY", emoji: "🏠", label: "Retirada" },
  { value: "DELIVERY", emoji: "🛵", label: "Entrega" },
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
  const [orderType, setOrderType] = useState<OrderType>("TAKEAWAY");
  const [deliveryAddr, setDeliveryAddr] = useState<DeliveryAddress>(EMPTY_DELIVERY_ADDRESS);
  const [addrTouched, setAddrTouched] = useState(false);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [successOrderId, setSuccessOrderId] = useState<string | null>(null);
  const modalRef = useRef<HTMLDivElement>(null);

  const [couponCode, setCouponCode] = useState("");
  const [applyingCoupon, setApplyingCoupon] = useState(false);
  const [appliedCoupon, setAppliedCoupon] = useState<ApplyCouponResponse | null>(null);
  const [couponError, setCouponError] = useState<string | null>(null);

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

  const subtotal = cart.reduce(
    (sum, l) => {
      const linePrice =
        l.product.effectivePriceCents +
        (l.options?.reduce((s, o) => s + o.priceCents, 0) ?? 0);
      return sum + linePrice * l.quantity;
    },
    0,
  );

  const discountCents = appliedCoupon?.valid ? appliedCoupon.discountCents : 0;
  const total = Math.max(0, subtotal - discountCents);

  function validateDelivery(): DeliveryFieldErrors {
    if (orderType !== "DELIVERY") return {};
    const e: DeliveryFieldErrors = {};
    if (deliveryAddr.zip.length !== 8) e.zip = "CEP obrigatorio (8 digitos)";
    if (!deliveryAddr.street.trim()) e.street = "Rua obrigatoria";
    if (!deliveryAddr.number.trim()) e.number = "Numero obrigatorio";
    if (!deliveryAddr.neighborhood.trim()) e.neighborhood = "Bairro obrigatorio";
    return e;
  }

  const deliveryErrors: DeliveryFieldErrors = addrTouched ? validateDelivery() : {};
  const deliveryValid = Object.keys(validateDelivery()).length === 0;

  function handleDeliveryChange(addr: DeliveryAddress) {
    setDeliveryAddr(addr);
    if (!addrTouched) setAddrTouched(true);
  }

  async function handleApplyCoupon() {
    const code = couponCode.trim().toUpperCase();
    if (!code) return;
    setApplyingCoupon(true);
    setCouponError(null);
    setAppliedCoupon(null);
    try {
      const res = await fetch(
        API_BASE + "/public/" + tenantSlug + "/apply-coupon",
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ code, subtotalCents: subtotal }),
        },
      );
      const data = (await res.json()) as ApplyCouponResponse & { message?: string };
      if (!res.ok || !data.valid) {
        const msg =
          typeof data.message === "string"
            ? data.message
            : "Cupom invalido ou expirado.";
        setCouponError(msg);
      } else {
        setAppliedCoupon(data);
      }
    } catch {
      setCouponError("Nao foi possivel verificar o cupom. Tente novamente.");
    } finally {
      setApplyingCoupon(false);
    }
  }

  function handleRemoveCoupon() {
    setAppliedCoupon(null);
    setCouponCode("");
    setCouponError(null);
  }

  const canSubmit =
    nome.trim().length > 0 && payment !== null && !sending && deliveryValid;

  async function handleSubmit() {
    if (!canSubmit || !payment) return;
    if (orderType === "DELIVERY" && !deliveryValid) {
      setAddrTouched(true);
      return;
    }
    setSending(true);
    setError(null);
    try {
      const deliveryPayload =
        orderType === "DELIVERY"
          ? {
              deliveryZip: deliveryAddr.zip,
              deliveryStreet: deliveryAddr.street,
              deliveryNumber: deliveryAddr.number,
              deliveryComplement: deliveryAddr.complement || undefined,
              deliveryNeighborhood: deliveryAddr.neighborhood,
              deliveryCity: deliveryAddr.city || undefined,
              deliveryReference: deliveryAddr.reference || undefined,
            }
          : {};

      const res = await fetch(
        API_BASE + "/public/" + tenantSlug + "/orders",
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            orderType,
            customerName: nome.trim(),
            paymentMethod: payment,
            tableLabel: tableLabel ?? undefined,
            observations: obs.trim() || undefined,
            couponCode: appliedCoupon?.valid
              ? couponCode.trim().toUpperCase()
              : undefined,
            ...deliveryPayload,
            items: cart.map((l) => ({
              productId: l.product.id,
              quantity: l.quantity,
              notes: l.notes,
              optionIds: l.options?.map((o) => o.optionId) ?? [],
            })),
          }),
        },
      );

      if (!res.ok) {
        const data = (await res.json().catch(() => ({}))) as Record<string, unknown>;
        throw new Error(
          typeof data.message === "string"
            ? data.message
            : "Erro " + String(res.status),
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
      <div className="absolute inset-0 bg-black/50" onClick={onClose} aria-hidden="true" />

      <div
        ref={modalRef}
        tabIndex={-1}
        className="relative bg-bg-primary rounded-t-2xl sm:rounded-2xl w-full sm:max-w-md p-6 outline-none max-h-[90vh] overflow-y-auto"
      >
        {successOrderId !== null ? (
          <div className="text-center py-4">
            <div className="text-5xl mb-4" aria-hidden="true">✅</div>
            <h2 className="text-xl font-bold text-text-primary mb-2">Pedido enviado!</h2>
            {successOrderId && (
              <p className="text-sm text-text-muted mb-2">Pedido #{successOrderId}</p>
            )}
            <p className="text-text-secondary mb-6">
              {orderType === "DELIVERY"
                ? "Seu pedido foi enviado para entrega! Aguarde o motoboy."
                : "Seu pedido foi enviado! Aguarde a preparacao."}
            </p>
            <button onClick={onNewOrder} className="btn-primary w-full min-h-[48px]">
              Novo Pedido
            </button>
          </div>
        ) : (
          <>
            <h2 className="text-xl font-bold text-text-primary mb-5">Finalizar Pedido</h2>

            {/* Tipo de pedido */}
            <div className="form-group">
              <p
                className="form-label"
                id="checkout-order-type-label"
              >
                Tipo de pedido
              </p>
              <div
                className="grid grid-cols-2 gap-2"
                role="group"
                aria-labelledby="checkout-order-type-label"
              >
                {ORDER_TYPES.map((opt) => (
                  <button
                    key={opt.value}
                    type="button"
                    onClick={() => {
                      setOrderType(opt.value);
                      setAddrTouched(false);
                    }}
                    aria-pressed={orderType === opt.value}
                    className={[
                      "flex items-center gap-2 px-3 py-2 rounded-lg border font-medium text-sm transition-colors min-h-[48px]",
                      orderType === opt.value
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

            {/* Endereco de entrega — so aparece quando DELIVERY */}
            {orderType === "DELIVERY" && (
              <div className="bg-bg-secondary rounded-xl p-4 mb-4">
                <h3 className="text-sm font-semibold text-text-primary mb-3">
                  Endereco de entrega
                </h3>
                <DeliveryAddressForm
                  value={deliveryAddr}
                  onChange={handleDeliveryChange}
                  errors={deliveryErrors}
                />
              </div>
            )}

            {/* Total resumo */}
            <div className="bg-bg-secondary rounded-lg p-3 mb-4">
              <div className="flex justify-between items-center">
                <span className="text-text-secondary">Subtotal</span>
                <span className="font-medium text-text-primary">{formatBRL(subtotal)}</span>
              </div>
              {discountCents > 0 && (
                <div className="flex justify-between items-center mt-1">
                  <span className="text-success text-sm">
                    Desconto ({couponCode.toUpperCase()})
                  </span>
                  <span className="text-success font-medium">
                    - {formatBRL(discountCents)}
                  </span>
                </div>
              )}
              <div className="flex justify-between items-center mt-2 pt-2 border-t border-border-light">
                <span className="text-text-secondary font-medium">Total</span>
                <span className="text-lg font-bold text-text-primary">
                  {formatBRL(total)}
                </span>
              </div>
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
                Observacoes{" "}
                <span className="text-text-muted text-xs">(opcional)</span>
              </label>
              <textarea
                id="checkout-obs"
                className="input-field resize-none"
                placeholder="Ex: sem cebola, bem passado..."
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

            {/* Campo de cupom */}
            <div className="form-group">
              <label className="form-label" htmlFor="checkout-coupon">
                <span className="flex items-center gap-1.5">
                  <Tag className="h-3.5 w-3.5 text-text-muted" aria-hidden="true" />
                  Tem um cupom?{" "}
                  <span className="text-text-muted text-xs">(opcional)</span>
                </span>
              </label>

              {appliedCoupon?.valid ? (
                <div
                  className="flex items-center justify-between bg-green-50 border border-green-200 rounded-lg px-3 py-2.5"
                  role="status"
                  aria-live="polite"
                >
                  <span className="flex items-center gap-2 text-sm font-medium text-green-700">
                    <CheckCircle className="h-4 w-4 flex-shrink-0" aria-hidden="true" />
                    Cupom aplicado: - {formatBRL(appliedCoupon.discountCents)}
                    {appliedCoupon.description && (
                      <span className="text-green-600 font-normal">
                        ({appliedCoupon.description})
                      </span>
                    )}
                  </span>
                  <button
                    type="button"
                    onClick={handleRemoveCoupon}
                    aria-label="Remover cupom"
                    className="text-green-600 hover:text-green-800 ml-2 flex-shrink-0"
                  >
                    <XCircle className="h-4 w-4" aria-hidden="true" />
                  </button>
                </div>
              ) : (
                <div className="flex gap-2">
                  <input
                    id="checkout-coupon"
                    type="text"
                    className="input-field flex-1 uppercase font-mono"
                    placeholder="PROMO10"
                    value={couponCode}
                    onChange={(e) => {
                      setCouponCode(e.target.value.toUpperCase());
                      setCouponError(null);
                    }}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") {
                        e.preventDefault();
                        void handleApplyCoupon();
                      }
                    }}
                    autoComplete="off"
                    autoCapitalize="characters"
                    aria-describedby={couponError ? "coupon-error" : undefined}
                  />
                  <button
                    type="button"
                    onClick={() => { void handleApplyCoupon(); }}
                    disabled={!couponCode.trim() || applyingCoupon}
                    className="btn-outline px-4 py-2 min-h-[44px] whitespace-nowrap disabled:opacity-50"
                  >
                    {applyingCoupon ? "..." : "Aplicar"}
                  </button>
                </div>
              )}

              {couponError && (
                <p
                  id="coupon-error"
                  className="text-error text-xs mt-1.5 flex items-center gap-1"
                  role="alert"
                >
                  <XCircle className="h-3.5 w-3.5 flex-shrink-0" aria-hidden="true" />
                  {couponError}
                </p>
              )}
            </div>

            {/* Erro geral */}
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
