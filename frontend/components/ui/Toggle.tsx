"use client";

// Switch acessivel (WAI-ARIA role="switch"). Promovido de app/configuracoes/page.tsx
// para reuso em Minha Loja. Hitbox 44px (h-11), estado tambem exposto por aria-checked;
// o TEXTO de estado ("Ativa/Inativa") fica a cargo de quem usa (WCAG 1.4.1 — nunca so cor).

interface ToggleProps {
  id: string;
  checked: boolean;
  disabled?: boolean;
  onChange: (next: boolean) => void;
  /** Rotulo acessivel; deve descrever o estado atual (ex.: "Pix ativa"). */
  label: string;
}

export function Toggle({ id, checked, disabled, onChange, label }: ToggleProps) {
  return (
    <button
      id={id}
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={[
        "relative inline-flex h-11 w-12 shrink-0 cursor-pointer items-center rounded-full",
        "transition-colors duration-200 focus-visible:outline-none",
        "focus-visible:ring-2 focus-visible:ring-primary-700 focus-visible:ring-offset-2",
        "disabled:cursor-not-allowed disabled:opacity-50",
        checked ? "bg-primary-700" : "bg-bg-tertiary border border-border-medium",
      ].join(" ")}
    >
      <span
        className={[
          "inline-block h-5 w-5 rounded-full bg-white shadow-card",
          "transform transition-transform duration-200",
          checked ? "translate-x-6" : "translate-x-1",
        ].join(" ")}
        aria-hidden="true"
      />
    </button>
  );
}
