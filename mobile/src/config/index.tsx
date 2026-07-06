import { Platform } from 'react-native';

// Configuracao do app. __DEV__ e injetado pelo Metro: true em debug, false em release.
// Em release apontamos para HTTPS/WSS (prod exige TLS; o cleartext so existe no debug manifest).
interface AppConfigShape {
  ENV: 'development' | 'production';
  DEBUG: boolean;
  API_URL: string;
  WS_URL: string;
  // URL publica do frontend -- usada para gerar o QR Code do cardapio digital.
  APP_URL: string;
  APP_NAME: string;
  APP_VERSION: string;
  IS_ANDROID: boolean;
  IS_IOS: boolean;
}

export const AppConfig: AppConfigShape = {
  ENV: __DEV__ ? 'development' : 'production',
  DEBUG: __DEV__,
  // Android emulador alcanca o host pela loopback especial 10.0.2.2 (nao localhost).
  API_URL: __DEV__
    ? 'http://10.0.2.2:8080/api/v1'
    : 'https://menuflow.duckdns.org/api/v1',
  // Handshake STOMP real: /api/v1/ws — o endpoint e registrado como "/ws" mas
  // vive SOB o server.servlet.context-path=/api/v1 do Spring Boot (em /ws da 404).
  WS_URL: __DEV__
    ? 'ws://10.0.2.2:8080/api/v1/ws'
    : 'wss://menuflow.duckdns.org/api/v1/ws',
  // URL do frontend para o QR Code do cardapio digital.
  APP_URL: __DEV__
    ? 'http://10.0.2.2:3000'
    : 'https://menuflow.duckdns.org',
  APP_NAME: 'MenuFlow',
  APP_VERSION: '1.0.0',
  IS_ANDROID: Platform.OS === 'android',
  IS_IOS: Platform.OS === 'ios',
};

export default AppConfig;
