import { Platform } from 'react-native';

export interface AppConfig {
  ENV: 'development' | 'staging' | 'production';
  DEBUG: boolean;
  API_URL: string;
  WS_URL: string;
  APP_NAME: string;
  APP_VERSION: string;
  IS_ANDROID: boolean;
  IS_IOS: boolean;
  IS_WEB: boolean;
}

const readEnv = (key: string, fallback: string) => {
  const env = globalThis.process?.env as Record<string, string | undefined> | undefined;
  return env?.[key] ?? fallback;
};

const env = readEnv('MF_APP_ENV', 'development') as AppConfig['ENV'];

const config: AppConfig = {
  ENV: env,
  DEBUG: env !== 'production',
  API_URL: readEnv('MF_API_URL', 'http://localhost:8080/api/v1'),
  WS_URL: readEnv('MF_WS_URL', 'ws://localhost:8080'),
  APP_NAME: 'MenuFlow',
  APP_VERSION: '1.0.0',
  IS_ANDROID: Platform.OS === 'android',
  IS_IOS: Platform.OS === 'ios',
  IS_WEB: Platform.OS === 'web',
};

export default config;
