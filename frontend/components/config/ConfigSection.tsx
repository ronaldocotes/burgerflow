"use client";

// Casca visual padrao das secoes de Minha Loja: <section aria-labelledby> + h3
// uppercase + card. O id ancora os chips de navegacao (scroll-mt compensa o Topbar sticky).

interface Props {
  id: string;
  title: string;
  description?: string;
  children: React.ReactNode;
}

export function ConfigSection({ id, title, description, children }: Props) {
  const headingId = `secao-${id}`;
  return (
    <section id={id} aria-labelledby={headingId} className="scroll-mt-24">
      <h3
        id={headingId}
        className="mb-3 text-sm font-semibold uppercase tracking-wider text-text-secondary"
      >
        {title}
      </h3>
      <div className="rounded-2xl bg-bg-primary p-6 shadow-card">
        {description && <p className="mb-5 text-sm text-text-secondary">{description}</p>}
        {children}
      </div>
    </section>
  );
}

// Botao de salvar por secao, com spinner e estado "Salvando...".
export function SaveButton({
  saving,
  label,
  disabled,
}: {
  saving: boolean;
  label: string;
  disabled?: boolean;
}) {
  return (
    <button
      type="submit"
      disabled={saving || disabled}
      className="btn-primary flex min-h-11 items-center gap-2 disabled:opacity-50"
    >
      {saving && (
        <span
          className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent"
          aria-hidden="true"
        />
      )}
      {saving ? "Salvando..." : label}
    </button>
  );
}

// Card de erro de carga por secao, com "Tentar novamente".
export function SectionRetry({ onRetry }: { onRetry: () => void }) {
  return (
    <div role="alert" className="flex flex-col items-center gap-3 py-4 text-center">
      <p className="text-sm font-medium text-text-primary">
        Nao foi possivel carregar esta secao.
      </p>
      <button className="btn-outline min-h-11" onClick={onRetry}>
        Tentar novamente
      </button>
    </div>
  );
}
