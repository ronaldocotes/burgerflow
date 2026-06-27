"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { getToken, logout } from "@/lib/auth";
import { Category, Page, Product, formatBRL } from "@/types/menu";
import LoadingSpinner from "@/components/loading-spinner";

export default function CardapioPage() {
  const router = useRouter();
  const [categories, setCategories] = useState<Category[]>([]);
  const [products, setProducts] = useState<Product[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [cats, prods] = await Promise.all([
        api.get<Page<Category>>("/categories?size=100"),
        api.get<Page<Product>>("/products?size=200"),
      ]);
      setCategories(cats.content);
      setProducts(prods.content);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Erro ao carregar o cardápio.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!getToken()) {
      router.push("/login");
      return;
    }
    void load();
  }, [router, load]);

  function onLogout() {
    logout();
    router.push("/login");
  }

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
        <p className="text-error font-medium" role="alert">
          {error}
        </p>
        <div className="flex gap-2">
          <button className="btn-primary" onClick={() => void load()}>
            Tentar de novo
          </button>
          <button className="btn-outline" onClick={onLogout}>
            Sair
          </button>
        </div>
      </div>
    );
  }

  // Agrupa produtos por categoria; o que não casar com categoria conhecida vai em "Outros".
  const byCategory = new Map<string, Product[]>();
  for (const p of products) {
    const list = byCategory.get(p.categoryId) ?? [];
    list.push(p);
    byCategory.set(p.categoryId, list);
  }
  const knownIds = new Set(categories.map((c) => c.id));
  const orphans = products.filter((p) => !knownIds.has(p.categoryId));
  const orderedCategories = [...categories].sort(
    (a, b) => a.displayOrder - b.displayOrder,
  );

  const sections: { key: string; title: string; items: Product[] }[] = [
    ...orderedCategories
      .map((c) => ({
        key: c.id,
        title: c.name,
        items: (byCategory.get(c.id) ?? []).sort(
          (a, b) => a.displayOrder - b.displayOrder,
        ),
      }))
      .filter((s) => s.items.length > 0),
    ...(orphans.length > 0
      ? [{ key: "__orphans__", title: "Outros", items: orphans }]
      : []),
  ];

  return (
    <main className="min-h-screen bg-bg-secondary">
      <header className="header sticky top-0 z-10">
        <h1 className="header-title">
          <span aria-hidden="true">🍔</span> Cardápio
        </h1>
        <button className="btn-outline" onClick={onLogout}>
          Sair
        </button>
      </header>

      <div className="max-w-5xl mx-auto p-4 md:p-6">
        {products.length === 0 ? (
          <div className="empty-state">
            <p className="empty-state-title">Cardápio vazio</p>
            <p className="empty-state-description">
              Nenhum produto cadastrado ainda. Cadastre produtos para que apareçam
              aqui.
            </p>
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

function ProductCard({ product }: { product: Product }) {
  const unavailable = !product.isAvailable;
  return (
    <article
      className={`pos-product-card relative ${unavailable ? "opacity-60" : ""}`}
    >
      {product.imageUrl ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={product.imageUrl}
          alt={product.name}
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
            INDISPONÍVEL
          </span>
        )}
      </div>

      <div className="p-3">
        <h3 className="font-semibold text-text-primary leading-tight">
          {product.name}
        </h3>
        {product.description && (
          <p className="text-sm text-text-secondary mt-1 line-clamp-2">
            {product.description}
          </p>
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
      </div>
    </article>
  );
}
