"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import {
  AlertTriangle,
  Battery,
  Bike,
  CheckCircle2,
  Clock,
  MapPin,
  MapPinOff,
  Package,
  Phone,
  RefreshCcw,
  Truck,
  User,
  UserCheck,
  X,
} from "lucide-react";
import { api, ApiError } from "@/lib/api";
import { getToken } from "@/lib/auth";
import { useModalA11y } from "@/lib/use-modal-a11y";

// ── Types ─────────────────────────────────────────────────────────────────────

type DeliveryStatus =
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

interface DeliveryOrder {
  orderId: string;
  orderNumber: string;
  driverId: string | null;
  deliveryStatus: DeliveryStatus | null;
  totalCents: number;
  tableNumber: string | null;
  updatedAt: string;
  // Fase 1B
  externalOrigin?: string;
  externalDisplayId?: string | null;
  deliveryRecipientName?: string | null;
  deliveryPhone?: string | null;
  deliveryNeighborhood?: string | null;
  deliveryCity?: string | null;
  deliveryStreet?: string | null;
  deliveryNumber?: string | null;
  deliveryComplement?: string | null;
  deliveryReference?: string | null;
  deliveryLat?: number | null;
  deliveryLng?: number | null;
  deliveryFeeCents?: number;
  salesChannel?: string;
  paymentStatus?: string;
  createdAt?: string;
}

interface Driver {
  id: string;
  name: string;
  phone: string;
  licensePlate: string | null;
  active: boolean;
  activeShift: boolean;
  lastLat: number | null;
  lastLng: number | null;
  lastLocationAt: string | null;
  batteryPct: number | null;
}

// ── Helpers ───────────────────────────────────────────────────────────────────

const STATUS_LABEL: Record<string, string> = {
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

function fmtMoney(cents: number): string {
  return (cents / 100).toLocaleString("pt-BR", {
    style: "currency",
    currency: "BRL",
  });
}

function fmtElapsed(isoStr: string): string {
  const diff = Math.floor((Date.now() - new Date(isoStr).getTime()) / 1000);
  if (diff < 60) return `${diff}s`;
  if (diff < 3600) return `${Math.floor(diff / 60)}min`;
  return `${Math.floor(diff / 3600)}h${Math.floor((diff % 3600) / 60)}min`;
}

function isLate(order: DeliveryOrder): boolean {
  if (!order.createdAt) return false;
  const diffMin = (Date.now() - new Date(order.createdAt).getTime()) / 60000;
  return diffMin > 45 && order.deliveryStatus !== "DELIVERED";
}

function nextDeliveryStatus(
  current: DeliveryStatus | null | undefined,
): DeliveryStatus | null {
  switch (current) {
    case "ACCEPTED":
    case "ASSIGNED":
    case "ARRIVED_AT_STORE":
      return "PICKED_UP";
    case "PICKED_UP":
      return "OUT_FOR_DELIVERY";
    case "OUT_FOR_DELIVERY":
      return "ARRIVED_AT_CUSTOMER";
    case "ARRIVED_AT_CUSTOMER":
      return "DELIVERED";
    default:
      return null;
  }
}

function nextStatusLabel(status: DeliveryStatus): string {
  switch (status) {
    case "PICKED_UP":
      return "Coletado na loja";
    case "OUT_FOR_DELIVERY":
      return "Saiu para entrega";
    case "ARRIVED_AT_CUSTOMER":
      return "Chegou ao cliente";
    case "DELIVERED":
      return "Marcar como entregue";
    default:
      return STATUS_LABEL[status] ?? status;
  }
}

// ── Skeleton ──────────────────────────────────────────────────────────────────

function SkeletonBlock({ className }: { className?: string }) {
  return (
    <div
      className={`animate-pulse rounded-lg bg-bg-tertiary ${className ?? ""}`}
    />
  );
}

function LoadingSkeleton() {
  return (
    <div className="flex flex-col gap-3" aria-label="Carregando...">
      {[1, 2, 3].map((i) => (
        <div key={i} className="rounded-xl border border-border-light p-4">
          <SkeletonBlock className="mb-2 h-5 w-32" />
          <SkeletonBlock className="mb-1 h-4 w-48" />
          <SkeletonBlock className="h-4 w-24" />
        </div>
      ))}
    </div>
  );
}

// ── KPI Card ──────────────────────────────────────────────────────────────────

function KpiCard({
  label,
  value,
  accent,
}: {
  label: string;
  value: number;
  accent?: string;
}) {
  return (
    <div className="flex flex-col gap-1 rounded-xl border border-border-light bg-bg-primary p-4">
      <span className={`text-2xl font-bold ${accent ?? "text-text-primary"}`}>
        {value}
      </span>
      <span className="text-xs text-text-muted">{label}</span>
    </div>
  );
}

// ── Operational Map ───────────────────────────────────────────────────────────

function OperationalMap({
  orders,
  drivers,
}: {
  orders: DeliveryOrder[];
  drivers: Driver[];
}) {
  const driversWithLoc = drivers.filter(
    (d) => d.lastLat !== null && d.lastLng !== null && d.activeShift,
  );
  const ordersWithLoc = orders.filter(
    (o) => o.deliveryLat != null && o.deliveryLng != null,
  );

  const hasCoords = driversWithLoc.length > 0 || ordersWithLoc.length > 0;

  const allLats = [
    ...driversWithLoc.map((d) => d.lastLat!),
    ...ordersWithLoc.map((o) => o.deliveryLat!),
  ];
  const allLngs = [
    ...driversWithLoc.map((d) => d.lastLng!),
    ...ordersWithLoc.map((o) => o.deliveryLng!),
  ];

  const minLat = hasCoords ? Math.min(...allLats) : 0;
  const maxLat = hasCoords ? Math.max(...allLats) : 1;
  const minLng = hasCoords ? Math.min(...allLngs) : 0;
  const maxLng = hasCoords ? Math.max(...allLngs) : 1;
  const latRange = maxLat - minLat || 0.01;
  const lngRange = maxLng - minLng || 0.01;

  function toPercent(lat: number, lng: number) {
    return {
      top: `${(1 - (lat - minLat) / latRange) * 76 + 12}%`,
      left: `${((lng - minLng) / lngRange) * 76 + 12}%`,
    };
  }

  return (
    <div className="relative flex h-60 w-full overflow-hidden rounded-xl border border-border-light bg-bg-secondary">
      {/* Grid */}
      <div
        className="absolute inset-0 opacity-10"
        style={{
          backgroundImage:
            "linear-gradient(var(--tw-color-border-light, #e2e8f0) 1px, transparent 1px), linear-gradient(90deg, var(--tw-color-border-light, #e2e8f0) 1px, transparent 1px)",
          backgroundSize: "32px 32px",
        }}
        aria-hidden="true"
      />
      {/* Radius circle */}
      <div
        className="absolute rounded-full border-2 border-dashed border-primary-700/30 bg-primary-700/5"
        style={{ top: "12%", left: "12%", width: "76%", height: "76%" }}
        aria-hidden="true"
      />
      {/* Store center */}
      <div
        className="absolute flex h-9 w-9 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full bg-primary-700 text-white shadow-lg"
        style={{ top: "50%", left: "50%" }}
        title="Loja"
        aria-label="Ponto da loja"
      >
        <span role="img" aria-hidden="true" style={{ fontSize: 15 }}>
          🏪
        </span>
      </div>
      {/* Drivers */}
      {hasCoords &&
        driversWithLoc.map((d) => {
          const pos = toPercent(d.lastLat!, d.lastLng!);
          return (
            <div
              key={d.id}
              className="absolute flex h-7 w-7 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full bg-blue-500 text-white shadow"
              style={pos}
              title={d.name}
              aria-label={`Motoboy ${d.name}`}
            >
              <Bike className="h-3.5 w-3.5" aria-hidden="true" />
            </div>
          );
        })}
      {/* Orders */}
      {hasCoords &&
        ordersWithLoc.map((o) => {
          const pos = toPercent(o.deliveryLat!, o.deliveryLng!);
          const late = isLate(o);
          return (
            <div
              key={o.orderId}
              className={`absolute h-3 w-3 -translate-x-1/2 -translate-y-1/2 rounded-full shadow ${late ? "bg-red-500" : "bg-amber-400"}`}
              style={pos}
              title={`Pedido ${o.orderNumber}`}
              aria-label={`Pedido ${o.orderNumber}`}
            />
          );
        })}
      {/* Empty coords */}
      {!hasCoords && (
        <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 text-center">
          <MapPinOff className="h-8 w-8 text-text-muted" aria-hidden="true" />
          <p className="text-sm text-text-muted">
            Aguardando localização dos motoboys
          </p>
        </div>
      )}
      {/* Caption */}
      <p className="absolute bottom-2 right-2 rounded-md bg-bg-primary/80 px-2 py-0.5 text-[10px] text-text-muted backdrop-blur-sm">
        Mapa operacional — rota real na próxima fase
      </p>
    </div>
  );
}

// ── Driver Card ───────────────────────────────────────────────────────────────

function DriverCard({
  driver,
  onToggleShift,
  toggling,
}: {
  driver: Driver;
  onToggleShift: (id: string, active: boolean) => void;
  toggling: boolean;
}) {
  return (
    <div
      className={`rounded-xl border p-4 ${
        driver.activeShift
          ? "border-primary-700/30 bg-bg-primary"
          : "border-border-light bg-bg-secondary"
      }`}
    >
      <div className="flex items-center justify-between gap-3">
        <div className="flex min-w-0 flex-col">
          <span className="truncate font-medium text-text-primary">
            {driver.name}
          </span>
          {driver.licensePlate && (
            <span className="text-xs text-text-muted">{driver.licensePlate}</span>
          )}
        </div>
        <button
          onClick={() => onToggleShift(driver.id, !driver.activeShift)}
          disabled={toggling}
          aria-label={
            driver.activeShift
              ? `Encerrar turno de ${driver.name}`
              : `Iniciar turno de ${driver.name}`
          }
          className={`min-h-11 shrink-0 rounded-lg px-3 py-2 text-xs font-medium transition-colors disabled:opacity-60 ${
            driver.activeShift
              ? "bg-primary-700 text-white hover:bg-primary-800"
              : "border border-border-light text-text-secondary hover:bg-bg-tertiary"
          }`}
        >
          {toggling ? "..." : driver.activeShift ? "Em turno" : "Fora"}
        </button>
      </div>
      {driver.activeShift && (
        <div className="mt-2 flex flex-wrap gap-3 text-xs text-text-muted">
          {driver.batteryPct !== null && (
            <span className="flex items-center gap-1">
              <Battery className="h-3.5 w-3.5" aria-hidden="true" />
              {driver.batteryPct}%
            </span>
          )}
          {driver.lastLocationAt && (
            <span className="flex items-center gap-1">
              <Clock className="h-3.5 w-3.5" aria-hidden="true" />
              Loc. {fmtElapsed(driver.lastLocationAt)} atrás
            </span>
          )}
          {!driver.lastLat && (
            <span className="flex items-center gap-1 text-amber-600">
              <MapPinOff className="h-3.5 w-3.5" aria-hidden="true" />
              Sem localização
            </span>
          )}
        </div>
      )}
    </div>
  );
}

// ── Assignment Modal ──────────────────────────────────────────────────────────

function AssignModal({
  order,
  drivers,
  onClose,
  onAssign,
  assigning,
}: {
  order: DeliveryOrder;
  drivers: Driver[];
  onClose: () => void;
  onAssign: (driverId: string) => void;
  assigning: boolean;
}) {
  const dialogRef = useRef<HTMLDivElement>(null);
  useModalA11y(dialogRef as React.RefObject<HTMLElement>, onClose);

  const onShift = drivers.filter((d) => d.activeShift && d.active);
  const offShift = drivers.filter((d) => !d.activeShift && d.active);
  const [selected, setSelected] = useState<string | null>(null);

  return (
    <div
      className="fixed inset-0 z-50 flex items-end justify-center sm:items-center"
      role="dialog"
      aria-modal="true"
      aria-labelledby="assign-modal-title"
    >
      <div
        className="absolute inset-0 bg-black/50"
        aria-hidden="true"
        onClick={onClose}
      />
      <div
        ref={dialogRef}
        className="relative z-10 w-full max-w-md rounded-t-2xl border border-border-light bg-bg-primary p-6 shadow-xl sm:rounded-2xl"
      >
        <div className="mb-4 flex items-center justify-between">
          <h2
            id="assign-modal-title"
            className="text-base font-semibold text-text-primary"
          >
            Atribuir motoboy — #{order.orderNumber}
          </h2>
          <button
            onClick={onClose}
            aria-label="Fechar modal"
            className="flex h-11 w-11 items-center justify-center rounded-lg text-text-muted hover:bg-bg-tertiary"
          >
            <X className="h-5 w-5" aria-hidden="true" />
          </button>
        </div>

        <div className="flex max-h-64 flex-col gap-2 overflow-y-auto">
          {onShift.length > 0 && (
            <>
              <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-text-muted">
                Em turno
              </p>
              {onShift.map((d) => (
                <button
                  key={d.id}
                  onClick={() => setSelected(d.id)}
                  className={`flex min-h-11 items-center gap-3 rounded-lg border px-4 py-3 text-left text-sm transition-colors ${
                    selected === d.id
                      ? "border-primary-700 bg-primary-700/10 text-text-primary"
                      : "border-border-light text-text-secondary hover:bg-bg-tertiary"
                  }`}
                >
                  <UserCheck
                    className="h-4 w-4 shrink-0 text-primary-700"
                    aria-hidden="true"
                  />
                  <span className="flex-1 font-medium">{d.name}</span>
                  {d.licensePlate && (
                    <span className="text-xs text-text-muted">
                      {d.licensePlate}
                    </span>
                  )}
                </button>
              ))}
            </>
          )}
          {offShift.length > 0 && (
            <>
              <p className="mb-1 mt-2 text-xs font-semibold uppercase tracking-wider text-text-muted">
                Fora de turno
              </p>
              {offShift.map((d) => (
                <button
                  key={d.id}
                  onClick={() => setSelected(d.id)}
                  className={`flex min-h-11 items-center gap-3 rounded-lg border px-4 py-3 text-left text-sm transition-colors ${
                    selected === d.id
                      ? "border-primary-700 bg-primary-700/10 text-text-primary"
                      : "border-border-light text-text-secondary hover:bg-bg-tertiary"
                  }`}
                >
                  <User className="h-4 w-4 shrink-0" aria-hidden="true" />
                  <span className="flex-1 font-medium">{d.name}</span>
                  {d.licensePlate && (
                    <span className="text-xs text-text-muted">
                      {d.licensePlate}
                    </span>
                  )}
                </button>
              ))}
            </>
          )}
          {drivers.filter((d) => d.active).length === 0 && (
            <p className="py-6 text-center text-sm text-text-muted">
              Nenhum motoboy cadastrado.{" "}
              <a href="/admin/entregadores" className="text-primary-700 underline">
                Cadastrar entregadores
              </a>
            </p>
          )}
        </div>

        <div className="mt-4">
          <button
            onClick={() => {
              if (selected) onAssign(selected);
            }}
            disabled={!selected || assigning}
            className="btn-primary min-h-11 w-full disabled:opacity-60"
          >
            {assigning ? "Atribuindo..." : "Confirmar atribuição"}
          </button>
        </div>
      </div>
    </div>
  );
}

// ── Delivery Order Card ───────────────────────────────────────────────────────

function DeliveryOrderCard({
  order,
  drivers,
  selected,
  onSelect,
}: {
  order: DeliveryOrder;
  drivers: Driver[];
  selected: boolean;
  onSelect: () => void;
}) {
  const late = isLate(order);
  const driver = drivers.find((d) => d.id === order.driverId);
  const label =
    STATUS_LABEL[order.deliveryStatus ?? "PENDING"] ??
    order.deliveryStatus ??
    "—";

  return (
    <button
      onClick={onSelect}
      className={`w-full rounded-xl border p-4 text-left transition-colors ${
        selected
          ? "border-primary-700 bg-primary-700/5"
          : "border-border-light bg-bg-primary hover:bg-bg-secondary"
      }`}
      aria-pressed={selected}
    >
      <div className="flex items-start justify-between gap-2">
        <div className="flex min-w-0 flex-col gap-0.5">
          <span className="font-semibold text-text-primary">
            #{order.orderNumber}
          </span>
          {order.deliveryRecipientName && (
            <span className="truncate text-sm text-text-secondary">
              {order.deliveryRecipientName}
            </span>
          )}
          {(order.deliveryNeighborhood || order.deliveryCity) && (
            <span className="flex items-center gap-1 truncate text-xs text-text-muted">
              <MapPin className="h-3 w-3 shrink-0" aria-hidden="true" />
              {[order.deliveryNeighborhood, order.deliveryCity]
                .filter(Boolean)
                .join(", ")}
            </span>
          )}
        </div>
        <div className="flex shrink-0 flex-col items-end gap-1">
          <span className="text-sm font-semibold text-text-primary">
            {fmtMoney(order.totalCents)}
          </span>
          {late && (
            <span className="flex items-center gap-1 text-xs font-medium text-red-600">
              <AlertTriangle className="h-3 w-3" aria-hidden="true" />
              Atrasado
            </span>
          )}
        </div>
      </div>
      <div className="mt-2 flex flex-wrap items-center gap-2">
        <span className="flex items-center gap-1 rounded-md bg-bg-secondary px-2 py-0.5 text-xs text-text-secondary">
          <Truck className="h-3 w-3 shrink-0" aria-hidden="true" />
          {label}
        </span>
        {driver && (
          <span className="truncate text-xs text-text-muted">{driver.name}</span>
        )}
        {order.createdAt && (
          <span className="ml-auto flex items-center gap-1 text-xs text-text-muted">
            <Clock className="h-3 w-3 shrink-0" aria-hidden="true" />
            {fmtElapsed(order.createdAt)}
          </span>
        )}
      </div>
    </button>
  );
}

// ── Detail Panel ──────────────────────────────────────────────────────────────

function DetailPanel({
  order,
  drivers,
  onAssign,
  onAdvanceStatus,
  onClose,
  statusUpdating,
}: {
  order: DeliveryOrder | null;
  drivers: Driver[];
  onAssign: (orderId: string) => void;
  onAdvanceStatus: (orderId: string, status: DeliveryStatus) => void;
  onClose: () => void;
  statusUpdating: boolean;
}) {
  if (!order) {
    return (
      <div className="flex h-full min-h-48 flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-border-light p-8 text-center">
        <Package className="h-10 w-10 text-text-muted" aria-hidden="true" />
        <p className="text-sm text-text-muted">
          Selecione uma entrega para ver os detalhes
        </p>
      </div>
    );
  }

  const driver = drivers.find((d) => d.id === order.driverId);
  const late = isLate(order);
  const next = nextDeliveryStatus(order.deliveryStatus);

  return (
    <div className="flex flex-col gap-4 rounded-xl border border-border-light bg-bg-primary p-5">
      <div className="flex items-start justify-between gap-2">
        <h3 className="font-semibold text-text-primary">
          Pedido #{order.orderNumber}
        </h3>
        <button
          onClick={onClose}
          aria-label="Fechar detalhe"
          className="flex h-11 w-11 items-center justify-center rounded-lg text-text-muted hover:bg-bg-tertiary"
        >
          <X className="h-5 w-5" aria-hidden="true" />
        </button>
      </div>

      {/* Status + late */}
      <div className="flex flex-wrap items-center gap-2">
        <span className="flex items-center gap-1 rounded-md bg-bg-secondary px-2 py-1 text-sm text-text-secondary">
          <Truck className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
          {STATUS_LABEL[order.deliveryStatus ?? "PENDING"] ?? "—"}
        </span>
        {late && (
          <span className="flex items-center gap-1 text-sm font-medium text-red-600">
            <AlertTriangle className="h-4 w-4" aria-hidden="true" />
            Atrasado
          </span>
        )}
      </div>

      {/* Delivery address */}
      {(order.deliveryStreet || order.deliveryRecipientName) && (
        <div className="flex flex-col gap-1.5 rounded-lg bg-bg-secondary p-3 text-sm">
          {order.deliveryRecipientName && (
            <span className="font-medium text-text-primary">
              {order.deliveryRecipientName}
            </span>
          )}
          {order.deliveryPhone && (
            <span className="flex items-center gap-1 text-text-muted">
              <Phone className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              {order.deliveryPhone}
            </span>
          )}
          {order.deliveryStreet && (
            <span className="text-text-secondary">
              {order.deliveryStreet}
              {order.deliveryNumber ? `, ${order.deliveryNumber}` : ""}
              {order.deliveryComplement
                ? ` (${order.deliveryComplement})`
                : ""}
            </span>
          )}
          {(order.deliveryNeighborhood || order.deliveryCity) && (
            <span className="text-text-muted">
              {[order.deliveryNeighborhood, order.deliveryCity]
                .filter(Boolean)
                .join(" — ")}
            </span>
          )}
          {order.deliveryReference && (
            <span className="text-xs italic text-text-muted">
              Ref: {order.deliveryReference}
            </span>
          )}
        </div>
      )}

      {/* Driver */}
      <div className="flex items-center justify-between gap-2 text-sm">
        <span className="text-text-muted">Motoboy:</span>
        {driver ? (
          <span className="font-medium text-text-primary">{driver.name}</span>
        ) : (
          <span className="text-amber-600">Não atribuído</span>
        )}
      </div>

      {/* Financial */}
      <div className="flex items-center justify-between gap-2 text-sm">
        <span className="text-text-muted">Total:</span>
        <span className="font-semibold text-text-primary">
          {fmtMoney(order.totalCents)}
        </span>
      </div>
      {order.deliveryFeeCents !== undefined && (
        <div className="flex items-center justify-between gap-2 text-sm">
          <span className="text-text-muted">Taxa de entrega:</span>
          <span className="text-text-secondary">
            {fmtMoney(order.deliveryFeeCents)}
          </span>
        </div>
      )}

      {/* Actions */}
      <div className="flex flex-col gap-2 border-t border-border-light pt-3">
        {!order.driverId && (
          <button
            onClick={() => onAssign(order.orderId)}
            className="btn-primary min-h-11"
          >
            Atribuir motoboy
          </button>
        )}
        {next && (
          <button
            onClick={() => onAdvanceStatus(order.orderId, next)}
            disabled={statusUpdating}
            className="btn-outline min-h-11 disabled:opacity-60"
          >
            {statusUpdating ? "Atualizando..." : nextStatusLabel(next)}
          </button>
        )}
      </div>
    </div>
  );
}

// ── Empty State ───────────────────────────────────────────────────────────────

function EmptyDeliveries() {
  return (
    <div className="flex flex-col items-center gap-3 rounded-xl border border-dashed border-border-light p-8 text-center">
      <CheckCircle2 className="h-10 w-10 text-text-muted" aria-hidden="true" />
      <p className="text-sm font-medium text-text-muted">Nenhuma entrega ativa</p>
      <a href="/pedidos" className="text-sm text-primary-700 underline">
        Ir para Pedidos
      </a>
    </div>
  );
}

function EmptyDrivers() {
  return (
    <div className="rounded-xl border border-dashed border-border-light p-6 text-center text-sm text-text-muted">
      Nenhum motoboy cadastrado.{" "}
      <a href="/admin/entregadores" className="text-primary-700 underline">
        Cadastrar entregadores
      </a>
    </div>
  );
}

// ── Tabs ──────────────────────────────────────────────────────────────────────

function TabBar({
  active,
  onChange,
  ordersCount,
  onShiftCount,
}: {
  active: "entregas" | "motoboys";
  onChange: (tab: "entregas" | "motoboys") => void;
  ordersCount: number;
  onShiftCount: number;
}) {
  return (
    <div
      className="flex gap-1 rounded-lg border border-border-light bg-bg-secondary p-1"
      role="tablist"
    >
      {(["entregas", "motoboys"] as const).map((tab) => (
        <button
          key={tab}
          role="tab"
          aria-selected={active === tab}
          onClick={() => onChange(tab)}
          className={`min-h-11 flex-1 rounded-md py-2 text-sm font-medium transition-colors ${
            active === tab
              ? "bg-bg-primary text-text-primary shadow-sm"
              : "text-text-muted hover:text-text-secondary"
          }`}
        >
          {tab === "entregas"
            ? `Entregas (${ordersCount})`
            : `Motoboys (${onShiftCount})`}
        </button>
      ))}
    </div>
  );
}

// ── Main Page ─────────────────────────────────────────────────────────────────

export default function DeliveryPage() {
  const router = useRouter();

  const [orders, setOrders] = useState<DeliveryOrder[]>([]);
  const [drivers, setDrivers] = useState<Driver[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedOrderId, setSelectedOrderId] = useState<string | null>(null);
  const [assigningOrderId, setAssigningOrderId] = useState<string | null>(null);
  const [assigningSubmit, setAssigningSubmit] = useState(false);
  const [togglingShiftId, setTogglingShiftId] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<"entregas" | "motoboys">(
    "entregas",
  );
  const [statusUpdating, setStatusUpdating] = useState(false);

  const selectedOrder =
    orders.find((o) => o.orderId === selectedOrderId) ?? null;

  const load = useCallback(async () => {
    if (!getToken()) {
      router.push("/login");
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const [fetchedOrders, fetchedDrivers] = await Promise.all([
        api.get<DeliveryOrder[]>("/delivery/orders/active"),
        api.get<Driver[]>("/delivery/drivers"),
      ]);
      setOrders(fetchedOrders);
      setDrivers(fetchedDrivers);
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        router.push("/login");
        return;
      }
      setError(
        err instanceof Error ? err.message : "Erro ao carregar dados de entrega",
      );
    } finally {
      setLoading(false);
    }
  }, [router]);

  useEffect(() => {
    void load();
  }, [load]);

  const handleAssign = useCallback(
    async (driverId: string) => {
      if (!assigningOrderId) return;
      setAssigningSubmit(true);
      try {
        const updated = await api.post<DeliveryOrder>(
          `/delivery/orders/${assigningOrderId}/assign`,
          { driverId },
        );
        setOrders((prev) =>
          prev.map((o) =>
            o.orderId === updated.orderId ? { ...o, ...updated } : o,
          ),
        );
        setAssigningOrderId(null);
      } catch (err) {
        alert(
          err instanceof Error ? err.message : "Erro ao atribuir motoboy",
        );
      } finally {
        setAssigningSubmit(false);
      }
    },
    [assigningOrderId],
  );

  const handleAdvanceStatus = useCallback(
    async (orderId: string, nextStatus: DeliveryStatus) => {
      setStatusUpdating(true);
      try {
        const updated = await api.put<DeliveryOrder>(
          `/delivery/orders/${orderId}/status`,
          { deliveryStatus: nextStatus },
        );
        setOrders((prev) =>
          prev.map((o) =>
            o.orderId === updated.orderId ? { ...o, ...updated } : o,
          ),
        );
      } catch (err) {
        alert(err instanceof Error ? err.message : "Erro ao avançar status");
      } finally {
        setStatusUpdating(false);
      }
    },
    [],
  );

  const handleToggleShift = useCallback(
    async (driverId: string, activeShift: boolean) => {
      setTogglingShiftId(driverId);
      try {
        const updated = await api.post<Driver>(
          `/delivery/drivers/${driverId}/shift`,
          { activeShift },
        );
        setDrivers((prev) =>
          prev.map((d) => (d.id === updated.id ? updated : d)),
        );
      } catch (err) {
        alert(err instanceof Error ? err.message : "Erro ao alterar turno");
      } finally {
        setTogglingShiftId(null);
      }
    },
    [],
  );

  const handleSelectOrder = useCallback((orderId: string) => {
    setSelectedOrderId((prev) => (prev === orderId ? null : orderId));
  }, []);

  // KPIs
  const kpiActive = orders.length;
  const kpiAwaiting = orders.filter(
    (o) => !o.driverId || o.deliveryStatus === "PENDING",
  ).length;
  const kpiEnRoute = orders.filter(
    (o) => o.deliveryStatus === "OUT_FOR_DELIVERY",
  ).length;
  const kpiLate = orders.filter((o) => isLate(o)).length;
  const kpiOnShift = drivers.filter((d) => d.activeShift).length;

  const orderCards = loading ? (
    <LoadingSkeleton />
  ) : orders.length === 0 ? (
    <EmptyDeliveries />
  ) : (
    orders.map((o) => (
      <DeliveryOrderCard
        key={o.orderId}
        order={o}
        drivers={drivers}
        selected={selectedOrderId === o.orderId}
        onSelect={() => handleSelectOrder(o.orderId)}
      />
    ))
  );

  const driverCards = loading ? (
    <LoadingSkeleton />
  ) : drivers.length === 0 ? (
    <EmptyDrivers />
  ) : (
    drivers.map((d) => (
      <DriverCard
        key={d.id}
        driver={d}
        onToggleShift={handleToggleShift}
        toggling={togglingShiftId === d.id}
      />
    ))
  );

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {/* ── Page header ──────────────────────────────────────────────────── */}
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border-light bg-bg-primary px-4 py-4 sm:px-6">
        <div>
          <h1 className="text-lg font-semibold text-text-primary">
            Central de Entregas
          </h1>
          <p className="text-sm text-text-muted">
            Despacho operacional em tempo real
          </p>
        </div>
        <button
          onClick={() => void load()}
          disabled={loading}
          className="flex min-h-11 items-center gap-2 rounded-lg border border-border-light px-4 py-2 text-sm text-text-secondary hover:bg-bg-tertiary disabled:opacity-50"
        >
          <RefreshCcw
            className={`h-4 w-4 ${loading ? "animate-spin" : ""}`}
            aria-hidden="true"
          />
          Atualizar
        </button>
      </div>

      {/* ── Error banner ─────────────────────────────────────────────────── */}
      {error && (
        <div
          role="alert"
          className="mx-4 mt-4 flex items-center gap-3 rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-700 dark:border-red-800 dark:bg-red-950/30 dark:text-red-400"
        >
          <AlertTriangle className="h-5 w-5 shrink-0" aria-hidden="true" />
          <span className="flex-1">{error}</span>
          <button
            onClick={() => void load()}
            className="min-h-11 rounded-lg bg-red-600 px-3 py-2 text-xs font-medium text-white hover:bg-red-700"
          >
            Tentar novamente
          </button>
        </div>
      )}

      {/* ── KPI strip ────────────────────────────────────────────────────── */}
      <div className="grid grid-cols-2 gap-3 p-4 sm:grid-cols-3 lg:grid-cols-5 sm:px-6">
        <KpiCard label="Entregas ativas" value={kpiActive} />
        <KpiCard
          label="Aguardando motoboy"
          value={kpiAwaiting}
          accent={kpiAwaiting > 0 ? "text-amber-600" : undefined}
        />
        <KpiCard
          label="Em rota"
          value={kpiEnRoute}
          accent={kpiEnRoute > 0 ? "text-blue-600" : undefined}
        />
        <KpiCard
          label="Atrasadas"
          value={kpiLate}
          accent={kpiLate > 0 ? "text-red-600" : undefined}
        />
        <KpiCard
          label="Motoboys em turno"
          value={kpiOnShift}
          accent="text-primary-700"
        />
      </div>

      {/* ── Main content ─────────────────────────────────────────────────── */}
      <div className="flex min-h-0 flex-1 flex-col gap-4 px-4 pb-6 sm:px-6">
        {/* Desktop ≥1280px: 3-col grid */}
        <div className="hidden xl:grid xl:grid-cols-[1fr_380px_360px] xl:gap-4">
          {/* Col 1: map + drivers */}
          <div className="flex flex-col gap-4">
            <OperationalMap orders={orders} drivers={drivers} />
            <h2 className="text-sm font-semibold text-text-primary">
              Motoboys
            </h2>
            <div className="flex flex-col gap-2">{driverCards}</div>
          </div>
          {/* Col 2: order queue */}
          <div className="flex flex-col gap-3">
            <h2 className="text-sm font-semibold text-text-primary">
              Fila de entregas{" "}
              {!loading && (
                <span className="font-normal text-text-muted">
                  ({orders.length})
                </span>
              )}
            </h2>
            <div className="flex flex-col gap-2">{orderCards}</div>
          </div>
          {/* Col 3: detail */}
          <div>
            <DetailPanel
              order={selectedOrder}
              drivers={drivers}
              onAssign={(id) => setAssigningOrderId(id)}
              onAdvanceStatus={handleAdvanceStatus}
              onClose={() => setSelectedOrderId(null)}
              statusUpdating={statusUpdating}
            />
          </div>
        </div>

        {/* Tablet 768-1279px: map + tabs */}
        <div className="hidden flex-col gap-4 md:flex xl:hidden">
          <OperationalMap orders={orders} drivers={drivers} />
          <TabBar
            active={activeTab}
            onChange={setActiveTab}
            ordersCount={orders.length}
            onShiftCount={kpiOnShift}
          />
          <div
            role="tabpanel"
            aria-label={activeTab === "entregas" ? "Entregas" : "Motoboys"}
          >
            {activeTab === "entregas" && (
              <div className="flex flex-col gap-3">
                <div className="flex flex-col gap-2">{orderCards}</div>
                {selectedOrder && (
                  <DetailPanel
                    order={selectedOrder}
                    drivers={drivers}
                    onAssign={(id) => setAssigningOrderId(id)}
                    onAdvanceStatus={handleAdvanceStatus}
                    onClose={() => setSelectedOrderId(null)}
                    statusUpdating={statusUpdating}
                  />
                )}
              </div>
            )}
            {activeTab === "motoboys" && (
              <div className="flex flex-col gap-2">{driverCards}</div>
            )}
          </div>
        </div>

        {/* Mobile <768px: map + tabs + cards */}
        <div className="flex flex-col gap-4 md:hidden">
          <OperationalMap orders={orders} drivers={drivers} />
          <TabBar
            active={activeTab}
            onChange={setActiveTab}
            ordersCount={orders.length}
            onShiftCount={kpiOnShift}
          />
          <div
            role="tabpanel"
            aria-label={activeTab === "entregas" ? "Entregas" : "Motoboys"}
          >
            {activeTab === "entregas" && (
              <div className="flex flex-col gap-3">
                <div className="flex flex-col gap-2">{orderCards}</div>
              </div>
            )}
            {activeTab === "motoboys" && (
              <div className="flex flex-col gap-2">{driverCards}</div>
            )}
          </div>
          {/* Mobile detail: fixed bottom sheet */}
          {selectedOrder && activeTab === "entregas" && (
            <div className="fixed inset-x-3 bottom-4 z-40 rounded-2xl border border-border-light bg-bg-primary shadow-xl">
              <div className="max-h-[60vh] overflow-y-auto p-1">
                <DetailPanel
                  order={selectedOrder}
                  drivers={drivers}
                  onAssign={(id) => setAssigningOrderId(id)}
                  onAdvanceStatus={handleAdvanceStatus}
                  onClose={() => setSelectedOrderId(null)}
                  statusUpdating={statusUpdating}
                />
              </div>
            </div>
          )}
        </div>
      </div>

      {/* ── Assignment Modal ─────────────────────────────────────────────── */}
      {assigningOrderId && (
        <AssignModal
          order={orders.find((o) => o.orderId === assigningOrderId)!}
          drivers={drivers}
          onClose={() => setAssigningOrderId(null)}
          onAssign={handleAssign}
          assigning={assigningSubmit}
        />
      )}
    </div>
  );
}
