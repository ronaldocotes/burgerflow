// Hook do carrinho do PDV/pedido manual: estado das linhas + cotação no servidor
// (POST /orders/quote, casado por índice — o total NUNCA é somado no front) + cupom
// de desconto (pré-checagem pública, revalidada quando o subtotal muda). Extraído
// de app/pdv/page.tsx (fatia 2) para ser reusado por /pedidos (fatia 3).

"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api, ApiError, API_BASE } from "./api";
import { getTenant } from "./auth";
import {
  CartLine,
  OrderItemInput,
  OrderType,
  QuoteRequest,
  QuoteResponse,
} from "@/types/cart";
import { ApplyCouponResponse } from "@/types/coupon";

export interface UseOrderCartOptions {
  orderType: OrderType;
  /** Chamado quando o quote falha com 401. Se retornar true, a falha já foi tratada (ex.: redirect ao login). */
  onUnauthorized?: (err: unknown) => boolean;
}

export interface OrderCart {
  cart: CartLine[];
  /** Itens na ordem das linhas (o backend preserva a ordem → quote casa por índice). */
  items: OrderItemInput[];
  /** Produto sem variação: se já há uma linha simples do mesmo produto, incrementa. */
  addSimple: (product: { id: string; name: string }) => void;
  /** Produto com variações já resolvidas (vem do ItemCustomizeModal). */
  addCustom: (line: CartLine) => void;
  setQuantity: (lineId: string, quantity: number) => void;
  removeItem: (lineId: string) => void;
  /** Limpa carrinho + quote + cupom. */
  clear: () => void;

  quote: QuoteResponse | null;
  quoteError: string | null;
  quoting: boolean;

  // Cupom de desconto: pre-checagem publica (apply-coupon); o desconto REAL e
  // recalculado no servidor ao criar o pedido (couponCode no POST /orders).
  couponCode: string;
  setCouponCode: (code: string) => void;
  appliedCoupon: ApplyCouponResponse | null;
  appliedCouponCode: string | null;
  couponError: string | null;
  setCouponError: (msg: string | null) => void;
  applyingCoupon: boolean;
  couponDiscountCents: number;
  applyCoupon: () => Promise<void>;
  removeCoupon: () => void;
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

export function useOrderCart(opts: UseOrderCartOptions): OrderCart {
  const { orderType, onUnauthorized } = opts;
  const [cart, setCart] = useState<CartLine[]>([]);

  const [quote, setQuote] = useState<QuoteResponse | null>(null);
  const [quoteError, setQuoteError] = useState<string | null>(null);
  const [quoting, setQuoting] = useState(false);

  const [couponCode, setCouponCode] = useState("");
  const [appliedCoupon, setAppliedCoupon] = useState<ApplyCouponResponse | null>(null);
  const [appliedCouponCode, setAppliedCouponCode] = useState<string | null>(null);
  const [couponError, setCouponError] = useState<string | null>(null);
  const [applyingCoupon, setApplyingCoupon] = useState(false);
  const couponDiscountCents =
    appliedCoupon && appliedCoupon.valid ? appliedCoupon.discountCents : 0;

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
        if (onUnauthorized?.(err)) return;
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
  }, [onUnauthorized, items, orderType]);

  const addSimple = useCallback((p: { id: string; name: string }) => {
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
  }, []);

  const addCustom = useCallback((line: CartLine) => {
    setCart((prev) => [...prev, line]);
  }, []);

  const setQuantity = useCallback((lineId: string, quantity: number) => {
    setCart((prev) =>
      quantity <= 0
        ? prev.filter((l) => l.lineId !== lineId)
        : prev.map((l) => (l.lineId === lineId ? { ...l, quantity } : l)),
    );
  }, []);

  const removeItem = useCallback((lineId: string) => {
    setCart((prev) => prev.filter((l) => l.lineId !== lineId));
  }, []);

  const removeCoupon = useCallback(() => {
    setAppliedCoupon(null);
    setAppliedCouponCode(null);
    setCouponCode("");
    setCouponError(null);
  }, []);

  const clear = useCallback(() => {
    setCart([]);
    setQuote(null);
    setQuoteError(null);
    removeCoupon();
  }, [removeCoupon]);

  const applyCoupon = useCallback(async () => {
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
  }, [couponCode, quote, applyingCoupon]);

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

  return {
    cart,
    items,
    addSimple,
    addCustom,
    setQuantity,
    removeItem,
    clear,
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
  };
}
