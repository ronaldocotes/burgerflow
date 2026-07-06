// Fábrica do cliente STOMP para o KDS.
// URL em NEXT_PUBLIC_WS_URL, ou derivada de API_BASE preservando o context-path.
// O handshake STOMP real é /api/v1/ws: o endpoint é registrado como "/ws" mas
// vive SOB o server.servlet.context-path=/api/v1 do Spring Boot.

import { Client, StompSubscription } from "@stomp/stompjs";
import { API_BASE, TOKEN_KEY } from "./api";

export function normalizeWsUrl(raw: string): string {
  try {
    const url = new URL(raw);
    if (!url.pathname || url.pathname === "/") {
      // URL sem caminho (só a origem): completa com o caminho REAL do
      // handshake, que inclui o context-path /api/v1.
      url.pathname = "/api/v1/ws";
    }
    return url.toString();
  } catch {
    return raw;
  }
}

const WS_URL = normalizeWsUrl(
  process.env.NEXT_PUBLIC_WS_URL ?? deriveWsUrlFromApiBase(API_BASE),
);

function deriveWsUrlFromApiBase(apiBase: string): string {
  try {
    // API_BASE já inclui o context-path (ex.: http://host:8080/api/v1) —
    // basta anexar /ws para chegar em /api/v1/ws.
    const url = new URL(apiBase);
    url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
    url.pathname = url.pathname.replace(/\/$/, "") + "/ws";
    url.search = "";
    url.hash = "";
    return url.toString();
  } catch {
    return "ws://localhost:8080/api/v1/ws";
  }
}

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
