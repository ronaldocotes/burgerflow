// Hook de feed de mesas para React Native.
// Estrategia: snapshot REST -> STOMP ao vivo -> polling 10s de fallback.
// Port de frontend/lib/use-tables-feed.ts com adaptacoes RN (AppState, async auth).

import { useEffect, useRef, useState, useCallback } from 'react';
import { AppState } from 'react-native';
import { api } from '@/lib/api';
import { createStompClient } from '@/lib/ws';
import type { FeedStatus } from '@/lib/ws';
import type { TableDto } from '@/types/tables';

export type { FeedStatus };

const POLL_INTERVAL_MS = 10_000;

function upsertTable(prev: TableDto[], incoming: TableDto): TableDto[] {
  const idx = prev.findIndex((t) => t.id === incoming.id);
  if (idx === -1) return [...prev, incoming];
  const updated = [...prev];
  updated[idx] = incoming;
  return updated;
}

export interface TablesFeed {
  tables: TableDto[];
  feedStatus: FeedStatus;
  refresh: () => Promise<void>;
}

export function useTablesFeed(): TablesFeed {
  const [tables, setTables] = useState<TableDto[]>([]);
  const [feedStatus, setFeedStatus] = useState<FeedStatus>('connecting');

  const stompRef = useRef<Awaited<ReturnType<typeof createStompClient>> | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const wsAlive = useRef(false);

  const fetchSnapshot = useCallback(async () => {
    try {
      const data = await api.get<TableDto[]>('/tables');
      setTables(data);
    } catch {
      // manter estado anterior; StatusBanner ja indica o problema
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
    await fetchSnapshot();

    const client = await createStompClient({
      topic: '/topic/tables/{tenant}',
      onMessage: (body) => {
        const event: TableDto = JSON.parse(body);
        setTables((prev) => upsertTable(prev, event));
      },
      onFeed: (status) => {
        wsAlive.current = status === 'live';
        setFeedStatus(status);
        if (status === 'live') {
          stopPolling();
          fetchSnapshot();
        } else if (status === 'reconnecting' || status === 'polling') {
          startPolling();
        }
      },
    });

    stompRef.current = client;
  }, [fetchSnapshot, startPolling, stopPolling]);

  useEffect(() => {
    connect();

    const fallbackTimer = setTimeout(() => {
      if (!wsAlive.current) {
        setFeedStatus('polling');
        startPolling();
      }
    }, 8_000);

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
      stompRef.current?.deactivate();
      appStateSub.remove();
    };
  }, [connect, fetchSnapshot, startPolling, stopPolling]);

  return { tables, feedStatus, refresh: fetchSnapshot };
}
