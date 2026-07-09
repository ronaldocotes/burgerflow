// Tipos compartilhados entre os modais de pedido (PDV e, futuramente, /pedidos).
// Movidos de app/pdv/page.tsx (extração behavior-preserving, fatia 1).

import {
  ProductCrustPrice,
  ProductFlavor,
  ProductOptionGroup,
  ProductSize,
} from "@/types/menu";

// Variações de um produto (tamanho/sabor/borda/complementos) carregadas sob
// demanda ao abrir o ItemCustomizeModal.
export interface Variations {
  groups: ProductOptionGroup[];
  sizes: ProductSize[];
  flavors: ProductFlavor[];
  crusts: ProductCrustPrice[];
}

// Tipo do pedido criado (POST /orders).
export interface OrderCreatedResponse {
  id: string;
}

// Intenção de pagamento PIX (POST /payments/pix-qr e GET /payments/pix-qr/status/:id).
export interface PaymentIntentResponse {
  id: string;
  orderId: string;
  status: "PENDING" | "PAID" | "FAILED" | "EXPIRED";
  pixQrImage: string; // base64 PNG
  pixCopyPaste: string; // string copia-e-cola
  amountCents: number;
  expiresAt: string; // ISO
}
