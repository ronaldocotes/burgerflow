"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Copy, ExternalLink, QrCode, Plus } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import { getTenant } from "@/lib/auth";
import { slugify, isValidSlug } from "@/lib/slug";
import { useModalA11y } from "@/lib/use-modal-a11y";
import type { MenuLink, MenuLinkVariant } from "@/types/store-config";
import type { TableDto } from "@/types/tables";
import { Toggle } from "@/components/ui/Toggle";
import { QrLinkModal } from "@/components/QrLinkModal";
import { ConfigSection, SectionRetry } from "./ConfigSection";

interface Props {
  showToast: (msg: string, type?: "success" | "error") => void;
}

const APP_URL = process.env.NEXT_PUBLIC_APP_URL ?? "http://localhost:3000";

const VARIANTS: { value: MenuLinkVariant; title: string; hint: string }[] = [
  { value: "FULL", title: "Cardapio completo", hint: "Cliente pede e paga, com entrega." },
  { value: "VIEW_ONLY", title: "So visualizacao", hint: "Vitrine/parede, sem pedido." },
  { value: "COUNTER", title: "Balcao ou mesa", hint: "Pedido no local, sem entrega." },
];

function badgeClass(v: MenuLinkVariant): string {
  if (v === "FULL") return "bg-success-light text-success-dark";
  if (v === "COUNTER") return "bg-info-light text-info-dark";
  return "bg-bg-tertiary text-text-secondary";
}

function badgeLabel(v: MenuLinkVariant, tableLabel?: string | null): string {
  if (v === "FULL") return "Cardapio completo";
  if (v === "VIEW_ONLY") return "So visualizacao";
  return tableLabel ? `Balcao · Mesa ${tableLabel}` : "Balcao";
}

function linkUrl(tenant: string, slug: string): string {
  return `${APP_URL}/l/${tenant}/${slug}`;
}

export function MenuLinksSection({ showToast }: Props) {
  const [state, setState] = useState<"loading" | "error" | "ok">("loading");
  const [links, setLinks] = useState<MenuLink[]>([]);
  const [tables, setTables] = useState<TableDto[]>([]);
  const [modal, setModal] = useState<{ mode: "new" } | { mode: "edit"; link: MenuLink } | null>(null);
  const [qr, setQr] = useState<{ url: string; title: string } | null>(null);
  const tenant = getTenant() ?? process.env.NEXT_PUBLIC_TENANT_SLUG ?? "demo";

  const load = useCallback(async () => {
    setState("loading");
    try {
      const [ls, ts] = await Promise.all([
        api.get<MenuLink[]>("/config/menu-links"),
        api.get<TableDto[]>("/tables").catch(() => [] as TableDto[]),
      ]);
      setLinks(ls);
      setTables(ts);
      setState("ok");
    } catch {
      setState("error");
    }
  }, []);

  useEffect(() => {
    queueMicrotask(() => void load());
  }, [load]);

  function tableLabelOf(id: string | null): string | null {
    if (!id) return null;
    return tables.find((t) => t.id === id)?.label ?? null;
  }

  async function copy(url: string) {
    try {
      await navigator.clipboard.writeText(url);
      showToast("Link copiado", "success");
    } catch {
      showToast("Nao foi possivel copiar", "error");
    }
  }

  async function toggleActive(link: MenuLink) {
    if (!link.id) return;
    try {
      const updated = await api.put<MenuLink>(`/config/menu-links/${link.id}`, {
        slug: link.slug,
        variant: link.variant,
        label: link.label,
        tableId: link.tableId,
        active: !link.active,
      });
      setLinks((prev) => prev.map((l) => (l.id === link.id ? updated : l)));
    } catch (err) {
      showToast(err instanceof ApiError ? err.message : "Erro ao alterar o link.", "error");
    }
  }

  async function onSaved() {
    setModal(null);
    await load();
  }

  return (
    <ConfigSection
      id="links"
      title="Links e QR do cardapio"
      description="Cada link mostra o mesmo cardapio de um jeito: com pedido, so vitrine, ou pedido de balcao/mesa."
    >
      <div className="mb-4 flex justify-end">
        <button
          type="button"
          className="btn-primary inline-flex min-h-11 items-center gap-2"
          onClick={() => setModal({ mode: "new" })}
        >
          <Plus className="h-4 w-4" aria-hidden="true" /> Novo link
        </button>
      </div>

      {state === "loading" && (
        <div className="animate-pulse space-y-3" aria-busy="true" aria-label="Carregando links">
          <div className="h-24 rounded-xl bg-bg-tertiary" />
          <div className="h-24 rounded-xl bg-bg-tertiary" />
        </div>
      )}

      {state === "error" && <SectionRetry onRetry={() => void load()} />}

      {state === "ok" && links.length === 0 && (
        <div className="empty-state">
          <p className="empty-state-title">Nenhum link ainda</p>
          <p className="empty-state-description">
            Crie links e QRs do seu cardapio: um para delivery, um para a vitrine, um por mesa.
          </p>
          <button className="btn-primary mt-3 min-h-11" onClick={() => setModal({ mode: "new" })}>
            + Criar primeiro link
          </button>
        </div>
      )}

      {state === "ok" && links.length > 0 && (
        <ul className="flex flex-col gap-3">
          {links.map((l) => {
            const url = linkUrl(tenant, l.slug);
            return (
              <li key={l.id} className={"rounded-xl border border-border-light p-4" + (l.active ? "" : " opacity-60")}>
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="truncate font-semibold text-text-primary">{l.label}</p>
                    <span
                      className={"mt-1 inline-block rounded-full px-2 py-0.5 text-xs font-medium " + badgeClass(l.variant)}
                    >
                      {badgeLabel(l.variant, tableLabelOf(l.tableId))}
                    </span>
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="text-xs text-text-secondary">{l.active ? "Ativo" : "Inativo"}</span>
                    <Toggle
                      id={`link-${l.id}`}
                      checked={l.active}
                      onChange={() => void toggleActive(l)}
                      label={`${l.label} ${l.active ? "ativo" : "inativo"}`}
                    />
                  </div>
                </div>

                <p className="mt-2 truncate font-mono text-sm text-text-secondary" title={url}>
                  {url}
                </p>

                <div className="mt-3 flex flex-wrap gap-2">
                  <button type="button" className="btn-outline inline-flex min-h-11 items-center gap-1 text-sm" onClick={() => void copy(url)}>
                    <Copy className="h-4 w-4" aria-hidden="true" /> Copiar
                  </button>
                  <a href={url} target="_blank" rel="noreferrer" className="btn-outline inline-flex min-h-11 items-center gap-1 text-sm">
                    <ExternalLink className="h-4 w-4" aria-hidden="true" /> Abrir
                  </a>
                  <button type="button" className="btn-outline inline-flex min-h-11 items-center gap-1 text-sm" onClick={() => setQr({ url, title: l.label })}>
                    <QrCode className="h-4 w-4" aria-hidden="true" /> Ver QR
                  </button>
                  <button type="button" className="inline-flex min-h-11 items-center px-2 text-sm text-primary-700" onClick={() => setModal({ mode: "edit", link: l })}>
                    Editar
                  </button>
                </div>
              </li>
            );
          })}
        </ul>
      )}

      {modal && (
        <LinkModal
          mode={modal.mode}
          link={modal.mode === "edit" ? modal.link : undefined}
          tables={tables}
          tenant={tenant}
          showToast={showToast}
          onClose={() => setModal(null)}
          onSaved={() => void onSaved()}
        />
      )}

      {qr && <QrLinkModal url={qr.url} title={qr.title} onClose={() => setQr(null)} />}
    </ConfigSection>
  );
}

// ── Modal Novo/Editar link ──────────────────────────────────────────────────────

interface ModalProps {
  mode: "new" | "edit";
  link?: MenuLink;
  tables: TableDto[];
  tenant: string;
  showToast: (msg: string, type?: "success" | "error") => void;
  onClose: () => void;
  onSaved: () => void;
}

function LinkModal({ mode, link, tables, tenant, showToast, onClose, onSaved }: ModalProps) {
  const ref = useRef<HTMLDivElement>(null);
  useModalA11y(ref as React.RefObject<HTMLElement>, onClose);

  const [variant, setVariant] = useState<MenuLinkVariant>(link?.variant ?? "FULL");
  const [label, setLabel] = useState(link?.label ?? "");
  const [slug, setSlug] = useState(link?.slug ?? "");
  const [slugTouched, setSlugTouched] = useState(mode === "edit");
  const [tableId, setTableId] = useState<string | null>(link?.tableId ?? null);
  const [saving, setSaving] = useState(false);
  const [slugConfirmed, setSlugConfirmed] = useState(false);

  // Auto-slug a partir do label enquanto o usuario nao editar o slug manualmente.
  function onLabelChange(v: string) {
    setLabel(v);
    if (!slugTouched) setSlug(slugify(v));
  }

  const slugOk = isValidSlug(slug);
  const counterNeedsTable = variant === "COUNTER" && !tableId;
  const slugChangedOnActive =
    mode === "edit" && link?.active === true && slug !== link.slug;
  const canSave =
    label.trim().length > 0 &&
    slugOk &&
    !counterNeedsTable &&
    (!slugChangedOnActive || slugConfirmed) &&
    !saving;

  async function save(e: React.FormEvent) {
    e.preventDefault();
    if (!canSave) return;
    setSaving(true);
    const body = {
      slug,
      variant,
      label: label.trim(),
      tableId: variant === "COUNTER" ? tableId : null,
      active: link?.active ?? true,
    };
    try {
      if (mode === "edit" && link?.id) {
        await api.put(`/config/menu-links/${link.id}`, body);
      } else {
        await api.post("/config/menu-links", body);
      }
      showToast("Link salvo", "success");
      onSaved();
    } catch (err) {
      showToast(err instanceof ApiError ? err.message : "Erro ao salvar o link.", "error");
      setSaving(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4" aria-modal="true">
      <div
        ref={ref}
        role="dialog"
        aria-labelledby="link-modal-title"
        className="max-h-[90vh] w-full max-w-md overflow-y-auto rounded-2xl bg-bg-primary p-6 shadow-dropdown"
      >
        <h2 id="link-modal-title" className="mb-4 text-lg font-semibold text-text-primary">
          {mode === "edit" ? "Editar link" : "Novo link"}
        </h2>

        <form onSubmit={(e) => void save(e)} className="space-y-4">
          {/* Variante em radio-cards */}
          <fieldset>
            <legend className="mb-2 text-sm font-semibold text-text-primary">Tipo de link</legend>
            <div className="flex flex-col gap-2">
              {VARIANTS.map((v) => (
                <label
                  key={v.value}
                  className={
                    "flex cursor-pointer items-start gap-3 rounded-xl border p-3 " +
                    (variant === v.value ? "border-primary-700 bg-success-light" : "border-border-light")
                  }
                >
                  <input
                    type="radio"
                    name="variant"
                    className="mt-1 h-4 w-4"
                    checked={variant === v.value}
                    onChange={() => setVariant(v.value)}
                  />
                  <span>
                    <span className="block text-sm font-semibold text-text-primary">{v.title}</span>
                    <span className="block text-xs text-text-secondary">{v.hint}</span>
                  </span>
                </label>
              ))}
            </div>
          </fieldset>

          {/* Mesa (so COUNTER) */}
          {variant === "COUNTER" && (
            <div className="form-group">
              <label className="form-label" htmlFor="link-table">
                Mesa <span className="text-error" aria-hidden="true">*</span>
                <span className="sr-only">(obrigatorio)</span>
              </label>
              <select
                id="link-table"
                className="input-field"
                value={tableId ?? ""}
                onChange={(e) => setTableId(e.target.value || null)}
              >
                <option value="">Selecione a mesa</option>
                {tables.map((t) => (
                  <option key={t.id} value={t.id}>
                    Mesa {t.label}
                  </option>
                ))}
              </select>
              {counterNeedsTable && (
                <p className="mt-1 text-xs text-error" role="alert">
                  Escolha uma mesa para o link de balcao.
                </p>
              )}
            </div>
          )}

          {/* Label */}
          <div className="form-group">
            <label className="form-label" htmlFor="link-label">
              Nome do link <span className="text-error" aria-hidden="true">*</span>
              <span className="sr-only">(obrigatorio)</span>
            </label>
            <input
              id="link-label"
              type="text"
              className="input-field"
              maxLength={80}
              value={label}
              onChange={(e) => onLabelChange(e.target.value)}
              placeholder="Ex: Delivery e retirada"
            />
          </div>

          {/* Slug */}
          <div className="form-group">
            <label className="form-label" htmlFor="link-slug">
              Endereco (slug) <span className="text-error" aria-hidden="true">*</span>
              <span className="sr-only">(obrigatorio)</span>
            </label>
            <input
              id="link-slug"
              type="text"
              className={"input-field" + (slug && !slugOk ? " border-red-500" : "")}
              maxLength={60}
              value={slug}
              onChange={(e) => {
                setSlugTouched(true);
                setSlug(e.target.value);
                setSlugConfirmed(false);
              }}
              placeholder="pedir"
              aria-invalid={!!slug && !slugOk}
            />
            <p className="mt-1 break-all font-mono text-xs text-text-muted">
              {linkUrl(tenant, slug || "seu-slug")}
            </p>
            {slug && !slugOk && (
              <p className="mt-1 text-xs text-error" role="alert">
                Use 2 a 60 caracteres, minusculos, numeros e hifens (ex.: mesa-4).
              </p>
            )}
          </div>

          {/* Aviso de troca de slug em link ativo (QR impresso quebra) */}
          {slugChangedOnActive && (
            <label className="flex items-start gap-2 rounded-xl bg-warning-light p-3 text-xs text-warning-dark">
              <input
                type="checkbox"
                className="mt-0.5 h-4 w-4"
                checked={slugConfirmed}
                onChange={(e) => setSlugConfirmed(e.target.checked)}
              />
              QRs ja impressos com o endereco antigo vao parar de funcionar. Alterar mesmo assim.
            </label>
          )}

          <div className="flex justify-end gap-3 pt-2">
            <button type="button" className="btn-outline min-h-11" onClick={onClose}>
              Cancelar
            </button>
            <button type="submit" className="btn-primary min-h-11 disabled:opacity-50" disabled={!canSave}>
              {saving ? "Salvando..." : "Salvar"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
