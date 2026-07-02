"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useParams, useSearchParams } from "next/navigation";

const API_BASE =
  process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080/api/v1";
const DEFAULT_TENANT = process.env.NEXT_PUBLIC_TENANT_SLUG ?? "demo";
const POLL_INTERVAL_MS = 15_000;

// ---------------------------------------------------------------------------
// Tipos
// ---------------------------------------------------------------------------

type TrackingStatus =
  | "PREPARING"
  | "ASSIGNED"
  | "OUT_FOR_DELIVERY"
  | "DELIVERED"
  | "FAILED";

interface TrackingData {
  status: TrackingStatus;
  restaurantName: string | null;
  neighborhoodLabel: string | null;
  driverName: string | null;
  driverLicensePlate: string | null;
  updatedAt: string | null;
}

type PageState =
  | { kind: "loading" }
  | { kind: "error" }
  | { kind: "not_found" }
  | { kind: "success"; data: TrackingData };

// ---------------------------------------------------------------------------
// Constantes de UI
// ---------------------------------------------------------------------------

interface TimelineStep {
  label: string;
  icon: string;
  ariaLabel: string;
  activeStatuses: TrackingStatus[];
}

const STEPS: TimelineStep[] = [
  {
    label: "Pedido confirmado",
    icon: "✓",
    ariaLabel: "Pedido confirmado",
    activeStatuses: [
      "PREPARING",
      "ASSIGNED",
      "OUT_FOR_DELIVERY",
      "DELIVERED",
      "FAILED",
    ],
  },
  {
    label: "Entregador designado",
    icon: "🛵",
    ariaLabel: "Entregador designado",
    activeStatuses: ["ASSIGNED", "OUT_FOR_DELIVERY", "DELIVERED"],
  },
  {
    label: "A caminho",
    icon: "📦",
    ariaLabel: "Pedido a caminho",
    activeStatuses: ["OUT_FOR_DELIVERY", "DELIVERED"],
  },
  {
    label: "Entregue!",
    icon: "🎉",
    ariaLabel: "Pedido entregue",
    activeStatuses: ["DELIVERED"],
  },
];

const STATUS_MESSAGES: Record<TrackingStatus, string> = {
  PREPARING: "Preparando seu pedido",
  ASSIGNED: "Entregador a caminho da loja",
  OUT_FOR_DELIVERY: "A caminho da sua casa",
  DELIVERED: "Pedido entregue!",
  FAILED: "Problema na entrega — entre em contato com o restaurante",
};

const STATUS_ICONS: Record<TrackingStatus, string> = {
  PREPARING: "🍳",
  ASSIGNED: "🛵",
  OUT_FOR_DELIVERY: "📦",
  DELIVERED: "🎉",
  FAILED: "⚠️",
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString("pt-BR", {
    hour: "2-digit",
    minute: "2-digit",
  });
}

function currentStepIndex(status: TrackingStatus): number {
  const order: TrackingStatus[] = [
    "PREPARING",
    "ASSIGNED",
    "OUT_FOR_DELIVERY",
    "DELIVERED",
  ];
  const idx = order.indexOf(status);
  return idx === -1 ? 0 : idx;
}

// ---------------------------------------------------------------------------
// Subcomponentes
// ---------------------------------------------------------------------------

function SkeletonLine({ width = "w-full" }: { width?: string }) {
  return (
    <div
      className={`h-4 rounded bg-gray-200 animate-pulse ${width}`}
      aria-hidden="true"
    />
  );
}

function LoadingSkeleton() {
  return (
    <div
      className="min-h-screen bg-bg-primary flex flex-col items-center justify-start p-4"
      role="status"
      aria-label="Carregando rastreio do pedido"
    >
      <div className="w-full max-w-md mt-8 space-y-6">
        <div className="text-center space-y-2">
          <div
            className="w-16 h-16 rounded-full bg-gray-200 animate-pulse mx-auto"
            aria-hidden="true"
          />
          <SkeletonLine width="w-48 mx-auto" />
          <SkeletonLine width="w-32 mx-auto" />
        </div>
        <div className="flex justify-between items-center px-4">
          {[0, 1, 2, 3].map((i) => (
            <div key={i} className="flex flex-col items-center gap-1">
              <div
                className="w-10 h-10 rounded-full bg-gray-200 animate-pulse"
                aria-hidden="true"
              />
              <SkeletonLine width="w-16" />
            </div>
          ))}
        </div>
        <div className="space-y-3 px-4">
          <SkeletonLine width="w-3/4" />
          <SkeletonLine width="w-1/2" />
          <SkeletonLine width="w-2/3" />
        </div>
      </div>
    </div>
  );
}

function Timeline({ status }: { status: TrackingStatus }) {
  const activeIdx = currentStepIndex(status);
  const connectorPercents = ["0%", "33%", "66%", "100%"];

  return (
    <div
      className="relative flex items-start justify-between px-2 py-4"
      role="list"
      aria-label="Progresso do pedido"
    >
      <div
        className="absolute top-9 left-8 right-8 h-0.5 bg-gray-200"
        aria-hidden="true"
      >
        <div
          className="h-full bg-primary-700 transition-all duration-500"
          style={{ width: connectorPercents[activeIdx] ?? "0%" }}
        />
      </div>

      {STEPS.map((step, idx) => {
        const isDone = idx < activeIdx;
        const isActive = idx === activeIdx;

        return (
          <div
            key={step.label}
            role="listitem"
            className="flex flex-col items-center gap-2 z-10 flex-1"
            aria-label={`${step.ariaLabel}: ${
              isDone
                ? "concluido"
                : isActive
                ? "em andamento"
                : "pendente"
            }`}
          >
            <div
              className={[
                "flex items-center justify-center rounded-full border-2 transition-all duration-300",
                isDone
                  ? "w-10 h-10 bg-primary-700 border-primary-700 text-white text-base"
                  : isActive
                  ? "w-12 h-12 bg-primary-700 border-primary-700 text-white text-xl shadow-lg"
                  : "w-10 h-10 bg-white border-gray-300 text-gray-400 text-base",
              ].join(" ")}
              aria-hidden="true"
            >
              {isDone ? "✓" : step.icon}
            </div>
            <span
              className={[
                "text-center leading-tight transition-all duration-300",
                isDone
                  ? "text-xs text-text-secondary font-medium"
                  : isActive
                  ? "text-xs text-primary-700 font-bold"
                  : "text-xs text-text-muted",
              ].join(" ")}
              style={{ maxWidth: "5rem" }}
            >
              {step.label}
            </span>
          </div>
        );
      })}
    </div>
  );
}

function TrackingCard({ data }: { data: TrackingData }) {
  const icon = STATUS_ICONS[data.status];
  const message = STATUS_MESSAGES[data.status];
  const hasDriver = data.driverName != null;

  return (
    <div className="w-full max-w-md mx-auto min-h-screen bg-bg-primary flex flex-col">
      <div className="bg-primary-700 text-white px-6 py-5">
        <div className="flex items-center gap-3">
          <span className="text-3xl" aria-hidden="true">
            🍔
          </span>
          <div>
            <h1 className="text-lg font-bold leading-tight">
              {data.restaurantName ?? "Restaurante"}
            </h1>
            <p className="text-sm opacity-90">Acompanhe seu pedido</p>
          </div>
        </div>
      </div>

      <div className="flex-1 px-4 py-6 space-y-6">
        {data.status !== "FAILED" && (
          <section aria-labelledby="timeline-heading">
            <h2 id="timeline-heading" className="sr-only">
              Etapas do pedido
            </h2>
            <Timeline status={data.status} />
          </section>
        )}

        <section
          className="bg-bg-secondary rounded-2xl p-5 text-center space-y-3"
          aria-live="polite"
          aria-atomic="true"
        >
          <span
            className="text-5xl block"
            role="img"
            aria-label={message}
          >
            {icon}
          </span>
          <p className="text-text-primary text-lg font-semibold">{message}</p>

          {hasDriver && (
            <div className="pt-2 space-y-1">
              <p className="text-text-secondary text-sm">
                <span className="font-medium">Entregador:</span>{" "}
                {data.driverName}
                {data.driverLicensePlate && (
                  <span className="ml-1 text-text-muted">
                    &bull; {data.driverLicensePlate}
                  </span>
                )}
              </p>
            </div>
          )}

          {data.neighborhoodLabel && (
            <p className="text-text-secondary text-sm">
              <span className="font-medium">Bairro:</span>{" "}
              {data.neighborhoodLabel}
            </p>
          )}
        </section>
      </div>

      {data.updatedAt && (
        <footer className="px-4 py-4 border-t border-gray-100 text-center">
          <p className="text-text-muted text-xs">
            Ultima atualizacao: {formatTime(data.updatedAt)}
          </p>
        </footer>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Pagina principal
// ---------------------------------------------------------------------------

export default function AcompanharPage() {
  const params = useParams<{ uuid: string }>();
  const searchParams = useSearchParams();

  const uuid = params?.uuid ?? "";
  const tenantSlug = searchParams?.get("tenant") ?? DEFAULT_TENANT;

  const [state, setState] = useState<PageState>({ kind: "loading" });
  const pollRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const isMountedRef = useRef(true);
  // Ultimo status recebido — permite decidir se o polling continua sem
  // colocar efeitos colaterais dentro de um updater do setState.
  const lastStatusRef = useRef<TrackingStatus | null>(null);

  const fetchTracking = useCallback(
    async (signal?: AbortSignal) => {
      if (!uuid) return;
      const url = `${API_BASE}/public/${tenantSlug}/rastreio/${uuid}`;
      try {
        const res = await fetch(url, { signal });
        if (!isMountedRef.current) return;
        if (res.status === 404) {
          setState({ kind: "not_found" });
          return;
        }
        if (!res.ok) {
          setState((prev) =>
            prev.kind === "success" ? prev : { kind: "error" }
          );
          return;
        }
        const data: TrackingData = await res.json();
        if (!isMountedRef.current) return;
        lastStatusRef.current = data.status;
        setState({ kind: "success", data });
      } catch (err: unknown) {
        if (err instanceof DOMException && err.name === "AbortError") return;
        if (!isMountedRef.current) return;
        setState((prev) =>
          prev.kind === "success" ? prev : { kind: "error" }
        );
      }
    },
    [uuid, tenantSlug]
  );

  useEffect(() => {
    isMountedRef.current = true;
    const controller = new AbortController();

    async function poll() {
      await fetchTracking(controller.signal);
      if (!isMountedRef.current) return;
      const status = lastStatusRef.current;
      // Estado terminal: para de consultar a API.
      if (status === "DELIVERED" || status === "FAILED") return;
      pollRef.current = setTimeout(poll, POLL_INTERVAL_MS);
    }
    poll();

    return () => {
      isMountedRef.current = false;
      controller.abort();
      if (pollRef.current) clearTimeout(pollRef.current);
    };
  }, [fetchTracking]);

  if (state.kind === "loading") {
    return <LoadingSkeleton />;
  }

  if (state.kind === "not_found") {
    return (
      <div
        className="min-h-screen bg-bg-primary flex flex-col items-center justify-center px-6 text-center"
        role="main"
      >
        <span className="text-5xl mb-4" role="img" aria-label="Nao encontrado">
          🔍
        </span>
        <h1 className="text-text-primary text-xl font-bold mb-2">
          Pedido nao encontrado
        </h1>
        <p className="text-text-secondary text-sm">
          O link pode ter expirado ou o pedido nao existe.
        </p>
      </div>
    );
  }

  if (state.kind === "error") {
    return (
      <div
        className="min-h-screen bg-bg-primary flex flex-col items-center justify-center px-6 text-center"
        role="main"
      >
        <span className="text-5xl mb-4" role="img" aria-label="Erro">
          ⚠️
        </span>
        <h1 className="text-text-primary text-xl font-bold mb-2">
          Nao foi possivel carregar o rastreio
        </h1>
        <p className="text-text-secondary text-sm mb-6">
          Verifique sua conexao e tente novamente.
        </p>
        <button
          type="button"
          onClick={() => {
            setState({ kind: "loading" });
            fetchTracking();
          }}
          className="btn-primary px-6 py-3 rounded-xl text-sm font-semibold"
        >
          Tentar novamente
        </button>
      </div>
    );
  }

  return (
    <main role="main" aria-label="Rastreio de entrega">
      <TrackingCard data={state.data} />
    </main>
  );
}
