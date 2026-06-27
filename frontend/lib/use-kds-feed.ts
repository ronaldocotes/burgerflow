// Hook que gerencia o board do KDS: snapshot REST → STOMP ao vivo → polling de fallback.
// Estratégia de degradação graciosa: nasce funcional só com REST; STOMP entra por cima.
// Se o WS cair, volta a polling ~10s; ao reconectar, re-busca snapshot para reconciliar
// eventos perdidos durante a queda.

"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "./api";
import { getTenant } from "./auth";
import { createKdsClient } from "./ws";
import { FeedStatus, KdsEvent, KdsOrder, OrderStatus } from "@/types/kds";

const POLL_INTERVAL_MS = 10_000;
const AGING_TICK_MS = 30_000;

const TERMINAL: OrderStatus[] = ["DELIVERED", "CANCELLED"];

function upsertOrder(
  prev: KdsOrder[],
  event: Partial<KdsOrder> & { orderId: string; status: OrderStatus },
): KdsOrder[] {
  if (TERMINAL.includes(event.status)) {
    return prev.filter((o) => o.orderId !== event.orderId);
  }
  const idx = prev.findIndex((o) => o.orderId === event.orderId);
  if (idx === -1) {
    return prev;
  }
  const updated = { ...prev[idx], ...event };
  const rest = prev.filter((o) => o.orderId !== event.orderId);
  return [...rest, updated];
}

export interface KdsFeed {
  orders: KdsOrder[];
  feedStatus: FeedStatus;
  now: number;
  refresh: () => Promise<void>;
}

export function useKdsFeed(): KdsFeed {
  const [orders, setOrders] = useState<KdsOrder[]>([]);
  const [feedStatus, setFeedStatus] = useState<FeedStatus>("connecting");
  const [now, setNow] = useState(() => Date.now());

  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const agingRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const wsAlive = useRef(false);

  const fetchSnapshot = useCallback(async () => {
    try {
      const data = await api.get<KdsOrder[]>("/kds/orders");
      setOrders(data);
    } catch {
      // manter estado anterior; o banner de status já indica o problema
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
    fetchSnapshot();

    // timer de aging (atualiza `now` para re-colorir cartões)
    agingRef.current = setInterval(() => setNow(Date.now()), AGING_TICK_MS);

    const tenant = getTenant();
    if (!tenant) {
      setFeedStatus("polling");
      startPolling();
      return;
    }

    const handle = createKdsClient(
      `/topic/kds/${tenant}`,
      (data) => {
        const event = data as KdsEvent;
        setOrders((prev) => upsertOrder(prev, event));
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

    setFeedStatus("connecting");
    handle.activate();

    // polling de fallback enquanto WS não conecta (primeiros segundos)
    const fallbackTimer = setTimeout(() => {
      if (!wsAlive.current) {
        setFeedStatus("polling");
        startPolling();
      }
    }, 8000);

    return () => {
      clearTimeout(fallbackTimer);
      stopPolling();
      if (agingRef.current) clearInterval(agingRef.current);
      handle.deactivate();
    };
  }, [fetchSnapshot, startPolling, stopPolling]);

  return { orders, feedStatus, now, refresh: fetchSnapshot };
}
