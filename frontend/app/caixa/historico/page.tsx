"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { api, ApiError } from "@/lib/api";
import { getToken, logout } from "@/lib/auth";
import { formatBRL } from "@/types/menu";
import type { CashSessionResponse, Page } from "@/types/cash";
import { ArrowLeft, AlertCircle, ChevronLeft, ChevronRight } from "lucide-react";

// ── helpers ───────────────────────────────────────────────────────────────────

function fmtDateTime(iso: string): string {
  return new Date(iso).toLocaleString("pt-BR", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function DiffBadge({ cents }: { cents?: number }) {
  if (cents === undefined) return <span className="text-text-muted">—</span>;
  if (cents === 0)
    return (
      <span className="inline-block rounded-full bg-bg-tertiary px-2 py-0.5 text-xs font-medium text-text-secondary">
        Exato
      </span>
    );
  return cents > 0 ? (
    <span className="inline-block rounded-full bg-green-100 px-2 py-0.5 text-xs font-medium text-green-800">
      +{formatBRL(cents)}
    </span>
  ) : (
    <span className="inline-block rounded-full bg-red-100 px-2 py-0.5 text-xs font-medium text-red-800">
      {formatBRL(cents)}
    </span>
  );
}

// ── skeleton ──────────────────────────────────────────────────────────────────

function SkeletonRows() {
  return (
    <div className="divide-y divide-border-light">
      {Array.from({ length: 5 }).map((_, i) => (
        <div key={i} className="flex items-center gap-4 px-4 py-3">
          <div className="skeleton h-4 flex-1 rounded" />
          <div className="skeleton h-4 w-24 rounded" />
          <div className="skeleton h-4 w-20 rounded" />
        </div>
      ))}
    </div>
  );
}

// ── página ────────────────────────────────────────────────────────────────────

const PAGE_SIZE = 15;

export default function CaixaHistoricoPage() {
  const router = useRouter();
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<CashSessionResponse> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const redirectToLogin = useCallback(() => {
    logout();
    router.replace("/login");
  }, [router]);

  const fetchPage = useCallback(
    async (p: number) => {
      setLoading(true);
      setError(null);
      try {
        const result = await api.get<Page<CashSessionResponse>>(
          `/cash-sessions?page=${p}&size=${PAGE_SIZE}&sort=openedAt,desc`,
        );
        setData(result);
        setPage(p);
      } catch (err) {
        if (err instanceof ApiError && err.status === 401) {
          redirectToLogin();
          return;
        }
        setError(
          err instanceof Error ? err.message : "Erro ao carregar o historico.",
        );
      } finally {
        setLoading(false);
      }
    },
    [redirectToLogin],
  );

  useEffect(() => {
    if (!getToken()) {
      router.push("/login");
      return;
    }
    void fetchPage(0);
  }, [router, fetchPage]);

  const sessions = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;

  return (
    <main className="min-h-full bg-bg-secondary p-4 md:p-6">
      <div className="mx-auto max-w-5xl">
        {/* Cabeçalho */}
        <div className="mb-6 flex items-center gap-3">
          <Link
            href="/caixa"
            className="flex h-11 w-11 items-center justify-center rounded-lg border border-border-light bg-bg-primary text-text-secondary hover:bg-bg-tertiary transition-colors"
            aria-label="Voltar ao caixa"
          >
            <ArrowLeft className="h-4 w-4" aria-hidden="true" />
          </Link>
          <h1 className="text-2xl font-bold text-text-primary">Historico de turnos</h1>
        </div>

        {/* Conteúdo */}
        <div className="rounded-xl border border-border-light bg-bg-primary overflow-hidden">
          {/* Cabeçalho da tabela */}
          <div className="hidden grid-cols-[1fr_1fr_auto_auto_auto_auto] gap-4 border-b border-border-light bg-bg-tertiary px-4 py-3 text-sm font-semibold uppercase tracking-wide text-text-muted md:grid">
            <span>Abertura</span>
            <span>Fechamento</span>
            <span className="text-right">Fundo</span>
            <span className="text-right">Esperado</span>
            <span className="text-right">Contado</span>
            <span className="text-right">Diferenca</span>
          </div>

          {/* Estado loading */}
          {loading && <SkeletonRows />}

          {/* Estado erro */}
          {!loading && error && (
            <div className="flex flex-col items-center gap-3 py-12 text-center">
              <AlertCircle className="h-8 w-8 text-error" aria-hidden="true" />
              <p className="text-error text-sm" role="alert">
                {error}
              </p>
              <button
                className="btn-primary text-sm"
                onClick={() => void fetchPage(page)}
              >
                Tentar de novo
              </button>
            </div>
          )}

          {/* Estado vazio */}
          {!loading && !error && sessions.length === 0 && (
            <div className="empty-state py-16">
              <p className="empty-state-title">Nenhum turno registrado</p>
              <p className="empty-state-description">
                Os turnos de caixa aparecrao aqui apos serem abertos e fechados.
              </p>
            </div>
          )}

          {/* Linhas */}
          {!loading && !error && sessions.length > 0 && (
            <ul className="divide-y divide-border-light">
              {sessions.map((s) => {
                const isOpen = s.status === "OPEN";
                return (
                  <li
                    key={s.id}
                    className="flex flex-col gap-1 px-4 py-3 text-sm md:grid md:grid-cols-[1fr_1fr_auto_auto_auto_auto] md:items-center md:gap-4"
                  >
                    {/* Abertura */}
                    <div>
                      <span className="font-medium text-text-primary">
                        {fmtDateTime(s.openedAt)}
                      </span>
                      <span className="ml-2 text-xs text-text-muted">
                        {s.openedByUserId}
                      </span>
                    </div>

                    {/* Fechamento */}
                    <div>
                      {isOpen ? (
                        <span className="inline-block rounded-full bg-primary-50 px-2 py-0.5 text-xs font-medium text-primary-700">
                          Em aberto
                        </span>
                      ) : (
                        <span className="text-text-secondary">
                          {s.closedAt ? fmtDateTime(s.closedAt) : "—"}
                        </span>
                      )}
                    </div>

                    {/* Fundo */}
                    <span className="tabular-nums text-text-secondary md:text-right">
                      {formatBRL(s.openingAmountCents)}
                    </span>

                    {/* Esperado */}
                    <span className="tabular-nums text-text-secondary md:text-right">
                      {formatBRL(s.expectedCents)}
                    </span>

                    {/* Contado */}
                    <span className="tabular-nums text-text-secondary md:text-right">
                      {s.countedCents !== undefined ? formatBRL(s.countedCents) : "—"}
                    </span>

                    {/* Diferença */}
                    <div className="md:text-right">
                      <DiffBadge cents={s.differenceCents} />
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
        </div>

        {/* Paginação */}
        {!loading && !error && totalPages > 1 && (
          <div className="mt-4 flex items-center justify-between text-sm">
            <span className="text-text-muted">
              Pagina {page + 1} de {totalPages}
            </span>
            <div className="flex gap-2">
              <button
                type="button"
                disabled={page === 0}
                onClick={() => void fetchPage(page - 1)}
                className="btn-outline flex items-center gap-1 text-sm disabled:opacity-40"
                aria-label="Pagina anterior"
              >
                <ChevronLeft className="h-4 w-4" aria-hidden="true" />
                Anterior
              </button>
              <button
                type="button"
                disabled={page >= totalPages - 1}
                onClick={() => void fetchPage(page + 1)}
                className="btn-outline flex items-center gap-1 text-sm disabled:opacity-40"
                aria-label="Proxima pagina"
              >
                Proxima
                <ChevronRight className="h-4 w-4" aria-hidden="true" />
              </button>
            </div>
          </div>
        )}
      </div>
    </main>
  );
}
