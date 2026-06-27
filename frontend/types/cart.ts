// Tipos do carrinho/PDV. O total NUNCA é calculado no front: vem do POST /orders/quote
// (mesma lógica do create no backend). Dinheiro em CENTAVOS.

export interface OrderItemInput {
  productId: string;
  quantity: number;
  optionIds?: string[];
  sizeId?: string | null;
  flavor1Id?: string | null;
  flavor2Id?: string | null;
  crustType?: string | null;
  doughType?: string | null;
  notes?: string | null;
}

export type OrderType = "DINE_IN" | "TAKEAWAY" | "DELIVERY";
export type PaymentMethod = "CASH" | "CREDIT_CARD" | "DEBIT_CARD" | "PIX" | "OTHER";

export interface QuoteRequest {
  orderType: OrderType;
  items: OrderItemInput[];
  deliveryFeeCents?: number;
  discountCents?: number;
}

export interface QuoteItemResponse {
  productId: string;
  productName: string;
  quantity: number;
  unitPriceCents: number;
  totalPriceCents: number;
}

export interface QuoteResponse {
  items: QuoteItemResponse[];
  subtotalCents: number;
  discountCents: number;
  deliveryFeeCents: number;
  totalCents: number;
}

export interface OrderCreateInput {
  orderType: OrderType;
  items: OrderItemInput[];
  paymentMethod?: PaymentMethod;
  discountCents?: number;
  deliveryFeeCents?: number;
  tableNumber?: string | null;
}

// Linha do carrinho no PDV. Tem id próprio porque o mesmo produto com escolhas
// diferentes (tamanho/sabor/borda/complementos) vira linhas distintas. `item`
// carrega as escolhas; `label` é o resumo legível; o preço unitário NÃO mora aqui —
// vem do quote, casado por índice (ordem do request preservada pelo backend).
export interface CartLine {
  lineId: string;
  productId: string;
  productName: string;
  quantity: number;
  item: OrderItemInput;
  label: string;
}
