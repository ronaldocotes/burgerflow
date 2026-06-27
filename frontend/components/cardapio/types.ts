import type { Product } from "@/types/menu";

export interface CartItem {
  product: Product;
  quantity: number;
}

export type CartAction =
  | { type: "ADD"; product: Product }
  | { type: "INCREMENT"; productId: string }
  | { type: "DECREMENT"; productId: string }
  | { type: "CLEAR" };

export function cartReducer(state: CartItem[], action: CartAction): CartItem[] {
  switch (action.type) {
    case "ADD": {
      const idx = state.findIndex((i) => i.product.id === action.product.id);
      if (idx >= 0) {
        const next = [...state];
        next[idx] = { ...next[idx], quantity: next[idx].quantity + 1 };
        return next;
      }
      return [...state, { product: action.product, quantity: 1 }];
    }
    case "INCREMENT":
      return state.map((i) =>
        i.product.id === action.productId ? { ...i, quantity: i.quantity + 1 } : i,
      );
    case "DECREMENT":
      return state
        .map((i) =>
          i.product.id === action.productId ? { ...i, quantity: i.quantity - 1 } : i,
        )
        .filter((i) => i.quantity > 0);
    case "CLEAR":
      return [];
    default:
      return state;
  }
}
