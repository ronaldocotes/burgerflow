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
import Link from "next/link";
import { api, ApiError, API_BASE } from "@/lib/api";
import { getTenant, getToken, logout } from "@/lib/auth";
import { useModalA11y } from "@/lib/use-modal-a11y";
import {
  Category,
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
import { ApplyCouponResponse } from "@/types/coupon";
import LoadingSpinner from "@/components/loading-spinner";
import { ShoppingCart, ShoppingBag, Truck, UtensilsCrossed, Check, CheckCircle, Clock } from "lucide-react";

// PDV: grade de produtos -> carrinho (linhas com variação) -> total do servidor
// (POST /orders/quote, casado por índice) -> finalizar (POST /orders + Idempotency-Key).
// Produto com tamanho/sabor/borda/complemento abre o modal de personalização;
// produto simples entra direto no carrinho.

// --- Tipo do pedido criado (POST /orders) ---
interface OrderCreatedResponse {
  id: string;
}

// --- Intenção de pagamento PIX (POST /payments/pix-qr e GET /payments/pix-qr/status/:id) ---
interface PaymentIntentResponse {
  id: string;
  orderId: string;
  status: "PENDING" | "PAID" | "FAILED" | "EXPIRED";
  pixQrImage: string;    // base64 PNG
  pixCopyPaste: string;  // string copia-e-cola
  amountCents: number;
  expiresAt: string;     // ISO
}

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
  const [categories, setCategories] = useState<Category[]>([]);
  const [selectedCategoryId, setSelectedCategoryId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [clearPending, setClearPending] = useState(false);
  const [showMobileCart, setShowMobileCart] = useState(false);

  const [cart, setCart] = useState<CartLine[]>([]);
  const [orderType, setOrderType] = useState<OrderType>("DINE_IN");

  const [quote, setQuote] = useState<QuoteResponse | null>(null);
  const [quoteError, setQuoteError] = useState<string | null>(null);
  const [quoting, setQuoting] = useState(false);

  // Cupom de desconto: pre-checagem publica (apply-coupon); o desconto REAL e
  // recalculado no servidor ao criar o pedido (couponCode no POST /orders).
  const [couponCode, setCouponCode] = useState("");
  const [appliedCoupon, setAppliedCoupon] = useState<ApplyCouponResponse | null>(null);
  const [appliedCouponCode, setAppliedCouponCode] = useState<string | null>(null);
  const [couponError, setCouponError] = useState<string | null>(null);
  const [applyingCoupon, setApplyingCoupon] = useState(false);
  const couponDiscountCents =
    appliedCoupon && appliedCoupon.valid ? appliedCoupon.discountCents : 0;

  const [loadingProductId, setLoadingProductId] = useState<string | null>(null);
  const [editing, setEditing] = useState<{
    product: Product;
    variations: Variations;
  } | null>(null);
  const [showPayment, setShowPayment] = useState(false);
  // true = caixa aberto, false = sem turno (verificado 1x ao montar)
  const [hasCashSession, setHasCashSession] = useState<boolean>(false);

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

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [prods, cats, cashSession] = await Promise.all([
        api.get<Page<Product>>("/products?size=200"),
        api.get<Page<Category>>("/categories?size=200").catch(() => null),
        // 204 → undefined (sem turno aberto); erro de rede → ignora silenciosamente
        api.get<unknown>("/cash-sessions/current").catch(() => undefined),
      ]);
      setProducts(prods.content);
      setCategories(
        (cats?.content ?? [])
          .filter((c) => c.active)
          .sort((a, b) => a.displayOrder - b.displayOrder),
      );
      // cashSession = undefined significa 204 (sem turno) ou falha na rede
      setHasCashSession(cashSession !== undefined && cashSession !== null);
    } catch (err) {
      if (handleUnauthorized(err)) return;
      setError(err instanceof Error ? err.message : "Erro ao carregar produtos.");
    } finally {
      setLoading(false);
    }
  }, [handleUnauthorized]);

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
        if (handleUnauthorized(err)) return;
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
  }, [handleUnauthorized, items, orderType]);

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
    removeCoupon();
  }

  async function applyCoupon() {
    const code = couponCode.trim().toUpperCase();
    if (!code || !quote || applyingCoupon) return;
    setApplyingCoupon(true);
    setCouponError(null);
    try {
      const res = await fetch(
        `${API_BASE}/public/${getTenant() ?? ""}/apply-coupon`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ code, subtotalCents: quote.subtotalCents }),
        },
      );
      const data = (await res.json()) as ApplyCouponResponse & {
        message?: string;
      };
      if (!res.ok || !data.valid) {
        setAppliedCoupon(null);
        setAppliedCouponCode(null);
        setCouponError(
          typeof data.message === "string"
            ? data.message
            : "Cupom invalido ou expirado.",
        );
      } else {
        setAppliedCoupon(data);
        setAppliedCouponCode(code);
        setCouponCode("");
      }
    } catch {
      setCouponError("Nao foi possivel verificar o cupom. Tente novamente.");
    } finally {
      setApplyingCoupon(false);
    }
  }

  function removeCoupon() {
    setAppliedCoupon(null);
    setAppliedCouponCode(null);
    setCouponCode("");
    setCouponError(null);
  }

  // Revalida o cupom quando o subtotal muda (min_order pode deixar de valer).
  // Se a rede falhar, mantem o cupom: o servidor revalida ao criar o pedido.
  useEffect(() => {
    if (!appliedCouponCode) return;
    const subtotal = quote?.subtotalCents;
    if (subtotal == null) {
      setAppliedCoupon(null);
      setAppliedCouponCode(null);
      setCouponError(null);
      return;
    }
    let cancelled = false;
    const t = window.setTimeout(() => {
      void (async () => {
        try {
          const res = await fetch(
            `${API_BASE}/public/${getTenant() ?? ""}/apply-coupon`,
            {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({
                code: appliedCouponCode,
                subtotalCents: subtotal,
              }),
            },
          );
          const data = (await res.json()) as ApplyCouponResponse & {
            message?: string;
          };
          if (cancelled) return;
          if (!res.ok || !data.valid) {
            setAppliedCoupon(null);
            setAppliedCouponCode(null);
            setCouponError(
              typeof data.message === "string"
                ? data.message
                : "O cupom deixou de valer para este pedido.",
            );
          } else {
            setAppliedCoupon(data);
          }
        } catch {
          // rede indisponivel: mantem o cupom aplicado
        }
      })();
    }, 300);
    return () => {
      cancelled = true;
      window.clearTimeout(t);
    };
  }, [quote?.subtotalCents, appliedCouponCode]);

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
          cart={cart}
          couponCode={appliedCoupon ? appliedCouponCode : null}
          couponDiscountCents={couponDiscountCents}
          hasCashSession={hasCashSession}
          onClose={() => setShowPayment(false)}
          onUnauthorized={redirectToLogin}
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

// --- Modal de pagamento PIX: QR + copia-e-cola + polling + countdown ---

function PixPaymentModal({
  intent: initialIntent,
  onPaid,
  onCancel,
  onUnauthorized,
}: {
  intent: PaymentIntentResponse;
  onPaid: () => void;
  onCancel: () => void;
  onUnauthorized: () => void;
}) {
  const dialogRef = useRef<HTMLDivElement>(null);
  // ESC retorna à seleção de método (não cancela o pedido — ele já foi criado)
  useModalA11y(dialogRef, onCancel);

  const [paymentIntent, setPaymentIntent] = useState<PaymentIntentResponse>(initialIntent);
  const [secondsLeft, setSecondsLeft] = useState(0);
  const [copied, setCopied] = useState(false);
  const [generatingNew, setGeneratingNew] = useState(false);
  const [qrError, setQrError] = useState<string | null>(null);

  const { status, pixQrImage, pixCopyPaste, amountCents, expiresAt, orderId } = paymentIntent;

  // Polling a cada 3s enquanto PENDING
  useEffect(() => {
    if (status !== "PENDING") return;
    const interval = setInterval(() => {
      void api
        .get<PaymentIntentResponse>(`/payments/pix-qr/status/${orderId}`)
        .then((data) => {
          setPaymentIntent(data);
          if (data.status !== "PENDING") clearInterval(interval);
        })
        .catch((e) => {
          if (e instanceof ApiError && e.status === 401) {
            clearInterval(interval);
            onUnauthorized();
          }
          // Silencia erros de rede durante polling — o intervalo tenta de novo
        });
    }, 3000);
    return () => clearInterval(interval);
  }, [status, orderId, onUnauthorized]);

  // Countdown até expiresAt (reseta ao trocar de intenção)
  useEffect(() => {
    if (!expiresAt || status !== "PENDING") return;
    const calc = () =>
      Math.max(0, Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000));
    setSecondsLeft(calc());
    const tick = setInterval(() => {
      setSecondsLeft((s) => {
        if (s <= 1) { clearInterval(tick); return 0; }
        return s - 1;
      });
    }, 1000);
    return () => clearInterval(tick);
  }, [expiresAt, status]);

  async function generateNewQr() {
    setGeneratingNew(true);
    setQrError(null);
    try {
      const data = await api.post<PaymentIntentResponse>("/payments/pix-qr", { orderId });
      setPaymentIntent(data);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) { onUnauthorized(); return; }
      setQrError(e instanceof ApiError ? e.message : "Falha ao gerar novo QR.");
    } finally {
      setGeneratingNew(false);
    }
  }

  async function copyToClipboard() {
    try {
      await navigator.clipboard.writeText(pixCopyPaste);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      /* fallback: usuário copia manualmente do campo read-only */
    }
  }

  const mins = Math.floor(secondsLeft / 60);
  const secs = String(secondsLeft % 60).padStart(2, "0");
  const titleId = "pix-modal-title";

  return (
    <div className="fixed inset-0 z-[60] flex items-end sm:items-center justify-center bg-black/60 p-0 sm:p-4">
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="bg-bg-primary w-full sm:max-w-sm rounded-t-2xl sm:rounded-2xl p-5 space-y-4"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Cabeçalho */}
        <div className="flex items-center justify-between">
          <h2 id={titleId} className="text-base font-bold text-text-primary">
            {status === "PAID"
              ? "Pagamento confirmado"
              : status === "EXPIRED"
                ? "QR expirado"
                : status === "FAILED"
                  ? "Falha no pagamento"
                  : "Aguardando pagamento PIX"}
          </h2>
          <span className="text-base font-bold text-primary-700">
            {formatBRL(amountCents)}
          </span>
        </div>

        {/* Estado: PAID */}
        {status === "PAID" && (
          <div className="flex flex-col items-center gap-3 py-4" aria-live="polite">
            <CheckCircle className="h-16 w-16 text-success" aria-hidden="true" />
            <p className="text-center text-base font-semibold text-text-primary">
              Pagamento confirmado!
            </p>
            <button type="button" className="btn-primary w-full" onClick={onPaid}>
              Fechar
            </button>
          </div>
        )}

        {/* Estado: FAILED */}
        {status === "FAILED" && (
          <div className="flex flex-col items-center gap-3 py-4" aria-live="assertive">
            <p className="text-center text-error font-medium">
              O pagamento falhou. Tente outro método.
            </p>
            <button type="button" className="btn-outline w-full" onClick={onCancel}>
              Usar outro método
            </button>
          </div>
        )}

        {/* Estado: EXPIRED */}
        {status === "EXPIRED" && (
          <div className="flex flex-col items-center gap-3 py-4" aria-live="polite">
            <p className="text-center text-text-secondary">
              O QR expirou. Gere um novo para continuar.
            </p>
            {qrError && (
              <p className="text-sm text-error" role="alert">
                {qrError}
              </p>
            )}
            <button
              type="button"
              className="btn-primary w-full"
              disabled={generatingNew}
              onClick={() => void generateNewQr()}
            >
              {generatingNew ? "Gerando…" : "Gerar novo QR"}
            </button>
            <button type="button" className="btn-outline w-full" onClick={onCancel}>
              Usar outro método
            </button>
          </div>
        )}

        {/* Estado: PENDING — QR exibido + countdown + copia-e-cola + polling */}
        {status === "PENDING" && (
          <>
            {/* Imagem do QR */}
            <div className="flex justify-center">
              {/* QR em data URI gerado pela API; next/image nao otimiza esse caso. */}
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={`data:image/png;base64,${pixQrImage}`}
                alt="QR Code PIX — escaneie com o app do seu banco"
                className="w-48 h-48 rounded-lg border border-border-light"
              />
            </div>

            {/* Countdown */}
            <div
              className="flex items-center justify-center gap-1.5"
              aria-live="polite"
              aria-atomic="true"
            >
              <Clock className="h-4 w-4 text-text-muted shrink-0" aria-hidden="true" />
              <span className="text-sm text-text-secondary">
                {secondsLeft > 0
                  ? `Expira em ${mins}:${secs}`
                  : "Verificando expiração…"}
              </span>
            </div>

            {/* Copia-e-cola */}
            <div className="space-y-1.5">
              <p className="text-xs font-medium text-text-secondary">Pix Copia e Cola</p>
              <div className="flex items-center gap-2">
                <input
                  type="text"
                  readOnly
                  value={pixCopyPaste}
                  className="input-field flex-1 text-xs truncate"
                  aria-label="Código Pix Copia e Cola"
                />
                <button
                  type="button"
                  onClick={() => void copyToClipboard()}
                  className={`shrink-0 flex items-center gap-1 px-3 py-2 rounded-md border text-sm font-medium transition-colors ${
                    copied
                      ? "bg-success/10 border-success/40 text-success"
                      : "bg-bg-secondary border-border-light text-text-secondary hover:bg-bg-tertiary"
                  }`}
                  aria-label={copied ? "Código copiado" : "Copiar código PIX"}
                >
                  {copied ? (
                    <>
                      <Check className="h-4 w-4" aria-hidden="true" />
                      Copiado
                    </>
                  ) : (
                    "Copiar"
                  )}
                </button>
              </div>
            </div>

            {/* Indicador de polling */}
            <p className="text-xs text-center text-text-muted" aria-live="polite">
              Verificando pagamento automaticamente…
            </p>

            {/* Botão cancelar */}
            <button
              type="button"
              className="btn-outline w-full"
              onClick={onCancel}
            >
              Cancelar / usar outro método
            </button>
          </>
        )}
      </div>
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
  cart,
  couponCode,
  couponDiscountCents,
  hasCashSession,
  onClose,
  onUnauthorized,
  onConfirmed,
}: {
  quote: QuoteResponse;
  orderType: OrderType;
  items: OrderItemInput[];
  cart: CartLine[];
  couponCode: string | null;
  couponDiscountCents: number;
  hasCashSession: boolean;
  onClose: () => void;
  onUnauthorized: () => void;
  onConfirmed: () => void;
}) {
  const [method, setMethod] = useState<PaymentMethod>("CASH");
  const [received, setReceived] = useState(""); // valor recebido em reais (texto)
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  // Quando PIX: guarda a intenção de pagamento gerada após criar o pedido
  const [pixIntent, setPixIntent] = useState<PaymentIntentResponse | null>(null);

  const dialogRef = useRef<HTMLDivElement>(null);
  useModalA11y(dialogRef, onClose);

  const receivedCents = useMemo(() => {
    const normalized = received.replace(/\./g, "").replace(",", ".");
    const value = Number.parseFloat(normalized);
    return Number.isFinite(value) ? Math.round(value * 100) : null;
  }, [received]);

  // Total devido na tela: quote menos o cupom pre-validado. O valor final
  // continua sendo o do servidor (que revalida o cupom ao criar o pedido).
  const dueCents = Math.max(0, quote.totalCents - couponDiscountCents);

  const changeCents =
    method === "CASH" && receivedCents != null
      ? receivedCents - dueCents
      : null;

  const insufficientCash =
    method === "CASH" && receivedCents != null && receivedCents < dueCents;

  async function confirm() {
    if (submitting) return;
    setErr(null);
    setSubmitting(true);
    try {
      const body: OrderCreateInput = {
        orderType,
        items,
        paymentMethod: method,
        couponCode: couponCode ?? undefined,
      };
      const created = await api.post<OrderCreatedResponse>("/orders", body, {
        "Idempotency-Key": crypto.randomUUID(),
      });

      if (method === "PIX" && created?.id) {
        // Fluxo PIX: buscar QR e abrir modal dedicado — não chamar onConfirmed() aqui.
        const intent = await api.post<PaymentIntentResponse>("/payments/pix-qr", {
          orderId: created.id,
        });
        setPixIntent(intent);
        setSubmitting(false);
      } else {
        // CASH, CARD, OTHER: encerra normalmente.
        onConfirmed();
      }
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        onUnauthorized();
        return;
      }
      setErr(e instanceof ApiError ? e.message : "Falha ao registrar o pedido.");
      setSubmitting(false);
    }
  }

  return (
    <>
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
          <span className="text-lg font-bold text-primary-700">
            {formatBRL(dueCents)}
          </span>
        </div>

        {/* Resumo dos itens */}
        <div className="rounded-lg border border-border-light bg-bg-secondary divide-y divide-border-light max-h-36 overflow-y-auto">
          {cart.map((line, idx) => {
            const q = quote.items[idx];
            return (
              <div key={line.lineId} className="flex items-center justify-between gap-2 px-3 py-2">
                <div className="min-w-0">
                  <p className="text-sm font-medium text-text-primary truncate">
                    {line.quantity > 1 && <span className="text-primary-700 font-bold mr-1">{line.quantity}×</span>}
                    {line.productName}
                  </p>
                  {line.label && <p className="text-xs text-text-muted truncate">{line.label}</p>}
                </div>
                <span className="shrink-0 text-sm font-semibold text-text-primary">
                  {q ? formatBRL(q.totalPriceCents) : "—"}
                </span>
              </div>
            );
          })}
          {couponCode && couponDiscountCents > 0 && (
            <div className="flex items-center justify-between gap-2 px-3 py-2">
              <p className="text-sm font-medium text-success">
                Cupom {couponCode}
              </p>
              <span className="shrink-0 text-sm font-semibold text-success">
                - {formatBRL(couponDiscountCents)}
              </span>
            </div>
          )}
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

        {method === "CASH" && !hasCashSession && (
          <div className="flex items-start gap-2 rounded-lg border border-warning/40 bg-warning/10 p-3 text-sm">
            <span className="mt-0.5 shrink-0 text-warning" aria-hidden="true">&#9888;</span>
            <span className="text-text-primary">
              Abra o caixa antes de registrar vendas em dinheiro.{" "}
              <Link href="/caixa" className="font-semibold underline hover:text-primary-700">
                Ir para Caixa
              </Link>
            </span>
          </div>
        )}

        {method === "CASH" && hasCashSession && (
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
            disabled={submitting || insufficientCash || (method === "CASH" && !hasCashSession)}
          >
            {submitting ? "Registrando…" : "Confirmar pedido"}
          </button>
        </div>
      </div>
    </div>

    {/* Modal PIX — aparece sobre o PaymentModal quando método = PIX */}
    {pixIntent && (
      <PixPaymentModal
        intent={pixIntent}
        onPaid={onConfirmed}
        onCancel={() => setPixIntent(null)}
        onUnauthorized={onUnauthorized}
      />
    )}
    </>
  );
}
