// Ref global de navegacao: permite resetar para o Login de fora da arvore React
// (ex.: handler global de 401 registrado em RootNavigator).
import { createNavigationContainerRef } from '@react-navigation/native';
import type { RootStackParamList } from './RootNavigator';

export const navigationRef = createNavigationContainerRef<RootStackParamList>();

export function resetToLogin() {
  if (navigationRef.isReady()) {
    navigationRef.resetRoot({ index: 0, routes: [{ name: 'Login' }] });
  }
}
