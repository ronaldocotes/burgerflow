"use client";

import {
  useCallback,
  useEffect,
  useMemo,
  useState,
} from "react";
import { useRouter } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { getToken, logout } from "@/lib/auth";
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
import { ShoppingCart, ShoppingBag, Truck, UtensilsCrossed, Check } from "lucide-react";
import { ItemCustomizeModal } from "@/components/order/ItemCustomizeModal";
import { OrderPaymentModal } from "@/components/order/OrderPaymentModal";
import { Variations } from "@/components/order/types";
import { useCatalog } from "@/lib/use-catalog";
import { useOrderCart } from "@/lib/use-order-cart";

// PDV: grade de produtos -> carrinho (linhas com variação) -> total do servidor
// (POST /orders/quote, casado por índice) -> finalizar (POST /orders + Idempotency-Key).
// Produto com tamanho/sabor/borda/complemento abre o modal de personalização;
// produto simples entra direto no carrinho.
// Os modais de personalização e pagamento vivem em components/order/ (prop-driven,
// compartilhados com o futuro /pedidos — fatia 1 da extração). O catálogo (produtos/
// categorias/config/caixa) e o carrinho (linhas/quote/cupom) vivem em
// lib/use-catalog.ts e lib/use-order-cart.ts (fatia 2 — mesmos hooks que a fatia 3
// vai reusar em /pedidos).

export default function PdvPage() {
  const router = useRouter();
  const [selectedCategoryId, setSelectedCategoryId] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [clearPending, setClearPending] = useState(false);
  const [showMobileCart, setShowMobileCart] = useState(false);

  const [orderType, setOrderType] = useState<OrderType>("DINE_IN");

  const [loadingProductId, setLoadingProductId] = useState<string | null>(null);
  const [editing, setEditing] = useState<{
    product: Product;
    variations: Variations;
  } | null>(null);
  const [showPayment, setShowPayment] = useState(false);
  // Fidelidade (Fase 3.3): aviso de pontos pos-venda (some sozinho apos 6s).
  const [loyaltyToast, setLoyaltyToast] = useState<string | null>(null);

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
    if (!getToken()) {
      router.push("/login");
      return;
    }
    queueMicrotask(() => {
      void reload();
    });
  }, [router, reload]);

  // Toast de fidelidade: some sozinho apos 6s
  useEffect(() => {
    if (!loyaltyToast) return;
    const t = setTimeout(() => setLoyaltyToast(null), 6000);
    return () => clearTimeout(t);
  }, [loyaltyToast]);

  function onLogout() {
    logout();
    router.push("/login");
  }

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
    } catch (err) {
      if (handleUnauthorized(err)) return;
      // Se as variações não puderem ser lidas, cai no caminho simples (o quote ainda valida).
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
      (p) =>
        p.name.toLowerCase().includes(term) ||
        p.sku.toLowerCase().includes(term),
    );
  }, [products, search, selectedCategoryId]);

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
          <button className="btn-primary" onClick={() => void reload()}>
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
    <main className="flex h-full min-h-0 flex-col bg-bg-secondary">
      <div className="grid min-h-0 flex-1 grid-cols-1 gap-0 lg:grid-cols-[minmax(0,1fr)_20rem] xl:grid-cols-[minmax(0,1fr)_22rem]">
        {/* Grade de produtos */}
        <section className="min-w-0 overflow-y-auto p-4 md:p-5">
          <input
            type="search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Buscar produto por nome ou SKU…"
            aria-label="Buscar produto"
            className="input-field mb-3 w-full"
          />

          {/* Abas de categorias */}
          {categories.length > 0 && (
            <div className="mb-4 flex flex-wrap gap-2 overflow-visible pb-1" role="group" aria-label="Filtrar por categoria">
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
                  ? "Cadastre produtos para vender no PDV."
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
                        <span className="mt-2 inline-block text-sm text-text-muted">
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

        {/* Carrinho — desktop: coluna fixa; mobile: overlay quando showMobileCart */}
        <aside className={[
          "min-w-0 flex flex-col border-border-light bg-bg-primary",
          "lg:h-full lg:min-h-0 lg:border-l",
          showMobileCart
            ? "fixed inset-0 z-40 lg:static lg:inset-auto"
            : "hidden lg:flex",
        ].join(" ")}>
          {/* Scrim mobile */}
          {showMobileCart && (
            <div
              className="absolute inset-0 bg-black/40 lg:hidden"
              aria-hidden="true"
              onClick={() => setShowMobileCart(false)}
            />
          )}
          <div className={[
            "relative flex h-full min-h-0 flex-col bg-bg-primary",
            showMobileCart ? "ml-auto w-full max-w-sm shadow-xl lg:shadow-none lg:ml-0 lg:max-w-none" : "",
          ].join(" ")}>
          <div className="p-4 border-b border-border-light flex items-center justify-between">
            <h2 className="font-bold text-text-primary">Carrinho</h2>
            <div className="flex items-center gap-3">
              {cart.length > 0 && !clearPending && (
                <button
                  className="text-sm text-error hover:underline"
                  onClick={() => setClearPending(true)}
                >
                  Limpar
                </button>
              )}
              {clearPending && (
                <span className="flex items-center gap-2 text-sm">
                  <span className="text-text-secondary">Limpar tudo?</span>
                  <button
                    className="font-semibold text-error hover:underline"
                    onClick={() => { clearCart(); setClearPending(false); }}
                  >
                    Sim
                  </button>
                  <button
                    className="text-text-muted hover:underline"
                    onClick={() => setClearPending(false)}
                  >
                    Não
                  </button>
                </span>
              )}
              {showMobileCart && (
                <button
                  aria-label="Fechar carrinho"
                  onClick={() => setShowMobileCart(false)}
                  className="text-text-muted hover:text-text-primary text-xl leading-none lg:hidden"
                >
                  ✕
                </button>
              )}
            </div>
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
                  ["DINE_IN", "Balcão", UtensilsCrossed],
                  ["TAKEAWAY", "Retirada", ShoppingBag],
                  ["DELIVERY", "Entrega", Truck],
                ] as [OrderType, string, typeof UtensilsCrossed][]
              ).map(([value, label, Icon]) => (
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
          </div>

          {/* Linhas */}
          <div className="flex-1 overflow-y-auto p-4 space-y-2">
            {cart.length === 0 ? (
              <div className="flex flex-col items-center justify-center gap-3 py-10 text-text-muted">
                <ShoppingCart className="h-10 w-10 opacity-30" aria-hidden="true" />
                <p className="text-sm text-center">Toque num produto para adicionar.</p>
              </div>
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
                      <p className="mt-0.5 text-sm text-text-muted">
                        {q ? formatBRL(q.unitPriceCents) : quoting ? "…" : "—"}
                      </p>
                    </div>
                    <div className="flex items-center gap-1 shrink-0">
                      <button
                        type="button"
                        aria-label={`Diminuir ${line.productName}`}
                        onClick={() => setQuantity(line.lineId, line.quantity - 1)}
                        className="w-11 h-11 rounded-md bg-bg-tertiary text-text-primary font-bold leading-none hover:bg-border-light"
                      >
                        −
                      </button>
                      <span
                        className="w-7 text-center text-sm font-semibold"
                        aria-label={`Quantidade ${line.quantity}`}
                      >
                        {line.quantity}
                      </span>
                      <button
                        type="button"
                        aria-label={`Aumentar ${line.productName}`}
                        onClick={() => setQuantity(line.lineId, line.quantity + 1)}
                        className="w-11 h-11 rounded-md bg-bg-tertiary text-text-primary font-bold leading-none hover:bg-border-light"
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
            {/* Cupom de desconto */}
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
                    aria-label="Codigo do cupom"
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
                    disabled={
                      !couponCode.trim() || applyingCoupon || !quote || quoting
                    }
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
              <span>
                {quoting
                  ? "…"
                  : quote
                    ? formatBRL(
                        Math.max(0, quote.totalCents - couponDiscountCents),
                      )
                    : "—"}
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
          </div>{/* fim div wrapper mobile */}
        </aside>
      </div>

      {/* FAB carrinho — mobile only (sempre visível para orientar o usuário) */}
      <div className="fixed bottom-6 right-6 z-30 lg:hidden">
        <button
          type="button"
          onClick={() => setShowMobileCart(true)}
          className="flex items-center gap-3 rounded-full bg-primary-700 px-5 py-3 text-white shadow-lg hover:bg-primary-800 active:scale-95 transition-transform"
          aria-label="Ver carrinho"
        >
          <span className="flex h-6 w-6 items-center justify-center rounded-full bg-white text-xs font-bold text-primary-700">
            {cart.reduce((s, l) => s + l.quantity, 0)}
          </span>
          <span className="text-sm font-semibold">
            {cart.length === 0 ? "Ver pedido" : quoting ? "…" : quote ? formatBRL(Math.max(0, quote.totalCents - couponDiscountCents)) : "Ver pedido"}
          </span>
        </button>
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
          onClose={() => setShowPayment(false)}
          onUnauthorized={redirectToLogin}
          onConfirmed={(customerPhone) => {
            // Fidelidade (Fase 3.3): estimativa local com a mesma formula do
            // servidor — floor(reais) * pontos por real, sobre o total ja com cupom.
            if (loyaltyCfg?.enabled && customerPhone && quote) {
              const dueCents = Math.max(0, quote.totalCents - couponDiscountCents);
              const pts = Math.floor(dueCents / 100) * loyaltyCfg.pointsPerReal;
              if (pts > 0) {
                setLoyaltyToast(
                  `🌟 +${pts} pontos creditados para ${customerPhone}`,
                );
              }
            }
            setShowPayment(false);
            clearCart();
          }}
        />
      )}

      {loyaltyToast && (
        <div
          role="status"
          aria-live="polite"
          className="fixed bottom-4 left-1/2 z-50 -translate-x-1/2 rounded-xl bg-primary-700 px-4 py-3 text-sm font-semibold text-white shadow-dropdown"
        >
          {loyaltyToast}
        </div>
      )}
    </main>
  );
}
