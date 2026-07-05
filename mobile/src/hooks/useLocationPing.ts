// Ping de GPS do motoboy (Fase 6.2). SOMENTE foreground (LGPD): roda quando
// habilitado (turno ONLINE + entrega ativa) e o app esta em primeiro plano.
// A cada ~15s envia POST /delivery/location; para na hora ao desabilitar.
import { useEffect, useState } from 'react';
import { AppState, PermissionsAndroid, Platform } from 'react-native';
import Geolocation from 'react-native-geolocation-service';
import { sendLocation } from '@/api/delivery';

const PING_INTERVAL_MS = 15_000;

export type GpsPermission = 'unknown' | 'granted' | 'denied';

async function ensurePermission(): Promise<boolean> {
  if (Platform.OS !== 'android') return true;
  const already = await PermissionsAndroid.check(
    PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
  );
  if (already) return true;
  // Pedido EM CONTEXTO: so chega aqui quando ha turno online + entrega ativa.
  const result = await PermissionsAndroid.request(
    PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
    {
      title: 'Localizacao durante a entrega',
      message:
        'Usamos sua posicao apenas enquanto voce esta ONLINE e com entrega em ' +
        'andamento, para o restaurante acompanhar a entrega. Nada e coletado ' +
        'fora do turno.',
      buttonPositive: 'Permitir',
      buttonNegative: 'Agora nao',
    },
  );
  return result === PermissionsAndroid.RESULTS.GRANTED;
}

export function useLocationPing(enabled: boolean): GpsPermission {
  const [permission, setPermission] = useState<GpsPermission>('unknown');

  useEffect(() => {
    if (!enabled) return;
    let disposed = false;
    let timer: ReturnType<typeof setInterval> | null = null;

    const ping = () => {
      // Foreground only: com o app fora de foco nao coletamos posicao.
      if (AppState.currentState !== 'active') return;
      Geolocation.getCurrentPosition(
        (pos) => {
          sendLocation({
            lat: pos.coords.latitude,
            lng: pos.coords.longitude,
          }).catch(() => {
            // Falha de rede: o proximo tick tenta de novo.
          });
        },
        () => {
          // Sem fix de GPS agora: o proximo tick tenta de novo.
        },
        { enableHighAccuracy: true, timeout: 10_000, maximumAge: 5_000 },
      );
    };

    (async () => {
      const ok = await ensurePermission();
      if (disposed) return;
      setPermission(ok ? 'granted' : 'denied');
      if (!ok) return;
      ping();
      timer = setInterval(ping, PING_INTERVAL_MS);
    })();

    return () => {
      disposed = true;
      if (timer) clearInterval(timer);
    };
  }, [enabled]);

  return permission;
}
