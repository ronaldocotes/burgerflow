"use client";

import { Suspense, useCallback, useEffect, useReducer, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import { API_BASE } from "@/lib/api";
import { Category, Product, formatBRL } from "@/types/menu";
import LoadingSpinner from "@/components/loading-spinner";
import { cartReducer } from "@/components/cardapio/types";
import type { CartLine } from "@/components/cardapio/types";
import { CartButton } from "@/components/cardapio/CartButton";
import { CartSheet } from "@/components/cardapio/CartSheet";
import { CheckoutModal } from "@/components/cardapio/CheckoutModal";
import { ProductDetailModal } from "@/components/cardapio/ProductDetailModal";
import { RestaurantHero } from "@/components/cardapio/RestaurantHero";

const PUBLIC_TENANT = process.env.NEXT_PUBLIC_TENANT_SLUG ?? "demo";
const CART_KEY = "mf_cart";

function loadCart(): CartLine[] {
  try {
    const raw = sessionStorage.getItem(CART_KEY);
    if (!raw) return [];
    return JSON.parse(raw) as CartLine[];
  } catch {
    return [];
  }
}

function saveCart(cart: CartLine[]): void {
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
      aria-label="Categorias do cardápio"
    >
      <div ref={barRef} className="flex flex-wrap gap-1.5 overflow-visible px-4 py-2">
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
                "min-h-11 whitespace-nowrap rounded-full px-4 py-2 text-sm font-medium transition-colors duration-150",
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
      // Vitrine PUBLICA: sempre o endpoint publico, sem Authorization. Antes a
      // pagina usava o token de admin do localStorage (login anterior no mesmo
      // navegador) e chamava /categories e /products protegidos; um token
      // expirado devolvia 401 e derrubava o cardapio inteiro ("Erro 401"). O
      // cardapio do cliente nao depende de sessao — e o endpoint publico ainda
      // traz hero, PIX, mais-vendidos e complementos, que o caminho autenticado
      // nao trazia.
      const res = await fetch(`${API_BASE}/public/${PUBLIC_TENANT}/menu`);
      if (!res.ok) throw new Error("Cardápio indisponível no momento.");
      const data = (await res.json()) as PublicMenuResponse;
      setCategories(data.categories);
      setProducts(data.products);
      setPixKey(data.pixKey ?? null);
      setRestaurantInfo(data.restaurantInfo ?? EMPTY_RESTAURANT_INFO);
      setBestsellerIds(data.bestsellerIds ?? []);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Erro ao carregar o cardápio.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    queueMicrotask(() => {
      void load();
    });
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
  const [cart, dispatch] = useReducer(cartReducer, [] as CartLine[]);
  const [cartHydrated, setCartHydrated] = useState(false);

  useEffect(() => {
    if (cartHydrated) return;
    const saved = loadCart();
    saved.forEach((item) => {
      dispatch({ type: "ADD_LINE", product: item.product, quantity: item.quantity, notes: item.notes });
    });
    queueMicrotask(() => {
      setCartHydrated(true);
    });
  }, [cartHydrated]);

  useEffect(() => {
    if (cartHydrated) saveCart(cart);
  }, [cart, cartHydrated]);

  const [showCart, setShowCart] = useState(false);
  const [showCheckout, setShowCheckout] = useState(false);
  const [selectedProduct, setSelectedProduct] = useState<Product | null>(null);
  const [exitToast, setExitToast] = useState(false);

  // Refs para leitura no closure do popstate sem re-registrar o listener
  const showCartRef = useRef(showCart);
  const showCheckoutRef = useRef(showCheckout);
  const selectedProductRef = useRef(selectedProduct);
  useEffect(() => { showCartRef.current = showCart; }, [showCart]);
  useEffect(() => { showCheckoutRef.current = showCheckout; }, [showCheckout]);
  useEffect(() => { selectedProductRef.current = selectedProduct; }, [selectedProduct]);

  const exitTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Intercepta botão voltar do Android/browser: fecha modais em cascata,
  // depois exige 2 cliques para sair (double-back-to-exit padrão Android).
  // Usa location.href como URL no pushState para não conflitar com o router do Next.js.
  useEffect(() => {
    history.pushState(null, "", location.href);

    function reintercept() {
      history.pushState(null, "", location.href);
    }

    function onPopState() {
      if (showCheckoutRef.current) { setShowCheckout(false); reintercept(); return; }
      if (showCartRef.current)     { setShowCart(false);     reintercept(); return; }
      if (selectedProductRef.current) { setSelectedProduct(null); reintercept(); return; }

      if (exitTimerRef.current) {
        clearTimeout(exitTimerRef.current);
        exitTimerRef.current = null;
        setExitToast(false);
        return; // segundo clique → sai de verdade (browser vai p/ página anterior ou fecha PWA)
      }

      reintercept();
      setExitToast(true);
      exitTimerRef.current = setTimeout(() => {
        setExitToast(false);
        exitTimerRef.current = null;
      }, 2000);
    }

    window.addEventListener("popstate", onPopState);
    return () => {
      window.removeEventListener("popstate", onPopState);
      if (exitTimerRef.current) clearTimeout(exitTimerRef.current);
    };
  }, []);

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

  const cartQtyFor = (productId: string) =>
    cart.reduce((sum, l) => (l.product.id === productId ? sum + l.quantity : sum), 0);

  function decrementProduct(productId: string) {
    const lastLine = [...cart].reverse().find((l) => l.product.id === productId);
    if (lastLine) dispatch({ type: "DECREMENT_LINE", lineId: lastLine.lineId });
  }

  const productMap = new Map(products.map((p) => [p.id, p]));
  const bestsellerProducts = bestsellerIds
    .map((id) => productMap.get(id))
    .filter((p): p is Product => p !== undefined);

  return (
    <main className="min-h-screen bg-bg-secondary pb-24">
      <header className="header sticky top-0 z-10">
        {/* Botão voltar — flex item, não absolute, para garantir visibilidade em todos os browsers */}
        <button
          onClick={() => window.history.back()}
          className="flex h-11 w-11 flex-shrink-0 items-center justify-center rounded-full text-text-primary transition-colors hover:bg-bg-tertiary active:bg-bg-tertiary"
          aria-label="Voltar"
        >
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M19 12H5M12 5l-7 7 7 7"/>
          </svg>
        </button>
        <h1 className="flex-1 text-center text-xl font-bold text-text-primary">
          <span aria-hidden="true">🍔</span> Cardápio
        </h1>
        {/* Espaçador igual ao botão para manter o título centralizado */}
        <div className="w-11 flex-shrink-0" aria-hidden="true" />
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
              {bestsellerProducts.map((p) => (
                <BestsellerCard
                  key={p.id}
                  product={p}
                  cartQuantity={cartQtyFor(p.id)}
                  onOpen={() => setSelectedProduct(p)}
                  onDecrement={() => decrementProduct(p.id)}
                />
              ))}
            </div>
          </div>
        )}
        {sections.length === 0 ? (
          <div className="empty-state">
            <p className="empty-state-title">Cardápio vazio</p>
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
                    cartQuantity={cartQtyFor(p.id)}
                    onOpen={() => setSelectedProduct(p)}
                    onDecrement={() => decrementProduct(p.id)}
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

      {selectedProduct && (
        <ProductDetailModal
          product={selectedProduct}
          onClose={() => setSelectedProduct(null)}
          onAdd={(qty, notes, options) => {
            dispatch({ type: "ADD_LINE", product: selectedProduct, quantity: qty, notes, options });
            setSelectedProduct(null);
          }}
        />
      )}

      {/* Toast double-back-to-exit */}
      {exitToast && (
        <div
          role="status"
          aria-live="polite"
          className="fixed bottom-28 left-1/2 -translate-x-1/2 z-[100] bg-gray-800/90 text-white text-sm px-5 py-2.5 rounded-full shadow-lg pointer-events-none whitespace-nowrap"
        >
          Pressione voltar novamente para sair
        </div>
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
  onOpen,
  onDecrement,
}: {
  product: Product;
  cartQuantity: number;
  onOpen: () => void;
  onDecrement: () => void;
}) {
  const unavailable = !product.isAvailable;
  return (
    <article
      className={`flex gap-3 w-56 flex-shrink-0 bg-bg-primary rounded-xl p-3 shadow-sm border border-border-light ${unavailable ? "opacity-60" : "cursor-pointer"}`}
      onClick={!unavailable ? onOpen : undefined}
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
            <span className="text-sm text-text-muted">Indisponivel</span>
          ) : cartQuantity > 0 ? (
            <div className="flex items-center gap-1.5">
              <button
                onClick={(e) => { e.stopPropagation(); onDecrement(); }}
                className="w-7 h-7 rounded-full border border-border-medium flex items-center justify-center text-text-primary hover:bg-bg-tertiary transition-colors text-sm leading-none"
                aria-label={`Remover ${product.name} do carrinho`}
              >
                −
              </button>
              <span className="w-4 text-center text-sm font-semibold text-text-primary">
                {cartQuantity}
              </span>
              <button
                onClick={(e) => { e.stopPropagation(); onOpen(); }}
                className="w-7 h-7 rounded-full bg-primary-700 text-white flex items-center justify-center hover:bg-primary-800 transition-colors text-sm leading-none"
                aria-label={`Adicionar mais ${product.name} ao carrinho`}
              >
                +
              </button>
            </div>
          ) : (
            <button
              onClick={(e) => { e.stopPropagation(); onOpen(); }}
              className="rounded-lg bg-primary-700 px-2 py-1 text-sm text-white hover:bg-primary-800 transition-colors"
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
  onOpen,
  onDecrement,
}: {
  product: Product;
  cartQuantity: number;
  onOpen: () => void;
  onDecrement: () => void;
}) {
  const unavailable = !product.isAvailable;
  return (
    <article
      className={`pos-product-card relative ${unavailable ? "opacity-60" : ""} ${!unavailable ? "cursor-pointer" : ""}`}
      onClick={!unavailable ? onOpen : undefined}
    >
      {/* Imagem + botão flutuante sobre ela */}
      <div className="relative">
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

        {/* Badges no canto superior esquerdo da imagem */}
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
              INDISPONÍVEL
            </span>
          )}
        </div>

        {/* Stepper flutuante — só aparece quando já há item no carrinho */}
        {!unavailable && cartQuantity > 0 && (
          <div
            className="absolute bottom-2 right-2 flex items-center gap-1 bg-bg-primary rounded-full shadow-lg px-1.5 py-1"
            onClick={(e) => e.stopPropagation()}
          >
            <button
              onClick={onDecrement}
              className="w-7 h-7 rounded-full border border-border-medium flex items-center justify-center text-text-primary hover:bg-bg-tertiary transition-colors text-base leading-none"
              aria-label={`Remover um ${product.name}`}
            >
              {'−'}
            </button>
            <span className="w-5 text-center font-bold text-text-primary text-sm select-none">
              {cartQuantity}
            </span>
            <button
              onClick={onOpen}
              className="w-7 h-7 rounded-full bg-primary-700 text-white flex items-center justify-center hover:bg-primary-800 transition-colors text-base leading-none"
              aria-label={`Adicionar mais ${product.name} ao carrinho`}
            >
              +
            </button>
          </div>
        )}
      </div>

      <div className="p-3 flex flex-col gap-0.5">
        <h3 className="font-semibold text-text-primary leading-tight">{product.name}</h3>
        {product.description && (
          <p className="line-clamp-2 text-sm text-text-secondary">{product.description}</p>
        )}
        <div className="mt-1.5 flex items-baseline gap-1.5">
          <span className="text-base font-bold text-text-primary">
            {formatBRL(product.effectivePriceCents)}
          </span>
          {product.onPromo && (
            <span className="text-sm text-text-muted line-through">
              {formatBRL(product.priceCents)}
            </span>
          )}
        </div>
      </div>
    </article>
  );
}
