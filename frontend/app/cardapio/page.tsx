"use client";

import { Suspense, useCallback, useEffect, useReducer, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import { API_BASE, api, TOKEN_KEY } from "@/lib/api";
import { Category, Page, Product, formatBRL } from "@/types/menu";
import LoadingSpinner from "@/components/loading-spinner";
import { cartReducer } from "@/components/cardapio/types";
import type { CartItem } from "@/components/cardapio/types";
import { CartButton } from "@/components/cardapio/CartButton";
import { CartSheet } from "@/components/cardapio/CartSheet";
import { CheckoutModal } from "@/components/cardapio/CheckoutModal";
import { RestaurantHero } from "@/components/cardapio/RestaurantHero";

const PUBLIC_TENANT = process.env.NEXT_PUBLIC_TENANT_SLUG ?? "demo";
const CART_KEY = "mf_cart";

function loadCart(): CartItem[] {
  try {
    const raw = sessionStorage.getItem(CART_KEY);
    if (!raw) return [];
    return JSON.parse(raw) as CartItem[];
  } catch {
    return [];
  }
}

function saveCart(cart: CartItem[]): void {
  try {
    sessionStorage.setItem(CART_KEY, JSON.stringify(cart));
  } catch {
    // sessionStorage pode estar bloqueado em modo privado
  }
}

interface RestaurantInfo {
  name: string | null;
  logoUrl: string | null;
  coverUrl: string | null;
  address: string | null;
  openingHours: string | null;
}

const EMPTY_RESTAURANT_INFO: RestaurantInfo = {
  name: null,
  logoUrl: null,
  coverUrl: null,
  address: null,
  openingHours: null,
};

interface PublicMenuResponse {
  categories: Category[];
  products: Product[];
  pixKey: string | null;
  restaurantInfo: RestaurantInfo;
  bestsellerIds: string[];
}

// ── Barra de categorias sticky estilo iFood ─────────────────────────────────────────────

function CategoryBar({
  sections,
  activeId,
  onSelect,
}: {
  sections: { key: string; title: string }[];
  activeId: string;
  onSelect: (id: string) => void;
}) {
  const barRef = useRef<HTMLDivElement>(null);

  // Mantém o botão ativo visível dentro da barra horizontal
  useEffect(() => {
    const bar = barRef.current;
    if (!bar || !activeId) return;
    const btn = bar.querySelector<HTMLElement>(`[data-cat="${activeId}"]`);
    if (btn) btn.scrollIntoView({ behavior: "smooth", block: "nearest", inline: "center" });
  }, [activeId]);

  if (!sections.length) return null;

  return (
    <div
      className="sticky top-16 z-[9] bg-bg-primary border-b border-border-light shadow-sm"
      role="navigation"
      aria-label="Categorias do cardapio"
    >
      <div ref={barRef} className="flex gap-1.5 px-4 py-2 overflow-x-auto no-scrollbar">
        {sections.map((s) => {
          const id = `section-${s.key}`;
          const isActive = activeId === id;
          return (
            <button
              key={s.key}
              data-cat={id}
              onClick={() => onSelect(id)}
              aria-current={isActive ? "true" : undefined}
              className={[
                "whitespace-nowrap rounded-full px-4 py-1.5 text-sm font-medium transition-colors duration-150 flex-shrink-0",
                isActive
                  ? "bg-primary-700 text-white"
                  : "bg-bg-tertiary text-text-secondary hover:text-text-primary",
              ].join(" ")}
            >
              {s.title}
            </button>
          );
        })}
      </div>
    </div>
  );
}

// ── Rastreia qual seção está visível (IntersectionObserver) ───────────────────

function useActiveSection(ids: string[]) {
  const [activeId, setActiveId] = useState("");

  useEffect(() => {
    if (!ids.length) return;
    // rootMargin desconta header (64px) + barra de categorias (~48px) no topo
    const observer = new IntersectionObserver(
      (entries) => {
        const visible = entries
          .filter((e) => e.isIntersecting)
          .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top);
        if (visible.length) setActiveId(visible[0].target.id);
      },
      { rootMargin: "-112px 0px -60% 0px", threshold: 0 },
    );
    ids.forEach((id) => {
      const el = document.getElementById(id);
      if (el) observer.observe(el);
    });
    return () => observer.disconnect();
  }, [ids]);

  return activeId;
}

function CardapioContent() {
  const searchParams = useSearchParams();
  const tableParam = searchParams.get("table");
  const tableLabel = tableParam ? decodeURIComponent(tableParam) : null;

  const [categories, setCategories] = useState<Category[]>([]);
  const [products, setProducts] = useState<Product[]>([]);
  const [pixKey, setPixKey] = useState<string | null>(null);
  const [restaurantInfo, setRestaurantInfo] = useState<RestaurantInfo>(EMPTY_RESTAURANT_INFO);
  const [bestsellerIds, setBestsellerIds] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const token =
        typeof window !== "undefined"
          ? window.localStorage.getItem(TOKEN_KEY)
          : null;

      if (token) {
        const [cats, prods] = await Promise.all([
          api.get<Page<Category>>("/categories?size=100"),
          api.get<Page<Product>>("/products?size=200"),
        ]);
        setCategories(cats.content);
        setProducts(prods.content);
        setPixKey(null);
      } else {
        const res = await fetch(`${API_BASE}/public/${PUBLIC_TENANT}/menu`);
        if (!res.ok) throw new Error("Cardapio indisponivel no momento.");
        const data = (await res.json()) as PublicMenuResponse;
        setCategories(data.categories);
        setProducts(data.products);
        setPixKey(data.pixKey ?? null);
        setRestaurantInfo(data.restaurantInfo ?? EMPTY_RESTAURANT_INFO);
        setBestsellerIds(data.bestsellerIds ?? []);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "Erro ao carregar o cardapio.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

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
        <p className="text-error font-medium" role="alert">{error}</p>
        <button className="btn-primary" onClick={() => void load()}>
          Tentar de novo
        </button>
      </div>
    );
  }

  const byCategory = new Map<string, Product[]>();
  for (const p of products) {
    const list = byCategory.get(p.categoryId) ?? [];
    list.push(p);
    byCategory.set(p.categoryId, list);
  }
  const knownIds = new Set(categories.map((c) => c.id));
  const orphans = products.filter((p) => !knownIds.has(p.categoryId));
  const orderedCategories = [...categories].sort((a, b) => a.displayOrder - b.displayOrder);

  const sections: { key: string; title: string; items: Product[] }[] = [
    ...orderedCategories
      .map((c) => ({
        key: c.id,
        title: c.name,
        items: (byCategory.get(c.id) ?? []).sort((a, b) => a.displayOrder - b.displayOrder),
      }))
      .filter((s) => s.items.length > 0),
    ...(orphans.length > 0 ? [{ key: "__orphans__", title: "Outros", items: orphans }] : []),
  ];

  const sectionIds = sections.map((s) => `section-${s.key}`);

  return (
    <CardapioView
      sections={sections}
      sectionIds={sectionIds}
      tableLabel={tableLabel}
      pixKey={pixKey}
      products={products}
      restaurantInfo={restaurantInfo}
      bestsellerIds={bestsellerIds}
    />
  );
}

// Componente separado para poder usar hooks após os dados estarem prontos
function CardapioView({
  sections,
  sectionIds,
  tableLabel,
  pixKey,
  products,
  restaurantInfo,
  bestsellerIds,
}: {
  sections: { key: string; title: string; items: Product[] }[];
  sectionIds: string[];
  tableLabel: string | null;
  pixKey: string | null;
  products: Product[];
  restaurantInfo: RestaurantInfo;
  bestsellerIds: string[];
}) {
  // Carrinho: inicia vazio, carrega do sessionStorage após hidratação
  const [cart, dispatch] = useReducer(cartReducer, [] as CartItem[]);
  const [cartHydrated, setCartHydrated] = useState(false);

  useEffect(() => {
    if (cartHydrated) return;
    const saved = loadCart();
    saved.forEach((item) => {
      for (let i = 0; i < item.quantity; i++) {
        dispatch({ type: "ADD", product: item.product });
      }
    });
    setCartHydrated(true);
  }, [cartHydrated]);

  useEffect(() => {
    if (cartHydrated) saveCart(cart);
  }, [cart, cartHydrated]);

  const [showCart, setShowCart] = useState(false);
  const [showCheckout, setShowCheckout] = useState(false);

  const activeId = useActiveSection(sectionIds);

  function scrollTo(id: string) {
    const el = document.getElementById(id);
    if (el) el.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  function handleNewOrder() {
    dispatch({ type: "CLEAR" });
    setShowCheckout(false);
    setShowCart(false);
  }

  const productMap = new Map(products.map((p) => [p.id, p]));
  const bestsellerProducts = bestsellerIds
    .map((id) => productMap.get(id))
    .filter((p): p is Product => p !== undefined);

  return (
    <main className="min-h-screen bg-bg-secondary pb-24">
      <header className="header sticky top-0 z-10">
        <h1 className="header-title">
          <span aria-hidden="true">🍔</span> Cardapio
        </h1>
      </header>

      <RestaurantHero restaurantInfo={restaurantInfo} />

      <CategoryBar
        sections={sections.map((s) => ({ key: s.key, title: s.title }))}
        activeId={activeId}
        onSelect={scrollTo}
      />

      {tableLabel && (
        <div
          className="bg-primary-700 text-white px-4 py-2 text-sm font-semibold flex items-center gap-2"
          role="status"
          aria-label={`Pedindo para a mesa ${tableLabel}`}
        >
          <span aria-hidden="true">🍽</span>
          Mesa {tableLabel}
        </div>
      )}

      <div className="max-w-5xl mx-auto p-4 md:p-6">
        {bestsellerProducts.length > 0 && (
          <div className="mb-10">
            <h2 className="text-lg font-bold text-text-primary mb-3 pb-2 border-b border-border-light">
              <span aria-hidden="true">🔥</span> Mais Pedidos
            </h2>
            <div className="flex gap-4 overflow-x-auto no-scrollbar pb-2">
              {bestsellerProducts.map((p) => {
                const qty = cart.find((i) => i.product.id === p.id)?.quantity ?? 0;
                return (
                  <BestsellerCard
                    key={p.id}
                    product={p}
                    cartQuantity={qty}
                    onAdd={() => dispatch({ type: "ADD", product: p })}
                    onIncrement={() => dispatch({ type: "INCREMENT", productId: p.id })}
                    onDecrement={() => dispatch({ type: "DECREMENT", productId: p.id })}
                  />
                );
              })}
            </div>
          </div>
        )}
        {sections.length === 0 ? (
          <div className="empty-state">
            <p className="empty-state-title">Cardapio vazio</p>
            <p className="empty-state-description">Nenhum produto cadastrado ainda.</p>
          </div>
        ) : (
          sections.map((section) => (
            <section
              key={section.key}
              id={`section-${section.key}`}
              className="mb-10 scroll-mt-28"
            >
              <h2 className="text-lg font-bold text-text-primary mb-3 pb-2 border-b border-border-light">
                {section.title}
              </h2>
              <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
                {section.items.map((p) => (
                  <ProductCard
                    key={p.id}
                    product={p}
                    cartQuantity={
                      cart.find((i) => i.product.id === p.id)?.quantity ?? 0
                    }
                    onAdd={() => dispatch({ type: "ADD", product: p })}
                  />
                ))}
              </div>
            </section>
          ))
        )}
      </div>

      <CartButton cart={cart} onClick={() => setShowCart(true)} />

      {showCart && (
        <CartSheet
          cart={cart}
          dispatch={dispatch}
          onClose={() => setShowCart(false)}
          onCheckout={() => {
            setShowCart(false);
            setShowCheckout(true);
          }}
        />
      )}

      {showCheckout && (
        <CheckoutModal
          cart={cart}
          pixKey={pixKey}
          tableLabel={tableLabel}
          tenantSlug={PUBLIC_TENANT}
          onClose={() => setShowCheckout(false)}
          onNewOrder={handleNewOrder}
        />
      )}
    </main>
  );
}

export default function CardapioPage() {
  return (
    <Suspense
      fallback={
        <div className="min-h-screen flex items-center justify-center bg-bg-secondary">
          <LoadingSpinner size="lg" />
        </div>
      }
    >
      <CardapioContent />
    </Suspense>
  );
}

function BestsellerCard({
  product,
  cartQuantity,
  onAdd,
  onIncrement,
  onDecrement,
}: {
  product: Product;
  cartQuantity: number;
  onAdd: () => void;
  onIncrement: () => void;
  onDecrement: () => void;
}) {
  const unavailable = !product.isAvailable;
  return (
    <article
      className={`flex gap-3 w-56 flex-shrink-0 bg-bg-primary rounded-xl p-3 shadow-sm border border-border-light ${unavailable ? "opacity-60" : ""}`}
    >
      {/* Imagem com badge de destaque */}
      <div className="relative flex-shrink-0">
        {product.imageUrl ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={product.imageUrl}
            alt={product.name}
            className="w-16 h-16 rounded-lg object-cover"
          />
        ) : (
          <div
            className="w-16 h-16 rounded-lg bg-bg-tertiary flex items-center justify-center text-2xl"
            aria-hidden="true"
          >
            🍽️
          </div>
        )}
        <span className="absolute top-1 left-1 text-xs bg-amber-100 text-amber-800 px-1.5 py-0.5 rounded-full font-medium leading-none">
          🔥 Mais Pedido
        </span>
      </div>

      {/* Informacoes e controles */}
      <div className="flex flex-col flex-1 min-w-0">
        <h3 className="text-sm font-semibold text-text-primary leading-tight line-clamp-2">
          {product.name}
        </h3>
        <p className="text-sm font-bold text-primary-600 mt-1">
          {formatBRL(product.effectivePriceCents)}
        </p>

        <div className="mt-auto pt-1">
          {unavailable ? (
            <span className="text-xs text-text-muted">Indisponivel</span>
          ) : cartQuantity > 0 ? (
            <div className="flex items-center gap-1.5">
              <button
                onClick={onDecrement}
                className="w-7 h-7 rounded-full border border-border-medium flex items-center justify-center text-text-primary hover:bg-bg-tertiary transition-colors text-sm leading-none"
                aria-label={`Remover ${product.name} do carrinho`}
              >
                −
              </button>
              <span className="text-xs font-semibold text-text-primary w-4 text-center">
                {cartQuantity}
              </span>
              <button
                onClick={onIncrement}
                className="w-7 h-7 rounded-full bg-primary-700 text-white flex items-center justify-center hover:bg-primary-800 transition-colors text-sm leading-none"
                aria-label={`Adicionar mais ${product.name} ao carrinho`}
              >
                +
              </button>
            </div>
          ) : (
            <button
              onClick={onAdd}
              className="text-xs bg-primary-700 text-white px-2 py-1 rounded-lg hover:bg-primary-800 transition-colors"
              aria-label={`Adicionar ${product.name} ao carrinho`}
            >
              Adicionar
            </button>
          )}
        </div>
      </div>
    </article>
  );
}

function ProductCard({
  product,
  cartQuantity,
  onAdd,
}: {
  product: Product;
  cartQuantity: number;
  onAdd: () => void;
}) {
  const unavailable = !product.isAvailable;
  return (
    <article className={`pos-product-card relative ${unavailable ? "opacity-60" : ""}`}>
      {product.imageUrl ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={product.imageUrl}
          alt={product.name}
          width={400}
          height={192}
          className="pos-product-image"
        />
      ) : (
        <div
          aria-hidden="true"
          className="pos-product-image bg-bg-tertiary flex items-center justify-center text-4xl"
        >
          🍽️
        </div>
      )}

      <div className="absolute top-2 left-2 flex flex-wrap gap-1">
        {product.onPromo && (
          <span className="text-xs font-bold px-2 py-0.5 rounded-full bg-amber-100 text-amber-800 border border-amber-300">
            PROMO
          </span>
        )}
        {product.isFeatured && (
          <span className="text-xs font-bold px-2 py-0.5 rounded-full bg-primary-100 text-primary-800 border border-primary-300">
            DESTAQUE
          </span>
        )}
        {unavailable && (
          <span className="text-xs font-bold px-2 py-0.5 rounded-full bg-red-100 text-red-800 border border-red-300">
            INDISPONIVEL
          </span>
        )}
      </div>

      <div className="p-3">
        <h3 className="font-semibold text-text-primary leading-tight">{product.name}</h3>
        {product.description && (
          <p className="text-sm text-text-secondary mt-1 line-clamp-2">{product.description}</p>
        )}
        <div className="mt-2 flex items-baseline gap-2">
          <span className="text-lg font-bold text-primary-600">
            {formatBRL(product.effectivePriceCents)}
          </span>
          {product.onPromo && (
            <span className="text-sm text-text-muted line-through">
              {formatBRL(product.priceCents)}
            </span>
          )}
        </div>

        {unavailable ? (
          <button
            disabled
            className="btn-primary w-full mt-2 py-2 text-sm min-h-[48px]"
            aria-label={`${product.name} indisponivel`}
          >
            Indisponivel
          </button>
        ) : cartQuantity > 0 ? (
          <button
            onClick={onAdd}
            className="btn-outline w-full mt-2 py-2 text-sm min-h-[48px]"
            aria-label={`Adicionar mais ${product.name} ao carrinho`}
          >
            {cartQuantity} no carrinho
          </button>
        ) : (
          <button
            onClick={onAdd}
            className="btn-primary w-full mt-2 py-2 text-sm min-h-[48px]"
            aria-label={`Adicionar ${product.name} ao carrinho`}
          >
            Adicionar
          </button>
        )}
      </div>
    </article>
  );
}
