"use client";

import { useState } from "react";
import { api } from "@/lib/api";
import { ConfigSection, SaveButton } from "@/components/config/ConfigSection";
import { Toggle } from "@/components/ui/Toggle";
import type { ThemeConfig, ThemeDraft, ThemePatch } from "@/types/personalization";
import {
  DEFAULT_PRIMARY,
  THEME_PRESETS,
  computeContrast,
  isValidHex,
} from "@/lib/contrast";

// Seção "Aparência" (issue #12): cor principal (picker nativo + hex + 8 presets),
// card de legibilidade WCAG ao vivo e os 3 toggles de exibição. Controlado pelo
// pai (value/onChange) para alimentar o preview vivo; o save (PATCH /config parcial)
// mora aqui. autoAcceptOrders é obrigatório no PATCH → passthrough.

interface Props {
  value: ThemeDraft;
  onChange: (next: ThemeDraft) => void;
  dirty: boolean;
  autoAcceptOrders: boolean;
  onSaved: (cfg: ThemeConfig) => void;
  showToast: (msg: string, type?: "success" | "error") => void;
}

// Formata um número com 1 casa e vírgula decimal PT-BR (ex.: 5,5).
function ratioLabel(v: number): string {
  return v.toFixed(1).replace(".", ",");
}

export function ThemeSection({ value, onChange, dirty, autoAcceptOrders, onSaved, showToast }: Props) {
  const [saving, setSaving] = useState(false);

  // Cor efetiva mostrada no picker/contraste: o valor digitado se for hex válido,
  // senão o default do sistema (estado "sem cor" → verde MenuFlow).
  const effectiveColor = isValidHex(value.primaryColor) ? value.primaryColor : DEFAULT_PRIMARY;
  const usingDefault = !isValidHex(value.primaryColor) || value.primaryColor === "";
  const contrast = computeContrast(effectiveColor)!; // effectiveColor é sempre válido
  const hexInvalid = value.primaryColor !== "" && !isValidHex(value.primaryColor);

  function setColor(hex: string) {
    onChange({ ...value, primaryColor: hex });
  }

  function onHexInput(raw: string) {
    // Aceita colar "047857" ou "#047857"; força "#" inicial; limita a 7 chars.
    let v = raw.trim();
    if (v && !v.startsWith("#")) v = "#" + v;
    v = v.slice(0, 7);
    setColor(v);
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (hexInvalid || saving) return;
    setSaving(true);
    try {
      const patch: ThemePatch = {
        autoAcceptOrders,
        // "" limpa a cor (volta ao default). Hex válido é enviado normalizado.
        themePrimaryColor: usingDefault ? "" : value.primaryColor.toUpperCase(),
        themeShowPrices: value.showPrices,
        themeShowDescriptions: value.showDescriptions,
        themeShowPhotos: value.showPhotos,
      };
      const updated = await api.patch<ThemeConfig>("/config", patch);
      onSaved(updated);
      showToast("Aparência salva — seu cardápio já está com a nova cor.");
    } catch (err) {
      showToast(err instanceof Error ? err.message : "Falha ao salvar a aparência.", "error");
    } finally {
      setSaving(false);
    }
  }

  return (
    <ConfigSection
      id="aparencia"
      title="Aparência"
      description="A cor principal do seu cardápio (botões, destaques e realces) e o que o cliente vê."
    >
      <form onSubmit={handleSubmit} className="space-y-6">
        {/* Cor principal: picker nativo + hex + presets */}
        <div>
          <label htmlFor="theme-color" className="block text-sm font-medium text-text-primary">
            Cor principal
          </label>
          <div className="mt-2 flex flex-wrap items-center gap-3">
            {/* Picker nativo com hitbox ampliada (44x44) via label estilizado. */}
            <label
              className="flex h-11 w-11 shrink-0 cursor-pointer items-center justify-center rounded-lg border border-border-medium overflow-hidden"
              style={{ backgroundColor: effectiveColor }}
            >
              <span className="sr-only">Escolher cor no seletor</span>
              <input
                id="theme-color"
                type="color"
                value={effectiveColor}
                onChange={(e) => setColor(e.target.value.toUpperCase())}
                className="h-14 w-14 cursor-pointer opacity-0"
                aria-label="Seletor de cor principal"
              />
            </label>

            <div>
              <label htmlFor="theme-hex" className="sr-only">Código da cor (hexadecimal)</label>
              <input
                id="theme-hex"
                type="text"
                inputMode="text"
                value={value.primaryColor}
                onChange={(e) => onHexInput(e.target.value)}
                placeholder={DEFAULT_PRIMARY}
                aria-invalid={hexInvalid}
                aria-describedby={hexInvalid ? "theme-hex-err" : undefined}
                className={[
                  "input-field w-28 font-mono uppercase",
                  hexInvalid ? "border-red-500" : "",
                ].join(" ")}
              />
            </div>

            <button
              type="button"
              onClick={() => setColor("")}
              className="text-sm font-medium text-text-secondary underline underline-offset-2 hover:text-text-primary min-h-11"
            >
              Restaurar padrão
            </button>
          </div>

          {hexInvalid && (
            <p id="theme-hex-err" className="mt-1 text-sm text-red-600" role="alert">
              Use o formato #RRGGBB.
            </p>
          )}
          {usingDefault && !hexInvalid && (
            <p className="mt-1 text-xs text-text-muted">Padrão MenuFlow ({DEFAULT_PRIMARY}).</p>
          )}

          {/* 8 presets curados (todos ratioOnWhite >= 4.5) */}
          <div className="mt-3 flex flex-wrap gap-2" role="group" aria-label="Cores sugeridas">
            {THEME_PRESETS.map((preset) => {
              const selected = value.primaryColor.toUpperCase() === preset.hex.toUpperCase();
              return (
                <button
                  key={preset.hex}
                  type="button"
                  onClick={() => setColor(preset.hex)}
                  aria-label={preset.name}
                  aria-pressed={selected}
                  title={preset.name}
                  className={[
                    "h-11 w-11 rounded-full border-2 transition-transform",
                    selected ? "border-text-primary scale-110" : "border-border-light",
                  ].join(" ")}
                  style={{ backgroundColor: preset.hex }}
                />
              );
            })}
          </div>
        </div>

        {/* Card de legibilidade (WCAG) — sempre com ícone + TEXTO, nunca só cor. */}
        <div className="rounded-xl border border-border-light bg-bg-secondary p-4">
          <p className="text-sm font-semibold text-text-primary">Legibilidade</p>
          <p role="status" className="mt-1.5 flex items-start gap-2 text-sm text-text-secondary">
            <span aria-hidden="true" className="text-success-dark">✓</span>
            <span>
              Texto {contrast.recommendedTextColor === "#FFFFFF" ? "branco" : "preto"} sobre esta cor:
              contraste {ratioLabel(contrast.recommendedTextColor === "#FFFFFF" ? contrast.ratioOnWhite : contrast.ratioOnBlack)}:1 — passa AA.
            </span>
          </p>
          {contrast.ratioOnWhite < 3.0 && (
            <p
              role="status"
              className="mt-2 flex items-start gap-2 rounded-lg bg-warning-light px-3 py-2 text-sm text-warning-dark"
            >
              <span aria-hidden="true">⚠</span>
              <span>
                Cor muito clara: botões e preços podem sumir no fundo branco do cardápio.
                Considere um tom mais escuro.
              </span>
            </p>
          )}
        </div>

        {/* Toggles de exibição (efeito imediato no preview; persiste só no salvar). */}
        <div>
          <p className="text-sm font-medium text-text-primary">O que o cliente vê</p>
          <div className="mt-2 divide-y divide-border-light rounded-xl border border-border-light">
            {(
              [
                { key: "showPrices", label: "Mostrar preços", hint: "Oculta os preços no cardápio público (o carrinho e o checkout sempre mostram o total)." },
                { key: "showDescriptions", label: "Mostrar descrições", hint: "Oculta as descrições dos produtos no cardápio." },
                { key: "showPhotos", label: "Mostrar fotos", hint: "Oculta as fotos dos produtos (layout compacto)." },
              ] as const
            ).map(({ key, label, hint }) => (
              <div key={key} className="flex items-center justify-between gap-4 p-3">
                <div>
                  <label htmlFor={`toggle-${key}`} className="text-sm font-medium text-text-primary">
                    {label}
                  </label>
                  <p className="text-xs text-text-muted">{hint}</p>
                </div>
                <div className="flex items-center gap-2">
                  <span className="text-xs text-text-muted" aria-hidden="true">
                    {value[key] ? "Sim" : "Não"}
                  </span>
                  <Toggle
                    id={`toggle-${key}`}
                    checked={value[key]}
                    onChange={(next) => onChange({ ...value, [key]: next })}
                    label={`${label}: ${value[key] ? "ativado" : "desativado"}`}
                  />
                </div>
              </div>
            ))}
          </div>
        </div>

        <div className="flex justify-end">
          <SaveButton saving={saving} label={dirty ? "Salvar aparência •" : "Salvar aparência"} disabled={hexInvalid} />
        </div>
      </form>
    </ConfigSection>
  );
}
