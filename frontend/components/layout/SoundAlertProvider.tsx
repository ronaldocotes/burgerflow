"use client";

// Alerta sonoro global de pedido novo (issue #5).
// Montado UMA vez no shell autenticado (ClientLayout): assina o mesmo topico
// STOMP do KDS (/topic/kds/{tenant}) e toca um beep curto (Web Audio API)
// quando chega um pedido genuinamente NOVO — em qualquer tela do operador.
//
// Deteccao de "pedido novo": Set de orderId ja vistos + janela de carencia
// pos-conexao (backfill/reconciliacao nao toca som) + status PENDING
// (pedido nasce PENDING; mudanca de status de pedido antigo nao toca).
//
// Autoplay: navegador exige gesto do usuario para liberar audio. O clique no
// toggle cria/resume o AudioContext. Se o toggle ja estava ligado ao recarregar
// a pagina, um listener one-shot de pointerdown/keydown faz o unlock.

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
} from "react";
import { getTenant } from "@/lib/auth";
import { createKdsClient } from "@/lib/ws";
import type { KdsEvent } from "@/types/kds";

const STORAGE_KEY = "mf_sound_alert_enabled";
// Janela pos-conexao em que eventos so marcam "visto" (sem som): cobre
// replay/burst de reconexao sem depender do formato do backfill.
const CONNECT_GRACE_MS = 3000;

interface SoundAlertContextValue {
  enabled: boolean;
  toggle: () => void;
}

const SoundAlertContext = createContext<SoundAlertContextValue | null>(null);

export function useSoundAlert(): SoundAlertContextValue {
  const ctx = useContext(SoundAlertContext);
  if (!ctx) {
    throw new Error(
      "useSoundAlert deve ser usado dentro de SoundAlertProvider",
    );
  }
  return ctx;
}

/** Beep curto de 2 tons (la5 -> re6), ~300ms no total, via Web Audio API. */
function playNewOrderBeep(ctx: AudioContext): void {
  const t0 = ctx.currentTime;
  const tones: Array<[frequency: number, offsetSec: number]> = [
    [880, 0],
    [1174.66, 0.16],
  ];
  for (const [frequency, offset] of tones) {
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = "sine";
    osc.frequency.value = frequency;
    gain.gain.setValueAtTime(0.0001, t0 + offset);
    gain.gain.exponentialRampToValueAtTime(0.25, t0 + offset + 0.02);
    gain.gain.exponentialRampToValueAtTime(0.0001, t0 + offset + 0.14);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start(t0 + offset);
    osc.stop(t0 + offset + 0.16);
  }
}

function createAudioContext(): AudioContext | null {
  if (typeof window === "undefined") return null;
  const Ctor =
    window.AudioContext ??
    (window as unknown as { webkitAudioContext?: typeof AudioContext })
      .webkitAudioContext;
  return Ctor ? new Ctor() : null;
}

export function SoundAlertProvider({
  children,
}: {
  children: React.ReactNode;
}) {
  const [enabled, setEnabled] = useState(false);

  const audioRef = useRef<AudioContext | null>(null);
  // orderIds ja vistos: persiste entre liga/desliga e reconexoes do WS.
  const seenRef = useRef<Set<string>>(new Set());
  const graceUntilRef = useRef(0);

  // Estado persistido: lido na montagem (evita mismatch SSR/hidratacao).
  useEffect(() => {
    queueMicrotask(() => {
      setEnabled(window.localStorage.getItem(STORAGE_KEY) === "true");
    });
  }, []);

  /** Cria/resume o AudioContext. Deve ser chamado a partir de gesto do usuario. */
  const unlockAudio = useCallback(() => {
    if (!audioRef.current) {
      audioRef.current = createAudioContext();
    }
    if (audioRef.current && audioRef.current.state === "suspended") {
      void audioRef.current.resume();
    }
  }, []);

  // Toggle ligado apos reload: ainda nao houve gesto, entao o AudioContext
  // nasceria suspenso. Unlock one-shot no primeiro pointerdown/keydown.
  useEffect(() => {
    if (!enabled) return;
    if (audioRef.current && audioRef.current.state === "running") return;

    const onGesture = () => {
      unlockAudio();
      window.removeEventListener("pointerdown", onGesture);
      window.removeEventListener("keydown", onGesture);
    };
    window.addEventListener("pointerdown", onGesture);
    window.addEventListener("keydown", onGesture);
    return () => {
      window.removeEventListener("pointerdown", onGesture);
      window.removeEventListener("keydown", onGesture);
    };
  }, [enabled, unlockAudio]);

  // Assinatura STOMP global (so para o som): ativa apenas com toggle ligado.
  // Adicional a do KDS — STOMP suporta multiplas subscricoes no mesmo topico.
  useEffect(() => {
    if (!enabled) return;
    const tenant = getTenant();
    if (!tenant) return;

    const handle = createKdsClient(
      `/topic/kds/${tenant}`,
      (data) => {
        const event = data as KdsEvent;
        if (!event || typeof event.orderId !== "string") return;

        const alreadySeen = seenRef.current.has(event.orderId);
        seenRef.current.add(event.orderId);
        if (alreadySeen) return;
        if (Date.now() < graceUntilRef.current) return;
        // Pedido nasce PENDING (fluxo PENDING → PREPARING → READY → DELIVERED);
        // orderId inedito em outro status = pedido antigo mudando de estado.
        if (event.status !== "PENDING") return;

        const audio = audioRef.current;
        if (audio && audio.state === "running") {
          playNewOrderBeep(audio);
        }
      },
      (status) => {
        if (status === "live") {
          graceUntilRef.current = Date.now() + CONNECT_GRACE_MS;
        }
      },
    );
    handle.activate();

    return () => {
      void handle.deactivate();
    };
  }, [enabled]);

  const toggle = useCallback(() => {
    // Efeitos fora do updater do setState (StrictMode pode rodar updaters 2x).
    const next = !enabled;
    window.localStorage.setItem(STORAGE_KEY, String(next));
    if (next) {
      // O clique e o gesto que a politica de autoplay exige.
      unlockAudio();
      const audio = audioRef.current;
      if (audio && audio.state === "running") {
        // Beep de confirmacao: prova ao operador que o som esta funcionando.
        playNewOrderBeep(audio);
      }
    }
    setEnabled(next);
  }, [enabled, unlockAudio]);

  return (
    <SoundAlertContext.Provider value={{ enabled, toggle }}>
      {children}
    </SoundAlertContext.Provider>
  );
}
