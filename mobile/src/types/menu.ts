// Tipos do cardapio espelhando as respostas do backend (CategoryResponse,
// ProductResponse, Page<T> do Spring Data). Dinheiro em CENTAVOS (number).
// Espelha frontend/types/menu.ts — manter em sincronia.

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

// --- Variacoes de produto (pizza) e complementos ---
// O catalogo NAO sinaliza se um produto e pizza/tem complemento; o PDV descobre
// buscando estes endpoints por produto. Lista vazia = nao se aplica.

/** GET /products/{id}/option-groups */
export interface ProductOptionGroup {
  id: string;
  name: string;
  minSelect: number;
  maxSelect: number;
  required: boolean;
  active: boolean;
  displayOrder: number;
  options: ProductOption[];
}

export interface ProductOption {
  id: string;
  name: string;
  priceCents: number;
  active: boolean;
  displayOrder: number;
}

/** GET /products/{id}/sizes */
export interface ProductSize {
  id: string;
  name: string;
  code: string;
  priceCents: number;
  promoPriceCents: number | null;
  active: boolean;
  displayOrder: number;
}

/** GET /products/{id}/flavors */
export interface ProductFlavor {
  id: string;
  name: string;
  description: string;
  priceCents: number;
  active: boolean;
  displayOrder: number;
}

/** GET /products/{id}/crust-prices */
export interface ProductCrustPrice {
  id: string;
  crustType: string;
  priceCents: number;
}

/** Variacoes carregadas em paralelo ao tocar num produto. */
export interface ProductVariations {
  groups: ProductOptionGroup[];
  sizes: ProductSize[];
  flavors: ProductFlavor[];
  crusts: ProductCrustPrice[];
}

/** Tipos de massa (enum DoughType do backend) com rotulo PT-BR. */
export const DOUGH_TYPES: { value: string; label: string }[] = [
  { value: 'FINA', label: 'Fina' },
  { value: 'GROSSA', label: 'Grossa' },
  { value: 'INTEGRAL', label: 'Integral' },
  { value: 'AMERICANA', label: 'Americana' },
  { value: 'NAPOLITANA', label: 'Napolitana' },
];

/** Rotulos PT-BR das bordas (enum CrustType). */
export const CRUST_LABELS: Record<string, string> = {
  TRADICIONAL: 'Tradicional',
  CATUPIRY: 'Catupiry',
  CHEDDAR: 'Cheddar',
  CHOCOLATE: 'Chocolate',
  CREAM_CHEESE: 'Cream cheese',
  SEM_BORDA: 'Sem borda',
};
