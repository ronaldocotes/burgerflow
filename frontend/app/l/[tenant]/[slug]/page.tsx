"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { API_BASE } from "@/lib/api";
import type { PublicMenuLinkResponse } from "@/types/store-config";
import LoadingSpinner from "@/components/loading-spinner";

// Rota publica de resolucao de link/QR do cardapio (issue #11).
// URL: /l/{tenant}/{slug} -> resolve GET /public/{tenant}/l/{slug} e encaminha para
// /cardapio no modo certo:
//   - FULL      -> cardapio com pedido
//   - VIEW_ONLY -> ?view=only (esconde carrinho/checkout)
//   - COUNTER   -> cardapio com pedido (balcao). Ver nota sobre a mesa abaixo.
// Slug invalido/inativo -> 404 amigavel.

export default function ResolveMenuLinkPage() {
  const params = useParams<{ tenant: string; slug: string }>();
  const router = useRouter();
  const [status, setStatus] = useState<"resolving" | "notfound" | "error">("resolving");

  useEffect(() => {
    const tenant = params.tenant;
    const slug = params.slug;
    if (!tenant || !slug) {
      setStatus("notfound");
      return;
    }
    const ctrl = new AbortController();
    (async () => {
      try {
        const res = await fetch(`${API_BASE}/public/${tenant}/l/${slug}`, { signal: ctrl.signal });
        if (res.status === 404) {
          setStatus("notfound");
          return;
        }
        if (!res.ok) {
          setStatus("error");
          return;
        }
        const data = (await res.json()) as PublicMenuLinkResponse;
        const qs = new URLSearchParams({ tenant });
        if (!data.orderingEnabled || data.variant === "VIEW_ONLY") {
          qs.set("view", "only");
        }
        // NOTA: para COUNTER o backend devolve apenas tableId (UUID), sem o rotulo
        // da mesa; publicamente nao ha como resolver o "Mesa N". O pedido segue como
        // balcao. Pre-selecionar a mesa exige o label no PublicMenuLinkResponse
        // (follow-up de backend). Ver resumo da entrega.
        router.replace(`/cardapio?${qs.toString()}`);
      } catch (err) {
        if ((err as Error).name === "AbortError") return;
        setStatus("error");
      }
    })();
    return () => ctrl.abort();
  }, [params.tenant, params.slug, router]);

  if (status === "resolving") {
    return (
      <div className="flex min-h-screen items-center justify-center bg-bg-secondary">
        <LoadingSpinner size="lg" />
      </div>
    );
  }

  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-3 bg-bg-secondary px-4 text-center">
      <p className="text-lg font-semibold text-text-primary">
        {status === "notfound" ? "Link nao encontrado" : "Nao foi possivel abrir este link"}
      </p>
      <p className="text-sm text-text-secondary">
        {status === "notfound"
          ? "Este cardapio nao existe ou foi desativado."
          : "Tente novamente em instantes."}
      </p>
    </div>
  );
}
