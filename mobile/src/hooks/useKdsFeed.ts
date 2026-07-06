// Hook de feed do KDS para React Native.
// Estratégia: snapshot REST → STOMP ao vivo → polling 10s de fallback.
// Diferenças do web: getToken/getTenant são async; AppState para reconexão.
// Expõe loading (primeiro snapshot) e error (última falha de fetch) para a
// tela renderizar os 4 estados (loading/erro/vazio/sucesso).

import { useEffect, useRef, useState, useCallback } from 'react';
import { AppState } from 'react-native';
import { api } from '@/lib/api';
import { createStompClient } from '@/lib/ws';
import type { FeedStatus } from '@/lib/ws';
import type { KdsOrder, KdsEvent, OrderStatus } from '@/types/kds';

export type { FeedStatus };

const POLL_INTERVAL_MS = 10_000;
const AGING_TICK_MS = 30_000;

const TERMINAL: OrderStatus[] = ['DELIVERED', 'CANCELLED'];

function upsertOrder(
  prev: KdsOrder[],
  event: Partial<KdsOrder> & { orderId: string; status: OrderStatus },
): KdsOrder[] {
  if (TERMINAL.includes(event.status)) {
    return prev.filter((o) => o.orderId !== event.orderId);
  }
  const idx = prev.findIndex((o) => o.orderId === event.orderId);
  if (idx === -1) return prev; // pedido desconhecido: o chamador refaz o snapshot
  const updated = { ...prev[idx], ...event };
  return [
    ...prev.filter((o) => o.orderId !== event.orderId),
    updated,
  ];
}

export interface KdsFeed {
  orders: KdsOrder[];
  feedStatus: FeedStatus;
  now: number;
  /** True até o PRIMEIRO snapshot resolver (sucesso ou falha). */
  loading: boolean;
  /** Mensagem da última falha de snapshot; null após um fetch bem-sucedido. */
  error: string | null;
  refresh: () => Promise<void>;
}

export function useKdsFeed(): KdsFeed {
  const [orders, setOrders] = useState<KdsOrder[]>([]);
  const [feedStatus, setFeedStatus] = useState<FeedStatus>('connecting');
  const [now, setNow] = useState(() => Date.now());
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const stompRef = useRef<Awaited<ReturnType<typeof createStompClient>> | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const agingRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const wsAlive = useRef(false);
  // Espelho do board para decidir, no onMessage, se o evento é de pedido
  // conhecido (merge) ou novo (refetch) sem efeito colateral dentro do updater.
  const ordersRef = useRef<KdsOrder[]>([]);
  // Coalescência do refetch disparado por evento de pedido novo: uma rajada de
  // pedidos não pode virar uma rajada de GETs concorrentes (respostas fora de
  // ordem sobrescreveriam o board mais novo com um mais velho).
  const snapshotInFlight = useRef(false);

  const fetchSnapshot = useCallback(async () => {
    try {
      const data = await api.get<KdsOrder[]>('/kds/orders');
      ordersRef.current = data;
      setOrders(data);
      setError(null);
    } catch {
      // Mantém o board anterior; a tela decide (erro em destaque só sem dados).
      setError('Falha ao buscar pedidos.');
    } finally {
      setLoading(false);
    }
  }, []);

  const startPolling = useCallback(() => {
    if (pollRef.current) return;
    pollRef.current = setInterval(fetchSnapshot, POLL_INTERVAL_MS);
  }, [fetchSnapshot]);

  const stopPolling = useCallback(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, []);

  const connect = useCallback(async () => {
    // Snapshot inicial antes de abrir o WS
    await fetchSnapshot();

    const client = await createStompClient({
      topic: '/topic/kds/{tenant}',
      onMessage: (body) => {
        const event: KdsEvent = JSON.parse(body);
        const known = ordersRef.current.some((o) => o.orderId === event.orderId);
        if (!known && !TERMINAL.includes(event.status)) {
          // Pedido NOVO no board (ex.: recém-criado no PDV/cardápio): o payload
          // do evento (KdsOrderEvent) NÃO carrega todos os campos do card
          // (orderType, tableNumber, createdAt, estimatedPrepTimeMinutes...) —
          // refazer o snapshot é mais simples e robusto que montar o card.
          if (!snapshotInFlight.current) {
            snapshotInFlight.current = true;
            void fetchSnapshot().finally(() => {
              snapshotInFlight.current = false;
            });
          }
          return;
        }
        const next = upsertOrder(ordersRef.current, event);
        ordersRef.current = next;
        setOrders(next);
      },
      onFeed: (status) => {
        wsAlive.current = status === 'live';
        setFeedStatus(status);
        if (status === 'live') {
          stopPolling();
          // Reconcilia eventos perdidos durante queda do WS
          fetchSnapshot();
        } else if (status === 'reconnecting' || status === 'polling') {
          startPolling();
        }
      },
    });

    stompRef.current = client;
    // client.activate() já foi chamado dentro de createStompClient
  }, [fetchSnapshot, startPolling, stopPolling]);

  useEffect(() => {
    connect();

    // Timer de aging: re-coloriza barras a cada 30s
    agingRef.current = setInterval(() => setNow(Date.now()), AGING_TICK_MS);

    // Fallback: se WS não conectar em 8s, inicia polling
    const fallbackTimer = setTimeout(() => {
      if (!wsAlive.current) {
        setFeedStatus('polling');
        startPolling();
      }
    }, 8_000);

    // AppState: reconecta ao voltar do background
    const appStateSub = AppState.addEventListener('change', (state) => {
      if (state === 'active') {
        stompRef.current?.activate();
        fetchSnapshot();
      } else {
        stompRef.current?.deactivate();
      }
    });

    return () => {
      clearTimeout(fallbackTimer);
      stopPolling();
      if (agingRef.current) clearInterval(agingRef.current);
      stompRef.current?.deactivate();
      appStateSub.remove();
    };
  }, [connect, fetchSnapshot, startPolling, stopPolling]);

  return { orders, feedStatus, now, loading, error, refresh: fetchSnapshot };
}
