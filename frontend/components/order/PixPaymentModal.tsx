"use client";

// Modal de pagamento PIX: QR + copia-e-cola + polling + countdown. Movido de
// app/pdv/page.tsx (extração behavior-preserving, fatia 1).

import { useEffect, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { useModalA11y } from "@/lib/use-modal-a11y";
import { formatBRL } from "@/types/menu";
import { Check, CheckCircle, Clock } from "lucide-react";
import { PaymentIntentResponse } from "./types";

export function PixPaymentModal({
  intent: initialIntent,
  onPaid,
  onCancel,
  onUnauthorized,
}: {
  intent: PaymentIntentResponse;
  onPaid: () => void;
  onCancel: () => void;
  onUnauthorized: () => void;
}) {
  const dialogRef = useRef<HTMLDivElement>(null);
  // ESC retorna à seleção de método (não cancela o pedido — ele já foi criado)
  useModalA11y(dialogRef, onCancel);

  const [paymentIntent, setPaymentIntent] = useState<PaymentIntentResponse>(initialIntent);
  const [secondsLeft, setSecondsLeft] = useState(0);
  const [copied, setCopied] = useState(false);
  const [generatingNew, setGeneratingNew] = useState(false);
  const [qrError, setQrError] = useState<string | null>(null);

  const { status, pixQrImage, pixCopyPaste, amountCents, expiresAt, orderId } = paymentIntent;

  // Polling a cada 3s enquanto PENDING
  useEffect(() => {
    if (status !== "PENDING") return;
    const interval = setInterval(() => {
      void api
        .get<PaymentIntentResponse>(`/payments/pix-qr/status/${orderId}`)
        .then((data) => {
          setPaymentIntent(data);
          if (data.status !== "PENDING") clearInterval(interval);
        })
        .catch((e) => {
          if (e instanceof ApiError && e.status === 401) {
            clearInterval(interval);
            onUnauthorized();
          }
          // Silencia erros de rede durante polling — o intervalo tenta de novo
        });
    }, 3000);
    return () => clearInterval(interval);
  }, [status, orderId, onUnauthorized]);

  // Countdown até expiresAt (reseta ao trocar de intenção)
  useEffect(() => {
    if (!expiresAt || status !== "PENDING") return;
    const calc = () =>
      Math.max(0, Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000));
    setSecondsLeft(calc());
    const tick = setInterval(() => {
      setSecondsLeft((s) => {
        if (s <= 1) { clearInterval(tick); return 0; }
        return s - 1;
      });
    }, 1000);
    return () => clearInterval(tick);
  }, [expiresAt, status]);

  async function generateNewQr() {
    setGeneratingNew(true);
    setQrError(null);
    try {
      const data = await api.post<PaymentIntentResponse>("/payments/pix-qr", { orderId });
      setPaymentIntent(data);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) { onUnauthorized(); return; }
      setQrError(e instanceof ApiError ? e.message : "Falha ao gerar novo QR.");
    } finally {
      setGeneratingNew(false);
    }
  }

  async function copyToClipboard() {
    try {
      await navigator.clipboard.writeText(pixCopyPaste);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      /* fallback: usuário copia manualmente do campo read-only */
    }
  }

  const mins = Math.floor(secondsLeft / 60);
  const secs = String(secondsLeft % 60).padStart(2, "0");
  const titleId = "pix-modal-title";

  return (
    <div className="fixed inset-0 z-[60] flex items-end sm:items-center justify-center bg-black/60 p-0 sm:p-4">
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="bg-bg-primary w-full sm:max-w-sm rounded-t-2xl sm:rounded-2xl p-5 space-y-4"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Cabeçalho */}
        <div className="flex items-center justify-between">
          <h2 id={titleId} className="text-base font-bold text-text-primary">
            {status === "PAID"
              ? "Pagamento confirmado"
              : status === "EXPIRED"
                ? "QR expirado"
                : status === "FAILED"
                  ? "Falha no pagamento"
                  : "Aguardando pagamento PIX"}
          </h2>
          <span className="text-base font-bold text-primary-700">
            {formatBRL(amountCents)}
          </span>
        </div>

        {/* Estado: PAID */}
        {status === "PAID" && (
          <div className="flex flex-col items-center gap-3 py-4" aria-live="polite">
            <CheckCircle className="h-16 w-16 text-success" aria-hidden="true" />
            <p className="text-center text-base font-semibold text-text-primary">
              Pagamento confirmado!
            </p>
            <button type="button" className="btn-primary w-full" onClick={onPaid}>
              Fechar
            </button>
          </div>
        )}

        {/* Estado: FAILED */}
        {status === "FAILED" && (
          <div className="flex flex-col items-center gap-3 py-4" aria-live="assertive">
            <p className="text-center text-error font-medium">
              O pagamento falhou. Tente outro método.
            </p>
            <button type="button" className="btn-outline w-full" onClick={onCancel}>
              Usar outro método
            </button>
          </div>
        )}

        {/* Estado: EXPIRED */}
        {status === "EXPIRED" && (
          <div className="flex flex-col items-center gap-3 py-4" aria-live="polite">
            <p className="text-center text-text-secondary">
              O QR expirou. Gere um novo para continuar.
            </p>
            {qrError && (
              <p className="text-sm text-error" role="alert">
                {qrError}
              </p>
            )}
            <button
              type="button"
              className="btn-primary w-full"
              disabled={generatingNew}
              onClick={() => void generateNewQr()}
            >
              {generatingNew ? "Gerando…" : "Gerar novo QR"}
            </button>
            <button type="button" className="btn-outline w-full" onClick={onCancel}>
              Usar outro método
            </button>
          </div>
        )}

        {/* Estado: PENDING — QR exibido + countdown + copia-e-cola + polling */}
        {status === "PENDING" && (
          <>
            {/* Imagem do QR */}
            <div className="flex justify-center">
              {/* QR em data URI gerado pela API; next/image nao otimiza esse caso. */}
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={`data:image/png;base64,${pixQrImage}`}
                alt="QR Code PIX — escaneie com o app do seu banco"
                className="w-48 h-48 rounded-lg border border-border-light"
              />
            </div>

            {/* Countdown */}
            <div
              className="flex items-center justify-center gap-1.5"
              aria-live="polite"
              aria-atomic="true"
            >
              <Clock className="h-4 w-4 text-text-muted shrink-0" aria-hidden="true" />
              <span className="text-sm text-text-secondary">
                {secondsLeft > 0
                  ? `Expira em ${mins}:${secs}`
                  : "Verificando expiração…"}
              </span>
            </div>

            {/* Copia-e-cola */}
            <div className="space-y-1.5">
              <p className="text-xs font-medium text-text-secondary">Pix Copia e Cola</p>
              <div className="flex items-center gap-2">
                <input
                  type="text"
                  readOnly
                  value={pixCopyPaste}
                  className="input-field flex-1 text-xs truncate"
                  aria-label="Código Pix Copia e Cola"
                />
                <button
                  type="button"
                  onClick={() => void copyToClipboard()}
                  className={`shrink-0 flex items-center gap-1 px-3 py-2 rounded-md border text-sm font-medium transition-colors ${
                    copied
                      ? "bg-success/10 border-success/40 text-success"
                      : "bg-bg-secondary border-border-light text-text-secondary hover:bg-bg-tertiary"
                  }`}
                  aria-label={copied ? "Código copiado" : "Copiar código PIX"}
                >
                  {copied ? (
                    <>
                      <Check className="h-4 w-4" aria-hidden="true" />
                      Copiado
                    </>
                  ) : (
                    "Copiar"
                  )}
                </button>
              </div>
            </div>

            {/* Indicador de polling */}
            <p className="text-xs text-center text-text-muted" aria-live="polite">
              Verificando pagamento automaticamente…
            </p>

            {/* Botão cancelar */}
            <button
              type="button"
              className="btn-outline w-full"
              onClick={onCancel}
            >
              Cancelar / usar outro método
            </button>
          </>
        )}
      </div>
    </div>
  );
}
