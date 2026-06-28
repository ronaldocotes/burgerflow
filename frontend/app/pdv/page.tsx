"use client";

import {
  useCallback,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
} from "react";
import { useRouter } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { getToken, logout } from "@/lib/auth";
import { useModalA11y } from "@/lib/use-modal-a11y";
import {
  CRUST_LABELS,
  DOUGH_TYPES,
  Page,
  Product,
  ProductCrustPrice,
  ProductFlavor,
  ProductOptionGroup,
  ProductSize,
  formatBRL,
} from "@/types/menu";
import {
  CartLine,
  OrderCreateInput,
  OrderItemInput,
  OrderType,
  PaymentMethod,
  QuoteRequest,
  QuoteResponse,
} from "@/types/cart";
import LoadingSpinner from "@/components/loading-spinner";

// PDV: grade de produtos -> carrinho (linhas com variação) -> total do servidor
// (POST /orders/quote, casado por índice) -> finalizar (POST /orders + Idempotency-Key).
// Produto com tamanho/sabor/borda/complemento abre o modal de personalização;
// produto simples entra direto no carrinho.

const PAYMENT_LABELS: Record<PaymentMethod, string> = {
  CASH: "Dinheiro",
  CREDIT_CARD: "Crédito",
  DEBIT_CARD: "Débito",
  PIX: "Pix",
  OTHER: "Outro",
};

interface Variations {
  groups: ProductOptionGroup[];
  sizes: ProductSize[];
  flavors: ProductFlavor[];
  crusts: ProductCrustPrice[];
}

function isSimpleLine(item: OrderItemInput): boolean {
  return (
    !item.sizeId &&
    !item.flavor1Id &&
    !item.flavor2Id &&
    !item.crustType &&
    !item.doughType &&
    (item.optionIds?.length ?? 0) === 0
  );
}

export default function PdvPage() {
  const router = useRouter();
  const [products, setProducts] = useState<Product[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState("");

  const [cart, setCart] = useState<CartLine[]>([]);
  const [orderType, setOrderType] = useState<OrderType>("DINE_IN");

  const [quote, setQuote] = useState<QuoteResponse | null>(null);
  const [quoteError, setQuoteError] = useState<string | null>(null);
  const [quoting, setQuoting] = useState(false);

  const [loadingProductId, setLoadingProductId] = useState<string | null>(null);
  const [editing, setEditing] = useState<{
    product: Product;
    variations: Variations;
  } | null>(null);
  const [showPayment, setShowPayment] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const prods = await api.get<Page<Product>>("/products?size=200");
      setProducts(prods.content);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Erro ao carregar produtos.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!getToken()) {
      router.push("/login");
      return;
    }
    queueMicrotask(() => {
      void load();
    });
  }, [router, load]);

  function onLogout() {
    logout();
    router.push("/login");
  }

  // Itens do pedido na ordem das linhas (o backend preserva a ordem → quote casa por índice).
  const items: OrderItemInput[] = useMemo(
    () => cart.map((l) => ({ ...l.item, quantity: l.quantity })),
    [cart],
  );

  // Recota no servidor a cada mudança. O total NUNCA é somado no front.
  const quoteSeq = useRef(0);
  useEffect(() => {
    let cancelled = false;

    if (items.length === 0) {
      quoteSeq.current += 1;
      queueMicrotask(() => {
        if (cancelled) return;
        setQuote(null);
        setQuoteError(null);
        setQuoting(false);
      });
      return () => {
        cancelled = true;
      };
    }

    const seq = ++quoteSeq.current;
    queueMicrotask(() => {
      if (cancelled || seq !== quoteSeq.current) return;
      setQuoting(true);
      setQuoteError(null);
    });
    const body: QuoteRequest = { orderType, items };
    api
      .post<QuoteResponse>("/orders/quote", body)
      .then((res) => {
        if (cancelled || seq !== quoteSeq.current) return;
        setQuote(res);
      })
      .catch((err) => {
        if (cancelled || seq !== quoteSeq.current) return;
        setQuote(null);
        setQuoteError(
          err instanceof ApiError
            ? err.message
            : "Não foi possível calcular o total.",
        );
      })
      .finally(() => {
        if (!cancelled && seq === quoteSeq.current) setQuoting(false);
      });

    return () => {
      cancelled = true;
    };
  }, [items, orderType]);

  // Clicar num produto: descobre variações; se houver, abre o modal; senão entra direto.
  async function onPickProduct(p: Product) {
    if (loadingProductId) return;
    setLoadingProductId(p.id);
    try {
      const [groups, sizes, flavors, crusts] = await Promise.all([
        api.get<ProductOptionGroup[]>(`/products/${p.id}/option-groups`),
        api.get<ProductSize[]>(`/products/${p.id}/sizes`),
        api.get<ProductFlavor[]>(`/products/${p.id}/flavors`),
        api.get<ProductCrustPrice[]>(`/products/${p.id}/crust-prices`),
      ]);
      const variations: Variations = {
        groups: groups.filter((g) => g.active),
        sizes: sizes.filter((s) => s.active),
        flavors: flavors.filter((f) => f.active),
        crusts,
      };
      const hasVariation =
        variations.groups.length > 0 ||
        variations.sizes.length > 0 ||
        variations.flavors.length > 0 ||
        variations.crusts.length > 0;
      if (hasVariation) {
        setEditing({ product: p, variations });
      } else {
        addSimple(p);
      }
    } catch {
      // Se as variações não puderem ser lidas, cai no caminho simples (o quote ainda valida).
      addSimple(p);
    } finally {
      setLoadingProductId(null);
    }
  }

  // Produto simples: se já há uma linha simples do mesmo produto, incrementa.
  function addSimple(p: Product) {
    setCart((prev) => {
      const idx = prev.findIndex(
        (l) => l.productId === p.id && isSimpleLine(l.item),
      );
      if (idx >= 0) {
        const next = [...prev];
        next[idx] = { ...next[idx], quantity: next[idx].quantity + 1 };
        return next;
      }
      return [
        ...prev,
        {
          lineId: crypto.randomUUID(),
          productId: p.id,
          productName: p.name,
          quantity: 1,
          item: { productId: p.id, quantity: 1 },
          label: "",
        },
      ];
    });
  }

  function addCustom(line: CartLine) {
    setCart((prev) => [...prev, line]);
  }

  function setQuantity(lineId: string, quantity: number) {
    setCart((prev) =>
      quantity <= 0
        ? prev.filter((l) => l.lineId !== lineId)
        : prev.map((l) => (l.lineId === lineId ? { ...l, quantity } : l)),
    );
  }

  function clearCart() {
    setCart([]);
    setQuote(null);
    setQuoteError(null);
  }

  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase();
    const visible = products.filter((p) => p.active);
    if (!term) return visible;
    return visible.filter(
      (p) =>
        p.name.toLowerCase().includes(term) ||
        p.sku.toLowerCase().includes(term),
    );
  }, [products, search]);

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-bg-secondary">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  if (error) {
    return (
      <div className="min-h-screen flex flex-col items-center justify-center gap-4 bg-bg-secondary px-4 text-center">
        <p className="text-error font-medium" role="alert">
          {error}
        </p>
        <div className="flex gap-2">
          <button className="btn-primary" onClick={() => void load()}>
            Tentar de novo
          </button>
          <button className="btn-outline" onClick={onLogout}>
            Sair
          </button>
        </div>
      </div>
    );
  }

  return (
    <main className="min-h-screen bg-bg-secondary flex flex-col">
      <div className="flex-1 grid grid-cols-1 lg:grid-cols-[1fr_22rem] gap-0">
        {/* Grade de produtos */}
        <section className="p-4 md:p-6">
          <input
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Buscar produto por nome ou SKU…"
            aria-label="Buscar produto"
            className="input-field mb-4 w-full"
          />
          {filtered.length === 0 ? (
            <div className="empty-state">
              <p className="empty-state-title">Nenhum produto</p>
              <p className="empty-state-description">
                {products.length === 0
                  ? "Cadastre produtos para vender no PDV."
                  : "Nenhum produto corresponde à busca."}
              </p>
            </div>
          ) : (
            <div className="grid grid-cols-2 sm:grid-cols-3 xl:grid-cols-4 gap-3">
              {filtered.map((p) => {
                const unavailable = !p.isAvailable;
                const loadingThis = loadingProductId === p.id;
                return (
                  <button
                    key={p.id}
                    type="button"
                    disabled={unavailable || loadingThis}
                    aria-busy={loadingThis}
                    onClick={() => void onPickProduct(p)}
                    className="pos-product-card text-left disabled:opacity-50 disabled:cursor-not-allowed focus:outline-none focus-visible:ring-2 focus-visible:ring-primary-500"
                  >
                    <div className="p-3">
                      <h3 className="font-semibold text-text-primary leading-tight line-clamp-2">
                        {p.name}
                      </h3>
                      <div className="mt-2 flex items-baseline gap-2">
                        <span className="text-base font-bold text-primary-600">
                          {formatBRL(p.effectivePriceCents)}
                        </span>
                        {p.onPromo && (
                          <span className="text-xs text-text-muted line-through">
                            {formatBRL(p.priceCents)}
                          </span>
                        )}
                      </div>
                      {loadingThis && (
                        <span className="mt-2 inline-block text-xs text-text-muted">
                          Carregando…
                        </span>
                      )}
                      {unavailable && (
                        <span className="mt-2 inline-block text-xs font-bold px-2 py-0.5 rounded-full bg-red-100 text-red-800 border border-red-300">
                          INDISPONÍVEL
                        </span>
                      )}
                    </div>
                  </button>
                );
              })}
            </div>
          )}
        </section>

        {/* Carrinho */}
        <aside className="bg-bg-primary border-t lg:border-t-0 lg:border-l border-border-light flex flex-col lg:sticky lg:top-16 lg:h-[calc(100vh-4rem)]">
          <div className="p-4 border-b border-border-light flex items-center justify-between">
            <h2 className="font-bold text-text-primary">Carrinho</h2>
            {cart.length > 0 && (
              <button
                className="text-sm text-error hover:underline"
                onClick={clearCart}
              >
                Limpar
              </button>
            )}
          </div>

          {/* Tipo de pedido */}
          <div className="p-4 border-b border-border-light">
            <div
              role="group"
              aria-label="Tipo de pedido"
              className="grid grid-cols-3 gap-1"
            >
              {(
                [
                  ["DINE_IN", "Balcão"],
                  ["TAKEAWAY", "Retirada"],
                  ["DELIVERY", "Entrega"],
                ] as [OrderType, string][]
              ).map(([value, label]) => (
                <button
                  key={value}
                  type="button"
                  onClick={() => setOrderType(value)}
                  aria-pressed={orderType === value}
                  className={`text-sm py-2 rounded-md border transition-colors ${
                    orderType === value
                      ? "bg-primary-700 text-white border-primary-700"
                      : "bg-bg-secondary text-text-secondary border-border-light hover:bg-bg-tertiary"
                  }`}
                >
                  {label}
                </button>
              ))}
            </div>
          </div>

          {/* Linhas */}
          <div className="flex-1 overflow-y-auto p-4 space-y-2">
            {cart.length === 0 ? (
              <p className="text-sm text-text-muted text-center py-8">
                Toque num produto para adicionar.
              </p>
            ) : (
              cart.map((line, idx) => {
                const q = quote?.items[idx];
                return (
                  <div
                    key={line.lineId}
                    className="flex items-start gap-2 bg-bg-secondary rounded-md p-2"
                  >
                    <div className="flex-1 min-w-0">
                      <p className="text-sm font-medium text-text-primary truncate">
                        {line.productName}
                      </p>
                      {line.label && (
                        <p className="text-xs text-text-muted line-clamp-2">
                          {line.label}
                        </p>
                      )}
                      <p className="text-xs text-text-muted mt-0.5">
                        {q ? formatBRL(q.unitPriceCents) : quoting ? "…" : "—"}
                      </p>
                    </div>
                    <div className="flex items-center gap-1 shrink-0">
                      <button
                        type="button"
                        aria-label={`Diminuir ${line.productName}`}
                        onClick={() => setQuantity(line.lineId, line.quantity - 1)}
                        className="w-7 h-7 rounded-md bg-bg-tertiary text-text-primary font-bold leading-none hover:bg-border-light"
                      >
                        −
                      </button>
                      <span
                        className="w-6 text-center text-sm font-semibold"
                        aria-label={`Quantidade ${line.quantity}`}
                      >
                        {line.quantity}
                      </span>
                      <button
                        type="button"
                        aria-label={`Aumentar ${line.productName}`}
                        onClick={() => setQuantity(line.lineId, line.quantity + 1)}
                        className="w-7 h-7 rounded-md bg-bg-tertiary text-text-primary font-bold leading-none hover:bg-border-light"
                      >
                        +
                      </button>
                    </div>
                  </div>
                );
              })
            )}
          </div>

          {/* Total + finalizar */}
          <div className="p-4 border-t border-border-light space-y-3">
            {quoteError && (
              <p className="text-sm text-error" role="alert">
                {quoteError}
              </p>
            )}
            <div className="flex items-center justify-between text-sm text-text-secondary">
              <span>Subtotal</span>
              <span>{quote ? formatBRL(quote.subtotalCents) : "—"}</span>
            </div>
            <div className="flex items-center justify-between text-lg font-bold text-text-primary">
              <span>Total</span>
              <span>
                {quoting ? "…" : quote ? formatBRL(quote.totalCents) : "—"}
              </span>
            </div>
            <button
              type="button"
              className="btn-primary w-full"
              disabled={cart.length === 0 || quoting || !quote || !!quoteError}
              onClick={() => setShowPayment(true)}
            >
              Finalizar
            </button>
          </div>
        </aside>
      </div>

      {editing && (
        <ItemModal
          product={editing.product}
          variations={editing.variations}
          onClose={() => setEditing(null)}
          onAdd={(line) => {
            addCustom(line);
            setEditing(null);
          }}
        />
      )}

      {showPayment && quote && (
        <PaymentModal
          quote={quote}
          orderType={orderType}
          items={items}
          onClose={() => setShowPayment(false)}
          onConfirmed={() => {
            setShowPayment(false);
            clearCart();
          }}
        />
      )}
    </main>
  );
}

// --- Modal de personalização (tamanho + meia-a-meia + borda + massa + complementos) ---

function ItemModal({
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

function Section({
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

function ChoiceButton({
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

// --- Modal de pagamento ---

function PaymentModal({
  quote,
  orderType,
  items,
  onClose,
  onConfirmed,
}: {
  quote: QuoteResponse;
  orderType: OrderType;
  items: OrderItemInput[];
  onClose: () => void;
  onConfirmed: () => void;
}) {
  const [method, setMethod] = useState<PaymentMethod>("CASH");
  const [received, setReceived] = useState(""); // valor recebido em reais (texto)
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const dialogRef = useRef<HTMLDivElement>(null);
  useModalA11y(dialogRef, onClose);

  const receivedCents = useMemo(() => {
    const normalized = received.replace(/\./g, "").replace(",", ".");
    const value = Number.parseFloat(normalized);
    return Number.isFinite(value) ? Math.round(value * 100) : null;
  }, [received]);

  const changeCents =
    method === "CASH" && receivedCents != null
      ? receivedCents - quote.totalCents
      : null;

  const insufficientCash =
    method === "CASH" && receivedCents != null && receivedCents < quote.totalCents;

  async function confirm() {
    if (submitting) return;
    setErr(null);
    setSubmitting(true);
    try {
      const body: OrderCreateInput = {
        orderType,
        items,
        paymentMethod: method,
      };
      await api.post("/orders", body, {
        "Idempotency-Key": crypto.randomUUID(),
      });
      onConfirmed();
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : "Falha ao registrar o pedido.");
      setSubmitting(false);
    }
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
        aria-label="Pagamento"
        className="bg-bg-primary w-full sm:max-w-md rounded-t-2xl sm:rounded-2xl p-5 space-y-4"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-bold text-text-primary">Pagamento</h2>
          <span className="text-lg font-bold text-primary-600">
            {formatBRL(quote.totalCents)}
          </span>
        </div>

        <div
          role="group"
          aria-label="Forma de pagamento"
          className="grid grid-cols-3 gap-2"
        >
          {(Object.keys(PAYMENT_LABELS) as PaymentMethod[]).map((m) => (
            <button
              key={m}
              type="button"
              onClick={() => setMethod(m)}
              aria-pressed={method === m}
              className={`text-sm py-2 rounded-md border transition-colors ${
                method === m
                  ? "bg-primary-700 text-white border-primary-700"
                  : "bg-bg-secondary text-text-secondary border-border-light hover:bg-bg-tertiary"
              }`}
            >
              {PAYMENT_LABELS[m]}
            </button>
          ))}
        </div>

        {method === "CASH" && (
          <div className="space-y-2">
            <label
              htmlFor="received"
              className="block text-sm font-medium text-text-secondary"
            >
              Valor recebido (R$)
            </label>
            <input
              id="received"
              type="text"
              inputMode="decimal"
              value={received}
              onChange={(e) => setReceived(e.target.value)}
              placeholder="0,00"
              className="input-field w-full"
            />
            <div className="flex items-center justify-between text-sm">
              <span className="text-text-secondary">Troco</span>
              <span
                className={`font-semibold ${
                  insufficientCash ? "text-error" : "text-text-primary"
                }`}
              >
                {changeCents == null
                  ? "—"
                  : insufficientCash
                    ? "Valor insuficiente"
                    : formatBRL(changeCents)}
              </span>
            </div>
          </div>
        )}

        {err && (
          <p className="text-sm text-error" role="alert">
            {err}
          </p>
        )}

        <div className="flex gap-2 pt-1">
          <button
            type="button"
            className="btn-outline flex-1"
            onClick={onClose}
            disabled={submitting}
          >
            Cancelar
          </button>
          <button
            type="button"
            className="btn-primary flex-1"
            onClick={() => void confirm()}
            disabled={submitting || insufficientCash}
          >
            {submitting ? "Registrando…" : "Confirmar pedido"}
          </button>
        </div>
      </div>
    </div>
  );
}
