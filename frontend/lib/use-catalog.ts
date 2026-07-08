// Hook do catálogo do PDV/pedido manual: carrega produtos, categorias, turno de
// caixa e a config de fidelidade que o carrinho usa no aviso pós-venda. Extraído
// de app/pdv/page.tsx (fatia 2) para ser reusado por /pedidos (fatia 3).
//
// NÃO dispara o fetch sozinho ao montar: quem chama decide QUANDO (o /pdv guarda
// o carregamento atrás de um check de token — ver a guarda em app/pdv/page.tsx).
// Chame `reload()` a partir de um efeito do componente que usa o hook.

"use client";

import { useCallback, useState } from "react";
import { api } from "./api";
import { Category, Page, Product } from "@/types/menu";

export interface CatalogLoyaltyConfig {
  enabled: boolean;
  pointsPerReal: number;
}

export interface UseCatalogOptions {
  /** Chamado quando uma chamada falha com 401. Se retornar true, a falha já foi tratada (ex.: redirect ao login). */
  onUnauthorized?: (err: unknown) => boolean;
}

export interface CatalogState {
  products: Product[];
  categories: Category[];
  /** Config de fidelidade (Fase 3.3) — null se a config não pôde ser lida (fail-open, sem toast). */
  config: CatalogLoyaltyConfig | null;
  /** true = há turno de caixa aberto (checado ao carregar). */
  hasCashSession: boolean;
  loading: boolean;
  error: string | null;
  reload: () => Promise<void>;
}

export function useCatalog(opts: UseCatalogOptions = {}): CatalogState {
  const { onUnauthorized } = opts;
  const [products, setProducts] = useState<Product[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [config, setConfig] = useState<CatalogLoyaltyConfig | null>(null);
  const [hasCashSession, setHasCashSession] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [prods, cats, cashSession, cfg] = await Promise.all([
        api.get<Page<Product>>("/products?size=200"),
        api.get<Page<Category>>("/categories?size=200").catch(() => null),
        // 204 → undefined (sem turno aberto); erro de rede → ignora silenciosamente
        api.get<unknown>("/cash-sessions/current").catch(() => undefined),
        // Fidelidade: falha na config nao derruba o PDV (fail-open, sem toast)
        api.get<Record<string, unknown>>("/config").catch(() => null),
      ]);
      setProducts(prods.content);
      setCategories(
        (cats?.content ?? [])
          .filter((c) => c.active)
          .sort((a, b) => a.displayOrder - b.displayOrder),
      );
      // cashSession = undefined significa 204 (sem turno) ou falha na rede
      setHasCashSession(cashSession !== undefined && cashSession !== null);
      setConfig(
        cfg
          ? {
              enabled: Boolean(cfg.loyaltyEnabled),
              pointsPerReal: Number(cfg.loyaltyPointsPerReal ?? 0),
            }
          : null,
      );
    } catch (err) {
      if (onUnauthorized?.(err)) return;
      setError(err instanceof Error ? err.message : "Erro ao carregar produtos.");
    } finally {
      setLoading(false);
    }
  }, [onUnauthorized]);

  return { products, categories, config, hasCashSession, loading, error, reload };
}
