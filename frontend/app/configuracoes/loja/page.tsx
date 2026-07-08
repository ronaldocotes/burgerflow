"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { getToken } from "@/lib/auth";
import type { StoreConfigFull } from "@/types/store-config";
import { useToast, ToastContainer } from "@/components/ui/toast";
import { StoreAddressSection } from "@/components/config/StoreAddressSection";
import { OpeningHoursSection } from "@/components/config/OpeningHoursSection";
import { PaymentMethodsSection } from "@/components/config/PaymentMethodsSection";
import { ModalityTimesSection } from "@/components/config/ModalityTimesSection";
import { CancellationReasonsSection } from "@/components/config/CancellationReasonsSection";
import { MenuLinksSection } from "@/components/config/MenuLinksSection";

// Chips-ancora da navegacao interna. scroll-mt-24 nas secoes compensa o Topbar sticky.
const CHIPS: { id: string; label: string }[] = [
  { id: "endereco", label: "Endereco" },
  { id: "horarios", label: "Horarios" },
  { id: "pagamento", label: "Pagamento" },
  { id: "tempos", label: "Tempos" },
  { id: "cancelamento", label: "Cancelamento" },
  { id: "links", label: "Links" },
];

function ConfigBlockSkeleton() {
  return (
    <div className="space-y-3 rounded-2xl bg-bg-primary p-6 shadow-card" aria-busy="true" aria-label="Carregando configuracoes da loja">
      <div className="h-5 w-1/3 rounded bg-bg-tertiary" aria-hidden="true" />
      <div className="h-10 rounded bg-bg-tertiary" aria-hidden="true" />
      <div className="h-10 rounded bg-bg-tertiary" aria-hidden="true" />
      <div className="h-40 rounded bg-bg-tertiary" aria-hidden="true" />
    </div>
  );
}

export default function MinhaLojaPage() {
  const router = useRouter();
  const { toasts, show: showToast } = useToast();

  const [config, setConfig] = useState<StoreConfigFull | null>(null);
  const [configState, setConfigState] = useState<"loading" | "error" | "ok">("loading");

  const isAuthenticated = typeof window === "undefined" || !!getToken();

  const loadConfig = useCallback(async () => {
    setConfigState("loading");
    try {
      const data = await api.get<StoreConfigFull>("/config");
      setConfig(data);
      setConfigState("ok");
    } catch {
      setConfigState("error");
    }
  }, []);

  useEffect(() => {
    if (!isAuthenticated) {
      router.replace("/login");
      return;
    }
    queueMicrotask(() => void loadConfig());
  }, [isAuthenticated, loadConfig, router]);

  if (!isAuthenticated) return null;

  return (
    <div className="flex min-h-screen flex-col bg-bg-secondary">
      {/* pt-2 no mobile da respiro entre o hamburguer do Topbar e o titulo (390px) */}
      <main className="mx-auto w-full max-w-3xl flex-1 px-4 pb-12 pt-6 sm:pt-8">
        <h2 className="mb-1 text-2xl font-bold text-text-primary">Minha Loja</h2>
        <p className="mb-4 text-sm text-text-secondary">
          Endereco, horarios, pagamento, prazos, cancelamentos e links do cardapio.
        </p>

        {/* Chips-ancora (rolam na horizontal no mobile) */}
        <nav aria-label="Secoes de Minha Loja" className="mb-6 flex gap-2 overflow-x-auto pb-1">
          {CHIPS.map((c) => (
            <a
              key={c.id}
              href={`#${c.id}`}
              className="inline-flex min-h-11 shrink-0 items-center rounded-full border border-border-light px-4 text-sm text-text-secondary hover:bg-bg-tertiary hover:text-text-primary"
            >
              {c.label}
            </a>
          ))}
        </nav>

        <div className="space-y-8">
          {/* Bloco baseado em GET /config: endereco, horarios, tempos */}
          {configState === "loading" && <ConfigBlockSkeleton />}
          {configState === "error" && (
            <div role="alert" className="flex flex-col items-center gap-4 rounded-2xl bg-bg-primary p-8 text-center shadow-card">
              <p className="text-base font-medium text-text-primary">
                Nao foi possivel carregar as configuracoes da loja.
              </p>
              <button className="btn-primary min-h-11" onClick={() => void loadConfig()}>
                Tentar novamente
              </button>
            </div>
          )}
          {configState === "ok" && config && (
            <>
              <StoreAddressSection config={config} showToast={showToast} />
              <OpeningHoursSection config={config} showToast={showToast} />
            </>
          )}

          {/* Secoes com fetch proprio (independentes do /config) */}
          <PaymentMethodsSection showToast={showToast} />

          {configState === "ok" && config && (
            <ModalityTimesSection config={config} showToast={showToast} />
          )}

          <CancellationReasonsSection showToast={showToast} />
          <MenuLinksSection showToast={showToast} />
        </div>
      </main>

      <ToastContainer toasts={toasts} />
    </div>
  );
}
