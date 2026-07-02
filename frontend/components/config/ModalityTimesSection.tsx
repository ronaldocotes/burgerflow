"use client";

import { useState } from "react";
import { api, ApiError } from "@/lib/api";
import type { StoreConfigFull } from "@/types/store-config";
import { ConfigSection, SaveButton } from "./ConfigSection";

interface Props {
  config: StoreConfigFull;
  showToast: (msg: string, type?: "success" | "error") => void;
}

interface Modality {
  key: "delivery" | "pickup" | "dinein";
  emoji: string;
  title: string;
  minField: keyof StoreConfigFull;
  maxField: keyof StoreConfigFull;
}

const MODALITIES: Modality[] = [
  { key: "delivery", emoji: "🛵", title: "Delivery", minField: "deliveryTimeMinMinutes", maxField: "deliveryTimeMaxMinutes" },
  { key: "pickup", emoji: "🥡", title: "Retirada", minField: "pickupTimeMinMinutes", maxField: "pickupTimeMaxMinutes" },
  { key: "dinein", emoji: "🍽", title: "Consumo local", minField: "dineinTimeMinMinutes", maxField: "dineinTimeMaxMinutes" },
];

export function ModalityTimesSection({ config, showToast }: Props) {
  const [vals, setVals] = useState<Record<string, number>>(() => ({
    deliveryTimeMinMinutes: config.deliveryTimeMinMinutes,
    deliveryTimeMaxMinutes: config.deliveryTimeMaxMinutes,
    pickupTimeMinMinutes: config.pickupTimeMinMinutes,
    pickupTimeMaxMinutes: config.pickupTimeMaxMinutes,
    dineinTimeMinMinutes: config.dineinTimeMinMinutes,
    dineinTimeMaxMinutes: config.dineinTimeMaxMinutes,
  }));
  const [saving, setSaving] = useState(false);

  function set(field: string, raw: string) {
    const n = Math.max(0, Math.min(1440, Number(raw) || 0));
    setVals((prev) => ({ ...prev, [field]: n }));
  }

  const invalid = MODALITIES.some(
    (m) => vals[m.minField as string] > vals[m.maxField as string],
  );

  async function handleSave(e: React.FormEvent) {
    e.preventDefault();
    if (saving || invalid) return;
    setSaving(true);
    try {
      await api.patch("/config", { autoAcceptOrders: config.autoAcceptOrders, ...vals });
      showToast("Tempos salvos", "success");
    } catch (err) {
      showToast(err instanceof ApiError ? err.message : "Erro ao salvar tempos.", "error");
    } finally {
      setSaving(false);
    }
  }

  return (
    <ConfigSection
      id="tempos"
      title="Tempo de entrega e preparo"
      description="Vira a promessa de prazo no cardapio publico."
    >
      <form onSubmit={(e) => void handleSave(e)}>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          {MODALITIES.map((m) => {
            const min = vals[m.minField as string];
            const max = vals[m.maxField as string];
            const bad = min > max;
            return (
              <div
                key={m.key}
                className={
                  "rounded-xl border p-4 " + (bad ? "border-red-500" : "border-border-light")
                }
              >
                <p className="mb-3 text-sm font-semibold text-text-primary">
                  <span aria-hidden="true">{m.emoji}</span> {m.title}
                </p>
                <div className="flex items-center gap-2">
                  <label htmlFor={`${m.key}-min`} className="w-8 text-sm text-text-secondary">
                    De
                  </label>
                  <input
                    id={`${m.key}-min`}
                    type="number"
                    inputMode="numeric"
                    min={0}
                    step={5}
                    value={min}
                    onChange={(e) => set(m.minField as string, e.target.value)}
                    className="input-field w-20 text-right"
                    aria-label={`${m.title}: tempo minimo em minutos`}
                  />
                  <span className="text-sm text-text-muted">min</span>
                </div>
                <div className="mt-2 flex items-center gap-2">
                  <label htmlFor={`${m.key}-max`} className="w-8 text-sm text-text-secondary">
                    Ate
                  </label>
                  <input
                    id={`${m.key}-max`}
                    type="number"
                    inputMode="numeric"
                    min={0}
                    step={5}
                    value={max}
                    onChange={(e) => set(m.maxField as string, e.target.value)}
                    className="input-field w-20 text-right"
                    aria-label={`${m.title}: tempo maximo em minutos`}
                  />
                  <span className="text-sm text-text-muted">min</span>
                </div>
                <p className="mt-3 text-xs text-text-secondary" aria-live="polite">
                  {bad ? (
                    <span className="text-error">O minimo nao pode ser maior que o maximo</span>
                  ) : (
                    <>Cliente vera: &quot;{min}–{max} min&quot;</>
                  )}
                </p>
              </div>
            );
          })}
        </div>

        <div className="mt-5 flex justify-end">
          <SaveButton saving={saving} label="Salvar tempos" disabled={invalid} />
        </div>
      </form>
    </ConfigSection>
  );
}
