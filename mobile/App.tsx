import 'react-native-gesture-handler';
import './global.css';
import React from 'react';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { PaperProvider } from 'react-native-paper';
import { NavigationContainer } from '@react-navigation/native';
import { paperTheme } from '@/theme/paperTheme';
import RootNavigator from '@/navigation/RootNavigator';

// Providers da Fase M0. Ordem importa: GestureHandlerRootView por fora (gesto/bottom-sheet),
// SafeAreaProvider, PaperProvider (tema) e NavigationContainer envolvendo o RootNavigator do Nick.
export default function App() {
  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <SafeAreaProvider>
        <PaperProvider theme={paperTheme}>
          <NavigationContainer>
            <RootNavigator />
          </NavigationContainer>
        </PaperProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}
