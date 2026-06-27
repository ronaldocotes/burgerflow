// Tipos do cardápio espelhando as respostas do backend (CategoryResponse,
// ProductResponse, Page<T> do Spring Data). Dinheiro em CENTAVOS (number).

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface Category {
  id: string;
  name: string;
  description: string;
  displayOrder: number;
  active: boolean;
  colorCode: string | null;
  iconUrl: string | null;
}

export interface Product {
  id: string;
  categoryId: string;
  sku: string;
  name: string;
  description: string;
  priceCents: number;
  costPriceCents: number | null;
  imageUrl: string | null;
  active: boolean;
  isAvailable: boolean;
  displayOrder: number;
  preparationTimeMinutes: number;
  isFeatured: boolean;
  promoPriceCents: number | null;
  promoStartsAt: string | null;
  promoEndsAt: string | null;
  effectivePriceCents: number;
  onPromo: boolean;
  createdAt: string;
  updatedAt: string;
}

/** Formata centavos como BRL (R$ 1.234,56). */
export function formatBRL(cents: number): string {
  return new Intl.NumberFormat("pt-BR", {
    style: "currency",
    currency: "BRL",
  }).format(cents / 100);
}
