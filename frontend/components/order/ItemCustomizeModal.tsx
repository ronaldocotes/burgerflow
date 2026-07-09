"use client";

// Modal de personalização de item (tamanho + meia-a-meia + borda + massa +
// complementos). Movido de app/pdv/page.tsx (ItemModal → ItemCustomizeModal,
// extração behavior-preserving, fatia 1).

import { useRef, useState } from "react";
import { useModalA11y } from "@/lib/use-modal-a11y";
import {
  CRUST_LABELS,
  DOUGH_TYPES,
  Product,
  ProductOptionGroup,
  formatBRL,
} from "@/types/menu";
import { CartLine, OrderItemInput } from "@/types/cart";
import { Variations } from "./types";
import { ChoiceButton, Section } from "./CustomizePrimitives";

export function ItemCustomizeModal({
  product,
  variations,
  onClose,
  onAdd,
}: {
  product: Product;
  variations: Variations;
  onClose: () => void;
  onAdd: (line: CartLine) => void;
}) {
  const dialogRef = useRef<HTMLDivElement>(null);
  useModalA11y(dialogRef, onClose);

  const { groups, sizes, flavors, crusts } = variations;
  const isPizza = sizes.length > 0 || flavors.length > 0 || crusts.length > 0;

  const [sizeId, setSizeId] = useState<string | null>(null);
  const [flavor1Id, setFlavor1Id] = useState<string | null>(null);
  const [flavor2Id, setFlavor2Id] = useState<string | null>(null);
  const [crustType, setCrustType] = useState<string | null>(null); // null = sem borda
  const [doughType, setDoughType] = useState<string | null>(null);
  // Opções marcadas por grupo: groupId -> Set<optionId>
  const [selected, setSelected] = useState<Record<string, string[]>>({});
  const [quantity, setQuantity] = useState(1);
  const [notes, setNotes] = useState("");

  function toggleOption(group: ProductOptionGroup, optionId: string) {
    setSelected((prev) => {
      const current = prev[group.id] ?? [];
      const has = current.includes(optionId);
      let next: string[];
      if (has) {
        next = current.filter((id) => id !== optionId);
      } else if (group.maxSelect === 1) {
        next = [optionId]; // single-select: substitui
      } else if (current.length >= group.maxSelect) {
        return prev; // atingiu o máximo
      } else {
        next = [...current, optionId];
      }
      return { ...prev, [group.id]: next };
    });
  }

  // Validação (espelha o servidor, que é a fonte da verdade).
  const errors: string[] = [];
  if (sizes.length > 0 && !sizeId) errors.push("Escolha o tamanho.");
  if (flavors.length > 0 && !flavor1Id) errors.push("Escolha o sabor.");
  if (flavor2Id && flavor2Id === flavor1Id)
    errors.push("O 2º sabor deve ser diferente do 1º.");
  for (const g of groups) {
    const count = (selected[g.id] ?? []).length;
    const min = g.required ? Math.max(1, g.minSelect) : g.minSelect;
    if (count < min)
      errors.push(
        `“${g.name}”: escolha ${min === g.maxSelect ? min : `pelo menos ${min}`}.`,
      );
  }
  const valid = errors.length === 0;

  function buildLine(): CartLine {
    const optionIds = groups.flatMap((g) => selected[g.id] ?? []);
    const item: OrderItemInput = {
      productId: product.id,
      quantity,
      sizeId: sizeId ?? undefined,
      flavor1Id: flavor1Id ?? undefined,
      flavor2Id: flavor2Id ?? undefined,
      crustType: crustType ?? undefined,
      doughType: doughType ?? undefined,
      optionIds: optionIds.length > 0 ? optionIds : undefined,
      notes: notes.trim() || undefined,
    };

    const parts: string[] = [];
    const size = sizes.find((s) => s.id === sizeId);
    if (size) parts.push(size.name);
    const f1 = flavors.find((f) => f.id === flavor1Id);
    const f2 = flavors.find((f) => f.id === flavor2Id);
    if (f1) parts.push(f2 ? `${f1.name} / ${f2.name}` : f1.name);
    if (crustType) parts.push(`Borda ${CRUST_LABELS[crustType] ?? crustType}`);
    if (doughType)
      parts.push(
        `Massa ${DOUGH_TYPES.find((d) => d.value === doughType)?.label ?? doughType}`,
      );
    for (const g of groups) {
      const names = (selected[g.id] ?? [])
        .map((id) => g.options.find((o) => o.id === id)?.name)
        .filter(Boolean);
      parts.push(...(names as string[]));
    }

    if (notes.trim()) parts.push(`Obs: ${notes.trim()}`);
    return {
      lineId: crypto.randomUUID(),
      productId: product.id,
      productName: product.name,
      quantity,
      item,
      label: parts.join(" · "),
    };
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-end sm:items-center justify-center bg-black/50 p-0 sm:p-4"
      onClick={onClose}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-label={`Personalizar ${product.name}`}
        className="bg-bg-primary w-full sm:max-w-lg max-h-[90vh] flex flex-col rounded-t-2xl sm:rounded-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="p-5 border-b border-border-light flex items-center justify-between">
          <h2 className="text-lg font-bold text-text-primary">{product.name}</h2>
          <button
            type="button"
            aria-label="Fechar"
            onClick={onClose}
            className="text-text-muted hover:text-text-primary text-xl leading-none"
          >
            ✕
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-5 space-y-5">
          {/* Tamanho */}
          {sizes.length > 0 && (
            <Section title="Tamanho" required>
              <div className="grid grid-cols-2 gap-2">
                {sizes.map((s) => (
                  <ChoiceButton
                    key={s.id}
                    selected={sizeId === s.id}
                    onClick={() => setSizeId(s.id)}
                    label={s.name}
                    priceCents={s.promoPriceCents ?? s.priceCents}
                  />
                ))}
              </div>
            </Section>
          )}

          {/* Sabores (meia a meia) */}
          {flavors.length > 0 && (
            <>
              <Section title="Sabor" required>
                <select
                  className="input-field w-full"
                  value={flavor1Id ?? ""}
                  onChange={(e) => setFlavor1Id(e.target.value || null)}
                  aria-label="Sabor 1"
                >
                  <option value="">Selecione…</option>
                  {flavors.map((f) => (
                    <option key={f.id} value={f.id}>
                      {f.name}
                    </option>
                  ))}
                </select>
              </Section>
              <Section title="2º sabor (meia a meia)">
                <select
                  className="input-field w-full"
                  value={flavor2Id ?? ""}
                  onChange={(e) => setFlavor2Id(e.target.value || null)}
                  aria-label="Segundo sabor"
                >
                  <option value="">Apenas 1 sabor</option>
                  {flavors.map((f) => (
                    <option key={f.id} value={f.id}>
                      {f.name}
                    </option>
                  ))}
                </select>
              </Section>
            </>
          )}

          {/* Borda */}
          {crusts.length > 0 && (
            <Section title="Borda">
              <div className="grid grid-cols-2 gap-2">
                <ChoiceButton
                  selected={crustType === null}
                  onClick={() => setCrustType(null)}
                  label="Sem borda"
                />
                {crusts.map((c) => (
                  <ChoiceButton
                    key={c.id}
                    selected={crustType === c.crustType}
                    onClick={() => setCrustType(c.crustType)}
                    label={CRUST_LABELS[c.crustType] ?? c.crustType}
                    priceCents={c.priceCents}
                  />
                ))}
              </div>
            </Section>
          )}

          {/* Massa */}
          {isPizza && (
            <Section title="Massa">
              <div className="grid grid-cols-2 gap-2">
                <ChoiceButton
                  selected={doughType === null}
                  onClick={() => setDoughType(null)}
                  label="Padrão"
                />
                {DOUGH_TYPES.map((d) => (
                  <ChoiceButton
                    key={d.value}
                    selected={doughType === d.value}
                    onClick={() => setDoughType(d.value)}
                    label={d.label}
                  />
                ))}
              </div>
            </Section>
          )}

          {/* Complementos */}
          {groups.map((g) => {
            const count = (selected[g.id] ?? []).length;
            const atMax = count >= g.maxSelect;
            return (
              <Section
                key={g.id}
                title={g.name}
                required={g.required}
                hint={
                  g.maxSelect > 1
                    ? `${count}/${g.maxSelect}${
                        atMax
                          ? " — máximo"
                          : g.minSelect > 0
                            ? ` (mín. ${g.minSelect})`
                            : ""
                      }`
                    : undefined
                }
              >
                <div className="space-y-1">
                  {g.options
                    .filter((o) => o.active)
                    .map((o) => {
                      const checked = (selected[g.id] ?? []).includes(o.id);
                      const disabled = !checked && atMax && g.maxSelect > 1;
                      return (
                        <label
                          key={o.id}
                          className={`flex items-center justify-between gap-2 p-2 rounded-md border cursor-pointer ${
                            checked
                              ? "border-primary-600 bg-primary-50"
                              : "border-border-light"
                          } ${disabled ? "opacity-50 cursor-not-allowed" : ""}`}
                        >
                          <span className="flex items-center gap-2 text-sm text-text-primary">
                            <input
                              type="checkbox"
                              checked={checked}
                              disabled={disabled}
                              onChange={() => toggleOption(g, o.id)}
                              className="accent-primary-600"
                            />
                            {o.name}
                          </span>
                          {o.priceCents > 0 && (
                            <span className="text-xs text-text-muted">
                              + {formatBRL(o.priceCents)}
                            </span>
                          )}
                        </label>
                      );
                    })}
                </div>
              </Section>
            );
          })}
        </div>

        {/* Observação por item */}
        <div className="px-5 pb-3">
          <label className="mb-1 block text-xs font-medium text-text-secondary" htmlFor="item-notes">
            Observação (opcional)
          </label>
          <input
            id="item-notes"
            type="text"
            className="input-field w-full text-sm"
            placeholder="Ex.: sem cebola, bem passado…"
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            maxLength={200}
          />
        </div>

        {/* Rodapé: quantidade + adicionar */}
        <div className="p-5 border-t border-border-light space-y-3">
          {!valid && (
            <p className="text-xs text-error" role="alert">
              {errors[0]}
            </p>
          )}
          <div className="flex items-center gap-3">
            <div className="flex items-center gap-1">
              <button
                type="button"
                aria-label="Diminuir quantidade"
                onClick={() => setQuantity((q) => Math.max(1, q - 1))}
                className="w-9 h-9 rounded-md bg-bg-tertiary text-text-primary font-bold hover:bg-border-light"
              >
                −
              </button>
              <span
                className="w-8 text-center font-semibold"
                aria-label={`Quantidade ${quantity}`}
              >
                {quantity}
              </span>
              <button
                type="button"
                aria-label="Aumentar quantidade"
                onClick={() => setQuantity((q) => Math.min(999, q + 1))}
                className="w-9 h-9 rounded-md bg-bg-tertiary text-text-primary font-bold hover:bg-border-light"
              >
                +
              </button>
            </div>
            <button
              type="button"
              className="btn-primary flex-1"
              disabled={!valid}
              onClick={() => onAdd(buildLine())}
            >
              Adicionar
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
