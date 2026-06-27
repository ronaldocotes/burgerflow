"use client";

import { Suspense, useCallback, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { API_BASE, api, TOKEN_KEY } from "@/lib/api";
import { Category, Page, Product, formatBRL } from "@/types/menu";
import LoadingSpinner from "@/components/loading-spinner";

const PUBLIC_TENANT = process.env.NEXT_PUBLIC_TENANT_SLUG ?? "demo";

function CardapioContent() {
  const searchParams = useSearchParams();
  const tableParam = searchParams.get("table");
  const tableLabel = tableParam ? decodeURIComponent(tableParam) : null;

  const [categories, setCategories] = useState<Category[]>([]);
  const [products, setProducts] = useState<Product[]>([]);
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
      } else {
        const res = await fetch(`${API_BASE}/public/${PUBLIC_TENANT}/menu`);
        if (!res.ok) throw new Error("Cardapio indisponivel no momento.");
        const data = (await res.json()) as {
          categories: Category[];
          products: Product[];
        };
        setCategories(data.categories);
        setProducts(data.products);
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

  return (
    <main className="min-h-screen bg-bg-secondary">
      <header className="header sticky top-0 z-10">
        <h1 className="header-title">
          <span aria-hidden="true">🍔</span> Cardapio
        </h1>
      </header>

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
        {products.length === 0 ? (
          <div className="empty-state">
            <p className="empty-state-title">Cardapio vazio</p>
            <p className="empty-state-description">Nenhum produto cadastrado ainda.</p>
          </div>
        ) : (
          sections.map((section) => (
            <section key={section.key} className="mb-8">
              <h2 className="text-lg font-bold text-text-primary mb-3 pb-2 border-b border-border-light">
                {section.title}
              </h2>
              <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4">
                {section.items.map((p) => (
                  <ProductCard key={p.id} product={p} />
                ))}
              </div>
            </section>
          ))
        )}
      </div>
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

function ProductCard({ product }: { product: Product }) {
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
          <span className="text-lg font-bold text-primary-600">{formatBRL(product.effectivePriceCents)}</span>
          {product.onPromo && (
            <span className="text-sm text-text-muted line-through">{formatBRL(product.priceCents)}</span>
          )}
        </div>
      </div>
    </article>
  );
}
