"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  AlertTriangle,
  Bike,
  CalendarClock,
  CheckCircle2,
  ChevronRight,
  Clock,
  CreditCard,
  FileClock,
  PackageCheck,
  Printer,
  Receipt,
  RefreshCcw,
  Search,
  ShoppingBag,
  Truck,
  UtensilsCrossed,
  X,
} from "lucide-react";
import { api, ApiError } from "@/lib/api";
import { getToken, getUserRole } from "@/lib/auth";
import { useModalA11y } from "@/lib/use-modal-a11y";
import { NovoPedidoSheet } from "@/components/order/NovoPedidoSheet";

// RBAC (deny-by-default, espelha @PreAuthorize de POST /orders no backend —
// ver OrderController.kt): só estes papéis podem criar pedido. A checagem real
// é do servidor; isto só evita mostrar ao KITCHEN um botão que ele não pode usar.
const CAN_CREATE_ORDER_ROLES = new Set(["ADMIN", "MANAGER", "STAFF", "CASHIER"]);

// RBAC do botão "Roteirizar" (navega para /delivery, a central de despacho):
// espelha o gate da própria Sidebar para o item "Entregas" — ver
// components/layout/Sidebar.tsx, NAV_GROUPS, item href '/delivery'. Hoje é o
// mesmo conjunto de CAN_CREATE_ORDER_ROLES, mas mantido como constante à
// parte porque os dois gates protegem coisas diferentes e podem divergir.
const CAN_ACCESS_DELIVERY_ROLES = new Set(["ADMIN", "MANAGER", "STAFF", "CASHIER"]);

type OrderStatus = "PENDING" | "PREPARING" | "READY" | "DELIVERED" | "CANCELLED";
type OrderType = "DINE_IN" | "TAKEAWAY" | "DELIVERY";
type PaymentStatus = "PENDING" | "PAID" | "FAILED" | "REFUNDED" | "PARTIALLY_REFUNDED";
type PaymentMethod = "CASH" | "CREDIT_CARD" | "DEBIT_CARD" | "PIX" | "OTHER";

interface OrderItemOptionView {
  optionId: string;
  groupName: string;
  optionName: string;
  priceCents: number;
}

interface OrderItemResponse {
  id: string;
  productId: string;
  productSku: string;
  productName: string;
  quantity: number;
  unitPriceCents: number;
  totalPriceCents: number;
  notes: string | null;
  status: string;
  sizeName?: string | null;
  flavor1Name?: string | null;
  flavor2Name?: string | null;
  options?: OrderItemOptionView[];
}

interface OrderResponse {
  id: string;
  orderNumber: string;
  customerId: string | null;
  userId: string | null;
  orderType: OrderType;
  status: OrderStatus;
  tableNumber: string | null;
  items: OrderItemResponse[];
  subtotalCents: number;
  discountCents: number;
  deliveryFeeCents: number;
  totalCents: number;
  paymentMethod: PaymentMethod | null;
  paymentStatus: PaymentStatus;
  priority: string;
  estimatedPrepTimeMinutes: number;
  notes: string | null;
  couponCode?: string | null;
  couponDiscountCents?: number;
  externalOrigin: string;
  externalDisplayId?: string | null;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
}

interface OrdersPage {
  content: OrderResponse[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

// Enriquecimento opcional de pedidos DELIVERY com o status de despacho.
// Espelha (subconjunto de) DeliveryOrderResponse do backend — ver
// backend/src/main/kotlin/com/menuflow/dto/DeliveryDtos.kt:121-147. Só os
// campos que este painel exibe; a central completa é /delivery.
type DeliveryDispatchStatus =
  | "PENDING"
  | "OFFERED"
  | "ACCEPTED"
  | "ASSIGNED"
  | "ARRIVED_AT_STORE"
  | "PICKED_UP"
  | "OUT_FOR_DELIVERY"
  | "ARRIVED_AT_CUSTOMER"
  | "DELIVERED"
  | "FAILED";

interface DeliveryOrderInfo {
  orderId: string;
  driverId: string | null;
  deliveryStatus: DeliveryDispatchStatus | null;
  deliveryRecipientName: string | null;
  deliveryNeighborhood: string | null;
}

// Rótulos em pt-BR do status de despacho — espelha STATUS_LABEL de
// app/delivery/page.tsx (as duas telas não compartilham hoje um módulo de
// tipos de delivery; duplicado deliberadamente para não acoplar as páginas).
const DELIVERY_DISPATCH_LABEL: Record<string, string> = {
  PENDING: "Aguardando motoboy",
  OFFERED: "Oferta enviada",
  ACCEPTED: "Aceito",
  ASSIGNED: "Atribuído",
  ARRIVED_AT_STORE: "Na loja",
  PICKED_UP: "Coletado",
  OUT_FOR_DELIVERY: "Em rota",
  ARRIVED_AT_CUSTOMER: "No cliente",
  DELIVERED: "Entregue",
  FAILED: "Falha na entrega",
};

type Period = "today" | "week" | "month";
type StatusFilter = OrderStatus | "ALL";

const STATUS_META: Record<OrderStatus, { label: string; dot: string; tone: string }> = {
  PENDING: {
    label: "Novo pedido",
    dot: "bg-sky-500",
    tone: "bg-sky-50 text-sky-700",
  },
  PREPARING: {
    label: "Em preparo",
    dot: "bg-secondary-500",
    tone: "bg-secondary-50 text-secondary-800",
  },
  READY: {
    label: "Pronto",
    dot: "bg-primary-600",
    tone: "bg-primary-50 text-primary-700",
  },
  DELIVERED: {
    label: "Concluído",
    dot: "bg-emerald-700",
    tone: "bg-emerald-50 text-emerald-700",
  },
  CANCELLED: {
    label: "Cancelado",
    dot: "bg-red-600",
    tone: "bg-red-50 text-red-700",
  },
};

const STATUS_FILTERS: { value: StatusFilter; label: string }[] = [
  { value: "ALL", label: "Todos" },
  { value: "PENDING", label: "Novo" },
  { value: "PREPARING", label: "Em preparo" },
  { value: "READY", label: "Pronto" },
  { value: "DELIVERED", label: "Concluído" },
  { value: "CANCELLED", label: "Cancelado" },
];

const NEXT_STATUS: Partial<Record<OrderStatus, OrderStatus>> = {
  PENDING: "PREPARING",
  PREPARING: "READY",
  READY: "DELIVERED",
};

const ADVANCE_LABEL: Partial<Record<OrderStatus, string>> = {
  PENDING: "Iniciar preparo",
  PREPARING: "Marcar pronto",
  READY: "Concluir pedido",
};

const ORDER_TYPE_LABEL: Record<OrderType, string> = {
  DINE_IN: "Mesa",
  TAKEAWAY: "Retirada",
  DELIVERY: "Delivery",
};

const PAYMENT_LABEL: Record<string, string> = {
  CASH: "Dinheiro",
  CREDIT_CARD: "Crédito",
  DEBIT_CARD: "Débito",
  PIX: "Pix",
  OTHER: "Outro",
  PENDING: "Pendente",
  PAID: "Pago",
  FAILED: "Falhou",
  REFUNDED: "Estornado",
  PARTIALLY_REFUNDED: "Estorno parcial",
};

function formatCents(cents: number): string {
  return (cents / 100).toLocaleString("pt-BR", {
    style: "currency",
    currency: "BRL",
  });
}

function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString("pt-BR", {
    dateStyle: "short",
    timeStyle: "short",
  });
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

/**
 * Estilos do cupom (~76mm, estilo térmico). Cada comanda vive num `.comanda`
 * com quebra de página antes da seguinte, para permitir concatenar N cupons
 * numa mesma janela de impressão (lote) sem depender de N pop-ups.
 */
const COMANDA_STYLES = `
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body { font-family: "Courier New", monospace; color: #000; font-size: 12px; }
    .comanda { width: 76mm; padding: 6mm 4mm; }
    .comanda + .comanda { page-break-before: always; }
    h1 { font-size: 16px; text-align: center; }
    .sub { text-align: center; font-size: 11px; margin-bottom: 6px; }
    hr { border: none; border-top: 1px dashed #000; margin: 6px 0; }
    .meta { font-size: 11px; line-height: 1.5; }
    .it { display: flex; justify-content: space-between; gap: 6px; margin: 4px 0; }
    .it .q { font-weight: bold; }
    .it .n { flex: 1; }
    .it .m { font-size: 10px; color: #333; }
    .it .obs { font-size: 10px; font-weight: bold; }
    .it .p { white-space: nowrap; }
    .row { display: flex; justify-content: space-between; font-size: 11px; margin: 2px 0; }
    .row.tot { font-weight: bold; font-size: 13px; margin-top: 4px; }
    .foot { text-align: center; font-size: 10px; margin-top: 8px; }
    @media print { .comanda { width: auto; } }
`;

/** Monta o HTML de UMA comanda (bloco `.comanda`), reaproveitável em lote. */
function comandaInnerHtml(order: OrderResponse): string {
  const title = order.externalDisplayId ?? `#${order.orderNumber}`;
  const tipo = ORDER_TYPE_LABEL[order.orderType] + (order.tableNumber ? ` ${order.tableNumber}` : "");
  const pagamento = order.paymentMethod ? PAYMENT_LABEL[order.paymentMethod] : "A definir";
  const itemsHtml = order.items
    .map((item) => {
      const mods = [item.sizeName, item.flavor1Name, item.flavor2Name, ...(item.options ?? []).map((o) => o.optionName)]
        .filter(Boolean)
        .join(" · ");
      return `<div class="it"><div class="q">${item.quantity}x</div><div class="n">${escapeHtml(item.productName)}${
        mods ? `<div class="m">${escapeHtml(mods)}</div>` : ""
      }${item.notes ? `<div class="obs">Obs.: ${escapeHtml(item.notes)}</div>` : ""}</div><div class="p">${formatCents(
        item.totalPriceCents,
      )}</div></div>`;
    })
    .join("");
  const linhaResumo = (label: string, valor: string, strong = false) =>
    `<div class="row${strong ? " tot" : ""}"><span>${label}</span><span>${valor}</span></div>`;
  return `<div class="comanda">
    <h1>COMANDA ${escapeHtml(title)}</h1>
    <div class="sub">${escapeHtml(tipo)}</div>
    <hr>
    <div class="meta">
      <div>Emitida: ${escapeHtml(formatDateTime(new Date().toISOString()))}</div>
      <div>Pedido criado: ${escapeHtml(formatDateTime(order.createdAt))}</div>
      <div>Pagamento: ${escapeHtml(pagamento)}</div>
    </div>
    <hr>
    ${itemsHtml}
    <hr>
    ${linhaResumo("Subtotal", formatCents(order.subtotalCents))}
    ${order.discountCents > 0 ? linhaResumo("Desconto", "-" + formatCents(order.discountCents)) : ""}
    ${order.deliveryFeeCents > 0 ? linhaResumo("Entrega", formatCents(order.deliveryFeeCents)) : ""}
    ${linhaResumo("TOTAL", formatCents(order.totalCents), true)}
    ${order.notes ? `<hr><div class="meta"><strong>Obs.:</strong> ${escapeHtml(order.notes)}</div>` : ""}
    <div class="foot">MenuFlow</div>
  </div>`;
}

/**
 * Abre UMA janela própria com o(s) cupom(ns) já montados e dispara a impressão.
 * Disparado por clique do usuário, então o popup não é bloqueado.
 */
function openPrintWindow(titleText: string, bodyHtml: string) {
  const win = window.open("", "_blank", "width=380,height=680");
  if (!win) {
    alert("Não foi possível abrir a janela de impressão. Verifique o bloqueador de pop-ups.");
    return;
  }
  win.document.write(
    `<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><title>${escapeHtml(
      titleText,
    )}</title><style>${COMANDA_STYLES}</style></head><body onload="window.print()">${bodyHtml}</body></html>`,
  );
  win.document.close();
}

/** Imprime a comanda de um único pedido. */
function printComanda(order: OrderResponse) {
  const title = order.externalDisplayId ?? `#${order.orderNumber}`;
  openPrintWindow(`Comanda ${title}`, comandaInnerHtml(order));
}

/**
 * Imprime várias comandas numa ÚNICA janela (quebra de página entre elas) —
 * evita a enxurrada de pop-ups (que o navegador bloqueia após o primeiro).
 */
function printComandas(orders: OrderResponse[]) {
  if (orders.length === 0) return;
  if (orders.length === 1) {
    printComanda(orders[0]);
    return;
  }
  openPrintWindow(`Comandas (${orders.length})`, orders.map(comandaInnerHtml).join(""));
}

function friendlyError(err: unknown): string {
  if (err instanceof ApiError) return err.message;
  if (err instanceof TypeError) return "Backend indisponível no momento. Tente atualizar em alguns segundos.";
  if (err instanceof Error && err.message !== "Failed to fetch") return err.message;
  return "Não foi possível carregar pedidos.";
}

function elapsedMinutes(order: OrderResponse): number {
  return Math.max(0, Math.floor((Date.now() - new Date(order.createdAt).getTime()) / 60_000));
}

function periodRange(period: Period): { from: string; to: string } {
  const now = new Date();
  const from = new Date(now);
  if (period === "today") {
    from.setHours(0, 0, 0, 0);
  } else if (period === "week") {
    const day = from.getDay();
    const diffToMonday = day === 0 ? 6 : day - 1;
    from.setDate(from.getDate() - diffToMonday);
    from.setHours(0, 0, 0, 0);
  } else {
    from.setDate(1);
    from.setHours(0, 0, 0, 0);
  }
  return { from: from.toISOString(), to: now.toISOString() };
}

function orderSearchText(order: OrderResponse): string {
  return [
    order.orderNumber,
    order.externalDisplayId,
    order.externalOrigin,
    order.tableNumber,
    order.notes,
    order.paymentMethod,
    order.paymentStatus,
    order.items.map((item) => item.productName).join(" "),
  ]
    .filter(Boolean)
    .join(" ")
    .toLowerCase();
}

function StatusBadge({ status }: { status: OrderStatus }) {
  const meta = STATUS_META[status];
  return (
    <span className={["inline-flex min-h-7 items-center gap-2 rounded-full px-2.5 text-xs font-semibold", meta.tone].join(" ")}>
      <span className={["h-2 w-2 rounded-full", meta.dot].join(" ")} aria-hidden="true" />
      {meta.label}
    </span>
  );
}

function PaymentBadge({ status }: { status: PaymentStatus }) {
  const paid = status === "PAID";
  return (
    <span
      className={[
        "inline-flex min-h-7 items-center gap-1.5 rounded-full px-2.5 text-xs font-semibold",
        paid ? "bg-primary-50 text-primary-700" : "bg-secondary-50 text-secondary-800",
      ].join(" ")}
    >
      {paid ? <CheckCircle2 className="h-3.5 w-3.5" aria-hidden="true" /> : <CreditCard className="h-3.5 w-3.5" aria-hidden="true" />}
      {PAYMENT_LABEL[status] ?? status}
    </span>
  );
}

function TypeIcon({ type }: { type: OrderType }) {
  if (type === "DELIVERY") return <Truck className="h-4 w-4" aria-hidden="true" />;
  if (type === "TAKEAWAY") return <ShoppingBag className="h-4 w-4" aria-hidden="true" />;
  return <UtensilsCrossed className="h-4 w-4" aria-hidden="true" />;
}

function EmptyState({
  onReload,
  canCreateOrder,
  onNewOrder,
}: {
  onReload: () => void;
  canCreateOrder: boolean;
  onNewOrder: () => void;
}) {
  return (
    <div className="flex min-h-[360px] flex-col items-center justify-center rounded-lg border border-dashed border-border-medium bg-bg-primary p-8 text-center">
      <FileClock className="h-12 w-12 text-text-muted" aria-hidden="true" />
      <h2 className="mt-4 text-base font-semibold text-text-primary">Nenhum pedido encontrado</h2>
      <p className="mt-1 max-w-sm text-sm text-text-muted">
        Ajuste os filtros, atualize a lista{canCreateOrder ? " ou lance um novo pedido" : ""}.
      </p>
      <div className="mt-5 flex flex-wrap justify-center gap-2">
        <button type="button" onClick={onReload} className="btn-outline gap-2">
          <RefreshCcw className="h-4 w-4" aria-hidden="true" />
          Atualizar
        </button>
        {canCreateOrder && (
          <button type="button" onClick={onNewOrder} className="btn-primary gap-2">
            <ShoppingBag className="h-4 w-4" aria-hidden="true" />
            Novo pedido
          </button>
        )}
      </div>
    </div>
  );
}

function OrderCard({
  order,
  selected,
  onSelect,
  batchSelected,
  onToggleBatch,
}: {
  order: OrderResponse;
  selected: boolean;
  onSelect: () => void;
  batchSelected: boolean;
  onToggleBatch: () => void;
}) {
  const elapsed = elapsedMinutes(order);
  const overdue = order.status !== "DELIVERED" && order.status !== "CANCELLED" && elapsed >= order.estimatedPrepTimeMinutes;
  const previewItems = order.items.slice(0, 2).map((item) => `${item.quantity}x ${item.productName}`).join(", ");
  const label = order.externalDisplayId ?? `#${order.orderNumber}`;

  return (
    <div
      className={[
        "flex items-stretch gap-1 rounded-lg transition-colors",
        batchSelected ? "bg-primary-50 ring-1 ring-primary-700" : "",
      ].join(" ")}
    >
      {/* Coluna de seleção em lote: irmã do botão (não aninhada) — evita
          interativo dentro de interativo e dispensa stopPropagation. */}
      <label className="flex min-w-11 shrink-0 cursor-pointer items-start justify-center pt-5">
        <input
          type="checkbox"
          checked={batchSelected}
          onChange={onToggleBatch}
          aria-label={`Selecionar pedido ${label} para ação em lote`}
          className="h-5 w-5 cursor-pointer rounded border-border-medium accent-primary-700 focus-visible:ring-2 focus-visible:ring-primary-700"
        />
      </label>
    <button
      type="button"
      onClick={onSelect}
      className={[
        "min-w-0 flex-1 rounded-lg border bg-bg-primary p-4 text-left shadow-card transition-colors hover:bg-bg-secondary focus-visible:ring-2 focus-visible:ring-primary-700",
        selected ? "border-primary-700 ring-1 ring-primary-700" : "border-border-light",
      ].join(" ")}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <p className="text-lg font-bold text-text-primary">
              {order.externalDisplayId ?? `#${order.orderNumber}`}
            </p>
            <StatusBadge status={order.status} />
          </div>
          {order.externalDisplayId && (
            <p className="mt-0.5 text-xs text-text-muted">interno #{order.orderNumber}</p>
          )}
        </div>
        <ChevronRight className="mt-1 h-5 w-5 shrink-0 text-text-muted" aria-hidden="true" />
      </div>

      <div className="mt-3 flex flex-wrap gap-2 text-sm text-text-secondary">
        <span className="inline-flex items-center gap-1">
          <TypeIcon type={order.orderType} />
          {ORDER_TYPE_LABEL[order.orderType]}
          {order.tableNumber ? ` ${order.tableNumber}` : ""}
        </span>
        <span className={["inline-flex items-center gap-1", overdue ? "font-semibold text-red-700" : ""].join(" ")}>
          <Clock className="h-4 w-4" aria-hidden="true" />
          {elapsed} min
        </span>
        {order.externalOrigin !== "OWN" && (
          <span className="inline-flex items-center gap-1">
            <Bike className="h-4 w-4" aria-hidden="true" />
            {order.externalOrigin}
          </span>
        )}
      </div>

      <p className="mt-3 line-clamp-2 text-sm text-text-secondary">
        {previewItems || "Sem itens registrados"}
      </p>

      <div className="mt-4 flex items-center justify-between gap-3">
        <PaymentBadge status={order.paymentStatus} />
        <span className="text-base font-bold text-text-primary">{formatCents(order.totalCents)}</span>
      </div>
    </button>
    </div>
  );
}

function DetailPanel({
  order,
  onAdvance,
  onCancel,
  busy,
  deliveryInfo,
}: {
  order: OrderResponse | null;
  onAdvance: (order: OrderResponse) => void;
  onCancel: (order: OrderResponse) => void;
  busy: boolean;
  deliveryInfo?: DeliveryOrderInfo | null;
}) {
  if (!order) {
    return (
      <aside className="hidden min-h-[520px] rounded-lg border border-border-light bg-bg-primary p-6 shadow-card lg:flex lg:flex-col lg:items-center lg:justify-center">
        <Receipt className="h-12 w-12 text-text-muted" aria-hidden="true" />
        <p className="mt-4 text-center text-sm font-medium text-text-secondary">
          Selecione um pedido para ver os detalhes
        </p>
      </aside>
    );
  }

  const next = NEXT_STATUS[order.status];
  const elapsed = elapsedMinutes(order);

  return (
    <aside className="rounded-lg border border-border-light bg-bg-primary shadow-card">
      <div className="border-b border-border-light p-5">
        <div className="flex items-start justify-between gap-4">
          <div>
            <p className="text-sm text-text-muted">Pedido</p>
            <h2 className="text-2xl font-bold text-text-primary">
              {order.externalDisplayId ?? `#${order.orderNumber}`}
            </h2>
          </div>
          <StatusBadge status={order.status} />
        </div>
        <div className="mt-4 grid gap-2 text-sm text-text-secondary sm:grid-cols-2 lg:grid-cols-1 xl:grid-cols-2">
          <span className="inline-flex items-center gap-2 rounded-lg bg-bg-secondary px-3 py-2">
            <Clock className="h-4 w-4" aria-hidden="true" />
            {elapsed} min na operação
          </span>
          <span className="inline-flex items-center gap-2 rounded-lg bg-bg-secondary px-3 py-2">
            <CalendarClock className="h-4 w-4" aria-hidden="true" />
            {formatDateTime(order.createdAt)}
          </span>
          <span className="inline-flex items-center gap-2 rounded-lg bg-bg-secondary px-3 py-2">
            <TypeIcon type={order.orderType} />
            {ORDER_TYPE_LABEL[order.orderType]}{order.tableNumber ? ` ${order.tableNumber}` : ""}
          </span>
          <span className="inline-flex items-center gap-2 rounded-lg bg-bg-secondary px-3 py-2">
            <CreditCard className="h-4 w-4" aria-hidden="true" />
            {order.paymentMethod ? PAYMENT_LABEL[order.paymentMethod] : "Pagamento não definido"}
          </span>
        </div>
      </div>

      <div className="grid gap-5 p-5">
        {order.orderType === "DELIVERY" && (
          <section className="rounded-lg border border-primary-200 bg-primary-50 p-3">
            <h3 className="flex items-center gap-2 text-sm font-semibold text-primary-900">
              <Bike className="h-4 w-4" aria-hidden="true" />
              Entrega
            </h3>
            {deliveryInfo ? (
              <div className="mt-2 grid gap-1 text-sm text-primary-900">
                <p>
                  Status:{" "}
                  <span className="font-semibold">
                    {DELIVERY_DISPATCH_LABEL[deliveryInfo.deliveryStatus ?? ""] ?? "Aguardando motoboy"}
                  </span>
                </p>
                <p>
                  Motoboy:{" "}
                  <span className="font-semibold">
                    {deliveryInfo.driverId ? "Atribuído" : "Ainda não atribuído"}
                  </span>
                </p>
                {(deliveryInfo.deliveryRecipientName || deliveryInfo.deliveryNeighborhood) && (
                  <p className="text-primary-800">
                    {[deliveryInfo.deliveryRecipientName, deliveryInfo.deliveryNeighborhood]
                      .filter(Boolean)
                      .join(" · ")}
                  </p>
                )}
              </div>
            ) : (
              <p className="mt-2 text-sm text-primary-800">
                Status de entrega não disponível aqui — acompanhe pela central de despacho.
              </p>
            )}
            <Link
              href="/delivery"
              className="mt-3 inline-flex items-center gap-1 text-sm font-semibold text-primary-700 hover:underline"
            >
              Ver na entrega
              <ChevronRight className="h-4 w-4" aria-hidden="true" />
            </Link>
          </section>
        )}

        <section>
          <h3 className="text-sm font-semibold text-text-primary">Itens</h3>
          <ul className="mt-3 grid gap-2">
            {order.items.map((item) => (
              <li key={item.id} className="rounded-lg bg-bg-secondary p-3">
                <div className="flex items-start justify-between gap-3">
                  <div>
                    <p className="text-sm font-semibold text-text-primary">
                      {item.quantity}x {item.productName}
                    </p>
                    {(item.sizeName || item.flavor1Name || item.flavor2Name || (item.options?.length ?? 0) > 0) && (
                      <p className="mt-1 text-xs text-text-muted">
                        {[item.sizeName, item.flavor1Name, item.flavor2Name, ...(item.options ?? []).map((opt) => opt.optionName)]
                          .filter(Boolean)
                          .join(" · ")}
                      </p>
                    )}
                    {item.notes && <p className="mt-1 text-xs font-medium text-secondary-800">{item.notes}</p>}
                  </div>
                  <span className="shrink-0 text-sm font-semibold text-text-primary">
                    {formatCents(item.totalPriceCents)}
                  </span>
                </div>
              </li>
            ))}
          </ul>
        </section>

        {order.notes && (
          <section className="rounded-lg border border-secondary-200 bg-secondary-50 p-3 text-sm text-secondary-900">
            <h3 className="font-semibold">Observações</h3>
            <p className="mt-1">{order.notes}</p>
          </section>
        )}

        <section>
          <h3 className="text-sm font-semibold text-text-primary">Resumo financeiro</h3>
          <dl className="mt-3 grid gap-2 text-sm">
            <div className="flex justify-between rounded-lg bg-bg-secondary px-3 py-2">
              <dt className="text-text-secondary">Subtotal</dt>
              <dd className="font-semibold text-text-primary">{formatCents(order.subtotalCents)}</dd>
            </div>
            <div className="flex justify-between rounded-lg bg-bg-secondary px-3 py-2">
              <dt className="text-text-secondary">Desconto</dt>
              <dd className="font-semibold text-text-primary">-{formatCents(order.discountCents)}</dd>
            </div>
            <div className="flex justify-between rounded-lg bg-bg-secondary px-3 py-2">
              <dt className="text-text-secondary">Entrega</dt>
              <dd className="font-semibold text-text-primary">{formatCents(order.deliveryFeeCents)}</dd>
            </div>
            <div className="flex justify-between rounded-lg bg-primary-50 px-3 py-2">
              <dt className="font-semibold text-primary-800">Total</dt>
              <dd className="font-bold text-primary-800">{formatCents(order.totalCents)}</dd>
            </div>
          </dl>
        </section>

        <section>
          <h3 className="text-sm font-semibold text-text-primary">Timeline</h3>
          <div className="mt-3 grid gap-2 text-sm text-text-secondary">
            <div className="flex items-center gap-2">
              <span className="h-2 w-2 rounded-full bg-primary-700" aria-hidden="true" />
              Criado em {formatDateTime(order.createdAt)}
            </div>
            <div className="flex items-center gap-2">
              <span className="h-2 w-2 rounded-full bg-border-medium" aria-hidden="true" />
              Atualizado em {formatDateTime(order.updatedAt)}
            </div>
            {order.completedAt && (
              <div className="flex items-center gap-2">
                <span className="h-2 w-2 rounded-full bg-emerald-700" aria-hidden="true" />
                Concluído em {formatDateTime(order.completedAt)}
              </div>
            )}
          </div>
        </section>

        <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-1 xl:grid-cols-2">
          {next && (
            <button type="button" onClick={() => onAdvance(order)} disabled={busy} className="btn-primary gap-2">
              <PackageCheck className="h-4 w-4" aria-hidden="true" />
              {busy ? "Atualizando..." : ADVANCE_LABEL[order.status]}
            </button>
          )}
          {order.status !== "DELIVERED" && order.status !== "CANCELLED" && (
            <button type="button" onClick={() => onCancel(order)} disabled={busy} className="btn-outline gap-2 text-red-700 hover:text-red-700">
              <X className="h-4 w-4" aria-hidden="true" />
              Cancelar
            </button>
          )}
          <button type="button" onClick={() => printComanda(order)} className="btn-outline gap-2">
            <Printer className="h-4 w-4" aria-hidden="true" />
            Imprimir comanda
          </button>
        </div>
      </div>
    </aside>
  );
}

function CancelModal({
  order,
  onClose,
  onConfirm,
  busy,
}: {
  order: OrderResponse;
  onClose: () => void;
  onConfirm: (reason: string) => void;
  busy: boolean;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useModalA11y(ref as React.RefObject<HTMLElement>, onClose);
  const [reason, setReason] = useState("");

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4" role="dialog" aria-modal="true" aria-labelledby="cancel-order-title">
      <div className="absolute inset-0 bg-black/50" aria-hidden="true" onClick={onClose} />
      <div ref={ref} className="relative z-10 w-full max-w-md rounded-lg bg-bg-primary p-5 shadow-dropdown">
        <h2 id="cancel-order-title" className="text-lg font-semibold text-text-primary">
          Cancelar pedido #{order.orderNumber}
        </h2>
        <p className="mt-1 text-sm text-text-muted">
          Informe o motivo para manter o histórico auditável.
        </p>
        <label htmlFor="cancel-reason" className="mt-4 block text-sm font-medium text-text-primary">
          Motivo
        </label>
        <textarea
          id="cancel-reason"
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          className="input-field mt-2 min-h-28 resize-none"
          placeholder="Ex.: cliente desistiu, item indisponível..."
          autoFocus
        />
        <div className="mt-5 flex gap-2">
          <button type="button" onClick={onClose} disabled={busy} className="btn-outline flex-1">
            Voltar
          </button>
          <button
            type="button"
            onClick={() => onConfirm(reason.trim())}
            disabled={busy || reason.trim().length < 3}
            className="inline-flex min-h-11 flex-1 items-center justify-center rounded-lg bg-red-700 px-4 py-2 font-medium text-white transition-colors hover:bg-red-800 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {busy ? "Cancelando..." : "Confirmar"}
          </button>
        </div>
      </div>
    </div>
  );
}

/**
 * Barra de ações em lote — só aparece quando há seleção. "Selecionar todos"
 * usa estado indeterminado quando a seleção é parcial (via ref, pois HTML não
 * tem atributo declarativo para isso).
 */
function BatchActionBar({
  selectedCount,
  advanceCount,
  allSelected,
  someSelected,
  busy,
  onToggleAll,
  onAdvance,
  onPrint,
  onClear,
}: {
  selectedCount: number;
  advanceCount: number;
  allSelected: boolean;
  someSelected: boolean;
  busy: boolean;
  onToggleAll: () => void;
  onAdvance: () => void;
  onPrint: () => void;
  onClear: () => void;
}) {
  const allRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (allRef.current) allRef.current.indeterminate = someSelected && !allSelected;
  }, [someSelected, allSelected]);

  return (
    <section
      className="sticky top-2 z-20 flex flex-wrap items-center gap-x-4 gap-y-2 rounded-lg border border-primary-700 bg-bg-primary p-3 shadow-dropdown"
      role="region"
      aria-label="Ações em lote"
    >
      <label className="inline-flex min-h-11 cursor-pointer items-center gap-2 text-sm font-medium text-text-primary">
        <input
          ref={allRef}
          type="checkbox"
          checked={allSelected}
          onChange={onToggleAll}
          className="h-5 w-5 cursor-pointer rounded border-border-medium accent-primary-700 focus-visible:ring-2 focus-visible:ring-primary-700"
        />
        Selecionar todos (filtrados)
      </label>
      <span className="text-sm font-semibold text-primary-700">
        {selectedCount} selecionado{selectedCount === 1 ? "" : "s"}
      </span>
      <div className="ml-auto flex flex-wrap gap-2">
        <button type="button" onClick={onAdvance} disabled={busy || advanceCount === 0} className="btn-primary gap-2">
          <PackageCheck className="h-4 w-4" aria-hidden="true" />
          {busy ? "Processando..." : `Avançar ${advanceCount}`}
        </button>
        <button type="button" onClick={onPrint} disabled={busy || selectedCount === 0} className="btn-outline gap-2">
          <Printer className="h-4 w-4" aria-hidden="true" />
          Imprimir {selectedCount}
        </button>
        <button type="button" onClick={onClear} disabled={busy} className="btn-outline gap-2">
          <X className="h-4 w-4" aria-hidden="true" />
          Limpar seleção
        </button>
      </div>
    </section>
  );
}

export default function PedidosPage() {
  const router = useRouter();
  const [orders, setOrders] = useState<OrderResponse[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set());
  const [batchBusy, setBatchBusy] = useState(false);
  const [batchNotice, setBatchNotice] = useState<{ ok: number; fail: number } | null>(null);
  const [period, setPeriod] = useState<Period>("today");
  const [status, setStatus] = useState<StatusFilter>("ALL");
  const [search, setSearch] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [cancelTarget, setCancelTarget] = useState<OrderResponse | null>(null);
  const [userRole, setUserRole] = useState<string | null>(null);
  const [showNovoPedido, setShowNovoPedido] = useState(false);

  // Papel lido do token (não da URL) — mesmo padrão de components/layout/Sidebar.tsx.
  // Roda em useEffect (não no render) para não divergir do HTML gerado no servidor.
  useEffect(() => {
    setUserRole(getUserRole());
  }, []);
  const canCreateOrder = userRole !== null && CAN_CREATE_ORDER_ROLES.has(userRole);
  const canAccessDelivery = userRole !== null && CAN_ACCESS_DELIVERY_ROLES.has(userRole);

  // Enriquecimento opcional dos pedidos DELIVERY com o status de despacho.
  // GET /delivery/orders/active é RBAC OPERATOR/MANAGER/ADMIN no backend (ver
  // DeliveryController.kt:80-81) — mais estreito que quem acessa /pedidos
  // (ADMIN/MANAGER/STAFF/CASHIER). Para STAFF/CASHIER o backend responde 403;
  // tratamos como "enriquecimento indisponível" (mapa vazio), sem erro na
  // tela — a central de despacho (/delivery) continua acessível pelo botão
  // Roteirizar e é onde de fato se atua sobre a entrega.
  const [deliveryInfoById, setDeliveryInfoById] = useState<Map<string, DeliveryOrderInfo>>(
    () => new Map(),
  );
  const loadDeliveryInfo = useCallback(async () => {
    try {
      const active = await api.get<DeliveryOrderInfo[]>("/delivery/orders/active");
      setDeliveryInfoById(new Map(active.map((info) => [info.orderId, info])));
    } catch {
      // 403 (sem permissão) ou qualquer outra falha: enriquecimento é
      // acessório, nunca pode derrubar a tela de pedidos.
      setDeliveryInfoById(new Map());
    }
  }, []);

  const load = useCallback(async () => {
    const token = getToken();
    if (!token) {
      router.replace("/login");
      return;
    }

    setLoading(true);
    setError(null);
    const range = periodRange(period);
    const params = new URLSearchParams({
      from: range.from,
      to: range.to,
      page: "0",
      size: "100",
      sort: "createdAt,desc",
    });
    if (status !== "ALL") params.set("status", status);

    try {
      const page = await api.get<OrdersPage>(`/orders?${params.toString()}`);
      setOrders(page.content);
      setSelectedId((current) => {
        if (current && page.content.some((order) => order.id === current)) return current;
        return page.content[0]?.id ?? null;
      });
      void loadDeliveryInfo();
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        router.replace("/login");
        return;
      }
      setError(friendlyError(err));
    } finally {
      setLoading(false);
    }
  }, [period, router, status, loadDeliveryInfo]);

  useEffect(() => {
    void load();
  }, [load]);

  const filteredOrders = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return orders;
    return orders.filter((order) => orderSearchText(order).includes(q));
  }, [orders, search]);

  // A seleção de lote se ajusta quando a lista recarrega: descarta ids de
  // pedidos que sumiram (recarga/troca de período/status no servidor).
  useEffect(() => {
    setSelectedIds((prev) => {
      if (prev.size === 0) return prev;
      const valid = new Set<string>();
      for (const order of orders) if (prev.has(order.id)) valid.add(order.id);
      return valid.size === prev.size ? prev : valid;
    });
  }, [orders]);

  // Seleção efetiva = interseção do Set com o que está VISÍVEL (após busca):
  // a barra nunca conta nem age sobre pedidos escondidos pelo filtro atual.
  const selectedVisible = useMemo(
    () => filteredOrders.filter((order) => selectedIds.has(order.id)),
    [filteredOrders, selectedIds],
  );
  const advanceableCount = useMemo(
    () => selectedVisible.filter((order) => NEXT_STATUS[order.status]).length,
    [selectedVisible],
  );
  const allFilteredSelected = filteredOrders.length > 0 && selectedVisible.length === filteredOrders.length;
  const someSelected = selectedVisible.length > 0;

  const toggleBatch = useCallback((id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  const toggleAllFiltered = useCallback(() => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      const allSel = filteredOrders.length > 0 && filteredOrders.every((order) => next.has(order.id));
      for (const order of filteredOrders) {
        if (allSel) next.delete(order.id);
        else next.add(order.id);
      }
      return next;
    });
  }, [filteredOrders]);

  const clearSelection = useCallback(() => {
    setSelectedIds(new Set());
    setBatchNotice(null);
  }, []);

  const selectedOrder = filteredOrders.find((order) => order.id === selectedId) ?? filteredOrders[0] ?? null;

  const counts = useMemo(() => {
    const base: Record<OrderStatus, number> = {
      PENDING: 0,
      PREPARING: 0,
      READY: 0,
      DELIVERED: 0,
      CANCELLED: 0,
    };
    for (const order of orders) base[order.status] += 1;
    return base;
  }, [orders]);

  async function updateStatus(order: OrderResponse, nextStatus: OrderStatus, reason?: string) {
    setBusy(true);
    setError(null);
    try {
      const updated = await api.put<OrderResponse>(`/orders/${order.id}/status`, {
        status: nextStatus,
        ...(reason ? { reason } : {}),
      });
      setOrders((current) => current.map((item) => (item.id === updated.id ? updated : item)));
      setSelectedId(updated.id);
      setCancelTarget(null);
    } catch (err) {
      setError(friendlyError(err));
    } finally {
      setBusy(false);
    }
  }

  // Avança em lote via Promise.allSettled sobre o PUT já existente: cada pedido
  // é isolado — um erro não aborta os demais e temos o resultado individual.
  async function advanceSelected() {
    const targets = filteredOrders.filter((order) => selectedIds.has(order.id) && NEXT_STATUS[order.status]);
    if (targets.length === 0) return;
    setBatchBusy(true);
    setError(null);
    setBatchNotice(null);
    const results = await Promise.allSettled(
      targets.map((order) =>
        api.put<OrderResponse>(`/orders/${order.id}/status`, { status: NEXT_STATUS[order.status] as OrderStatus }),
      ),
    );
    const failedIds = targets.filter((_, index) => results[index].status === "rejected").map((order) => order.id);
    setBatchNotice({ ok: targets.length - failedIds.length, fail: failedIds.length });
    // Mantém selecionados só os que falharam, para retry de um clique.
    setSelectedIds(new Set(failedIds));
    await load();
    setBatchBusy(false);
  }

  function printSelected() {
    printComandas(filteredOrders.filter((order) => selectedIds.has(order.id)));
  }

  const totalOpen = orders.filter((order) => !["DELIVERED", "CANCELLED"].includes(order.status)).length;
  const delayed = orders.filter((order) => {
    if (["DELIVERED", "CANCELLED"].includes(order.status)) return false;
    return elapsedMinutes(order) >= order.estimatedPrepTimeMinutes;
  }).length;

  return (
    <main className="min-h-full bg-bg-secondary p-4 sm:p-6">
      <div className="mx-auto flex max-w-7xl flex-col gap-4">
        <section className="rounded-lg border border-border-light bg-bg-primary p-4 shadow-card sm:p-5">
          <div className="flex flex-col gap-4 xl:flex-row xl:items-center xl:justify-between">
            <div>
              <p className="inline-flex items-center gap-2 text-sm font-medium text-primary-700">
                <Receipt className="h-4 w-4" aria-hidden="true" />
                Central operacional
              </p>
              <h1 className="mt-2 text-2xl font-bold text-text-primary">Pedidos</h1>
              <p className="mt-1 max-w-2xl text-sm text-text-muted">
                Busque, acompanhe e resolva pedidos sem misturar PDV, cozinha e caixa.
              </p>
            </div>
            <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
              {canCreateOrder && (
                <button
                  type="button"
                  onClick={() => setShowNovoPedido(true)}
                  className="btn-primary gap-2"
                >
                  <ShoppingBag className="h-4 w-4" aria-hidden="true" />
                  Novo pedido
                </button>
              )}
              {canAccessDelivery && (
                <button
                  type="button"
                  onClick={() => router.push("/delivery")}
                  className="btn-outline gap-2"
                  title="Abrir a central de despacho de entregas"
                >
                  <Bike className="h-4 w-4" aria-hidden="true" />
                  Roteirizar
                </button>
              )}
              <button type="button" onClick={() => void load()} className="btn-outline gap-2">
                <RefreshCcw className="h-4 w-4" aria-hidden="true" />
                Atualizar
              </button>
            </div>
          </div>

          <div className="mt-5 grid gap-3 lg:grid-cols-[minmax(260px,1fr)_auto_auto]">
            <label className="relative block">
              <span className="sr-only">Buscar pedido</span>
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-text-muted" aria-hidden="true" />
              <input
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                className="input-field input-with-leading-icon"
                placeholder="Pesquise por nº, item, mesa, canal ou pagamento"
              />
            </label>
            <div className="flex rounded-lg border border-border-light bg-bg-secondary p-1" role="group" aria-label="Período dos pedidos">
              {(["today", "week", "month"] as Period[]).map((option) => (
                <button
                  key={option}
                  type="button"
                  onClick={() => setPeriod(option)}
                  aria-pressed={period === option}
                  className={[
                    "min-h-10 rounded-md px-3 text-sm font-medium transition-colors",
                    period === option ? "bg-primary-700 text-white" : "text-text-secondary hover:bg-bg-primary",
                  ].join(" ")}
                >
                  {option === "today" ? "Hoje" : option === "week" ? "Semana" : "Mês"}
                </button>
              ))}
            </div>
            <div className="grid grid-cols-2 gap-2 sm:flex">
              <div className="rounded-lg bg-bg-secondary px-3 py-2">
                <p className="text-xs text-text-muted">Abertos</p>
                <p className="text-sm font-bold text-text-primary">{totalOpen}</p>
              </div>
              <div className="rounded-lg bg-bg-secondary px-3 py-2">
                <p className="text-xs text-text-muted">Atrasados</p>
                <p className={["text-sm font-bold", delayed > 0 ? "text-red-700" : "text-text-primary"].join(" ")}>{delayed}</p>
              </div>
            </div>
          </div>
        </section>

        <section className="overflow-x-auto no-scrollbar">
          <div className="flex min-w-max gap-2" role="tablist" aria-label="Filtro por status do pedido">
            {STATUS_FILTERS.map((filter) => {
              const active = status === filter.value;
              const count = filter.value === "ALL" ? orders.length : counts[filter.value];
              return (
                <button
                  key={filter.value}
                  type="button"
                  role="tab"
                  aria-selected={active}
                  onClick={() => setStatus(filter.value)}
                  className={[
                    "inline-flex min-h-11 items-center gap-2 rounded-lg border px-4 text-sm font-semibold transition-colors",
                    active
                      ? "border-primary-700 bg-primary-700 text-white"
                      : "border-border-light bg-bg-primary text-text-secondary hover:bg-bg-tertiary hover:text-text-primary",
                  ].join(" ")}
                >
                  {filter.value !== "ALL" && (
                    <span className={["h-2 w-2 rounded-full", STATUS_META[filter.value].dot].join(" ")} aria-hidden="true" />
                  )}
                  {filter.label}
                  <span className={["rounded-full px-2 py-0.5 text-xs", active ? "bg-white/20 text-white" : "bg-bg-tertiary text-text-secondary"].join(" ")}>
                    {count}
                  </span>
                </button>
              );
            })}
          </div>
        </section>

        {error && (
          <section className="rounded-lg border border-red-200 bg-red-50 p-4 text-red-700" role="alert">
            <div className="flex items-start gap-3">
              <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0" aria-hidden="true" />
              <div>
                <p className="font-semibold">Não foi possível concluir a ação</p>
                <p className="mt-1 text-sm">{error}</p>
              </div>
            </div>
          </section>
        )}

        {batchNotice && (
          <section
            className={[
              "rounded-lg border p-3 text-sm",
              batchNotice.fail > 0 ? "border-secondary-200 bg-secondary-50 text-secondary-900" : "border-primary-200 bg-primary-50 text-primary-800",
            ].join(" ")}
            role="status"
          >
            <div className="flex items-center gap-2">
              {batchNotice.fail > 0 ? (
                <AlertTriangle className="h-4 w-4 shrink-0" aria-hidden="true" />
              ) : (
                <CheckCircle2 className="h-4 w-4 shrink-0" aria-hidden="true" />
              )}
              <span className="font-medium">
                {batchNotice.ok} avançado{batchNotice.ok === 1 ? "" : "s"}
                {batchNotice.fail > 0 ? `, ${batchNotice.fail} falhou${batchNotice.fail === 1 ? "" : "ram"} (mantidos selecionados para tentar de novo)` : ""}
              </span>
            </div>
          </section>
        )}

        {someSelected && (
          <BatchActionBar
            selectedCount={selectedVisible.length}
            advanceCount={advanceableCount}
            allSelected={allFilteredSelected}
            someSelected={someSelected}
            busy={batchBusy}
            onToggleAll={toggleAllFiltered}
            onAdvance={() => void advanceSelected()}
            onPrint={printSelected}
            onClear={clearSelection}
          />
        )}

        {loading ? (
          <div className="grid gap-4 lg:grid-cols-[minmax(0,0.95fr)_minmax(360px,1.05fr)]">
            <div className="grid gap-3">
              {Array.from({ length: 5 }).map((_, index) => (
                <div key={index} className="h-40 animate-pulse rounded-lg border border-border-light bg-bg-primary shadow-card" />
              ))}
            </div>
            <div className="hidden min-h-[520px] animate-pulse rounded-lg border border-border-light bg-bg-primary shadow-card lg:block" />
          </div>
        ) : filteredOrders.length === 0 ? (
          <EmptyState
            onReload={() => void load()}
            canCreateOrder={canCreateOrder}
            onNewOrder={() => setShowNovoPedido(true)}
          />
        ) : (
          <div className="grid gap-4 lg:grid-cols-[minmax(0,0.95fr)_minmax(360px,1.05fr)]">
            <section className="grid gap-3">
              {filteredOrders.map((order) => (
                <OrderCard
                  key={order.id}
                  order={order}
                  selected={selectedOrder?.id === order.id}
                  onSelect={() => setSelectedId(order.id)}
                  batchSelected={selectedIds.has(order.id)}
                  onToggleBatch={() => toggleBatch(order.id)}
                />
              ))}
            </section>
            <div className="lg:sticky lg:top-4 lg:self-start">
              <DetailPanel
                order={selectedOrder}
                onAdvance={(order) => {
                  const next = NEXT_STATUS[order.status];
                  if (next) void updateStatus(order, next);
                }}
                onCancel={setCancelTarget}
                busy={busy || batchBusy}
                deliveryInfo={selectedOrder ? deliveryInfoById.get(selectedOrder.id) : undefined}
              />
            </div>
          </div>
        )}
      </div>

      {cancelTarget && (
        <CancelModal
          order={cancelTarget}
          busy={busy}
          onClose={() => setCancelTarget(null)}
          onConfirm={(reason) => void updateStatus(cancelTarget, "CANCELLED", reason)}
        />
      )}

      {showNovoPedido && canCreateOrder && (
        <NovoPedidoSheet
          onClose={() => setShowNovoPedido(false)}
          onCreated={(orderId) => {
            // Sucesso: fecha o sheet, recarrega a lista e seleciona o pedido
            // recém-criado. Também limpa filtro de status/busca — senão o
            // pedido novo (status PENDING) pode ficar escondido por um filtro
            // antigo (ex.: "Concluído") e parecer que "sumiu".
            setShowNovoPedido(false);
            setStatus("ALL");
            setSearch("");
            setSelectedId(orderId);
            void load();
          }}
        />
      )}
    </main>
  );
}
