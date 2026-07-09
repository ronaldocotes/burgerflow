"use client";

// Blocos reutilizados pelo ItemCustomizeModal (Section = grupo com título/hint,
// ChoiceButton = botão de escolha única tipo pill). Movidos de app/pdv/page.tsx
// (extração behavior-preserving, fatia 1).

import { useId } from "react";
import { formatBRL } from "@/types/menu";

export function Section({
  title,
  required,
  hint,
  children,
}: {
  title: string;
  required?: boolean;
  hint?: string;
  children: React.ReactNode;
}) {
  const titleId = useId();
  return (
    <div role="group" aria-labelledby={titleId}>
      <div className="flex items-baseline justify-between mb-2">
        <h3
          id={titleId}
          className="text-sm font-semibold text-text-primary"
        >
          {title}
          {required && (
            <span className="text-error ml-1" aria-hidden="true">
              *
            </span>
          )}
          {required && <span className="sr-only">(obrigatório)</span>}
        </h3>
        {hint && <span className="text-xs text-text-muted">{hint}</span>}
      </div>
      {children}
    </div>
  );
}

export function ChoiceButton({
  selected,
  onClick,
  label,
  priceCents,
}: {
  selected: boolean;
  onClick: () => void;
  label: string;
  priceCents?: number;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={selected}
      className={`text-sm py-2 px-3 rounded-md border text-left transition-colors ${
        selected
          ? "bg-primary-700 text-white border-primary-700"
          : "bg-bg-secondary text-text-secondary border-border-light hover:bg-bg-tertiary"
      }`}
    >
      <span className="font-medium">{label}</span>
      {priceCents != null && priceCents > 0 && (
        <span
          className={`block text-xs ${selected ? "text-white" : "text-text-muted"}`}
        >
          {formatBRL(priceCents)}
        </span>
      )}
    </button>
  );
}
