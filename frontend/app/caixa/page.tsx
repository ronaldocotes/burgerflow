"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { api, ApiError } from "@/lib/api";
import { getToken, logout } from "@/lib/auth";
import { useModalA11y } from "@/lib/use-modal-a11y";
import { formatBRL } from "@/types/menu";
import type { CashSessionResponse } from "@/types/cash";
import {
  LockKeyhole,
  Wallet,
  Clock,
  History,
  TrendingUp,
  TrendingDown,
  AlertCircle,
  CheckCircle,
} from "lucide-react";

// ── helpers ───────────────────────────────────────────────────────────────────

/** Converte string BR (ex.: "1.234,56") em centavos. Retorna null se inválido. */
function parseBRLInput(value: string): number | null {
  const normalized = value.replace(/\./g, "").replace(",", ".");
  const num = Number.parseFloat(normalized);
  return Number.isFinite(num) && num >= 0 ? Math.round(num * 100) : null;
}

/** "há 2h 15min" ou "há 45min" */
function relativeTime(iso: string): string {
  const diffMs = Date.now() - new Date(iso).getTime();
  const totalMin = Math.floor(diffMs / 60_000);
  if (totalMin < 1) return "agora mesmo";
  if (totalMin < 60) return `há ${totalMin}min`;
  const h = Math.floor(totalMin / 60);
  const m = totalMin % 60;
  return m > 0 ? `há ${h}h ${m}min` : `há ${h}h`;
}

function fmtHour(iso: string): string {
  return new Date(iso).toLocaleTimeString("pt-BR", {
    hour: "2-digit",
    minute: "2-digit",
  });
}

// ── estado A: caixa fechado ───────────────────────────────────────────────────

function ClosedState({ onOpen }: { onOpen: () => void }) {
  return (
    <div className="flex flex-col items-center justify-center py-24 gap-6">
      <div className="flex h-20 w-20 items-center justify-center rounded-full bg-bg-tertiary">
        <LockKeyhole className="h-10 w-10 text-text-muted" aria-hidden="true" />
      </div>
      <div className="text-center">
        <h2 className="text-xl font-semibold text-text-primary">Caixa fechado</h2>
        <p className="mt-1 text-text-secondary">Nenhum turno aberto</p>
      </div>
      <button type="button" className="btn-primary px-8 py-3 text-base" onClick={onOpen}>
        Abrir caixa
      </button>
    </div>
  );
}

// ── cartão de saldo ───────────────────────────────────────────────────────────

function SaldoCard({
  label,
  cents,
  highlight,
}: {
  label: string;
  cents: number;
  highlight?: boolean;
}) {
  return (
    <div
      className={[
        "rounded-xl border p-4",
        highlight
          ? "border-primary-600 bg-primary-50"
          : "border-border-light bg-bg-primary",
      ].join(" ")}
    >
      <p className="text-xs font-medium uppercase tracking-wide text-text-muted">{label}</p>
      <p
        className={[
          "mt-1 text-2xl font-bold tabular-nums",
          highlight ? "text-primary-700" : "text-text-primary",
        ].join(" ")}
      >
        {formatBRL(cents)}
      </p>
    </div>
  );
}

// ── badge de tipo de lançamento ───────────────────────────────────────────────

function EntryBadge({ type }: { type: "WITHDRAWAL" | "DEPOSIT" }) {
  return type === "DEPOSIT" ? (
    <span className="inline-flex items-center gap-1 rounded-full bg-green-100 px-2 py-0.5 text-xs font-medium text-green-800">
      <TrendingUp className="h-3 w-3" aria-hidden="true" />
      Reforco
    </span>
  ) : (
    <span className="inline-flex items-center gap-1 rounded-full bg-red-100 px-2 py-0.5 text-xs font-medium text-red-800">
      <TrendingDown className="h-3 w-3" aria-hidden="true" />
      Sangria
    </span>
  );
}

// ── estado B: turno aberto ────────────────────────────────────────────────────

function OpenState({
  session,
  onWithdrawal,
  onDeposit,
  onClose,
}: {
  session: CashSessionResponse;
  onWithdrawal: () => void;
  onDeposit: () => void;
  onClose: () => void;
}) {
  return (
    <div className="space-y-6">
      {/* Cabeçalho do turno */}
      <div className="flex flex-wrap items-center gap-3 rounded-xl border border-border-light bg-bg-primary p-4">
        <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-primary-50">
          <Wallet className="h-5 w-5 text-primary-700" aria-hidden="true" />
        </div>
        <div className="flex-1 min-w-0">
          <p className="text-sm font-medium text-text-primary">
            Aberto por{" "}
            <span className="font-semibold">{session.openedByUserId}</span>
          </p>
          <p className="flex items-center gap-1 text-xs text-text-muted">
            <Clock className="h-3 w-3" aria-hidden="true" />
            {relativeTime(session.openedAt)} &mdash; {fmtHour(session.openedAt)}
          </p>
        </div>
        {/* Botões de ação */}
        <div className="flex flex-wrap gap-2">
          <button type="button" className="btn-outline text-sm" onClick={onWithdrawal}>
            Sangria
          </button>
          <button type="button" className="btn-outline text-sm" onClick={onDeposit}>
            Reforco
          </button>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg bg-error px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-red-700 active:bg-red-800 focus:ring-2 focus:ring-error focus:ring-offset-2 disabled:opacity-50"
          >
            Fechar caixa
          </button>
        </div>
      </div>

      {/* Grade de saldos */}
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <SaldoCard label="Fundo inicial" cents={session.openingAmountCents} />
        <SaldoCard label="Vendas (dinheiro)" cents={session.cashSalesCents} />
        <SaldoCard label="Reforcoss" cents={session.depositsCents} />
        <SaldoCard label="Sangrias" cents={session.withdrawalsCents} />
      </div>
      <SaldoCard label="Esperado na gaveta" cents={session.expectedCents} highlight />

      {/* Lista de lançamentos */}
      <div>
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-text-muted">
          Lançamentos
        </h2>
        {session.entries.length === 0 ? (
          <div className="empty-state py-8">
            <p className="empty-state-title text-base">Sem lançamentos</p>
            <p className="empty-state-description text-sm">
              Nenhuma sangria ou reforço neste turno.
            </p>
          </div>
        ) : (
          <ul className="divide-y divide-border-light rounded-xl border border-border-light bg-bg-primary">
            {session.entries.map((e) => (
              <li key={e.id} className="flex items-center gap-3 px-4 py-3">
                <EntryBadge type={e.type} />
                <div className="flex-1 min-w-0">
                  <p className="truncate text-sm text-text-primary">{e.reason}</p>
                  <p className="text-xs text-text-muted">{fmtHour(e.createdAt)}</p>
                </div>
                <span
                  className={[
                    "shrink-0 text-sm font-semibold tabular-nums",
                    e.type === "DEPOSIT" ? "text-green-700" : "text-red-700",
                  ].join(" ")}
                >
                  {e.type === "WITHDRAWAL" ? "−" : "+"}
                  {formatBRL(e.amountCents)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

// ── resumo pós-fechamento ─────────────────────────────────────────────────────

function ClosedSummary({
  session,
  onDismiss,
}: {
  session: CashSessionResponse;
  onDismiss: () => void;
}) {
  const diff = session.differenceCents ?? 0;
  return (
    <div className="mx-auto max-w-md rounded-xl border border-border-light bg-bg-primary p-6 space-y-4">
      <div className="flex items-center gap-3">
        <CheckCircle className="h-6 w-6 text-success shrink-0" aria-hidden="true" />
        <h2 className="text-lg font-bold text-text-primary">Caixa fechado</h2>
      </div>
      <div className="divide-y divide-border-light rounded-lg border border-border-light">
        <Row label="Esperado na gaveta" value={formatBRL(session.expectedCents)} />
        <Row label="Valor contado" value={formatBRL(session.countedCents ?? 0)} />
        <Row
          label="Diferenca"
          value={
            diff === 0
              ? "Caixa exato"
              : `${diff > 0 ? "+" : ""}${formatBRL(diff)}`
          }
          valueClass={
            diff === 0
              ? "text-text-secondary"
              : diff > 0
                ? "text-green-700"
                : "text-red-700"
          }
        />
      </div>
      <button type="button" className="btn-primary w-full" onClick={onDismiss}>
        Novo turno
      </button>
    </div>
  );
}

function Row({
  label,
  value,
  valueClass,
}: {
  label: string;
  value: string;
  valueClass?: string;
}) {
  return (
    <div className="flex items-center justify-between px-4 py-3 text-sm">
      <span className="text-text-secondary">{label}</span>
      <span className={["font-semibold tabular-nums", valueClass ?? "text-text-primary"].join(" ")}>
        {value}
      </span>
    </div>
  );
}

// ── skeleton ──────────────────────────────────────────────────────────────────

function SkeletonPage() {
  return (
    <main className="min-h-full bg-bg-secondary p-4 md:p-6">
      <div className="mx-auto max-w-4xl space-y-6">
        <div className="skeleton h-8 w-32" />
        <div className="skeleton h-24 w-full rounded-xl" />
        <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="skeleton h-20 rounded-xl" />
          ))}
        </div>
        <div className="skeleton h-14 w-full rounded-xl" />
      </div>
    </main>
  );
}

// ── modal: abrir caixa ────────────────────────────────────────────────────────

function ModalAbrirCaixa({
  onClose,
  onOpened,
}: {
  onClose: () => void;
  onOpened: (session: CashSessionResponse) => void;
}) {
  const dialogRef = useRef<HTMLDivElement>(null);
  useModalA11y(dialogRef, onClose);

  const [troco, setTroco] = useState("");
  const [notes, setNotes] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (submitting) return;
    const openingAmountCents = parseBRLInput(troco) ?? 0;
    setErr(null);
    setSubmitting(true);
    try {
      const session = await api.post<CashSessionResponse>("/cash-sessions/open", {
        openingAmountCents,
        notes: notes.trim() || undefined,
      });
      onOpened(session);
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : "Falha ao abrir o caixa.");
      setSubmitting(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-end sm:items-center justify-center bg-black/50 p-0 sm:p-4"
      onClick={onClose}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-label="Abrir caixa"
        className="bg-bg-primary w-full sm:max-w-md rounded-t-2xl sm:rounded-2xl p-6 space-y-5"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-bold text-text-primary">Abrir caixa</h2>
          <button
            type="button"
            aria-label="Fechar"
            onClick={onClose}
            className="text-text-muted hover:text-text-primary text-xl leading-none"
          >
            ✕
          </button>
        </div>

        <form onSubmit={(e) => void handleSubmit(e)} className="space-y-4">
          <div>
            <label htmlFor="troco" className="form-label">
              Fundo de troco (R$)
            </label>
            <input
              id="troco"
              type="text"
              inputMode="decimal"
              className="input-field"
              placeholder="0,00"
              value={troco}
              onChange={(e) => setTroco(e.target.value)}
              autoComplete="off"
            />
            <p className="mt-1 text-xs text-text-muted">
              Deixe em branco para iniciar com R$&nbsp;0,00
            </p>
          </div>

          <div>
            <label htmlFor="obs-abrir" className="form-label">
              Observacao (opcional)
            </label>
            <textarea
              id="obs-abrir"
              rows={2}
              className="input-field resize-none"
              placeholder="Ex.: turno da manha"
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              maxLength={300}
            />
          </div>

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
            <button type="submit" className="btn-primary flex-1" disabled={submitting}>
              {submitting ? "Abrindo..." : "Abrir turno"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

// ── modal: sangria / reforço ──────────────────────────────────────────────────

function ModalEntry({
  sessionId,
  type: initialType,
  onClose,
  onDone,
}: {
  sessionId: string;
  type: "WITHDRAWAL" | "DEPOSIT";
  onClose: () => void;
  onDone: (session: CashSessionResponse) => void;
}) {
  const dialogRef = useRef<HTMLDivElement>(null);
  useModalA11y(dialogRef, onClose);

  const [type, setType] = useState<"WITHDRAWAL" | "DEPOSIT">(initialType);
  const [valor, setValor] = useState("");
  const [reason, setReason] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (submitting) return;
    const amountCents = parseBRLInput(valor);
    if (!amountCents || amountCents <= 0) {
      setErr("Informe um valor maior que zero.");
      return;
    }
    if (!reason.trim()) {
      setErr("O motivo e obrigatorio.");
      return;
    }
    setErr(null);
    setSubmitting(true);
    try {
      const session = await api.post<CashSessionResponse>(
        `/cash-sessions/${sessionId}/entries`,
        { type, amountCents, reason: reason.trim() },
      );
      onDone(session);
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : "Falha ao registrar o lancamento.");
      setSubmitting(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-end sm:items-center justify-center bg-black/50 p-0 sm:p-4"
      onClick={onClose}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-label={type === "WITHDRAWAL" ? "Sangria" : "Reforco de caixa"}
        className="bg-bg-primary w-full sm:max-w-md rounded-t-2xl sm:rounded-2xl p-6 space-y-5"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-bold text-text-primary">Lancamento</h2>
          <button
            type="button"
            aria-label="Fechar"
            onClick={onClose}
            className="text-text-muted hover:text-text-primary text-xl leading-none"
          >
            ✕
          </button>
        </div>

        {/* Toggle sangria / reforço */}
        <div
          role="group"
          aria-label="Tipo de lancamento"
          className="grid grid-cols-2 gap-1 rounded-lg border border-border-light p-1"
        >
          {(
            [
              ["WITHDRAWAL", "Sangria"],
              ["DEPOSIT", "Reforco"],
            ] as ["WITHDRAWAL" | "DEPOSIT", string][]
          ).map(([val, label]) => (
            <button
              key={val}
              type="button"
              aria-pressed={type === val}
              onClick={() => setType(val)}
              className={[
                "rounded-md py-2 text-sm font-medium transition-colors",
                type === val
                  ? "bg-primary-700 text-white"
                  : "text-text-secondary hover:bg-bg-tertiary",
              ].join(" ")}
            >
              {label}
            </button>
          ))}
        </div>

        <form onSubmit={(e) => void handleSubmit(e)} className="space-y-4">
          <div>
            <label htmlFor="entry-valor" className="form-label">
              Valor (R$)
              <span className="text-error ml-1" aria-hidden="true">*</span>
            </label>
            <input
              id="entry-valor"
              type="text"
              inputMode="decimal"
              className="input-field"
              placeholder="0,00"
              value={valor}
              onChange={(e) => setValor(e.target.value)}
              required
              autoComplete="off"
            />
          </div>

          <div>
            <label htmlFor="entry-reason" className="form-label">
              Motivo
              <span className="text-error ml-1" aria-hidden="true">*</span>
            </label>
            <input
              id="entry-reason"
              type="text"
              className="input-field"
              placeholder={
                type === "WITHDRAWAL"
                  ? "Ex.: pagamento de fornecedor"
                  : "Ex.: reforco de troco"
              }
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              required
              maxLength={200}
            />
          </div>

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
            <button type="submit" className="btn-primary flex-1" disabled={submitting}>
              {submitting ? "Registrando..." : "Confirmar"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

// ── modal: fechar caixa ───────────────────────────────────────────────────────

function ModalFecharCaixa({
  session,
  onClose,
  onClosed,
}: {
  session: CashSessionResponse;
  onClose: () => void;
  onClosed: (result: CashSessionResponse) => void;
}) {
  const dialogRef = useRef<HTMLDivElement>(null);
  useModalA11y(dialogRef, onClose);

  const [contado, setContado] = useState("");
  const [notes, setNotes] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const contadoCents = parseBRLInput(contado);
  const diffLive =
    contadoCents !== null ? contadoCents - session.expectedCents : null;

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (submitting) return;
    if (contadoCents === null || contadoCents < 0) {
      setErr("Informe o valor contado na gaveta.");
      return;
    }
    setErr(null);
    setSubmitting(true);
    try {
      const result = await api.post<CashSessionResponse>(
        `/cash-sessions/${session.id}/close`,
        {
          countedAmountCents: contadoCents,
          notes: notes.trim() || undefined,
        },
      );
      onClosed(result);
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : "Falha ao fechar o caixa.");
      setSubmitting(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-end sm:items-center justify-center bg-black/50 p-0 sm:p-4"
      onClick={onClose}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-label="Fechar caixa"
        className="bg-bg-primary w-full sm:max-w-md rounded-t-2xl sm:rounded-2xl p-6 space-y-5"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-bold text-text-primary">Fechar caixa</h2>
          <button
            type="button"
            aria-label="Fechar"
            onClick={onClose}
            className="text-text-muted hover:text-text-primary text-xl leading-none"
          >
            ✕
          </button>
        </div>

        {/* Esperado */}
        <div className="rounded-lg border border-border-light bg-bg-secondary p-4">
          <p className="text-xs text-text-muted">Esperado na gaveta</p>
          <p className="text-2xl font-bold tabular-nums text-text-primary">
            {formatBRL(session.expectedCents)}
          </p>
        </div>

        <form onSubmit={(e) => void handleSubmit(e)} className="space-y-4">
          <div>
            <label htmlFor="contado" className="form-label">
              Valor contado (R$)
              <span className="text-error ml-1" aria-hidden="true">*</span>
            </label>
            <input
              id="contado"
              type="text"
              inputMode="decimal"
              className="input-field"
              placeholder="0,00"
              value={contado}
              onChange={(e) => setContado(e.target.value)}
              autoComplete="off"
            />
          </div>

          {/* Diferença ao vivo */}
          {diffLive !== null && (
            <div aria-live="polite" aria-atomic="true">
              {diffLive === 0 ? (
                <span className="inline-flex items-center gap-1.5 rounded-full bg-bg-tertiary px-3 py-1 text-sm font-medium text-text-secondary">
                  <CheckCircle className="h-4 w-4" aria-hidden="true" />
                  Caixa exato
                </span>
              ) : diffLive > 0 ? (
                <span className="inline-flex items-center gap-1.5 rounded-full bg-green-100 px-3 py-1 text-sm font-medium text-green-800">
                  <TrendingUp className="h-4 w-4" aria-hidden="true" />
                  Sobra de {formatBRL(diffLive)}
                </span>
              ) : (
                <span className="inline-flex items-center gap-1.5 rounded-full bg-red-100 px-3 py-1 text-sm font-medium text-red-800">
                  <TrendingDown className="h-4 w-4" aria-hidden="true" />
                  Falta de {formatBRL(Math.abs(diffLive))}
                </span>
              )}
            </div>
          )}

          <div>
            <label htmlFor="obs-fechar" className="form-label">
              Observacao (opcional)
            </label>
            <textarea
              id="obs-fechar"
              rows={2}
              className="input-field resize-none"
              placeholder="Ex.: troco da tarde, moedas separadas"
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              maxLength={300}
            />
          </div>

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
              type="submit"
              disabled={submitting}
              className="flex-1 rounded-lg bg-error px-4 py-2 font-medium text-white transition-colors hover:bg-red-700 active:bg-red-800 focus:ring-2 focus:ring-error focus:ring-offset-2 disabled:opacity-50"
            >
              {submitting ? "Fechando..." : "Fechar turno"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

// ── página principal ──────────────────────────────────────────────────────────

export default function CaixaPage() {
  const router = useRouter();

  // undefined = carregando, null = sem turno aberto, objeto = turno aberto
  const [session, setSession] = useState<CashSessionResponse | null | undefined>(
    undefined,
  );
  const [error, setError] = useState<string | null>(null);
  const [showAbrir, setShowAbrir] = useState(false);
  const [showEntry, setShowEntry] = useState<"WITHDRAWAL" | "DEPOSIT" | null>(null);
  const [showFechar, setShowFechar] = useState(false);
  // Resultado do fechamento para exibir o resumo antes de voltar ao estado A
  const [closedResult, setClosedResult] = useState<CashSessionResponse | null>(null);

  const redirectToLogin = useCallback(() => {
    logout();
    router.replace("/login");
  }, [router]);

  const fetchSession = useCallback(async () => {
    setError(null);
    try {
      // 204 → api retorna undefined; mapeamos para null (sem turno)
      const data = await api.get<CashSessionResponse | undefined>(
        "/cash-sessions/current",
      );
      setSession(data ?? null);
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        redirectToLogin();
        return;
      }
      setError(
        err instanceof Error ? err.message : "Erro ao carregar informacoes do caixa.",
      );
    }
  }, [redirectToLogin]);

  useEffect(() => {
    if (!getToken()) {
      router.push("/login");
      return;
    }
    void fetchSession();
  }, [router, fetchSession]);

  // Estado de loading inicial
  if (session === undefined && !error) {
    return <SkeletonPage />;
  }

  // Estado de erro
  if (error) {
    return (
      <main className="flex min-h-full flex-col items-center justify-center gap-4 bg-bg-secondary p-6 text-center">
        <AlertCircle className="h-10 w-10 text-error" aria-hidden="true" />
        <p className="text-error font-medium" role="alert">
          {error}
        </p>
        <button className="btn-primary" onClick={() => void fetchSession()}>
          Tentar de novo
        </button>
      </main>
    );
  }

  return (
    <main className="min-h-full bg-bg-secondary p-4 md:p-6">
      <div className="mx-auto max-w-4xl">
        {/* Cabeçalho da página */}
        <div className="mb-6 flex items-center justify-between">
          <h1 className="text-2xl font-bold text-text-primary">Caixa</h1>
          <Link
            href="/caixa/historico"
            className="btn-outline flex items-center gap-2 text-sm"
          >
            <History className="h-4 w-4" aria-hidden="true" />
            Historico
          </Link>
        </div>

        {/* Resumo pós-fechamento */}
        {closedResult ? (
          <ClosedSummary
            session={closedResult}
            onDismiss={() => setClosedResult(null)}
          />
        ) : session == null ? (
          /* null = sem turno aberto; undefined nunca chega aqui (tratado acima no guard de loading) */
          <ClosedState onOpen={() => setShowAbrir(true)} />
        ) : (
          <OpenState
            session={session}
            onWithdrawal={() => setShowEntry("WITHDRAWAL")}
            onDeposit={() => setShowEntry("DEPOSIT")}
            onClose={() => setShowFechar(true)}
          />
        )}
      </div>

      {/* Modais */}
      {showAbrir && (
        <ModalAbrirCaixa
          onClose={() => setShowAbrir(false)}
          onOpened={(s) => {
            setSession(s);
            setShowAbrir(false);
          }}
        />
      )}

      {showEntry && session && (
        <ModalEntry
          sessionId={session.id}
          type={showEntry}
          onClose={() => setShowEntry(null)}
          onDone={(s) => {
            setSession(s);
            setShowEntry(null);
          }}
        />
      )}

      {showFechar && session && (
        <ModalFecharCaixa
          session={session}
          onClose={() => setShowFechar(false)}
          onClosed={(result) => {
            setSession(null);
            setShowFechar(false);
            setClosedResult(result);
          }}
        />
      )}
    </main>
  );
}
