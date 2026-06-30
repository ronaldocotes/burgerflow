"use client";

import { useRef, useState } from "react";
import { useRouter } from "next/navigation";
import QRCode from "react-qr-code";
import { QrCode } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import { getToken } from "@/lib/auth";
import { useModalA11y } from "@/lib/use-modal-a11y";
import { useTablesFeed } from "@/lib/use-tables-feed";
import { FeedStatus, TableDto } from "@/types/tables";

// Pagina /mesas - board visual de mesas com STOMP + fallback polling.

// ── Banner de conexao ─────────────────────────────────────────────────────────

function ConnectionBanner({ status }: { status: FeedStatus }) {
  if (status === "live") return null;
  const msg =
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
      <span className="inline-block h-2 w-2 rounded-full bg-warning-dark" aria-hidden="true" />
      {msg}
    </div>
  );
}

// ── Modal de QR Code ──────────────────────────────────────────────────────────

interface QrModalProps {
  table: TableDto;
  onClose: () => void;
}

function QrModal({ table, onClose }: QrModalProps) {
  const ref = useRef<HTMLDivElement>(null);
  useModalA11y(ref as React.RefObject<HTMLElement>, onClose);

  const appUrl = process.env.NEXT_PUBLIC_APP_URL ?? "http://localhost:3000";
  const qrUrl = `${appUrl}/cardapio?table=${encodeURIComponent(table.label)}`;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      aria-modal="true"
    >
      <div
        ref={ref}
        role="dialog"
        aria-labelledby="qr-modal-title"
        className="w-full max-w-xs rounded-2xl bg-bg-primary p-6 shadow-dropdown"
      >
        <h2
          id="qr-modal-title"
          className="mb-4 text-center text-lg font-semibold text-text-primary"
        >
          Mesa {table.label}
        </h2>

        <div className="flex justify-center rounded-xl bg-white p-4">
          <QRCode value={qrUrl} size={256} />
        </div>

        <p className="mt-4 text-center text-sm text-text-secondary">
          Aponte a câmera para acessar o cardápio
        </p>

        <div className="mt-6 flex gap-3">
          <button
            className="btn-outline flex-1"
            onClick={() => window.print()}
          >
            Imprimir
          </button>
          <button className="btn-primary flex-1" onClick={onClose}>
            Fechar
          </button>
        </div>
      </div>
    </div>
  );
}

// ── Modal de confirmação de fechamento ────────────────────────────────────────

interface CloseTableModalProps {
  table: TableDto;
  onClose: () => void;
  onConfirm: () => Promise<void>;
}

function CloseTableModal({ table, onClose, onConfirm }: CloseTableModalProps) {
  const ref = useRef<HTMLDivElement>(null);
  useModalA11y(ref as React.RefObject<HTMLElement>, onClose);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function handleConfirm() {
    setLoading(true);
    try {
      await onConfirm();
      onClose();
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : "Erro ao fechar mesa.");
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
        aria-labelledby="close-table-title"
        className="w-full max-w-sm rounded-2xl bg-bg-primary p-6 shadow-dropdown"
      >
        <h2 id="close-table-title" className="mb-1 text-lg font-semibold text-text-primary">
          Fechar {table.label}?
        </h2>
        <p className="mb-6 text-sm text-text-secondary">
          Esta ação não pode ser desfeita.
        </p>
        {err && <p className="mb-3 text-sm text-error">{err}</p>}
        <div className="flex gap-3">
          <button className="btn-outline flex-1" onClick={onClose} disabled={loading}>
            Cancelar
          </button>
          <button
            className="flex-1 rounded-xl bg-error px-4 py-3 text-sm font-semibold text-white transition-colors hover:bg-error-dark disabled:opacity-50"
            onClick={handleConfirm}
            disabled={loading}
          >
            {loading ? "Fechando…" : "Fechar mesa"}
          </button>
        </div>
      </div>
    </div>
  );
}

// ── Skeleton de carregamento ──────────────────────────────────────────────────

function SkeletonGrid() {
  return (
    <div
      className="grid grid-cols-2 gap-4 p-4 sm:grid-cols-3 lg:grid-cols-4"
      aria-label="Carregando mesas…"
      aria-busy="true"
    >
      {Array.from({ length: 8 }).map((_, i) => (
        <div
          key={i}
          className="animate-pulse rounded-2xl bg-bg-secondary p-5"
          style={{ minHeight: 140 }}
          aria-hidden="true"
        >
          <div className="mb-3 h-6 w-3/4 rounded bg-bg-tertiary" />
          <div className="mb-4 h-4 w-1/2 rounded bg-bg-tertiary" />
          <div className="h-8 w-full rounded-lg bg-bg-tertiary" />
        </div>
      ))}
    </div>
  );
}

// ── Logica de estado e badge ──────────────────────────────────────────────────

type TableState = "free" | "open" | "billing";

function tableState(table: TableDto): TableState {
  if (!table.session) return "free";
  return table.session.status === "BILLING" ? "billing" : "open";
}

const STATE_BADGE: Record<TableState, { label: string; badgeClass: string }> = {
  free: {
    label: "Livre",
    badgeClass: "bg-bg-secondary text-text-secondary border border-border-light",
  },
  open: {
    label: "Aberta",
    badgeClass: "bg-success-light text-success-dark",
  },
  billing: {
    label: "Conta pedida",
    badgeClass: "bg-warning-light text-warning-dark font-semibold",
  },
};

// ── Card de mesa (min 48dp touch via py-3.5) ──────────────────────────────────

interface TableCardProps {
  table: TableDto;
  onAction: (table: TableDto, action: "open" | "bill" | "close") => Promise<void>;
  onRequestClose: (table: TableDto) => void;
  onQrClick: (table: TableDto) => void;
}

function TableCard({ table, onAction, onRequestClose, onQrClick }: TableCardProps) {
  const state = tableState(table);
  const [busy, setBusy] = useState(false);

  const cardBg =
    state === "open"
      ? "bg-primary-700"
      : state === "billing"
        ? "border-2 border-warning bg-warning/10"
        : "bg-bg-primary border border-border-light";

  const labelClass = state === "open" ? "text-white" : "text-text-primary";
  const seatsClass = state === "open" ? "text-white/70" : "text-text-secondary";
  const qrBtnClass =
    state === "open"
      ? "text-white/60 hover:text-white hover:bg-white/10 focus-visible:ring-white"
      : "text-text-secondary hover:text-text-primary hover:bg-bg-secondary focus-visible:ring-primary-700";
  const seats = table.seats === 1 ? "lugar" : "lugares";

  async function handlePrimaryAction() {
    if (busy) return;
    setBusy(true);
    try {
      if (state === "free") await onAction(table, "open");
      else if (state === "open") await onAction(table, "bill");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div
      className={`relative flex min-h-[140px] flex-col rounded-2xl shadow-card transition-shadow hover:shadow-dropdown ${cardBg}`}
    >
      {/* Botao QR — canto superior direito */}
      <button
        className={`absolute right-2 top-2 inline-flex h-11 w-11 items-center justify-center rounded-lg transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-1 ${qrBtnClass}`}
        onClick={() => onQrClick(table)}
        aria-label={`QR Code da ${table.label}`}
        title="Gerar QR Code"
      >
        <QrCode size={16} aria-hidden="true" />
      </button>

      <div className="flex flex-1 flex-col p-5 pt-4">
        <div className={`mb-1 text-xl font-bold ${labelClass}`}>{table.label}</div>
        <div className={`mb-3 text-sm ${seatsClass}`}>
          {table.seats} {seats}
        </div>
        <div>
          <span
            className={`inline-block rounded-full px-3 py-1 text-xs font-medium ${STATE_BADGE[state].badgeClass}`}
          >
            {STATE_BADGE[state].label}
          </span>
        </div>
      </div>

      {/* Botao Abrir mesa — Livre */}
      {state === "free" && (
        <button
          className="w-full rounded-b-2xl bg-primary-700 px-4 py-3.5 text-sm font-semibold text-white transition-colors hover:bg-primary-800 disabled:opacity-50 focus-visible:ring-2 focus-visible:ring-primary-700 focus-visible:ring-offset-2"
          onClick={() => void handlePrimaryAction()}
          disabled={busy}
          aria-label={`Abrir ${table.label}`}
        >
          {busy ? "Aguarde…" : "Abrir mesa"}
        </button>
      )}

      {/* Botao Pedir conta — Aberta */}
      {state === "open" && (
        <button
          className="w-full rounded-b-2xl border-t border-white/20 bg-primary-800 px-4 py-3.5 text-sm font-semibold text-white transition-colors hover:bg-primary-900 disabled:opacity-50 focus-visible:ring-2 focus-visible:ring-white focus-visible:ring-offset-2"
          onClick={() => void handlePrimaryAction()}
          disabled={busy}
          aria-label={`Pedir conta para ${table.label}`}
        >
          {busy ? "Aguarde…" : "Pedir conta"}
        </button>
      )}

      {/* Botao Fechar mesa — Conta pedida */}
      {state === "billing" && (
        <button
          className="w-full rounded-b-2xl bg-error px-4 py-3.5 text-sm font-semibold text-white transition-colors hover:bg-error-dark disabled:opacity-50 focus-visible:ring-2 focus-visible:ring-error focus-visible:ring-offset-2"
          onClick={() => onRequestClose(table)}
          aria-label={`Fechar ${table.label}`}
        >
          Fechar mesa
        </button>
      )}
    </div>
  );
}

// ── Pagina principal ──────────────────────────────────────────────────────────

export default function MesasPage() {
  const router = useRouter();
  const { tables, feedStatus, refresh } = useTablesFeed();
  const [closeTarget, setCloseTarget] = useState<TableDto | null>(null);
  const [qrTarget, setQrTarget] = useState<TableDto | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [initialLoaded, setInitialLoaded] = useState(false);
  const [fetchError, setFetchError] = useState(false);

  // Guard de autenticação client-side (idêntico ao KDS/PDV)
  if (typeof window !== "undefined" && !getToken()) {
    router.replace("/");
    return null;
  }

  const activeTables = tables.filter((t) => t.active);
  const occupiedCount = activeTables.filter((t) => t.session).length;
  const freeCount = activeTables.filter((t) => !t.session).length;

  // Registra primeiro carregamento bem-sucedido
  if (!initialLoaded && tables.length > 0) {
    setInitialLoaded(true);
    setFetchError(false);
  }

  async function handleAction(
    table: TableDto,
    action: "open" | "bill" | "close",
  ) {
    const sessionPath =
      action === "open"
        ? `/tables/${table.id}/session/open`
        : action === "bill"
          ? `/tables/${table.id}/session/bill`
          : `/tables/${table.id}/session/close`;
    try {
      await api.post(sessionPath, {});
      await refresh();
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : "Erro na operação.";
      setActionError(msg);
      setTimeout(() => setActionError(null), 4000);
      await refresh();
    }
  }

  async function handleClose() {
    if (!closeTarget) return;
    await handleAction(closeTarget, "close");
  }

  async function handleRetry() {
    setFetchError(false);
    try {
      await refresh();
    } catch {
      setFetchError(true);
    }
  }

  const isLoading =
    !initialLoaded && feedStatus === "connecting" && tables.length === 0;
  const isEmpty =
    !isLoading &&
    !fetchError &&
    (feedStatus === "live" || feedStatus === "polling") &&
    activeTables.length === 0;

  const liveDot = feedStatus === "live" ? "bg-success" : "bg-warning animate-pulse";

  return (
    <div className="flex min-h-screen flex-col bg-bg-secondary">
      <ConnectionBanner status={feedStatus} />

      {/* Header */}
      <header className="sticky top-0 z-10 flex items-center justify-between border-b border-border-light bg-bg-primary px-6 py-3">
        <div className="flex items-center gap-3">
          <span
            className={`inline-block h-2.5 w-2.5 rounded-full ${liveDot}`}
            title={feedStatus === "live" ? "Ao vivo" : "Reconectando"}
            aria-label={feedStatus === "live" ? "Conexao ao vivo" : "Reconectando"}
          />
          {activeTables.length > 0 && (
            <span className="text-sm text-text-secondary">
              {occupiedCount} ocupada{occupiedCount !== 1 ? "s" : ""} &middot;{" "}
              {freeCount} livre{freeCount !== 1 ? "s" : ""}
            </span>
          )}
        </div>
        <nav className="flex items-center gap-3">
          <button
            onClick={() => void refresh()}
            className="inline-flex min-h-11 items-center justify-center rounded-lg px-3 text-sm text-text-secondary hover:bg-bg-secondary"
          >
            Atualizar
          </button>

        </nav>
      </header>

      {/* Toast de erro de ação */}
      {actionError && (
        <div
          role="alert"
          className="mx-auto mt-3 max-w-sm rounded-xl bg-error-light px-4 py-2 text-sm text-error-dark shadow-card"
        >
          {actionError}
        </div>
      )}

      {/* Conteudo principal — 4 estados obrigatorios */}
      <main className="flex-1">
        {/* 1. Carregando */}
        {isLoading && <SkeletonGrid />}

        {/* 2. Erro de rede */}
        {fetchError && (
          <div className="flex flex-col items-center justify-center gap-4 py-24 text-text-secondary">
            <p className="text-base font-medium">
              Não foi possível carregar as mesas.
            </p>
            <button
              className="btn-primary"
              onClick={() => void handleRetry()}
            >
              Tentar novamente
            </button>
          </div>
        )}

        {/* 3. Vazio */}
        {isEmpty && (
          <div className="flex flex-col items-center justify-center gap-3 py-24 text-text-secondary">
            <p className="text-base font-medium">Nenhuma mesa cadastrada.</p>
            <p className="text-sm">
              Configure as mesas no painel administrativo.
            </p>
          </div>
        )}

        {/* 4. Grid de mesas */}
        {!isLoading && !fetchError && activeTables.length > 0 && (
          <div className="grid grid-cols-2 gap-4 p-4 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
            {activeTables
              .slice()
              .sort((a, b) => a.sortOrder - b.sortOrder)
              .map((table) => (
                <TableCard
                  key={table.id}
                  table={table}
                  onAction={handleAction}
                  onRequestClose={setCloseTarget}
                  onQrClick={setQrTarget}
                />
              ))}
          </div>
        )}
      </main>

      {/* Modal de QR Code com useModalA11y (ESC + focus-trap) */}
      {qrTarget && (
        <QrModal
          table={qrTarget}
          onClose={() => setQrTarget(null)}
        />
      )}

      {/* Modal de fechamento com useModalA11y (ESC + focus-trap) */}
      {closeTarget && (
        <CloseTableModal
          table={closeTarget}
          onClose={() => setCloseTarget(null)}
          onConfirm={handleClose}
        />
      )}
    </div>
  );
}
