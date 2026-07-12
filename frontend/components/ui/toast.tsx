"use client";

import { useCallback, useRef, useState } from "react";

// Toast simples promovido de app/configuracoes/page.tsx. Container com aria-live="polite"
// (leitor de tela anuncia sem roubar foco). Auto-descarta em 3s.

export type ToastType = "success" | "error";

interface ToastState {
  id: number;
  message: string;
  type: ToastType;
}

export function useToast() {
  const [toasts, setToasts] = useState<ToastState[]>([]);
  const counter = useRef(0);

  const show = useCallback((message: string, type: ToastType = "success") => {
    const id = ++counter.current;
    setToasts((prev) => [...prev, { id, message, type }]);
    setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id));
    }, 3000);
  }, []);

  return { toasts, show };
}

export function ToastContainer({ toasts }: { toasts: ToastState[] }) {
  if (toasts.length === 0) return null;
  return (
    <div
      className="fixed bottom-4 right-4 z-50 flex flex-col gap-2"
      aria-live="polite"
      aria-atomic="false"
    >
      {toasts.map((t) => (
        <div
          key={t.id}
          role="status"
          className={[
            "rounded-xl px-4 py-3 text-sm font-medium shadow-dropdown animate-slide-up",
            // -dark garante contraste WCAG AA com texto branco (success-dark ~7,7:1 / error-dark ~8,3:1);
            // o DEFAULT reprovava (~2,5-3,8:1). Cores hex fixas: contraste igual em tema claro e escuro.
            t.type === "success"
              ? "bg-success-dark text-white"
              : "bg-error-dark text-white",
          ].join(" ")}
        >
          {t.message}
        </div>
      ))}
    </div>
  );
}
