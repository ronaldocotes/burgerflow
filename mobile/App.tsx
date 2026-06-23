import React from 'react';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { QueryClient, QueryClientProvider } from 'react-query';
import Toast from 'react-native-toast-message';

import { ThemeProvider } from '@/theme';
import { AuthProvider } from '@/store/AuthContext';
import { SocketProvider } from '@/services/socket';
import { Navigation } from '@/navigation';
import { AppConfigProvider } from '@/config';

// Create query client
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 1000 * 60 * 5, // 5 minutes
      retry: 1,
      refetchOnWindowFocus: true,
    },
  },
});

const App = () => {
  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <SafeAreaProvider>
        <QueryClientProvider client={queryClient}>
          <ThemeProvider>
            <AppConfigProvider>
              <AuthProvider>
                <SocketProvider>
                  <Navigation />
                  <Toast />
                </SocketProvider>
              </AuthProvider>
            </AppConfigProvider>
          </ThemeProvider>
        </QueryClientProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
};

export default App;
