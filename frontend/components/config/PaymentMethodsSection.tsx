"use client";

import { useCallback, useEffect, useState } from "react";
import { Info } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import type { PaymentMethodConfig } from "@/types/store-config";
import { Toggle } from "@/components/ui/Toggle";
import { ConfigSection, SaveButton, SectionRetry } from "./ConfigSection";

interface Props {
  showToast: (msg: string, type?: "success" | "error") => void;
}

function sameMethod(a: PaymentMethodConfig, b: PaymentMethodConfig): boolean {
  return (
    a.enabled === b.enabled &&
    a.feePct === b.feePct &&
    a.passFeeToCustomer === b.passFeeToCustomer &&
    a.label === b.label &&
    a.sortOrder === b.sortOrder
  );
}

export function PaymentMethodsSection({ showToast }: Props) {
  const [state, setState] = useState<"loading" | "error" | "ok">("loading");
  const [original, setOriginal] = useState<PaymentMethodConfig[]>([]);
  const [items, setItems] = useState<PaymentMethodConfig[]>([]);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setState("loading");
    try {
      const data = await api.get<PaymentMethodConfig[]>("/config/payment-methods");
      const sorted = [...data].sort((a, b) => a.sortOrder - b.sortOrder);
      setOriginal(sorted);
      setItems(sorted.map((m) => ({ ...m })));
      setState("ok");
    } catch {
      setState("error");
    }
  }, []);

  useEffect(() => {
    queueMicrotask(() => void load());
  }, [load]);

  function patch(method: string, p: Partial<PaymentMethodConfig>) {
    setItems((prev) => prev.map((m) => (m.method === method ? { ...m, ...p } : m)));
  }

  async function handleSave(e: React.FormEvent) {
    e.preventDefault();
    if (saving) return;
    const dirty = items.filter((m) => {
      const o = original.find((x) => x.method === m.method);
      return !o || !sameMethod(m, o);
    });
    if (dirty.length === 0) {
      showToast("Nada para salvar", "success");
      return;
    }
    setSaving(true);
    // PUT e unitario (upsert por method): salva em serie so as alteradas.
    const failed: string[] = [];
    for (const m of dirty) {
      try {
        await api.put("/config/payment-methods", {
          method: m.method,
          label: m.label,
          enabled: m.enabled,
          feePct: m.feePct,
          passFeeToCustomer: m.passFeeToCustomer,
          sortOrder: m.sortOrder,
        });
      } catch (err) {
        failed.push(m.label);
        void err;
      }
    }
    setSaving(false);
    if (failed.length === 0) {
      showToast("Pagamentos salvos", "success");
      setOriginal(items.map((m) => ({ ...m })));
    } else {
      showToast(`Erro ao salvar ${failed.join(", ")}`, "error");
      await load();
    }
  }

  return (
    <ConfigSection
      id="pagamento"
      title="Formas de pagamento"
      description="Formas que o cliente ve no checkout do cardapio e no PDV."
    >
      {state === "loading" && (
        <div className="animate-pulse space-y-3" aria-busy="true" aria-label="Carregando formas de pagamento">
          <div className="h-16 rounded-xl bg-bg-tertiary" />
          <div className="h-16 rounded-xl bg-bg-tertiary" />
        </div>
      )}

      {state === "error" && <SectionRetry onRetry={() => void load()} />}

      {state === "ok" && items.length === 0 && (
        <div className="empty-state">
          <p className="empty-state-title">Nenhuma forma configurada</p>
          <button className="btn-outline mt-3 min-h-11" onClick={() => void load()}>
            Recarregar
          </button>
        </div>
      )}

      {state === "ok" && items.length > 0 && (
        <form onSubmit={(e) => void handleSave(e)} className="space-y-3">
          {items.map((m) => (
            <div key={m.method} className="rounded-xl border border-border-light p-4">
              <div className="flex items-center justify-between gap-4">
                <span className="text-sm font-semibold text-text-primary">{m.label}</span>
                <div className="flex items-center gap-2">
                  <span className="text-sm text-text-secondary">
                    {m.enabled ? "Ativa" : "Inativa"}
                  </span>
                  <Toggle
                    id={`pm-${m.method}`}
                    checked={m.enabled}
                    onChange={(next) => patch(m.method, { enabled: next })}
                    label={`${m.label} ${m.enabled ? "ativa" : "inativa"}`}
                  />
                </div>
              </div>

              <div className={"mt-3 flex flex-wrap items-center gap-4" + (m.enabled ? "" : " opacity-50")}>
                <div className="flex items-center gap-1">
                  <label
                    htmlFor={`fee-${m.method}`}
                    className="text-sm text-text-secondary"
                  >
                    Taxa
                  </label>
                  <input
                    id={`fee-${m.method}`}
                    type="number"
                    inputMode="decimal"
                    min="0"
                    max="100"
                    step="0.1"
                    value={m.feePct}
                    onChange={(e) =>
                      patch(m.method, { feePct: Number(e.target.value) || 0 })
                    }
                    className="input-field w-24 text-right"
                    aria-label={`Taxa de ${m.label} em porcentagem`}
                  />
                  <span className="text-sm text-text-secondary">%</span>
                </div>

                <label className="flex min-h-11 cursor-pointer items-center gap-2 text-sm text-text-secondary">
                  <input
                    type="checkbox"
                    checked={m.passFeeToCustomer}
                    disabled={m.feePct === 0}
                    onChange={(e) => patch(m.method, { passFeeToCustomer: e.target.checked })}
                    className="h-4 w-4"
                  />
                  Repassar taxa ao cliente
                </label>
              </div>
            </div>
          ))}

          <p className="inline-flex items-start gap-1 text-xs text-warning-dark">
            <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
            O repasse da taxa ao cliente ainda nao altera o total do checkout — entra na proxima atualizacao.
          </p>

          <div className="flex justify-end pt-1">
            <SaveButton saving={saving} label="Salvar pagamentos" />
          </div>
        </form>
      )}
    </ConfigSection>
  );
}
