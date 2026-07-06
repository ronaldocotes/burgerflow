import React, { useEffect, useState } from 'react';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { CookingPot, ShoppingCart, Table } from 'phosphor-react-native';

import { getToken } from '@/lib/auth';
import { isKitchenOnlyToken } from '@/lib/jwt';
import KdsScreen from '@/screens/KdsScreen';
import PdvScreen from '@/screens/PdvScreen';
import MesasScreen from '@/screens/MesasScreen';

// --- Tokens (nao inventar outros) ---
const COLOR_ACTIVE = '#047857';   // primary-700
const COLOR_INACTIVE = '#64748B';
const COLOR_BG = '#FFFFFF';
const COLOR_BORDER = '#E2E8F0';

const Tab = createBottomTabNavigator();

type TabIconProps = { color: string; size: number; focused: boolean };

function KdsIcon({ color, size, focused }: TabIconProps) {
  return (
    <CookingPot
      color={color}
      size={size}
      weight={focused ? 'fill' : 'regular'}
    />
  );
}

function PdvIcon({ color, size, focused }: TabIconProps) {
  return (
    <ShoppingCart
      color={color}
      size={size}
      weight={focused ? 'fill' : 'regular'}
    />
  );
}

function MesasIcon({ color, size, focused }: TabIconProps) {
  return (
    <Table
      color={color}
      size={size}
      weight={focused ? 'fill' : 'regular'}
    />
  );
}

/**
 * M1: gate por papel (roles do JWT — mesma técnica do isDriverToken no login).
 * - KITCHEN puro (sem ADMIN/MANAGER): vê SÓ o KDS, em tela cheia (sem tab bar —
 *   mais área útil no tablet da cozinha).
 * - Demais papéis da loja (ADMIN, MANAGER, STAFF, CASHIER, OPERATOR, WAITER):
 *   todas as abas, incluindo Cozinha.
 * - DRIVER nunca chega aqui (RootNavigator/Login roteiam para DriverTabs).
 */
export default function AppTabs() {
  const [kitchenOnly, setKitchenOnly] = useState<boolean | null>(null);

  useEffect(() => {
    getToken()
      .then((t) => setKitchenOnly(isKitchenOnlyToken(t)))
      .catch(() => setKitchenOnly(false));
  }, []);

  // Leitura do token é local e rápida; evita montar as abas erradas por um frame.
  if (kitchenOnly === null) return null;

  if (kitchenOnly) return <KdsScreen />;

  return (
    <Tab.Navigator
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: COLOR_ACTIVE,
        tabBarInactiveTintColor: COLOR_INACTIVE,
        tabBarStyle: {
          backgroundColor: COLOR_BG,
          borderTopColor: COLOR_BORDER,
          borderTopWidth: 1,
        },
        tabBarLabelStyle: {
          fontSize: 12,
          fontWeight: '600',
        },
      }}
    >
      <Tab.Screen
        name="Pedidos"
        component={PdvScreen}
        options={{
          tabBarIcon: (props) => <PdvIcon {...props} />,
        }}
      />
      <Tab.Screen
        name="Mesas"
        component={MesasScreen}
        options={{
          tabBarIcon: (props) => <MesasIcon {...props} />,
        }}
      />
      <Tab.Screen
        name="Cozinha"
        component={KdsScreen}
        options={{
          tabBarIcon: (props) => <KdsIcon {...props} />,
        }}
      />
    </Tab.Navigator>
  );
}
