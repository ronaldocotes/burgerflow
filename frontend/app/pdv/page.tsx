"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { getToken, logout } from "@/lib/auth";
import { Page, Product, formatBRL } from "@/types/menu";
import {
  OrderCreateInput,
  OrderItemInput,
  OrderType,
  PaymentMethod,
  QuoteRequest,
  QuoteResponse,
} from "@/types/cart";
import LoadingSpinner from "@/components/loading-spinner";

// PDV base: grade de produtos -> carrinho (estado local) -> total vem do servidor
// (POST /orders/quote, mesma lógica do create) -> finalizar (POST /orders com
// Idempotency-Key). Produtos com complementos/variação (pizza) ficam para a fatia 2
// (modal); aqui o quote sinaliza se algum item exigir escolha.

interface CartLine {
  product: Product;
  quantity: number;
}

const PAYMENT_LABELS: Record<PaymentMethod, string> = {
  CASH: "Dinheiro",
  CREDIT_CARD: "Crédito",
  DEBIT_CARD: "Débito",
  PIX: "Pix",
  OTHER: "Outro",
};

export default function PdvPage() {
  const router = useRouter();
  const [products, setProducts] = useState<Product[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState("");

  // Carrinho: chave = productId (PDV base só lida com produtos simples).
  const [cart, setCart] = useState<Map<string, CartLine>>(new Map());
  const [orderType, setOrderType] = useState<OrderType>("DINE_IN");

  const [quote, setQuote] = useState<QuoteResponse | null>(null);
  const [quoteError, setQuoteError] = useState<string | null>(null);
  const [quoting, setQuoting] = useState(false);

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
    void load();
  }, [router, load]);

  function onLogout() {
    logout();
    router.push("/login");
  }

  // Itens do carrinho como entrada de pedido (ordem estável por inserção).
  const items: OrderItemInput[] = useMemo(
    () =>
      [...cart.values()].map((line) => ({
        productId: line.product.id,
        quantity: line.quantity,
      })),
    [cart],
  );

  // Recota no servidor sempre que o carrinho muda. O total NUNCA é somado no front.
  const quoteSeq = useRef(0);
  useEffect(() => {
    if (items.length === 0) {
      setQuote(null);
      setQuoteError(null);
      setQuoting(false);
      return;
    }
    const seq = ++quoteSeq.current;
    setQuoting(true);
    setQuoteError(null);
    const body: QuoteRequest = { orderType, items };
    api
      .post<QuoteResponse>("/orders/quote", body)
      .then((res) => {
        if (seq !== quoteSeq.current) return; // resposta obsoleta
        setQuote(res);
      })
      .catch((err) => {
        if (seq !== quoteSeq.current) return;
        setQuote(null);
        setQuoteError(
          err instanceof ApiError
            ? err.message
            : "Não foi possível calcular o total.",
        );
      })
      .finally(() => {
        if (seq === quoteSeq.current) setQuoting(false);
      });
  }, [items, orderType]);

  function addProduct(p: Product) {
    setCart((prev) => {
      const next = new Map(prev);
      const line = next.get(p.id);
      next.set(p.id, {
        product: p,
        quantity: (line?.quantity ?? 0) + 1,
      });
      return next;
    });
  }

  function setQuantity(productId: string, quantity: number) {
    setCart((prev) => {
      const next = new Map(prev);
      if (quantity <= 0) {
        next.delete(productId);
      } else {
        const line = next.get(productId);
        if (line) next.set(productId, { ...line, quantity });
      }
      return next;
    });
  }

  function clearCart() {
    setCart(new Map());
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

  const cartLines = [...cart.values()];

  return (
    <main className="min-h-screen bg-bg-secondary flex flex-col">
      <header className="header sticky top-0 z-10">
        <h1 className="header-title">
          <span aria-hidden="true">🧾</span> PDV
        </h1>
        <div className="flex items-center gap-2">
          <button
            className="btn-outline"
            onClick={() => router.push("/cardapio")}
          >
            Cardápio
          </button>
          <button className="btn-outline" onClick={onLogout}>
            Sair
          </button>
        </div>
      </header>

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
                return (
                  <button
                    key={p.id}
                    type="button"
                    disabled={unavailable}
                    onClick={() => addProduct(p)}
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
        <aside className="bg-bg-primary border-t lg:border-t-0 lg:border-l border-border-light flex flex-col lg:sticky lg:top-[var(--header-h,4rem)] lg:h-[calc(100vh-4rem)]">
          <div className="p-4 border-b border-border-light flex items-center justify-between">
            <h2 className="font-bold text-text-primary">Carrinho</h2>
            {cartLines.length > 0 && (
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
                      ? "bg-primary-600 text-white border-primary-600"
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
            {cartLines.length === 0 ? (
              <p className="text-sm text-text-muted text-center py-8">
                Toque num produto para adicionar.
              </p>
            ) : (
              cartLines.map((line) => (
                <div
                  key={line.product.id}
                  className="flex items-center gap-2 bg-bg-secondary rounded-md p-2"
                >
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium text-text-primary truncate">
                      {line.product.name}
                    </p>
                    <p className="text-xs text-text-muted">
                      {formatBRL(line.product.effectivePriceCents)}
                    </p>
                  </div>
                  <div className="flex items-center gap-1">
                    <button
                      type="button"
                      aria-label={`Diminuir ${line.product.name}`}
                      onClick={() =>
                        setQuantity(line.product.id, line.quantity - 1)
                      }
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
                      aria-label={`Aumentar ${line.product.name}`}
                      onClick={() =>
                        setQuantity(line.product.id, line.quantity + 1)
                      }
                      className="w-7 h-7 rounded-md bg-bg-tertiary text-text-primary font-bold leading-none hover:bg-border-light"
                    >
                      +
                    </button>
                  </div>
                </div>
              ))
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
              disabled={
                cartLines.length === 0 || quoting || !quote || !!quoteError
              }
              onClick={() => setShowPayment(true)}
            >
              Finalizar
            </button>
          </div>
        </aside>
      </div>

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

  // A11y do dialog (WAI-ARIA): trava o scroll do fundo, move o foco para dentro,
  // fecha no ESC e prende o Tab dentro do modal (focus trap).
  useEffect(() => {
    const node = dialogRef.current;
    const prevOverflow = document.body.style.overflow;
    const prevFocus = document.activeElement as HTMLElement | null;
    document.body.style.overflow = "hidden";

    const focusable = () =>
      node
        ? Array.from(
            node.querySelectorAll<HTMLElement>(
              'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
            ),
          ).filter((el) => !el.hasAttribute("disabled"))
        : [];

    focusable()[0]?.focus();

    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") {
        e.preventDefault();
        onClose();
        return;
      }
      if (e.key !== "Tab") return;
      const els = focusable();
      if (els.length === 0) return;
      const first = els[0];
      const last = els[els.length - 1];
      const active = document.activeElement;
      if (e.shiftKey && active === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && active === last) {
        e.preventDefault();
        first.focus();
      }
    }

    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.body.style.overflow = prevOverflow;
      prevFocus?.focus();
    };
  }, [onClose]);

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
      setErr(
        e instanceof ApiError ? e.message : "Falha ao registrar o pedido.",
      );
      setSubmitting(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-end sm:items-center justify-center bg-black/50 p-0 sm:p-4"
      role="dialog"
      aria-modal="true"
      aria-label="Pagamento"
      onClick={onClose}
    >
      <div
        ref={dialogRef}
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
                  ? "bg-brand text-brand-text border-brand"
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
