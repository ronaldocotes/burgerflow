import { Platform } from 'react-native';
import DeviceInfo from 'react-native-device-info';
import ReactNativeBlobUtil from 'react-native-blob-util';

import { AppConfig } from '@/config';

// MIME do pacote Android — o instalador do sistema so e acionado com este tipo.
const APK_MIME = 'application/vnd.android.package-archive';

/**
 * Uma versao publicada do app (espelha o GET /api/v1/public/app/latest do backend).
 * A `url` vem pronta do servidor (relativa ao host, ex.: "/api/v1/public/app/download/2"),
 * mas nao dependemos dela: montamos o endereco de download a partir do API_URL.
 */
export interface AppRelease {
  versionCode: number;
  versionName: string;
  notas?: string | null;
  obrigatoria: boolean;
  tamanhoBytes?: number | null;
  sha256?: string | null;
  url?: string | null;
}

/**
 * versionCode instalado. No Android, react-native-device-info.getBuildNumber() devolve
 * o versionCode (string). Sincrono. Fora do Android devolvemos 0 (nao distribuimos APK).
 */
export function versionCodeInstalado(): number {
  if (Platform.OS !== 'android') return 0;
  return parseInt(DeviceInfo.getBuildNumber(), 10) || 0;
}

/** versionName instalado (getVersion() = versionName no Android; sincrono). */
export function versionNameInstalado(): string {
  return DeviceInfo.getVersion() || '?';
}

/**
 * Consulta a ultima versao publicada e devolve a release SOMENTE se for mais nova que a
 * instalada. Silencioso em erro/sem-rede: a atualizacao nunca deve travar o uso do app
 * pelo motoboy (que trabalha em area com sinal ruim).
 */
export async function checarAtualizacao(): Promise<AppRelease | null> {
  if (Platform.OS !== 'android') return null; // por ora so distribuimos APK
  try {
    const res = await fetch(
      `${AppConfig.API_URL}/public/app/latest?plataforma=android`,
    );
    if (res.status === 204 || !res.ok) return null;
    const rel = (await res.json()) as AppRelease;
    if (!rel?.versionCode || rel.versionCode <= versionCodeInstalado()) return null;
    return rel;
  } catch {
    return null;
  }
}

/**
 * Baixa o APK da versao (reportando progresso 0..1) e abre o instalador do sistema.
 *
 * - Download via react-native-blob-util para o diretorio de cache do app. Esse diretorio
 *   e coberto pelo FileProvider que a propria lib registra (authority
 *   "${applicationId}.provider", cache-path "."), entao o actionViewIntent consegue gerar
 *   o content:// que o Android 7+ exige (nao aceita file://).
 * - actionViewIntent dispara ACTION_VIEW com o MIME de APK; o Android pede confirmacao e,
 *   na 1a vez, a permissao "instalar apps deste app" (Android 8+). Nao ha instalacao
 *   100% silenciosa fora de MDM/Play Store.
 */
export async function baixarEInstalar(
  rel: AppRelease,
  onProgress?: (frac: number) => void,
): Promise<void> {
  const url = `${AppConfig.API_URL}/public/app/download/${rel.versionCode}?plataforma=android`;
  const destino = `${ReactNativeBlobUtil.fs.dirs.CacheDir}/menuflow-motoboy-${rel.versionCode}.apk`;

  // Descarta download incompleto anterior (evita instalar um APK truncado).
  try {
    if (await ReactNativeBlobUtil.fs.exists(destino)) {
      await ReactNativeBlobUtil.fs.unlink(destino);
    }
  } catch {
    /* ignora */
  }

  const res = await ReactNativeBlobUtil.config({ path: destino })
    .fetch('GET', url)
    .progress({ interval: 250 }, (received, total) => {
      const t = Number(total);
      if (onProgress && t > 0) onProgress(Number(received) / t);
    });

  const status = res.info().status;
  if (status < 200 || status >= 300) {
    // Remove o arquivo parcial para nao envenenar uma proxima tentativa.
    try {
      await ReactNativeBlobUtil.fs.unlink(res.path());
    } catch {
      /* ignora */
    }
    throw new Error('Falha ao baixar a atualizacao.');
  }

  // Abre o instalador. A lib envolve o caminho no seu FileProvider (content://) e adiciona
  // FLAG_GRANT_READ_URI_PERMISSION internamente para Android 7+.
  ReactNativeBlobUtil.android.actionViewIntent(res.path(), APK_MIME);
}
