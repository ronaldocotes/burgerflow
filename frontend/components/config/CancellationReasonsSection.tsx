"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Pencil, Check, X } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import type { CancellationReason } from "@/types/store-config";
import { ConfigSection, SectionRetry } from "./ConfigSection";

interface Props {
  showToast: (msg: string, type?: "success" | "error") => void;
}

export function CancellationReasonsSection({ showToast }: Props) {
  const [state, setState] = useState<"loading" | "error" | "ok">("loading");
  const [items, setItems] = useState<CancellationReason[]>([]);
  const [newDesc, setNewDesc] = useState("");
  const [adding, setAdding] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editDesc, setEditDesc] = useState("");
  const [confirmId, setConfirmId] = useState<string | null>(null);
  const [showInactive, setShowInactive] = useState(false);
  const confirmTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const load = useCallback(async () => {
    setState("loading");
    try {
      const data = await api.get<CancellationReason[]>(
        "/config/cancellation-reasons?activeOnly=false",
      );
      setItems([...data].sort((a, b) => a.sortOrder - b.sortOrder));
      setState("ok");
    } catch {
      setState("error");
    }
  }, []);

  useEffect(() => {
    queueMicrotask(() => void load());
  }, [load]);

  async function add(e: React.FormEvent) {
    e.preventDefault();
    const desc = newDesc.trim();
    if (!desc || adding) return;
    setAdding(true);
    try {
      const created = await api.post<CancellationReason>("/config/cancellation-reasons", {
        description: desc,
        active: true,
        sortOrder: items.length,
      });
      setItems((prev) => [...prev, created]);
      setNewDesc("");
    } catch (err) {
      showToast(err instanceof ApiError ? err.message : "Erro ao adicionar motivo.", "error");
    } finally {
      setAdding(false);
    }
  }

  async function saveEdit(r: CancellationReason) {
    const desc = editDesc.trim();
    if (!desc || !r.id) return;
    try {
      const updated = await api.put<CancellationReason>(
        `/config/cancellation-reasons/${r.id}`,
        { description: desc, active: r.active, sortOrder: r.sortOrder },
      );
      setItems((prev) => prev.map((x) => (x.id === r.id ? updated : x)));
      setEditingId(null);
    } catch (err) {
      showToast(err instanceof ApiError ? err.message : "Erro ao salvar motivo.", "error");
    }
  }

  async function deactivate(r: CancellationReason) {
    if (!r.id) return;
    try {
      await api.del(`/config/cancellation-reasons/${r.id}`);
      setItems((prev) => prev.map((x) => (x.id === r.id ? { ...x, active: false } : x)));
      setConfirmId(null);
    } catch (err) {
      showToast(err instanceof ApiError ? err.message : "Erro ao desativar motivo.", "error");
    }
  }

  async function reactivate(r: CancellationReason) {
    if (!r.id) return;
    try {
      const updated = await api.put<CancellationReason>(
        `/config/cancellation-reasons/${r.id}`,
        { description: r.description, active: true, sortOrder: r.sortOrder },
      );
      setItems((prev) => prev.map((x) => (x.id === r.id ? updated : x)));
    } catch (err) {
      showToast(err instanceof ApiError ? err.message : "Erro ao reativar motivo.", "error");
    }
  }

  function armConfirm(id: string) {
    setConfirmId(id);
    if (confirmTimer.current) clearTimeout(confirmTimer.current);
    confirmTimer.current = setTimeout(() => setConfirmId(null), 4000);
  }

  const active = items.filter((r) => r.active);
  const inactive = items.filter((r) => !r.active);

  return (
    <ConfigSection
      id="cancelamento"
      title="Motivos de cancelamento"
      description="Aparecem ao cancelar um pedido no PDV e na Cozinha. Pedidos antigos guardam o texto da epoca."
    >
      {state === "loading" && (
        <div className="animate-pulse space-y-2" aria-busy="true" aria-label="Carregando motivos">
          <div className="h-10 rounded-lg bg-bg-tertiary" />
          <div className="h-10 rounded-lg bg-bg-tertiary" />
        </div>
      )}

      {state === "error" && <SectionRetry onRetry={() => void load()} />}

      {state === "ok" && (
        <>
          <form onSubmit={(e) => void add(e)} className="mb-4 flex gap-2">
            <input
              type="text"
              className="input-field flex-1"
              placeholder="Novo motivo..."
              value={newDesc}
              maxLength={140}
              onChange={(e) => setNewDesc(e.target.value)}
              aria-label="Novo motivo de cancelamento"
            />
            <button type="submit" className="btn-primary min-h-11" disabled={adding || !newDesc.trim()}>
              Adicionar
            </button>
          </form>

          {active.length === 0 && inactive.length === 0 ? (
            <div className="empty-state">
              <p className="empty-state-title">Nenhum motivo cadastrado</p>
              <p className="empty-state-description">
                Adicione o primeiro para o cancelamento parar de usar texto livre.
              </p>
            </div>
          ) : (
            <ul className="flex flex-col divide-y divide-border-light">
              {active.map((r) => (
                <li key={r.id} className="flex items-center gap-2 py-2">
                  {editingId === r.id ? (
                    <>
                      <input
                        type="text"
                        className="input-field flex-1"
                        value={editDesc}
                        maxLength={140}
                        onChange={(e) => setEditDesc(e.target.value)}
                        onKeyDown={(e) => {
                          if (e.key === "Escape") setEditingId(null);
                          if (e.key === "Enter") void saveEdit(r);
                        }}
                        aria-label="Editar motivo"
                        autoFocus
                      />
                      <button
                        type="button"
                        className="icon-button"
                        onClick={() => void saveEdit(r)}
                        aria-label="Salvar edicao"
                      >
                        <Check className="h-4 w-4" aria-hidden="true" />
                      </button>
                      <button
                        type="button"
                        className="icon-button"
                        onClick={() => setEditingId(null)}
                        aria-label="Cancelar edicao"
                      >
                        <X className="h-4 w-4" aria-hidden="true" />
                      </button>
                    </>
                  ) : (
                    <>
                      <span className="flex-1 text-sm text-text-primary">{r.description}</span>
                      <button
                        type="button"
                        className="inline-flex min-h-11 items-center gap-1 px-2 text-xs text-primary-700"
                        onClick={() => {
                          setEditingId(r.id);
                          setEditDesc(r.description);
                        }}
                      >
                        <Pencil className="h-3.5 w-3.5" aria-hidden="true" /> Editar
                      </button>
                      {confirmId === r.id ? (
                        <button
                          type="button"
                          className="inline-flex min-h-11 items-center px-2 text-xs font-semibold text-error"
                          onClick={() => void deactivate(r)}
                        >
                          Confirmar desativacao?
                        </button>
                      ) : (
                        <button
                          type="button"
                          className="inline-flex min-h-11 items-center px-2 text-xs text-text-secondary"
                          onClick={() => r.id && armConfirm(r.id)}
                        >
                          Desativar
                        </button>
                      )}
                    </>
                  )}
                </li>
              ))}
            </ul>
          )}

          {inactive.length > 0 && (
            <div className="mt-4 border-t border-border-light pt-3">
              <button
                type="button"
                className="mb-2 text-xs font-semibold text-text-secondary"
                aria-expanded={showInactive}
                onClick={() => setShowInactive((v) => !v)}
              >
                Inativos ({inactive.length}) {showInactive ? "▲" : "▼"}
              </button>
              {showInactive && (
                <ul className="flex flex-col divide-y divide-border-light">
                  {inactive.map((r) => (
                    <li key={r.id} className="flex items-center gap-2 py-2">
                      <span className="flex-1 text-sm text-text-muted line-through">
                        {r.description}
                      </span>
                      <span className="rounded-full bg-bg-tertiary px-2 py-0.5 text-xs text-text-secondary">
                        · inativo ·
                      </span>
                      <button
                        type="button"
                        className="inline-flex min-h-11 items-center px-2 text-xs text-primary-700"
                        onClick={() => void reactivate(r)}
                      >
                        Reativar
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}
        </>
      )}
    </ConfigSection>
  );
}
