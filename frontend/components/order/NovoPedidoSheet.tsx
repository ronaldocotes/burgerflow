"use client";

// Novo Pedido manual, aberto de dentro de /pedidos (fatia 3): sheet lateral no
// desktop / tela cheia no mobile que reusa os MESMOS hooks e modais do PDV —
// useCatalog (produtos/categorias/config-fidelidade/caixa), useOrderCart
// (carrinho + quote do servidor + cupom), ItemCustomizeModal (personalização)
// e OrderPaymentModal (forma de pagamento + POST /orders + PIX). O total NUNCA
// é somado aqui: vem do quote/create do backend.
//
// Descopes do MVP (decisão do Construtor):
// - Cliente = só telefone (o POST /orders resolve/cria Customer por telefone).
// - Sem agendamento (o backend não tem scheduledAt).
// - DELIVERY sem formulário de endereço nesta fatia (aviso inline); DINE_IN e
//   TAKEAWAY são o foco.

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import {
  Check,
  Info,
  ShoppingBag,
  ShoppingCart,
  Truck,
  UtensilsCrossed,
  X,
} from "lucide-react";
import { api, ApiError } from "@/lib/api";
import { logout } from "@/lib/auth";
import {
  Product,
  ProductCrustPrice,
  ProductFlavor,
  ProductOptionGroup,
  ProductSize,
  formatBRL,
} from "@/types/menu";
import { OrderType } from "@/types/cart";
import LoadingSpinner from "@/components/loading-spinner";
import { useModalA11y } from "@/lib/use-modal-a11y";
import { useCatalog } from "@/lib/use-catalog";
import { useOrderCart } from "@/lib/use-order-cart";
import { ItemCustomizeModal } from "./ItemCustomizeModal";
import { OrderPaymentModal } from "./OrderPaymentModal";
import { Variations } from "./types";

const ORDER_TYPE_OPTIONS: [OrderType, string, typeof UtensilsCrossed][] = [
  ["DINE_IN", "Balcão", UtensilsCrossed],
  ["TAKEAWAY", "Retirada", ShoppingBag],
  ["DELIVERY", "Entrega", Truck],
];

export function NovoPedidoSheet({
  onClose,
  onCreated,
}: {
  onClose: () => void;
  /** Chamado com o id do pedido recém-criado, após o pagamento ser confirmado. */
  onCreated: (orderId: string) => void;
}) {
  const router = useRouter();

  const [selectedCategoryId, setSelectedCategoryId] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [mobileTab, setMobileTab] = useState<"catalog" | "cart">("catalog");
  const [orderType, setOrderType] = useState<OrderType>("DINE_IN");
  const [phone, setPhone] = useState("");
  const [orderNotes, setOrderNotes] = useState("");
  const [clearPending, setClearPending] = useState(false);

  const [loadingProductId, setLoadingProductId] = useState<string | null>(null);
  const [editing, setEditing] = useState<{ product: Product; variations: Variations } | null>(null);
  const [showPayment, setShowPayment] = useState(false);

  const redirectToLogin = useCallback(() => {
    logout();
    router.replace("/login");
  }, [router]);

  const handleUnauthorized = useCallback(
    (err: unknown): boolean => {
      if (err instanceof ApiError && err.status === 401) {
        redirectToLogin();
        return true;
      }
      return false;
    },
    [redirectToLogin],
  );

  const {
    products,
    categories,
    config: loyaltyCfg,
    hasCashSession,
    loading,
    error,
    reload,
  } = useCatalog({ onUnauthorized: handleUnauthorized });

  const {
    cart,
    items,
    addSimple,
    addCustom,
    setQuantity,
    clear: clearCart,
    quote,
    quoteError,
    quoting,
    couponCode,
    setCouponCode,
    appliedCoupon,
    appliedCouponCode,
    couponError,
    setCouponError,
    applyingCoupon,
    couponDiscountCents,
    applyCoupon,
    removeCoupon,
  } = useOrderCart({ orderType, onUnauthorized: handleUnauthorized });

  useEffect(() => {
    void reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- só na abertura do sheet
  }, []);

  // Fecha o sheet inteiro só quando NÃO há modal filho aberto: evita que o
  // bubbling de clique-no-backdrop/ESC do ItemCustomizeModal ou do
  // OrderPaymentModal feche o Novo Pedido inteiro por engano (perderia o
  // carrinho). Cada modal filho trata seu próprio fechamento primeiro.
  // Guardado por REF (não por closure em [editing, showPayment]) para que a
  // identidade de `closeIfNoChildModal` fique estável quando um modal filho
  // abre/fecha — senão o useModalA11y do sheet re-monta o efeito de foco
  // nesse instante e disputa o foco com o do modal filho que acabou de abrir.
  const childModalOpenRef = useRef(false);
  useEffect(() => {
    childModalOpenRef.current = Boolean(editing || showPayment);
  }, [editing, showPayment]);
  const closeIfNoChildModal = useCallback(() => {
    if (childModalOpenRef.current) return;
    onClose();
  }, [onClose]);

  const dialogRef = useRef<HTMLDivElement>(null);
  useModalA11y(dialogRef, closeIfNoChildModal);

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
    } catch (err) {
      if (handleUnauthorized(err)) return;
      addSimple(p);
    } finally {
      setLoadingProductId(null);
    }
  }

  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase();
    let visible = products.filter((p) => p.active);
    if (selectedCategoryId) visible = visible.filter((p) => p.categoryId === selectedCategoryId);
    if (!term) return visible;
    return visible.filter(
      (p) => p.name.toLowerCase().includes(term) || p.sku.toLowerCase().includes(term),
    );
  }, [products, search, selectedCategoryId]);

  const cartCount = cart.reduce((s, l) => s + l.quantity, 0);
  const dueCents = quote ? Math.max(0, quote.totalCents - couponDiscountCents) : null;

  return (
    <div
      className="fixed inset-0 z-50 flex bg-black/50"
      onClick={closeIfNoChildModal}
      role="presentation"
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="novo-pedido-title"
        onClick={(e) => e.stopPropagation()}
        className="ml-auto flex h-full w-full min-w-0 flex-col bg-bg-secondary shadow-dropdown lg:max-w-6xl lg:border-l lg:border-border-light"
      >
        {/* Cabeçalho */}
        <header className="flex shrink-0 items-center justify-between gap-3 border-b border-border-light bg-bg-primary px-4 py-3">
          <div className="min-w-0">
            <h2 id="novo-pedido-title" className="truncate text-lg font-bold text-text-primary">
              Novo pedido
            </h2>
            <p className="text-xs text-text-muted">Cardápio e carrinho — o total é sempre calculado pelo servidor.</p>
          </div>
          <button
            type="button"
            aria-label="Fechar novo pedido"
            onClick={onClose}
            className="icon-button shrink-0"
          >
            <X className="h-5 w-5" aria-hidden="true" />
          </button>
        </header>

        {/* Abas — só no mobile (desktop mostra as 2 colunas lado a lado) */}
        <div
          className="flex shrink-0 border-b border-border-light bg-bg-primary lg:hidden"
          role="tablist"
          aria-label="Seções do novo pedido"
        >
          <button
            type="button"
            role="tab"
            aria-selected={mobileTab === "catalog"}
            onClick={() => setMobileTab("catalog")}
            className={[
              "min-h-11 flex-1 border-b-2 px-3 text-sm font-semibold transition-colors",
              mobileTab === "catalog"
                ? "border-primary-700 text-primary-700"
                : "border-transparent text-text-secondary",
            ].join(" ")}
          >
            Cardápio
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={mobileTab === "cart"}
            onClick={() => setMobileTab("cart")}
            className={[
              "min-h-11 flex-1 border-b-2 px-3 text-sm font-semibold transition-colors",
              mobileTab === "cart"
                ? "border-primary-700 text-primary-700"
                : "border-transparent text-text-secondary",
            ].join(" ")}
          >
            Carrinho{cartCount > 0 ? ` (${cartCount})` : ""}
          </button>
        </div>

        {loading ? (
          <div className="flex flex-1 items-center justify-center">
            <LoadingSpinner size="lg" />
          </div>
        ) : error ? (
          <div className="flex flex-1 flex-col items-center justify-center gap-4 px-4 text-center">
            <p className="text-error font-medium" role="alert">
              {error}
            </p>
            <button className="btn-primary" onClick={() => void reload()}>
              Tentar de novo
            </button>
          </div>
        ) : (
          <div className="grid min-h-0 flex-1 grid-cols-1 lg:grid-cols-[minmax(0,1fr)_23rem]">
            {/* Catálogo: busca + categorias + grid */}
            <section
              className={[
                "min-w-0 overflow-y-auto p-4",
                mobileTab === "catalog" ? "block" : "hidden",
                "lg:block",
              ].join(" ")}
            >
              <input
                type="search"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Buscar produto por nome ou SKU…"
                aria-label="Buscar produto"
                className="input-field mb-3 w-full"
              />

              {categories.length > 0 && (
                <div
                  className="mb-4 flex flex-wrap gap-2 overflow-visible pb-1"
                  role="group"
                  aria-label="Filtrar por categoria"
                >
                  <button
                    type="button"
                    onClick={() => setSelectedCategoryId(null)}
                    aria-pressed={selectedCategoryId === null}
                    className={`min-h-11 rounded-full px-4 py-2 text-sm font-medium transition-colors ${
                      selectedCategoryId === null
                        ? "bg-primary-700 text-white"
                        : "bg-bg-secondary text-text-secondary border border-border-light hover:bg-bg-tertiary"
                    }`}
                  >
                    Todos
                  </button>
                  {categories.map((cat) => (
                    <button
                      key={cat.id}
                      type="button"
                      onClick={() => setSelectedCategoryId(cat.id)}
                      aria-pressed={selectedCategoryId === cat.id}
                      className={`min-h-11 rounded-full px-4 py-2 text-sm font-medium transition-colors ${
                        selectedCategoryId === cat.id
                          ? "bg-primary-700 text-white"
                          : "bg-bg-secondary text-text-secondary border border-border-light hover:bg-bg-tertiary"
                      }`}
                    >
                      {cat.name}
                    </button>
                  ))}
                </div>
              )}

              {filtered.length === 0 ? (
                <div className="empty-state">
                  <p className="empty-state-title">Nenhum produto</p>
                  <p className="empty-state-description">
                    {products.length === 0
                      ? "Cadastre produtos para vender."
                      : "Nenhum produto corresponde à busca."}
                  </p>
                </div>
              ) : (
                <div className="grid grid-cols-[repeat(auto-fill,minmax(12.5rem,1fr))] gap-2.5">
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
                        className="pos-product-card min-h-[88px] text-left disabled:cursor-not-allowed disabled:opacity-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-primary-500"
                      >
                        <div className="p-4">
                          <h3 className="line-clamp-2 text-sm font-semibold leading-tight text-text-primary">
                            {p.name}
                          </h3>
                          <div className="mt-2 flex items-baseline gap-2">
                            <span className="text-base font-bold text-primary-700">
                              {formatBRL(p.effectivePriceCents)}
                            </span>
                            {p.onPromo && (
                              <span className="text-sm text-text-muted line-through">
                                {formatBRL(p.priceCents)}
                              </span>
                            )}
                          </div>
                          {loadingThis && (
                            <span className="mt-2 inline-block text-sm text-text-muted">Carregando…</span>
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

            {/* Carrinho: cliente, tipo de pedido, linhas, observações, total, pagar */}
            <aside
              className={[
                "min-w-0 flex-col overflow-y-auto border-border-light bg-bg-primary lg:flex lg:border-l",
                mobileTab === "cart" ? "flex" : "hidden",
              ].join(" ")}
            >
              {/* Cliente */}
              <div className="border-b border-border-light p-4">
                <label htmlFor="novo-pedido-phone" className="block text-sm font-medium text-text-secondary">
                  Telefone do cliente (opcional)
                </label>
                <input
                  id="novo-pedido-phone"
                  type="tel"
                  inputMode="tel"
                  autoComplete="off"
                  maxLength={16}
                  value={phone}
                  onChange={(e) => setPhone(e.target.value)}
                  placeholder="(96) 99999-9999"
                  className="input-field mt-1 w-full"
                />
                <p className="mt-1 text-xs text-text-muted">
                  {loyaltyCfg?.enabled
                    ? "Pontos de fidelidade e avisos do pedido por WhatsApp."
                    : "Avisos do pedido por WhatsApp."}
                </p>
              </div>

              {/* Tipo de pedido */}
              <div className="border-b border-border-light p-4">
                <div role="group" aria-label="Tipo de pedido" className="grid grid-cols-3 gap-1">
                  {ORDER_TYPE_OPTIONS.map(([value, label, Icon]) => (
                    <button
                      key={value}
                      type="button"
                      onClick={() => setOrderType(value)}
                      aria-pressed={orderType === value}
                      className={`inline-flex flex-col items-center gap-1 py-2 rounded-md border text-xs font-medium transition-colors ${
                        orderType === value
                          ? "bg-primary-700 text-white border-primary-700"
                          : "bg-bg-secondary text-text-secondary border-border-light hover:bg-bg-tertiary"
                      }`}
                    >
                      <Icon className="h-4 w-4" aria-hidden="true" />
                      {label}
                    </button>
                  ))}
                </div>
                {orderType === "DELIVERY" && (
                  <div className="mt-3 flex items-start gap-2 rounded-lg border border-secondary-200 bg-secondary-50 p-3 text-xs text-secondary-900">
                    <Info className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
                    <span>
                      Endereço de entrega ainda não é coletado aqui — chega numa próxima etapa. Por
                      ora, registre o pedido e combine a entrega por telefone/WhatsApp.
                    </span>
                  </div>
                )}
              </div>

              {/* Linhas do carrinho */}
              <div className="flex-1 space-y-2 p-4">
                {cart.length === 0 ? (
                  <div className="flex flex-col items-center justify-center gap-3 py-10 text-text-muted">
                    <ShoppingCart className="h-10 w-10 opacity-30" aria-hidden="true" />
                    <p className="text-center text-sm">Toque num produto para adicionar.</p>
                  </div>
                ) : (
                  <>
                    <div className="flex items-center justify-between">
                      <h3 className="font-bold text-text-primary">Carrinho</h3>
                      {!clearPending ? (
                        <button
                          type="button"
                          className="text-sm text-error hover:underline"
                          onClick={() => setClearPending(true)}
                        >
                          Limpar
                        </button>
                      ) : (
                        <span className="flex items-center gap-2 text-sm">
                          <span className="text-text-secondary">Limpar tudo?</span>
                          <button
                            type="button"
                            className="font-semibold text-error hover:underline"
                            onClick={() => {
                              clearCart();
                              setClearPending(false);
                            }}
                          >
                            Sim
                          </button>
                          <button
                            type="button"
                            className="text-text-muted hover:underline"
                            onClick={() => setClearPending(false)}
                          >
                            Não
                          </button>
                        </span>
                      )}
                    </div>
                    {cart.map((line, idx) => {
                      const q = quote?.items[idx];
                      return (
                        <div key={line.lineId} className="flex items-start gap-2 rounded-md bg-bg-secondary p-2">
                          <div className="min-w-0 flex-1">
                            <p className="truncate text-sm font-medium text-text-primary">{line.productName}</p>
                            {line.label && <p className="text-xs text-text-muted line-clamp-2">{line.label}</p>}
                            <p className="mt-0.5 text-sm text-text-muted">
                              {q ? formatBRL(q.unitPriceCents) : quoting ? "…" : "—"}
                            </p>
                          </div>
                          <div className="flex shrink-0 items-center gap-1">
                            <button
                              type="button"
                              aria-label={`Diminuir ${line.productName}`}
                              onClick={() => setQuantity(line.lineId, line.quantity - 1)}
                              className="h-11 w-11 rounded-md bg-bg-tertiary font-bold leading-none text-text-primary hover:bg-border-light"
                            >
                              −
                            </button>
                            <span className="w-7 text-center text-sm font-semibold" aria-label={`Quantidade ${line.quantity}`}>
                              {line.quantity}
                            </span>
                            <button
                              type="button"
                              aria-label={`Aumentar ${line.productName}`}
                              onClick={() => setQuantity(line.lineId, line.quantity + 1)}
                              className="h-11 w-11 rounded-md bg-bg-tertiary font-bold leading-none text-text-primary hover:bg-border-light"
                            >
                              +
                            </button>
                          </div>
                        </div>
                      );
                    })}
                  </>
                )}
              </div>

              {/* Observações do pedido */}
              <div className="border-t border-border-light p-4">
                <label htmlFor="novo-pedido-notes" className="block text-sm font-medium text-text-secondary">
                  Observações do pedido
                </label>
                <textarea
                  id="novo-pedido-notes"
                  value={orderNotes}
                  onChange={(e) => setOrderNotes(e.target.value)}
                  placeholder="Ex.: sem cebola, entregar no portão dos fundos…"
                  className="input-field mt-1 min-h-16 w-full resize-none"
                  maxLength={500}
                />
              </div>

              {/* Total + pagar */}
              <div className="space-y-3 border-t border-border-light p-4">
                {quoteError && (
                  <p className="text-sm text-error" role="alert">
                    {quoteError}
                  </p>
                )}

                {appliedCoupon ? (
                  <div className="flex items-center justify-between gap-2 rounded-lg border border-success/40 bg-success/10 px-3 py-2">
                    <span className="inline-flex min-w-0 items-center gap-1.5 text-sm font-semibold text-success">
                      <Check className="h-4 w-4 shrink-0" aria-hidden="true" />
                      <span className="truncate">
                        {appliedCouponCode}: -{formatBRL(couponDiscountCents)}
                      </span>
                    </span>
                    <button
                      type="button"
                      onClick={removeCoupon}
                      className="shrink-0 rounded-md px-2 py-2 text-sm font-medium text-text-secondary underline hover:text-error"
                      aria-label={`Remover cupom ${appliedCouponCode}`}
                    >
                      Remover
                    </button>
                  </div>
                ) : (
                  <div className="space-y-1">
                    <div className="flex gap-2">
                      <input
                        type="text"
                        value={couponCode}
                        onChange={(e) => {
                          setCouponCode(e.target.value.toUpperCase());
                          setCouponError(null);
                        }}
                        onKeyDown={(e) => {
                          if (e.key === "Enter") {
                            e.preventDefault();
                            void applyCoupon();
                          }
                        }}
                        placeholder="Cupom (opcional)"
                        aria-label="Código do cupom"
                        autoCapitalize="characters"
                        autoCorrect="off"
                        spellCheck={false}
                        className="input-field min-w-0 flex-1 uppercase"
                        disabled={cart.length === 0}
                      />
                      <button
                        type="button"
                        className="btn-outline shrink-0"
                        onClick={() => void applyCoupon()}
                        disabled={!couponCode.trim() || applyingCoupon || !quote || quoting}
                      >
                        {applyingCoupon ? "…" : "Aplicar"}
                      </button>
                    </div>
                    {couponError && (
                      <p className="text-sm text-error" role="alert">
                        {couponError}
                      </p>
                    )}
                  </div>
                )}

                <div className="flex items-center justify-between text-sm text-text-secondary">
                  <span>Subtotal</span>
                  <span>{quote ? formatBRL(quote.subtotalCents) : "—"}</span>
                </div>
                {couponDiscountCents > 0 && (
                  <div className="flex items-center justify-between text-sm font-medium text-success">
                    <span>Desconto ({appliedCouponCode})</span>
                    <span>- {formatBRL(couponDiscountCents)}</span>
                  </div>
                )}
                <div className="flex items-center justify-between text-lg font-bold text-text-primary">
                  <span>Total</span>
                  <span>{quoting ? "…" : dueCents != null ? formatBRL(dueCents) : "—"}</span>
                </div>

                {!hasCashSession && (
                  <p className="text-xs text-text-muted">
                    Caixa fechado — pagamentos em dinheiro exigem caixa aberto (outras formas funcionam normalmente).
                  </p>
                )}

                <button
                  type="button"
                  className="btn-primary w-full"
                  disabled={cart.length === 0 || quoting || !quote || !!quoteError}
                  onClick={() => setShowPayment(true)}
                >
                  Pagar
                </button>
              </div>
            </aside>
          </div>
        )}
      </div>

      {editing && (
        <ItemCustomizeModal
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
        <OrderPaymentModal
          quote={quote}
          orderType={orderType}
          items={items}
          cart={cart}
          couponCode={appliedCoupon ? appliedCouponCode : null}
          couponDiscountCents={couponDiscountCents}
          hasCashSession={hasCashSession}
          loyaltyEnabled={loyaltyCfg?.enabled ?? false}
          notes={orderNotes}
          initialPhone={phone}
          onClose={() => setShowPayment(false)}
          onUnauthorized={redirectToLogin}
          onConfirmed={(_customerPhone, orderId) => {
            setShowPayment(false);
            clearCart();
            onCreated(orderId);
          }}
        />
      )}
    </div>
  );
}
