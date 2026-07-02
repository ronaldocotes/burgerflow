"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { getToken } from "@/lib/auth";
import { useToast, ToastContainer } from "@/components/ui/toast";
import { ThemeSection } from "@/components/config/ThemeSection";
import { EntryPopupSection } from "@/components/config/EntryPopupSection";
import { MenuPreviewFrame } from "@/components/config/MenuPreviewFrame";
import type {
  EntryPopupConfig,
  PopupDraft,
  ThemeConfig,
  ThemeDraft,
} from "@/types/personalization";

// Tela de Personalização (Fase CONFIG-B, issues #12 tema + #13 pop-up). Tela PRÓPRIA
// (não seção de "Minha Loja"): o coração é o preview vivo em 2 colunas, incompatível
// com o padrão 1-coluna da CONFIG-A. Cada seção salva no seu endpoint (PATCH /config ×
// PUT /config/entry-popup) com seu próprio estado e toast. O preview mostra o estado
// EDITADO (antes de salvar) — daí o dirty guard.

type LoadState = "loading" | "error" | "ok";

function BlockSkeleton() {
  return (
    <div className="space-y-3 rounded-2xl bg-bg-primary p-6 shadow-card" aria-busy="true" aria-label="Carregando">
      <div className="h-5 w-1/3 rounded bg-bg-tertiary" aria-hidden="true" />
      <div className="h-11 rounded bg-bg-tertiary" aria-hidden="true" />
      <div className="h-24 rounded bg-bg-tertiary" aria-hidden="true" />
    </div>
  );
}

function RetryCard({ onRetry }: { onRetry: () => void }) {
  return (
    <div role="alert" className="flex flex-col items-center gap-4 rounded-2xl bg-bg-primary p-8 text-center shadow-card">
      <p className="text-base font-medium text-text-primary">Não foi possível carregar esta seção.</p>
      <button className="btn-primary min-h-11" onClick={onRetry}>Tentar novamente</button>
    </div>
  );
}

function themeDraftFrom(c: ThemeConfig): ThemeDraft {
  return {
    primaryColor: c.themePrimaryColor ?? "",
    showPrices: c.themeShowPrices,
    showDescriptions: c.themeShowDescriptions,
    showPhotos: c.themeShowPhotos,
  };
}

function popupDraftFrom(c: EntryPopupConfig): PopupDraft {
  return {
    enabled: c.enabled,
    title: c.title ?? "",
    productIds: c.products.map((p) => p.productId),
  };
}

export default function PersonalizacaoPage() {
  const router = useRouter();
  const { toasts, show: showToast } = useToast();

  const [themeState, setThemeState] = useState<LoadState>("loading");
  const [themeConfig, setThemeConfig] = useState<ThemeConfig | null>(null);
  const [themeDraft, setThemeDraft] = useState<ThemeDraft | null>(null);

  const [popupState, setPopupState] = useState<LoadState>("loading");
  const [popupConfig, setPopupConfig] = useState<EntryPopupConfig | null>(null);
  const [popupDraft, setPopupDraft] = useState<PopupDraft | null>(null);

  const [popupTrigger, setPopupTrigger] = useState(0);

  const isAuthenticated = typeof window === "undefined" || !!getToken();

  const loadTheme = useCallback(async () => {
    setThemeState("loading");
    try {
      const cfg = await api.get<ThemeConfig>("/config");
      setThemeConfig(cfg);
      setThemeDraft(themeDraftFrom(cfg));
      setThemeState("ok");
    } catch {
      setThemeState("error");
    }
  }, []);

  const loadPopup = useCallback(async () => {
    setPopupState("loading");
    try {
      const cfg = await api.get<EntryPopupConfig>("/config/entry-popup");
      setPopupConfig(cfg);
      setPopupDraft(popupDraftFrom(cfg));
      setPopupState("ok");
    } catch {
      setPopupState("error");
    }
  }, []);

  useEffect(() => {
    if (!isAuthenticated) {
      router.replace("/login");
      return;
    }
    queueMicrotask(() => {
      void loadTheme();
      void loadPopup();
    });
  }, [isAuthenticated, loadTheme, loadPopup, router]);

  // Dirty: rascunho diverge do estado salvo (baseline).
  const themeDirty = useMemo(() => {
    if (!themeConfig || !themeDraft) return false;
    const base = themeDraftFrom(themeConfig);
    return JSON.stringify(base) !== JSON.stringify(themeDraft);
  }, [themeConfig, themeDraft]);

  const popupDirty = useMemo(() => {
    if (!popupConfig || !popupDraft) return false;
    const base = popupDraftFrom(popupConfig);
    return JSON.stringify(base) !== JSON.stringify(popupDraft);
  }, [popupConfig, popupDraft]);

  const dirty = themeDirty || popupDirty;

  // Aviso de saída com alterações não salvas (o preview mostra o editado; sem o
  // guard o dono fecha achando que aplicou).
  useEffect(() => {
    if (!dirty) return;
    const handler = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = "";
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [dirty]);

  if (!isAuthenticated) return null;

  // Entradas do preview: usa o rascunho quando pronto, senão defaults (tudo visível).
  const preview = {
    primaryColor: themeDraft?.primaryColor ?? "",
    showPrices: themeDraft?.showPrices ?? true,
    showDescriptions: themeDraft?.showDescriptions ?? true,
    showPhotos: themeDraft?.showPhotos ?? true,
    popupTitle: popupDraft?.title ?? "",
    popupProductIds: popupDraft?.productIds ?? [],
  };

  return (
    <div className="flex min-h-screen flex-col bg-bg-secondary">
      <main className="mx-auto w-full max-w-6xl flex-1 px-4 pb-12 pt-6 sm:pt-8">
        <h2 className="mb-1 text-2xl font-bold text-text-primary">Personalização</h2>
        <p className="mb-6 text-sm text-text-secondary">
          Como seu cardápio aparece para o cliente. As mudanças só valem depois de salvar.
        </p>

        <div className="grid gap-8 lg:grid-cols-[1fr_360px]">
          {/* Coluna de controles */}
          <div className="space-y-8">
            {themeState === "loading" && <BlockSkeleton />}
            {themeState === "error" && <RetryCard onRetry={() => void loadTheme()} />}
            {themeState === "ok" && themeConfig && themeDraft && (
              <ThemeSection
                value={themeDraft}
                onChange={setThemeDraft}
                dirty={themeDirty}
                autoAcceptOrders={themeConfig.autoAcceptOrders}
                onSaved={(cfg) => {
                  setThemeConfig(cfg);
                  setThemeDraft(themeDraftFrom(cfg));
                }}
                showToast={showToast}
              />
            )}

            {popupState === "loading" && <BlockSkeleton />}
            {popupState === "error" && <RetryCard onRetry={() => void loadPopup()} />}
            {popupState === "ok" && popupDraft && (
              <EntryPopupSection
                value={popupDraft}
                onChange={setPopupDraft}
                dirty={popupDirty}
                onSaved={(cfg) => {
                  setPopupConfig(cfg);
                  setPopupDraft(popupDraftFrom(cfg));
                }}
                showToast={showToast}
                onPreviewPopup={() => setPopupTrigger((n) => n + 1)}
              />
            )}
          </div>

          {/* Coluna do preview vivo (sticky no desktop) */}
          <MenuPreviewFrame
            primaryColor={preview.primaryColor}
            showPrices={preview.showPrices}
            showDescriptions={preview.showDescriptions}
            showPhotos={preview.showPhotos}
            popupTitle={preview.popupTitle}
            popupProductIds={preview.popupProductIds}
            popupTrigger={popupTrigger}
          />
        </div>
      </main>

      <ToastContainer toasts={toasts} />
    </div>
  );
}
