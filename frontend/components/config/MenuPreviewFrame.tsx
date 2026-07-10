"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { PreviewMessage } from "@/types/personalization";
import { DEFAULT_PRIMARY, computeContrast } from "@/lib/contrast";
import { getTenant } from "@/lib/auth";

// Preview vivo do cardápio via IFRAME real do /cardapio?preview=1 (produtos REAIS
// do tenant, nunca mock). Aplica o estado EDITADO por postMessage — o iframe escuta
// e sobrepõe o que veio da API. Uma moldura de celular em CSS envolve o iframe.
// Desktop: mockup sticky ao lado dos controles. Mobile: bloco colapsável (o
// mockup-de-celular-dentro-do-celular espremido perde sentido).

interface Props {
  primaryColor: string | null; // "" ou null => default; textColor é derivado (WCAG)
  showPrices: boolean;
  showDescriptions: boolean;
  showPhotos: boolean;
  popupTitle: string | null;
  popupProductIds: string[];
  /** Incrementa a cada clique em "Ver pop-up" para forçar a abertura no iframe. */
  popupTrigger: number;
}

export function MenuPreviewFrame(props: Props) {
  const iframeRef = useRef<HTMLIFrameElement>(null);
  // Mesmo fallback de MenuLinksSection.tsx:49 — o iframe precisa carregar o cardápio
  // do tenant LOGADO. Sem ?tenant=, o /cardapio cairia no PUBLIC_TENANT (demo) e o
  // admin veria o cardápio errado no preview.
  const tenant = getTenant() ?? process.env.NEXT_PUBLIC_TENANT_SLUG ?? "demo";
  const previewSrc = `/cardapio?${new URLSearchParams({ preview: "1", tenant }).toString()}`;
  // Mantém o último estado dos props acessível de dentro do send() estável, sem
  // ler ref durante o render (atualizado por um effect logo abaixo).
  const stateRef = useRef(props);
  const [open, setOpen] = useState(true);

  useEffect(() => {
    stateRef.current = props;
  });

  const send = useCallback((showPopup: boolean) => {
    const p = stateRef.current;
    const win = iframeRef.current?.contentWindow;
    if (!win) return;
    const color = p.primaryColor && p.primaryColor.trim() !== "" ? p.primaryColor : null;
    const textColor = computeContrast(color ?? DEFAULT_PRIMARY)?.recommendedTextColor ?? "#FFFFFF";
    const msg: PreviewMessage = {
      type: "mf-preview",
      primaryColor: color,
      textColor,
      showPrices: p.showPrices,
      showDescriptions: p.showDescriptions,
      showPhotos: p.showPhotos,
      popup: { title: p.popupTitle, productIds: p.popupProductIds },
      showPopup,
    };
    // Mesma origem (o iframe é uma rota interna do próprio app).
    win.postMessage(msg, window.location.origin);
  }, []);

  // Reaplica o tema/toggles/pop-up sempre que o rascunho muda (sem debounce — é
  // estado local do iframe). showPopup=false: não força abrir o pop-up.
  useEffect(() => {
    send(false);
  }, [
    send,
    props.primaryColor,
    props.showPrices,
    props.showDescriptions,
    props.showPhotos,
    props.popupTitle,
    props.popupProductIds,
  ]);

  // Clique em "Ver pop-up" (trigger incrementa) → força a abertura no iframe.
  useEffect(() => {
    if (props.popupTrigger > 0) send(true);
  }, [props.popupTrigger, send]);

  return (
    <div className="lg:sticky lg:top-20">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        className="mb-3 w-full rounded-xl border border-border-light bg-bg-primary px-4 py-2.5 text-sm font-medium text-text-secondary hover:bg-bg-tertiary lg:hidden min-h-11"
      >
        <span aria-hidden="true">👁</span> {open ? "Ocultar preview" : "Ver preview"}
      </button>

      <div className={[open ? "block" : "hidden", "lg:block"].join(" ")}>
        <div className="flex flex-col items-center">
          {/* Moldura de celular */}
          <div className="w-[320px] max-w-full overflow-hidden rounded-[2rem] border-8 border-gray-900 bg-gray-900 shadow-xl">
            <iframe
              ref={iframeRef}
              src={previewSrc}
              title="Preview do cardápio"
              onLoad={() => send(false)}
              className="h-[560px] w-full bg-white"
            />
          </div>
          <p className="mt-3 max-w-[320px] text-center text-xs text-text-muted">
            Preview com seus produtos reais · as alterações aparecem aqui antes de salvar.
          </p>
        </div>
      </div>
    </div>
  );
}
