"use client";

import { useCallback, useEffect, useState } from "react";
import QRCode from "react-qr-code";
import { Smartphone, Download, ShieldAlert } from "lucide-react";

// ---------------------------------------------------------------------------
// Pagina PUBLICA de download do app do entregador (APK self-hospedado, fora da
// Play Store). Sem auth/sidebar: /motoboy ja esta nos PUBLIC_PREFIXES do
// ClientLayout. Molde de UX herdado do AppDownloadButton do SISATER: QR Code
// (destaque no desktop, aponta a camera do celular) + botao de download direto.
// Contrato do backend (PR #61):
//   GET /public/app/latest?plataforma=android -> 200 { versionCode, versionName,
//     notas, obrigatoria, tamanhoBytes, sha256, url } | 204 (sem release ainda).
//   O `url` ja vem pronto: /api/v1/public/app/download/{versionCode}.
// ---------------------------------------------------------------------------

const API_BASE =
  process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080/api/v1";

const QR_SIZE = 232;

interface AppRelease {
  versionCode: number;
  versionName: string;
  notas: string | null;
  obrigatoria: boolean;
  tamanhoBytes: number;
  sha256: string;
  url: string;
}

type PageState =
  | { kind: "loading" }
  | { kind: "empty" }
  | { kind: "error" }
  | { kind: "success"; release: AppRelease };

function formatMb(bytes: number): string {
  return (bytes / (1024 * 1024)).toFixed(1);
}

// ---------------------------------------------------------------------------
// Skeleton de carregamento
// ---------------------------------------------------------------------------

function LoadingSkeleton() {
  return (
    <div
      className="mx-auto max-w-md animate-pulse space-y-6"
      aria-busy="true"
      aria-label="Carregando informacoes do app"
    >
      <div className="mx-auto h-4 w-2/3 rounded bg-bg-tertiary" />
      <div className="mx-auto aspect-square w-64 rounded-2xl bg-bg-tertiary" />
      <div className="mx-auto h-12 w-full rounded-xl bg-bg-tertiary" />
      <div className="mx-auto h-3 w-1/2 rounded bg-bg-tertiary" />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Pagina
// ---------------------------------------------------------------------------

export default function MotoboyAppDownloadPage() {
  const [state, setState] = useState<PageState>({ kind: "loading" });
  // A origem so existe no cliente; guardar em estado evita hydration mismatch.
  const [origin, setOrigin] = useState("");

  useEffect(() => {
    setOrigin(window.location.origin);
  }, []);

  const fetchLatest = useCallback(async (signal?: AbortSignal) => {
    setState({ kind: "loading" });
    try {
      const res = await fetch(
        API_BASE + "/public/app/latest?plataforma=android",
        { signal }
      );
      // 204: nenhuma release publicada ainda (sem corpo para parsear).
      if (res.status === 204) {
        setState({ kind: "empty" });
        return;
      }
      if (!res.ok) {
        setState({ kind: "error" });
        return;
      }
      const release: AppRelease = await res.json();
      setState({ kind: "success", release });
    } catch (err: unknown) {
      if (err instanceof DOMException && err.name === "AbortError") return;
      setState({ kind: "error" });
    }
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    fetchLatest(controller.signal);
    return () => controller.abort();
  }, [fetchLatest]);

  // URL absoluta de download (origem atual + o `url` relativo do backend). O QR
  // e o link direto usam a mesma URL para a camera do celular resolver.
  const downloadUrl =
    state.kind === "success"
      ? new URL(state.release.url, origin || undefined).href
      : "";

  return (
    <main
      role="main"
      aria-label="Download do app do entregador"
      className="min-h-screen bg-bg-primary px-6 py-10"
    >
      <div className="mx-auto max-w-md">
        {/* Cabecalho */}
        <div className="mb-8 text-center">
          <span
            className="inline-flex h-14 w-14 items-center justify-center rounded-2xl bg-bg-secondary text-primary-700"
            aria-hidden="true"
          >
            <Smartphone className="h-7 w-7" />
          </span>
          <h1 className="mt-4 text-2xl font-bold text-text-primary">
            App do Entregador — MenuFlow (Android)
          </h1>
          <p className="mt-2 text-sm text-text-secondary">
            Instale o aplicativo para receber corridas e registrar suas
            entregas.
          </p>
        </div>

        {state.kind === "loading" && <LoadingSkeleton />}

        {state.kind === "empty" && (
          <div className="rounded-2xl bg-bg-secondary p-6 text-center">
            <p className="text-sm text-text-secondary">
              App ainda nao disponivel para download.
            </p>
          </div>
        )}

        {state.kind === "error" && (
          <div className="rounded-2xl bg-bg-secondary p-6 text-center">
            <p className="mb-4 text-sm text-text-secondary">
              Nao foi possivel carregar as informacoes do app. Verifique sua
              conexao e tente novamente.
            </p>
            <button
              type="button"
              onClick={() => fetchLatest()}
              className="btn-primary rounded-xl px-8"
            >
              Tentar novamente
            </button>
          </div>
        )}

        {state.kind === "success" && (
          <>
            {/* Versao e tamanho */}
            <div className="mb-6 flex items-center justify-center gap-2 text-sm text-text-secondary">
              <span className="font-semibold text-text-primary">
                Versao {state.release.versionName}
              </span>
              <span aria-hidden="true">·</span>
              <span>{formatMb(state.release.tamanhoBytes)} MB</span>
            </div>

            {/* QR Code — destaque no desktop */}
            <div className="mb-3 flex justify-center">
              <div className="rounded-2xl bg-white p-4">
                {origin ? (
                  <QRCode value={downloadUrl} size={QR_SIZE} />
                ) : (
                  <div
                    className="animate-pulse bg-gray-100"
                    style={{ width: QR_SIZE, height: QR_SIZE }}
                    aria-hidden="true"
                  />
                )}
              </div>
            </div>
            <p className="mb-6 text-center text-sm text-text-secondary">
              No computador: aponte a camera do celular para o QR Code.
            </p>

            {/* Download direto — para quem ja esta no celular */}
            <a
              href={downloadUrl}
              className="btn-primary flex w-full min-h-12 items-center justify-center gap-2 rounded-xl text-base font-semibold"
            >
              <Download className="h-5 w-5" aria-hidden="true" />
              Baixar / Instalar no celular
            </a>

            {/* Notas da versao */}
            {state.release.notas && (
              <div className="mt-6 rounded-2xl bg-bg-secondary p-5">
                <h2 className="mb-2 text-sm font-semibold text-text-primary">
                  Novidades desta versao
                </h2>
                <p className="whitespace-pre-line text-sm text-text-secondary">
                  {state.release.notas}
                </p>
              </div>
            )}

            {/* Aviso sobre APK fora da loja */}
            <div className="mt-6 flex items-start gap-3 rounded-2xl bg-bg-secondary p-5">
              <ShieldAlert
                className="mt-0.5 h-5 w-5 shrink-0 text-secondary-600"
                aria-hidden="true"
              />
              <p className="text-sm text-text-secondary leading-relaxed">
                E um arquivo Android (APK), fora da Play Store. Ao instalar, o
                celular pode pedir para permitir &quot;instalar apps de fontes
                desconhecidas&quot;.
              </p>
            </div>
          </>
        )}
      </div>
    </main>
  );
}
