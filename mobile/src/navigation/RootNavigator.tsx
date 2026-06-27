import React, { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';

import { getToken } from '@/lib/auth';
import LoginScreen from '@/screens/LoginScreen';
import AppTabs from './AppTabs';

// ---------------------------------------------------------------------------
// Tipos do stack raiz
// ---------------------------------------------------------------------------
export type RootStackParamList = {
  Login: undefined;
  Tabs: undefined;
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
// Navigator raiz: verifica token → direciona para Login ou Tabs
// ---------------------------------------------------------------------------
export default function RootNavigator() {
  const [loading, setLoading] = useState(true);
  const [hasToken, setHasToken] = useState(false);

  useEffect(() => {
    getToken()
      .then((t) => setHasToken(!!t))
      .catch(() => setHasToken(false))
      .finally(() => setLoading(false));
  }, []);

  if (loading) {
    return <SplashScreen />;
  }

  return (
    <Stack.Navigator screenOptions={{ headerShown: false, animation: 'fade' }}>
      {hasToken ? (
        <Stack.Screen name="Tabs" component={AppTabs} />
      ) : (
        <Stack.Screen name="Login" component={LoginScreen} />
      )}
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
