"use client";

import { useCallback, useRef, useState } from "react";
import QRCode from "react-qr-code";
import { Copy, Check, Download, Printer } from "lucide-react";
import { useModalA11y } from "@/lib/use-modal-a11y";

// Modal de QR generalizado (issue #11). Reaproveita o padrao do QrModal de /mesas
// (react-qr-code + useModalA11y). O QR fica sobre painel BRANCO fixo — precisa de
// contraste de leitura mesmo em dark mode. "Baixar PNG" e o que o dono imprime/cola.

interface Props {
  url: string;
  title: string;
  onClose: () => void;
}

const QR_SIZE = 256;

export function QrLinkModal({ url, title, onClose }: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const qrWrapRef = useRef<HTMLDivElement>(null);
  useModalA11y(ref as React.RefObject<HTMLElement>, onClose);
  const [copied, setCopied] = useState(false);

  const copy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(url);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      /* clipboard indisponivel: ignora silenciosamente */
    }
  }, [url]);

  // Converte o SVG do react-qr-code em PNG via canvas e dispara o download.
  const downloadPng = useCallback(() => {
    const svg = qrWrapRef.current?.querySelector("svg");
    if (!svg) return;
    const xml = new XMLSerializer().serializeToString(svg);
    const svg64 = btoa(unescape(encodeURIComponent(xml)));
    const img = new Image();
    img.onload = () => {
      const pad = 24;
      const canvas = document.createElement("canvas");
      canvas.width = QR_SIZE + pad * 2;
      canvas.height = QR_SIZE + pad * 2;
      const ctx = canvas.getContext("2d");
      if (!ctx) return;
      ctx.fillStyle = "#ffffff";
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      ctx.drawImage(img, pad, pad, QR_SIZE, QR_SIZE);
      const a = document.createElement("a");
      a.href = canvas.toDataURL("image/png");
      a.download = `qr-${title.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "")}.png`;
      a.click();
    };
    img.src = "data:image/svg+xml;base64," + svg64;
  }, [title]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      aria-modal="true"
    >
      <div
        ref={ref}
        role="dialog"
        aria-labelledby="qr-link-modal-title"
        className="w-full max-w-xs rounded-2xl bg-bg-primary p-6 shadow-dropdown"
      >
        <h2
          id="qr-link-modal-title"
          className="mb-1 text-center text-lg font-semibold text-text-primary"
        >
          {title}
        </h2>
        <p className="mb-4 break-all text-center font-mono text-xs text-text-secondary">
          {url}
        </p>

        <div ref={qrWrapRef} className="flex justify-center rounded-xl bg-white p-4">
          <QRCode value={url} size={QR_SIZE} />
        </div>

        <p className="mt-4 text-center text-sm text-text-secondary">
          Aponte a camera para acessar o cardapio
        </p>

        <div className="mt-6 grid grid-cols-2 gap-3">
          <button className="btn-outline flex min-h-11 items-center justify-center gap-2" onClick={() => void copy()}>
            {copied ? (
              <>
                <Check className="h-4 w-4" aria-hidden="true" /> Copiado
              </>
            ) : (
              <>
                <Copy className="h-4 w-4" aria-hidden="true" /> Copiar link
              </>
            )}
          </button>
          <button className="btn-outline flex min-h-11 items-center justify-center gap-2" onClick={downloadPng}>
            <Download className="h-4 w-4" aria-hidden="true" /> Baixar PNG
          </button>
          <button className="btn-outline flex min-h-11 items-center justify-center gap-2" onClick={() => window.print()}>
            <Printer className="h-4 w-4" aria-hidden="true" /> Imprimir
          </button>
          <button className="btn-primary flex min-h-11 items-center justify-center" onClick={onClose}>
            Fechar
          </button>
        </div>
      </div>
    </div>
  );
}
