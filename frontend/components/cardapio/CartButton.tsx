"use client";

import { formatBRL } from "@/types/menu";
import type { CartLine } from "./types";

interface Props {
  cart: CartLine[];
  onClick: () => void;
}

export function CartButton({ cart, onClick }: Props) {
  const count = cart.reduce((sum, i) => sum + i.quantity, 0);
  const total = cart.reduce(
    (sum, i) => sum + i.product.effectivePriceCents * i.quantity,
    0,
  );

  if (count === 0) return null;

  return (
    <button
      onClick={onClick}
      className="fixed bottom-6 left-1/2 -translate-x-1/2 z-30 bg-primary-700 text-white rounded-full px-6 py-3 shadow-xl min-h-[48px] flex items-center gap-2 font-medium"
      aria-label={`Ver carrinho: ${count} ${count === 1 ? "item" : "itens"}, total ${formatBRL(total)}`}
    >
      <span aria-hidden="true">🛒</span>
      {count} {count === 1 ? "item" : "itens"} · {formatBRL(total)}
    </button>
  );
}
