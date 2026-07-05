import React, { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';

import { setOnUnauthorized } from '@/lib/api';
import { clearSession, getToken } from '@/lib/auth';
import { isDriverToken } from '@/lib/jwt';
import LoginScreen from '@/screens/LoginScreen';
import AppTabs from './AppTabs';
import DriverTabs from './DriverTabs';
import { resetToLogin } from './navRef';

// ---------------------------------------------------------------------------
// Tipos do stack raiz
// ---------------------------------------------------------------------------
export type RootStackParamList = {
  Login: undefined;
  Tabs: undefined;
  DriverTabs: undefined;
};

const Stack = createNativeStackNavigator<RootStackParamList>();

// ---------------------------------------------------------------------------
// Tela de splash minima (exibida apenas durante a verificacao do token)
// ---------------------------------------------------------------------------
function SplashScreen() {
  return (
    <View style={styles.splash}>
      <Text style={styles.splashBrand}>MF</Text>
      <Text style={styles.splashName}>MenuFlow</Text>
      <ActivityIndicator
        size="large"
        color="#047857"
        style={styles.splashIndicator}
      />
    </View>
  );
}

// ---------------------------------------------------------------------------
// Navigator raiz: verifica token -> Login, Tabs (equipe) ou DriverTabs (motoboy).
// Todas as rotas ficam registradas; a inicial vem da sessao persistida.
// ---------------------------------------------------------------------------
export default function RootNavigator() {
  const [initialRoute, setInitialRoute] = useState<keyof RootStackParamList | null>(null);

  useEffect(() => {
    getToken()
      .then((t) =>
        setInitialRoute(t ? (isDriverToken(t) ? 'DriverTabs' : 'Tabs') : 'Login'),
      )
      .catch(() => setInitialRoute('Login'));
  }, []);

  // 401 em qualquer chamada (sessao morta/revogada) -> limpa e volta ao Login.
  useEffect(() => {
    setOnUnauthorized(() => {
      clearSession().finally(resetToLogin);
    });
    return () => setOnUnauthorized(null);
  }, []);

  if (!initialRoute) {
    return <SplashScreen />;
  }

  return (
    <Stack.Navigator
      initialRouteName={initialRoute}
      screenOptions={{ headerShown: false, animation: 'fade' }}
    >
      <Stack.Screen name="Login" component={LoginScreen} />
      <Stack.Screen name="Tabs" component={AppTabs} />
      <Stack.Screen name="DriverTabs" component={DriverTabs} />
    </Stack.Navigator>
  );
}

// ---------------------------------------------------------------------------
// Estilos
// ---------------------------------------------------------------------------
const styles = StyleSheet.create({
  splash: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#FFFFFF',
  },
  splashBrand: {
    fontSize: 48,
    fontWeight: '800',
    color: '#047857',
    letterSpacing: -1,
  },
  splashName: {
    fontSize: 18,
    fontWeight: '600',
    color: '#0F172A',
    marginTop: 4,
    marginBottom: 32,
  },
  splashIndicator: {
    marginTop: 0,
  },
});
