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

export interface PublicOptionResponse {
  id: string;
  name: string;
  priceCents: number;
}

export interface PublicOptionGroupResponse {
  id: string;
  name: string;
  minSelect: number;
  maxSelect: number;
  required: boolean;
  options: PublicOptionResponse[];
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
  optionGroups?: PublicOptionGroupResponse[];
}

// --- Variações de produto (pizza) e complementos ---
// O catálogo NÃO sinaliza se um produto é pizza/tem complemento; o PDV descobre
// buscando estes endpoints por produto. Lista vazia = não se aplica.

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

/** Tipos de massa (enum DoughType do backend) com rótulo PT-BR. */
export const DOUGH_TYPES: { value: string; label: string }[] = [
  { value: "FINA", label: "Fina" },
  { value: "GROSSA", label: "Grossa" },
  { value: "INTEGRAL", label: "Integral" },
  { value: "AMERICANA", label: "Americana" },
  { value: "NAPOLITANA", label: "Napolitana" },
];

/** Rótulos PT-BR das bordas (enum CrustType). */
export const CRUST_LABELS: Record<string, string> = {
  TRADICIONAL: "Tradicional",
  CATUPIRY: "Catupiry",
  CHEDDAR: "Cheddar",
  CHOCOLATE: "Chocolate",
  CREAM_CHEESE: "Cream cheese",
  SEM_BORDA: "Sem borda",
};

/** Formata centavos como BRL (R$ 1.234,56). */
export function formatBRL(cents: number): string {
  return new Intl.NumberFormat("pt-BR", {
    style: "currency",
    currency: "BRL",
  }).format(cents / 100);
}
