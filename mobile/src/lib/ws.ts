import { Client } from '@stomp/stompjs';
import { AppConfig } from '@/config';
import { getToken, getTenant } from './auth';

// Status do feed em tempo real (mesma semantica do front).
export type FeedStatus = 'connecting' | 'live' | 'reconnecting' | 'polling';

// Fabrica de cliente STOMP para React Native. Os polyfills (TextEncoder/getRandomValues)
// sao carregados em index.js ANTES deste modulo. brokerURL aponta para /ws na raiz.
export async function createStompClient(opts: {
  topic: string;
  onMessage: (body: string) => void;
  onFeed: (s: FeedStatus) => void;
}): Promise<Client> {
  const [token, tenant] = await Promise.all([getToken(), getTenant()]);
  const topic = opts.topic.replace('{tenant}', tenant ?? '');

  const client = new Client({
    brokerURL: AppConfig.WS_URL,
    forceBinaryWSFrames: true, // obrigatorio em RN bare
    appendMissingNULLonIncoming: true,
    connectHeaders: token ? { Authorization: `Bearer ${token}` } : {},
    reconnectDelay: 5000,
    onConnect: () => {
      opts.onFeed('live');
      client.subscribe(topic, (frame) => opts.onMessage(frame.body));
    },
    onDisconnect: () => opts.onFeed('reconnecting'),
    onStompError: () => opts.onFeed('polling'),
    onWebSocketError: () => opts.onFeed('reconnecting'),
  });

  client.activate();
  return client;
}
