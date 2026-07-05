// Estado compartilhado do motoboy (Fase 6.2): perfil/turno, entregas ativas
// (snapshot + STOMP + polling fallback) e ping de GPS foreground.
// Envolve as abas do motoboy (DriverTabs) — Home, Entregas e Ganhos leem daqui.
import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { AppState } from 'react-native';
import { ApiError } from '@/lib/api';
import { createStompClient } from '@/lib/ws';
import type { FeedStatus } from '@/lib/ws';
import { getMe, getMyOrders, setShift } from '@/api/delivery';
import { isActiveDelivery } from '@/utils/deliveryStatus';
import { useLocationPing } from '@/hooks/useLocationPing';
import type { GpsPermission } from '@/hooks/useLocationPing';
import type { DeliveryOrder, DriverProfile } from '@/types/delivery';

export type ProfileState = 'loading' | 'unlinked' | 'error' | 'ready';
export type ListState = 'loading' | 'error' | 'ready';

interface DriverContextValue {
  profile: DriverProfile | null;
  profileState: ProfileState;
  reloadProfile: () => void;
  shiftBusy: boolean;
  /** Retorna mensagem de erro para o toast, ou null em caso de sucesso. */
  toggleShift: () => Promise<string | null>;
  deliveries: DeliveryOrder[];
  deliveriesState: ListState;
  refreshDeliveries: () => Promise<void>;
  feedStatus: FeedStatus;
  gpsPermission: GpsPermission;
}

const DriverContext = createContext<DriverContextValue | null>(null);

export function useDriver(): DriverContextValue {
  const ctx = useContext(DriverContext);
  if (!ctx) throw new Error('useDriver deve ser usado dentro de <DriverProvider>');
  return ctx;
}

const POLL_INTERVAL_MS = 15_000;
const WS_FALLBACK_MS = 8_000;
const REFRESH_DEBOUNCE_MS = 500;

export function DriverProvider({ children }: { children: React.ReactNode }) {
  // ------------------------------------------------------------------
  // Perfil / turno
  // ------------------------------------------------------------------
  const [profile, setProfile] = useState<DriverProfile | null>(null);
  const [profileState, setProfileState] = useState<ProfileState>('loading');
  const [shiftBusy, setShiftBusy] = useState(false);

  const loadProfile = useCallback(async () => {
    setProfileState('loading');
    try {
      const me = await getMe();
      setProfile(me);
      setProfileState('ready');
    } catch (err) {
      // 404/403 = usuario sem entregador vinculado (ou endpoint /delivery/me
      // previsto ainda nao publicado pelo backend).
      if (err instanceof ApiError && (err.status === 404 || err.status === 403)) {
        setProfileState('unlinked');
      } else {
        setProfileState('error');
      }
    }
  }, []);

  useEffect(() => {
    loadProfile();
  }, [loadProfile]);

  const toggleShift = useCallback(async (): Promise<string | null> => {
    if (!profile || shiftBusy) return null;
    setShiftBusy(true);
    try {
      const updated = await setShift(profile.id, !profile.activeShift);
      setProfile(updated);
      return null;
    } catch {
      return 'Nao foi possivel alterar o turno. Verifique a conexao.';
    } finally {
      setShiftBusy(false);
    }
  }, [profile, shiftBusy]);

  // ------------------------------------------------------------------
  // Entregas ativas: snapshot REST -> STOMP ao vivo -> polling fallback
  // ------------------------------------------------------------------
  const [deliveries, setDeliveries] = useState<DeliveryOrder[]>([]);
  const [deliveriesState, setDeliveriesState] = useState<ListState>('loading');
  const [feedStatus, setFeedStatus] = useState<FeedStatus>('connecting');

  const stompRef = useRef<Awaited<ReturnType<typeof createStompClient>> | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const wsAlive = useRef(false);

  const refreshDeliveries = useCallback(async () => {
    try {
      const data = await getMyOrders();
      setDeliveries(data);
      setDeliveriesState('ready');
    } catch {
      // Se ja temos lista, mantemos (banner sinaliza); sem lista vira erro.
      setDeliveriesState((prev) => (prev === 'ready' ? 'ready' : 'error'));
    }
  }, []);

  const scheduleRefresh = useCallback(() => {
    if (debounceRef.current) return;
    debounceRef.current = setTimeout(() => {
      debounceRef.current = null;
      refreshDeliveries();
    }, REFRESH_DEBOUNCE_MS);
  }, [refreshDeliveries]);

  const stopPolling = useCallback(() => {
    if (pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, []);

  const startPolling = useCallback(() => {
    if (pollRef.current) return;
    pollRef.current = setInterval(refreshDeliveries, POLL_INTERVAL_MS);
  }, [refreshDeliveries]);

  useEffect(() => {
    let disposed = false;

    (async () => {
      await refreshDeliveries();
      const client = await createStompClient({
        // Canal do tenant: recebe DeliveryOrderResponse e DriverLocationEvent.
        topic: '/topic/delivery/{tenant}',
        onMessage: (body) => {
          try {
            const msg = JSON.parse(body) as { orderId?: string };
            // Evento de pedido (tem orderId) -> re-sincroniza a lista "minhas".
            // Eventos de posicao de outros motoboys sao ignorados.
            if (msg && typeof msg === 'object' && msg.orderId) scheduleRefresh();
          } catch {
            // Payload desconhecido: ignora.
          }
        },
        onFeed: (status) => {
          if (disposed) return;
          wsAlive.current = status === 'live';
          setFeedStatus(status);
          if (status === 'live') {
            stopPolling();
            refreshDeliveries();
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

    const appStateSub = AppState.addEventListener('change', (state) => {
      if (state === 'active') {
        stompRef.current?.activate();
        refreshDeliveries();
      } else {
        stompRef.current?.deactivate();
      }
    });

    return () => {
      disposed = true;
      clearTimeout(fallbackTimer);
      stopPolling();
      if (debounceRef.current) clearTimeout(debounceRef.current);
      stompRef.current?.deactivate();
      stompRef.current = null;
      appStateSub.remove();
    };
  }, [refreshDeliveries, scheduleRefresh, startPolling, stopPolling]);

  // ------------------------------------------------------------------
  // GPS: somente com turno ONLINE + entrega ativa (LGPD, foreground).
  // ------------------------------------------------------------------
  const gpsEnabled = !!profile?.activeShift && deliveries.some(isActiveDelivery);
  const gpsPermission = useLocationPing(gpsEnabled);

  const value = useMemo<DriverContextValue>(
    () => ({
      profile,
      profileState,
      reloadProfile: loadProfile,
      shiftBusy,
      toggleShift,
      deliveries,
      deliveriesState,
      refreshDeliveries,
      feedStatus,
      gpsPermission,
    }),
    [
      profile,
      profileState,
      loadProfile,
      shiftBusy,
      toggleShift,
      deliveries,
      deliveriesState,
      refreshDeliveries,
      feedStatus,
      gpsPermission,
    ],
  );

  return <DriverContext.Provider value={value}>{children}</DriverContext.Provider>;
}
