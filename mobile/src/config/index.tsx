import React, { createContext, useContext, useMemo } from 'react';
import { Platform } from 'react-native';
import Config from 'react-native-config';

// Environment configuration
export interface AppConfig {
  // Environment
  ENV: 'development' | 'staging' | 'production';
  DEBUG: boolean;
  
  // API
  API_URL: string;
  WS_URL: string;
  
  // App info
  APP_NAME: string;
  APP_VERSION: string;
  
  // Features
  FEATURE_AUTH: boolean;
  FEATURE_SOCKET: boolean;
  FEATURE_ANALYTICS: boolean;
  
  // Platform specific
  IS_ANDROID: boolean;
  IS_IOS: boolean;
  IS_WEB: boolean;
}

// Default configuration
const defaultConfig: AppConfig = {
  ENV: Config.ENV as any || 'development',
  DEBUG: Config.DEBUG === 'true' || __DEV__,
  
  API_URL: Config.API_URL || 'http://localhost:8080/api/v1',
  WS_URL: Config.WS_URL || 'ws://localhost:8080',
  
  APP_NAME: Config.APP_NAME || 'BurgerFlow',
  APP_VERSION: Config.APP_VERSION || '1.0.0',
  
  FEATURE_AUTH: Config.FEATURE_AUTH !== 'false',
  FEATURE_SOCKET: Config.FEATURE_SOCKET !== 'false',
  FEATURE_ANALYTICS: Config.FEATURE_ANALYTICS !== 'false',
  
  IS_ANDROID: Platform.OS === 'android',
  IS_IOS: Platform.OS === 'ios',
  IS_WEB: Platform.OS === 'web',
};

// Configuration context
const AppConfigContext = createContext<AppConfig>(defaultConfig);

// Provider component
export const AppConfigProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const config = useMemo(() => defaultConfig, []);
  
  return (
    <AppConfigContext.Provider value={config}>
      {children}
    </AppConfigContext.Provider>
  );
};

// Hook to use configuration
export const useAppConfig = () => useContext(AppConfigContext);

// Export configuration
export { defaultConfig as config };
export default defaultConfig;
