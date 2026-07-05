// Feed de ofertas de entrega (Fase 6.2).
// Estrategia: STOMP /topic/delivery/{tenant}/offers ao vivo + polling 15s de
// fallback (GET /delivery/offers/my — contrato previsto; 404 e tolerado enquanto
// o backend nao publica). A oferta expira sozinha pelo relogio (expiresAt).
import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '@/lib/api';
import { createStompClient } from '@/lib/ws';
import type { FeedStatus } from '@/lib/ws';
import { acceptOffer, getMyOffers, rejectOffer } from '@/api/delivery';
import type { DeliveryOffer } from '@/types/delivery';

const POLL_INTERVAL_MS = 15_000;
const WS_FALLBACK_MS = 8_000;

function msLeft(offer: DeliveryOffer): number {
  return new Date(offer.expiresAt).getTime() - Date.now();
}

function pickPending(list: DeliveryOffer[]): DeliveryOffer | null {
  return list.find((o) => o.status === 'OFFERED' && msLeft(o) > 0) ?? null;
}

export interface OffersFeed {
  offer: DeliveryOffer | null;
  secondsLeft: number;
  feedStatus: FeedStatus;
  acting: 'accept' | 'reject' | null;
  /** Retorna a mensagem de feedback para o toast/snackbar. */
  accept: () => Promise<string>;
  reject: () => Promise<string>;
}

export function useOffersFeed(opts: {
  driverId: string | null;
  enabled: boolean;
  onAccepted?: () => void;
}): OffersFeed {
  const { driverId, enabled } = opts;
  const [offer, setOffer] = useState<DeliveryOffer | null>(null);
  const [secondsLeft, setSecondsLeft] = useState(0);
  const [feedStatus, setFeedStatus] = useState<FeedStatus>('connecting');
  const [acting, setActing] = useState<'accept' | 'reject' | null>(null);

  const stompRef = useRef<Awaited<ReturnType<typeof createStompClient>> | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const wsAlive = useRef(false);
  const driverIdRef = useRef(driverId);
  driverIdRef.current = driverId;
  const onAcceptedRef = useRef(opts.onAccepted);
  onAcceptedRef.current = opts.onAccepted;

  const fetchOffers = useCallback(async () => {
    try {
      const list = await getMyOffers();
      setOffer((prev) => {
        const next = pickPending(list);
        // Nao troca a oferta na frente do motoboy sem necessidade.
        if (prev && next && prev.id === next.id) return prev;
        // O WS pode estar na frente do poll; so derruba se ja expirou.
        if (prev && !next && msLeft(prev) > 0) return prev;
        return next;
      });
    } catch {
      // 404 = endpoint previsto ainda nao publicado; outros erros: o banner de
      // conexao ja sinaliza. Em ambos os casos mantemos o estado atual.
    }
  }, []);

  const stopPolling = useCallback(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, []);

  const startPolling = useCallback(() => {
    if (pollRef.current) return;
    pollRef.current = setInterval(fetchOffers, POLL_INTERVAL_MS);
  }, [fetchOffers]);

  // Conexao STOMP + fallback de polling, apenas com o turno ONLINE.
  useEffect(() => {
    if (!enabled) {
      setOffer(null);
      setFeedStatus('connecting');
      return;
    }
    let disposed = false;

    (async () => {
      await fetchOffers();
      const client = await createStompClient({
        topic: '/topic/delivery/{tenant}/offers',
        onMessage: (body) => {
          try {
            const msg = JSON.parse(body) as DeliveryOffer;
            if (!msg || typeof msg !== 'object' || !msg.id || !msg.expiresAt) return;
            // Canal e por tenant: filtra pela propria oferta (driverId null = grupo).
            const mine = msg.driverId == null || msg.driverId === driverIdRef.current;
            if (!mine) return;
            if (msg.status === 'OFFERED' && msLeft(msg) > 0) {
              setOffer((prev) => (prev && prev.id !== msg.id && msLeft(prev) > 0 ? prev : msg));
            } else {
              // Aceita por outro / expirada / cancelada: limpa se for a atual.
              setOffer((prev) => (prev && prev.id === msg.id ? null : prev));
            }
          } catch {
            // Payload desconhecido no canal: ignora.
          }
        },
        onFeed: (status) => {
          if (disposed) return;
          wsAlive.current = status === 'live';
          setFeedStatus(status);
          if (status === 'live') {
            stopPolling();
            fetchOffers(); // reconcilia ofertas perdidas na queda
          } else {
            startPolling();
          }
        },
      });
      if (disposed) {
        client.deactivate();
        return;
      }
      stompRef.current = client;
    })();

    const fallbackTimer = setTimeout(() => {
      if (!wsAlive.current && !disposed) {
        setFeedStatus('polling');
        startPolling();
      }
    }, WS_FALLBACK_MS);

    return () => {
      disposed = true;
      clearTimeout(fallbackTimer);
      stopPolling();
      stompRef.current?.deactivate();
      stompRef.current = null;
    };
  }, [enabled, fetchOffers, startPolling, stopPolling]);

  // Contador regressivo (1s). Zerou -> oferta some sozinha.
  useEffect(() => {
    if (!offer) {
      setSecondsLeft(0);
      return;
    }
    const tick = () => {
      const left = Math.max(0, Math.ceil(msLeft(offer) / 1000));
      setSecondsLeft(left);
      if (left <= 0) setOffer(null);
    };
    tick();
    const timer = setInterval(tick, 1000);
    return () => clearInterval(timer);
  }, [offer]);

  const accept = useCallback(async (): Promise<string> => {
    if (!offer || acting) return 'Nenhuma oferta ativa.';
    setActing('accept');
    try {
      await acceptOffer(offer.id);
      setOffer(null);
      onAcceptedRef.current?.();
      return 'Entrega aceita. Boa corrida!';
    } catch (err) {
      if (err instanceof ApiError && err.status >= 400 && err.status < 500) {
        setOffer(null);
        return 'Essa oferta ja expirou ou foi aceita por outro entregador.';
      }
      return 'Nao foi possivel aceitar. Verifique a conexao e tente de novo.';
    } finally {
      setActing(null);
    }
  }, [offer, acting]);

  const reject = useCallback(async (): Promise<string> => {
    if (!offer || acting) return 'Nenhuma oferta ativa.';
    setActing('reject');
    try {
      await rejectOffer(offer.id);
      setOffer(null);
      return 'Oferta recusada.';
    } catch (err) {
      if (err instanceof ApiError && err.status >= 400 && err.status < 500) {
        setOffer(null);
        return 'Essa oferta ja nao estava mais ativa.';
      }
      return 'Nao foi possivel recusar. Verifique a conexao.';
    } finally {
      setActing(null);
    }
  }, [offer, acting]);

  return { offer, secondsLeft, feedStatus, acting, accept, reject };
}
