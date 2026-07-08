"use client";

import { useMemo, useState } from "react";
import { Copy, Info } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import type { StoreConfigFull } from "@/types/store-config";
import {
  WEEKDAY_KEYS,
  WEEKDAY_LABELS,
  splitRange,
  crossesMidnight,
  computeOpenNow,
} from "@/lib/store-hours";
import { Toggle } from "@/components/ui/Toggle";
import { ConfigSection, SaveButton } from "./ConfigSection";

interface DayState {
  enabled: boolean;
  open: string;
  close: string;
}

interface Props {
  config: StoreConfigFull;
  showToast: (msg: string, type?: "success" | "error") => void;
}

function initDays(config: StoreConfigFull): DayState[] {
  return WEEKDAY_KEYS.map((k) => {
    const range = splitRange(config[k]);
    return range
      ? { enabled: true, open: range[0], close: range[1] }
      : { enabled: false, open: "18:00", close: "23:00" };
  });
}

export function OpeningHoursSection({ config, showToast }: Props) {
  const [days, setDays] = useState<DayState[]>(() => initDays(config));
  const [saving, setSaving] = useState(false);

  function patchDay(i: number, patch: Partial<DayState>) {
    setDays((prev) => prev.map((d, idx) => (idx === i ? { ...d, ...patch } : d)));
  }

  function copyFromAbove(i: number) {
    if (i === 0) return;
    setDays((prev) => prev.map((d, idx) => (idx === i ? { ...prev[i - 1] } : d)));
  }

  // Chip "Agora: Aberto/Fechado" — reconstroi os 7 campos do estado do form.
  const openStatus = useMemo(() => {
    const hours: Record<string, string | null> = {};
    WEEKDAY_KEYS.forEach((k, i) => {
      hours[k] = days[i].enabled ? `${days[i].open}-${days[i].close}` : null;
    });
    return computeOpenNow(hours);
  }, [days]);

  async function handleSave(e: React.FormEvent) {
    e.preventDefault();
    if (saving) return;
    setSaving(true);
    try {
      const body: Record<string, unknown> = { autoAcceptOrders: config.autoAcceptOrders };
      WEEKDAY_KEYS.forEach((k, i) => {
        body[k] = days[i].enabled ? `${days[i].open}-${days[i].close}` : null;
      });
      await api.patch("/config", body);
      showToast("Horarios salvos", "success");
    } catch (err) {
      showToast(err instanceof ApiError ? err.message : "Erro ao salvar horarios.", "error");
    } finally {
      setSaving(false);
    }
  }

  return (
    <ConfigSection
      id="horarios"
      title="Horario de funcionamento"
      description='Define o selo "Aberto/Fechado" do cardapio publico.'
    >
      {/* Chip de status vivo */}
      <div className="mb-4" aria-live="polite">
        <span
          className={
            "inline-flex items-center gap-2 rounded-full px-3 py-1 text-sm font-medium " +
            (openStatus?.open
              ? "bg-success-light text-success-dark"
              : "bg-bg-tertiary text-text-secondary")
          }
        >
          <span
            className={
              "h-2 w-2 rounded-full " + (openStatus?.open ? "bg-success-dark" : "bg-text-muted")
            }
            aria-hidden="true"
          />
          {openStatus?.open
            ? `Agora: Aberto ate ${openStatus.closesAt}`
            : "Agora: Fechado"}
        </span>
      </div>

      <form onSubmit={(e) => void handleSave(e)}>
        <ul className="flex flex-col gap-3">
          {WEEKDAY_KEYS.map((k, i) => {
            const d = days[i];
            const madrugada = d.enabled && crossesMidnight(d.open, d.close);
            return (
              <li
                key={k}
                className="flex flex-col gap-2 rounded-xl border border-border-light p-3 sm:flex-row sm:flex-wrap sm:items-center sm:gap-4"
              >
                <span className="w-24 text-sm font-semibold text-text-primary">
                  {WEEKDAY_LABELS[k]}
                </span>
                <div className="flex items-center gap-2">
                  <Toggle
                    id={`day-${i}`}
                    checked={d.enabled}
                    onChange={(next) => patchDay(i, { enabled: next })}
                    label={`${WEEKDAY_LABELS[k]} ${d.enabled ? "aberto" : "fechado"}`}
                  />
                  <span className="text-sm text-text-secondary">
                    {d.enabled ? "Aberto" : "Fechado"}
                  </span>
                </div>

                {d.enabled ? (
                  <div className="flex items-center gap-2">
                    <input
                      type="time"
                      className="input-field w-28"
                      value={d.open}
                      onChange={(e) => patchDay(i, { open: e.target.value })}
                      aria-label={`${WEEKDAY_LABELS[k]}: abertura`}
                    />
                    <span className="text-sm text-text-muted">as</span>
                    <input
                      type="time"
                      className="input-field w-28"
                      value={d.close}
                      onChange={(e) => patchDay(i, { close: e.target.value })}
                      aria-label={`${WEEKDAY_LABELS[k]}: fechamento`}
                    />
                  </div>
                ) : (
                  <span className="text-sm text-text-muted">— fechado o dia todo —</span>
                )}

                {madrugada && (
                  <span className="inline-flex items-center gap-1 text-xs text-text-secondary">
                    <Info className="h-3.5 w-3.5" aria-hidden="true" /> vira a madrugada
                  </span>
                )}

                {i > 0 && (
                  <button
                    type="button"
                    className="ml-auto inline-flex min-h-11 items-center gap-1 text-xs text-primary-700"
                    onClick={() => copyFromAbove(i)}
                  >
                    <Copy className="h-3.5 w-3.5" aria-hidden="true" /> Copiar de cima
                  </button>
                )}
              </li>
            );
          })}
        </ul>

        <p className="mt-4 inline-flex items-start gap-1 text-xs text-text-secondary">
          <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
          Fechamento menor que a abertura significa que a loja vira a madrugada (ex.: 18:00 as 02:00).
        </p>

        <div className="mt-5 flex justify-end">
          <SaveButton saving={saving} label="Salvar horarios" />
        </div>
      </form>
    </ConfigSection>
  );
}
