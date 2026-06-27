import type { Product } from "@/types/menu";

export interface CartLineOption {
  optionId: string;
  groupName: string;
  optionName: string;
  priceCents: number;
}

export interface CartLine {
  lineId: string;
  product: Product;
  quantity: number;
  notes?: string;
  options?: CartLineOption[];
}

export type CartAction =
  | { type: "ADD_LINE"; product: Product; quantity: number; notes?: string; options?: CartLineOption[] }
  | { type: "INCREMENT_LINE"; lineId: string }
  | { type: "DECREMENT_LINE"; lineId: string }
  | { type: "CLEAR" };

export function cartReducer(state: CartLine[], action: CartAction): CartLine[] {
  switch (action.type) {
    case "ADD_LINE":
      return [
        ...state,
        {
          lineId: `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
          product: action.product,
          quantity: action.quantity,
          notes: action.notes?.trim() || undefined,
          options: action.options,
        },
      ];
    case "INCREMENT_LINE":
      return state.map((l) =>
        l.lineId === action.lineId ? { ...l, quantity: l.quantity + 1 } : l,
      );
    case "DECREMENT_LINE":
      return state
        .map((l) =>
          l.lineId === action.lineId ? { ...l, quantity: l.quantity - 1 } : l,
        )
        .filter((l) => l.quantity > 0);
    case "CLEAR":
      return [];
    default:
      return state;
  }
}
