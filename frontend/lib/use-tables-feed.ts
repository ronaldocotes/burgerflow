// Hook que gerencia o board de mesas: snapshot REST -> STOMP ao vivo -> polling de fallback.
// Estrategia de degradacao gracosa identica ao use-kds-feed: nasce funcional so com REST;
// STOMP entra por cima. Se o WS cair, volta a polling ~10s; ao reconectar, re-busca snapshot.

"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "./api";
import { getTenant } from "./auth";
import { createKdsClient } from "./ws";
import { FeedStatus, TableDto } from "@/types/tables";

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
  const [feedStatus, setFeedStatus] = useState<FeedStatus>("connecting");

  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const wsAlive = useRef(false);

  const fetchSnapshot = useCallback(async () => {
    try {
      const data = await api.get<TableDto[]>("/tables");
      setTables(data);
    } catch {
      // manter estado anterior; o banner de status ja indica o problema
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

  useEffect(() => {
    // snapshot inicial
    queueMicrotask(() => {
      void fetchSnapshot();
    });

    const tenant = getTenant();
    if (!tenant) {
      queueMicrotask(() => {
        setFeedStatus("polling");
      });
      startPolling();
      return () => {
        stopPolling();
      };
    }

    const handle = createKdsClient(
      `/topic/tables/${tenant}`,
      (data) => {
        const event = data as TableDto;
        setTables((prev) => upsertTable(prev, event));
      },
      (status) => {
        wsAlive.current = status === "live";
        if (status === "live") {
          setFeedStatus("live");
          stopPolling();
          // re-busca snapshot para reconciliar eventos perdidos durante queda
          fetchSnapshot();
        } else {
          setFeedStatus("reconnecting");
          startPolling();
        }
      },
    );

    queueMicrotask(() => {
      setFeedStatus("connecting");
    });
    handle.activate();

    // polling de fallback enquanto WS nao conecta (primeiros segundos)
    const fallbackTimer = setTimeout(() => {
      if (!wsAlive.current) {
        setFeedStatus("polling");
        startPolling();
      }
    }, 8000);

    return () => {
      clearTimeout(fallbackTimer);
      stopPolling();
      handle.deactivate();
    };
  }, [fetchSnapshot, startPolling, stopPolling]);

  return { tables, feedStatus, refresh: fetchSnapshot };
}
