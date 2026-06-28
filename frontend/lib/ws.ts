// Fábrica do cliente STOMP para o KDS.
// URL em NEXT_PUBLIC_WS_URL — NÃO derivar de API_BASE (/ws fica na raiz,
// fora do context-path /api/v1 do Spring MVC).

import { Client, StompSubscription } from "@stomp/stompjs";
import { TOKEN_KEY } from "./api";

export function normalizeWsUrl(raw: string): string {
  try {
    const url = new URL(raw);
    if (!url.pathname || url.pathname === "/") {
      url.pathname = "/ws";
    }
    return url.toString();
  } catch {
    return raw;
  }
}

const WS_URL = normalizeWsUrl(
  process.env.NEXT_PUBLIC_WS_URL ?? "ws://localhost:8080/ws",
);

export type WsStatus = "live" | "reconnecting";

export interface StompHandle {
  client: Client;
  activate: () => void;
  deactivate: () => Promise<void>;
}

export function createKdsClient(
  topic: string,
  onMessage: (data: unknown) => void,
  onStatus: (s: WsStatus) => void,
): StompHandle {
  let sub: StompSubscription | null = null;

  const client = new Client({
    brokerURL: WS_URL,
    reconnectDelay: 5000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    beforeConnect: async () => {
      const token =
        typeof window !== "undefined"
          ? window.localStorage.getItem(TOKEN_KEY)
          : null;
      client.connectHeaders = token
        ? { Authorization: `Bearer ${token}` }
        : {};
    },
    onConnect: () => {
      onStatus("live");
      sub = client.subscribe(topic, (frame) => {
        try {
          onMessage(JSON.parse(frame.body));
        } catch {
          // frame malformado — ignorar
        }
      });
    },
    onDisconnect: () => {
      sub = null;
      onStatus("reconnecting");
    },
    onStompError: () => onStatus("reconnecting"),
    onWebSocketError: () => onStatus("reconnecting"),
  });

  return {
    client,
    activate: () => client.activate(),
    deactivate: () => client.deactivate(),
  };
}
