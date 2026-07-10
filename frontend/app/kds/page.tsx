"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { getToken } from "@/lib/auth";
import { useModalA11y } from "@/lib/use-modal-a11y";
import { useKdsFeed } from "@/lib/use-kds-feed";
import { agingBarClass, elapsedLabel, isLate } from "@/lib/kds-aging";
import { ExternalOrigin, FeedStatus, KdsOrder, OrderStatus } from "@/types/kds";
import LoadingSpinner from "@/components/loading-spinner";

// Mapeamento de status para o avanço do pedido no KDS.
const NEXT_STATUS: Partial<Record<OrderStatus, OrderStatus>> = {
  PENDING: "PREPARING",
  PREPARING: "READY",
  READY: "DELIVERED",
};

const STATUS_LABEL: Record<OrderStatus, string> = {
  PENDING: "Novo",
  PREPARING: "Em preparo",
  READY: "Pronto",
  DELIVERED: "Entregue",
  CANCELLED: "Cancelado",
};

const ADVANCE_LABEL: Partial<Record<OrderStatus, string>> = {
  PENDING: "Iniciar preparo",
  PREPARING: "Marcar pronto",
  READY: "Entregar",
};

const ORDER_TYPE_LABEL: Record<string, string> = {
  DINE_IN: "Mesa",
  TAKEAWAY: "Retirada",
  DELIVERY: "Entrega",
};

// Aging: verde → âmbar → vermelho baseado em estimatedPrepTimeMinutes.
// Padrão kohli.design: barra colorida no topo do card (mais legível em cozinha com luz forte)
// que fundo colorido no card inteiro. Verde→âmbar→vermelho pelo tempo decorrido.
// Regra extraída para frontend/lib/kds-aging.ts (reuso pelo dashboard, Fase 3) —
// agingBarClass/elapsedLabel importados acima; isLate substitui o antigo isOverdue local.

// ── Badge de canal de origem ──────────────────────────────────────────────────
// OWN não renderiza nada (padrão interno).
// IFOOD → badge laranja; RAPPI → badge amarelo; NINETY_NINE (99Food) → badge azul.
// O badge fica inline com o número do pedido no cabeçalho do card.

function OriginBadge({ origin }: { origin?: ExternalOrigin }) {
  if (!origin || origin === "OWN") return null;
  if (origin === "IFOOD") {
    return (
      <span
        className="inline-flex items-center gap-1 rounded-full bg-orange-100 px-2 py-0.5 text-xs font-semibold text-orange-700"
        aria-label="Pedido via iFood"
      >
        🛵 iFood
      </span>
    );
  }
  if (origin === "RAPPI") {
    return (
      <span
        className="inline-flex items-center gap-1 rounded-full bg-yellow-100 px-2 py-0.5 text-xs font-semibold text-yellow-700"
        aria-label="Pedido via Rappi"
      >
        🟡 Rappi
      </span>
    );
  }
  if (origin === "NINETY_NINE") {
    return (
      <span
        className="inline-flex items-center gap-1 rounded-full bg-sky-100 px-2 py-0.5 text-xs font-semibold text-sky-700"
        aria-label="Pedido via 99Food"
      >
        🚕 99Food
      </span>
    );
  }
  return null;
}

// ── Modal de cancelamento ────────────────────────────────────────────────────

interface CancelModalProps {
  order: KdsOrder;
  onClose: () => void;
  onConfirm: (reason: string) => Promise<void>;
}

function CancelModal({ order, onClose, onConfirm }: CancelModalProps) {
  const ref = useRef<HTMLDivElement>(null);
  useModalA11y(ref as React.RefObject<HTMLElement>, onClose);
  const [reason, setReason] = useState("");
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function handleConfirm() {
    if (!reason.trim()) {
      setErr("Informe o motivo do cancelamento.");
      return;
    }
    setLoading(true);
    try {
      await onConfirm(reason.trim());
      onClose();
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : "Erro ao cancelar.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      aria-modal="true"
    >
      <div
        ref={ref}
        role="dialog"
        aria-labelledby="cancel-title"
        className="w-full max-w-sm rounded-2xl bg-bg-primary p-6 shadow-dropdown"
      >
        <h2 id="cancel-title" className="mb-1 text-lg font-semibold text-text-primary">
          Cancelar pedido #{order.orderNumber}
        </h2>
        <p className="mb-4 text-sm text-text-secondary">
          Esta ação não pode ser desfeita. Informe o motivo.
        </p>
        <textarea
          className="input-field mb-3 h-24 w-full resize-none"
          placeholder="Ex.: cliente desistiu, ingrediente em falta…"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          autoFocus
        />
        {err && <p className="mb-3 text-sm text-error">{err}</p>}
        <div className="flex gap-3">
          <button
            className="btn-outline flex-1"
            onClick={onClose}
            disabled={loading}
          >
            Voltar
          </button>
          <button
            className="flex-1 rounded-xl bg-error px-4 py-3 text-sm font-semibold text-white transition-colors hover:bg-error-dark disabled:opacity-50"
            onClick={handleConfirm}
            disabled={loading}
          >
            {loading ? "Cancelando…" : "Confirmar cancelamento"}
          </button>
        </div>
      </div>
    </div>
  );
}

// ── Cartão de pedido ─────────────────────────────────────────────────────────

interface OrderCardProps {
  order: KdsOrder;
  now: number;
  onAdvance: (order: KdsOrder) => Promise<void>;
  onCancel: (order: KdsOrder) => void;
}

function OrderCard({ order, now, onAdvance, onCancel }: OrderCardProps) {
  const [advancing, setAdvancing] = useState(false);

  async function handleAdvance() {
    if (advancing) return;
    setAdvancing(true);
    try {
      await onAdvance(order);
    } finally {
      setAdvancing(false);
    }
  }

  const nextStatus = NEXT_STATUS[order.status];

  // Quando existe ID do canal externo, usá-lo como número principal.
  // O número interno fica como referência secundária para rastreio.
  const displayNumber = order.externalDisplayId ?? `#${order.orderNumber}`;
  const showInternalRef =
    !!order.externalDisplayId &&
    order.externalDisplayId !== `#${order.orderNumber}`;

  return (
    <div className="flex flex-col overflow-hidden rounded-2xl bg-bg-primary shadow-card transition-shadow hover:shadow-dropdown">
      {/* Barra de aging no topo (kohli.design pattern) */}
      <div
        className={`h-1.5 w-full transition-colors ${agingBarClass(order, now)}`}
        aria-hidden="true"
      />
      <div className="flex flex-col p-4">
      {/* Cabeçalho */}
      <div className="mb-3 flex items-start justify-between gap-2">
        <div>
          <div className="flex items-center gap-2 flex-wrap">
            <span className="text-xl font-bold text-text-primary">
              {displayNumber}
            </span>
            <OriginBadge origin={order.externalOrigin} />
          </div>
          {showInternalRef && (
            <p className="mt-0.5 text-xs text-text-muted">
              int #{order.orderNumber}
            </p>
          )}
          <div className="mt-0.5 flex items-center gap-2 text-sm text-text-secondary">
            <span>{ORDER_TYPE_LABEL[order.orderType] ?? order.orderType}</span>
            {order.tableNumber && <span>· Mesa {order.tableNumber}</span>}
          </div>
        </div>
        <div className="flex flex-col items-end gap-1">
          <span
            className={`font-mono text-sm font-semibold transition-colors ${
              isLate(order, now)
                ? "text-error text-base font-bold"
                : "text-text-secondary"
            }`}
            aria-label={`Tempo decorrido: ${elapsedLabel(order, now)}`}
          >
            {elapsedLabel(order, now)}
          </span>
          <button
            onClick={() => onCancel(order)}
            className="inline-flex min-h-11 items-center justify-center rounded-lg px-2 text-sm text-text-muted underline underline-offset-2 hover:bg-bg-tertiary hover:text-error focus:outline-none focus-visible:ring-2 focus-visible:ring-error"
            aria-label="Cancelar pedido"
          >
            Cancelar
          </button>
        </div>
      </div>

      {/* Itens */}
      <ul className="mb-4 flex-1 space-y-1.5" aria-label="Itens do pedido">
        {order.items.map((item, i) => (
          <li key={i} className="text-sm">
            <span className="font-semibold text-text-primary">
              {item.quantity}× {item.productName}
            </span>
            {item.notes && (
              <p className="mt-0.5 flex items-start gap-1 text-sm text-warning-dark">
                <span aria-hidden="true" className="mt-px shrink-0">⚠</span>
                <span className="font-medium">{item.notes}</span>
              </p>
            )}
          </li>
        ))}
      </ul>

      </div>{/* fim do conteúdo com padding */}
      {/* Botão de avanço */}
      {nextStatus && (
        <button
          onClick={handleAdvance}
          disabled={advancing}
          className="w-full rounded-b-2xl bg-primary-700 px-4 py-3.5 text-sm font-semibold text-white transition-colors hover:bg-primary-800 active:bg-primary-900 disabled:opacity-50 focus-visible:ring-2 focus-visible:ring-primary-700 focus-visible:ring-offset-2"
          aria-label={`${ADVANCE_LABEL[order.status]} — pedido #${order.orderNumber}`}
        >
          {advancing ? "Aguarde…" : ADVANCE_LABEL[order.status]}
        </button>
      )}
    </div>
  );
}

// ── Coluna do board ──────────────────────────────────────────────────────────

interface ColumnProps {
  title: string;
  status: OrderStatus;
  orders: KdsOrder[];
  now: number;
  onAdvance: (order: KdsOrder) => Promise<void>;
  onCancel: (order: KdsOrder) => void;
  headerClass: string;
}

function Column({
  title,
  status,
  orders,
  now,
  onAdvance,
  onCancel,
  headerClass,
}: ColumnProps) {
  const col = orders.filter((o) => o.status === status);
  return (
    <div className="flex min-h-0 flex-col rounded-2xl bg-bg-secondary">
      <div className={`flex items-center gap-2 rounded-t-2xl px-4 py-3 ${headerClass}`}>
        <h2 className="text-sm font-semibold text-white">{title}</h2>
        <span className="rounded-full bg-white/20 px-2 py-0.5 text-xs font-bold text-white">
          {col.length}
        </span>
      </div>
      <div className="flex-1 overflow-y-auto p-3">
        {col.length === 0 ? (
          <p className="mt-6 text-center text-sm text-text-muted">
            {status === "READY" ? "Nenhum pedido pronto" : "Nenhum pedido"}
          </p>
        ) : (
          <div className="space-y-3">
            {col
              .slice()
              .sort(
                (a, b) =>
                  new Date(a.createdAt).getTime() -
                  new Date(b.createdAt).getTime(),
              )
              .map((o) => (
                <OrderCard
                  key={o.orderId}
                  order={o}
                  now={now}
                  onAdvance={onAdvance}
                  onCancel={onCancel}
                />
              ))}
          </div>
        )}
      </div>
    </div>
  );
}

// ── Banner de conexão ─────────────────────────────────────────────────────────

function ConnectionBanner({ status }: { status: FeedStatus }) {
  if (status === "live") return null;
  const text =
    status === "connecting"
      ? "Conectando…"
      : status === "reconnecting"
        ? "Reconectando… usando dados em cache"
        : "Modo offline — atualizando a cada 10s";
  return (
    <div
      className="flex items-center justify-center gap-2 bg-warning px-4 py-1.5 text-xs font-medium text-warning-dark"
      role="status"
      aria-live="polite"
    >
      <span
        className="inline-block h-2 w-2 rounded-full bg-warning-dark"
        aria-hidden="true"
      />
      {text}
    </div>
  );
}

// ── Página principal ─────────────────────────────────────────────────────────

export default function KdsPage() {
  const router = useRouter();
  const { orders, feedStatus, now, refresh } = useKdsFeed();
  const [cancelTarget, setCancelTarget] = useState<KdsOrder | null>(null);
  const [advanceError, setAdvanceError] = useState<string | null>(null);
  const isAuthenticated = typeof window === "undefined" || !!getToken();

  const handleAdvance = useCallback(
    async (order: KdsOrder) => {
      const next = NEXT_STATUS[order.status];
      if (!next) return;
      try {
        await api.put(`/orders/${order.orderId}/status`, { status: next });
        // STOMP atualiza o board; se WS cair, polling reconcilia
      } catch (e) {
        setAdvanceError(
          e instanceof ApiError ? e.message : "Erro ao avançar pedido.",
        );
        setTimeout(() => setAdvanceError(null), 4000);
        // re-fetch para garantir consistência
        await refresh();
      }
    },
    [refresh],
  );

  const handleCancel = useCallback(
    async (reason: string) => {
      if (!cancelTarget) return;
      await api.put(`/orders/${cancelTarget.orderId}/status`, {
        status: "CANCELLED",
        reason,
      });
    },
    [cancelTarget],
  );

  useEffect(() => {
    if (!isAuthenticated) router.replace("/");
  }, [isAuthenticated, router]);

  if (!isAuthenticated) return null;

  const liveDot =
    feedStatus === "live" ? "bg-success" : "bg-warning animate-pulse";

  return (
    <div className="flex h-screen flex-col overflow-hidden bg-bg-primary">
      {/* Barra de conexão */}
      <ConnectionBanner status={feedStatus} />

      {/* Header */}
      <header className="flex items-center justify-between border-b border-border-light px-6 py-3">
        <div className="flex items-center gap-3">
          <span
            className={`inline-block h-2.5 w-2.5 rounded-full ${liveDot}`}
            title={feedStatus === "live" ? "Ao vivo" : "Reconectando"}
            aria-label={feedStatus === "live" ? "Conexão ao vivo" : "Reconectando"}
          />
        </div>
        <nav className="flex items-center gap-3">
          <button
            onClick={() => refresh()}
            className="inline-flex min-h-11 items-center justify-center rounded-lg px-3 text-sm text-text-secondary hover:bg-bg-secondary"
          >
            Atualizar
          </button>

        </nav>
      </header>

      {/* Toast de erro de avanço */}
      {advanceError && (
        <div
          role="alert"
          className="mx-auto mt-3 max-w-sm rounded-xl bg-error-light px-4 py-2 text-sm text-error-dark shadow-card"
        >
          {advanceError}
        </div>
      )}

      {/* Board */}
      <main className="grid flex-1 grid-cols-3 gap-4 overflow-hidden p-4">
        <Column
          title="Novos"
          status="PENDING"
          orders={orders}
          now={now}
          onAdvance={handleAdvance}
          onCancel={setCancelTarget}
          headerClass="bg-kds-pending"
        />
        <Column
          title="Em preparo"
          status="PREPARING"
          orders={orders}
          now={now}
          onAdvance={handleAdvance}
          onCancel={setCancelTarget}
          headerClass="bg-kds-inPrep"
        />
        <Column
          title="Prontos"
          status="READY"
          orders={orders}
          now={now}
          onAdvance={handleAdvance}
          onCancel={setCancelTarget}
          headerClass="bg-kds-ready"
        />
      </main>

      {/* Modal de cancelamento */}
      {cancelTarget && (
        <CancelModal
          order={cancelTarget}
          onClose={() => setCancelTarget(null)}
          onConfirm={handleCancel}
        />
      )}
    </div>
  );
}
