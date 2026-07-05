import 'react-native-gesture-handler';
import './global.css';
import React from 'react';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { PaperProvider } from 'react-native-paper';
import { BottomSheetModalProvider } from '@gorhom/bottom-sheet';
import { NavigationContainer } from '@react-navigation/native';
import { paperTheme } from '@/theme/paperTheme';
import RootNavigator from '@/navigation/RootNavigator';
import { navigationRef } from '@/navigation/navRef';

// Providers da Fase M0 + M2. Ordem importa:
// GestureHandlerRootView (gesto/bottom-sheet) > SafeAreaProvider > PaperProvider >
// BottomSheetModalProvider (requer GestureHandlerRootView acima) > NavigationContainer.
// navigationRef (Fase 6.2): permite ao handler global de 401 resetar para o Login.
export default function App() {
  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <SafeAreaProvider>
        <PaperProvider theme={paperTheme}>
          <BottomSheetModalProvider>
            <NavigationContainer ref={navigationRef}>
              <RootNavigator />
            </NavigationContainer>
          </BottomSheetModalProvider>
        </PaperProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}
