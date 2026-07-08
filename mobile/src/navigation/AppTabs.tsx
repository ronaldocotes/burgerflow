import React, { useEffect, useState } from 'react';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { CookingPot, Money, ShoppingCart, Table } from 'phosphor-react-native';

import { getToken } from '@/lib/auth';
import { canAccessCaixaToken, isKitchenOnlyToken } from '@/lib/jwt';
import KdsScreen from '@/screens/KdsScreen';
import PdvScreen from '@/screens/PdvScreen';
import MesasScreen from '@/screens/MesasScreen';
import CaixaScreen from '@/screens/CaixaScreen';

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

function CaixaIcon({ color, size, focused }: TabIconProps) {
  return (
    <Money
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
 * - M4: a aba Caixa aparece SO para ADMIN/MANAGER/CASHIER (mesmo RBAC do backend).
 * - DRIVER nunca chega aqui (RootNavigator/Login roteiam para DriverTabs).
 */
export default function AppTabs() {
  const [gate, setGate] = useState<{
    kitchenOnly: boolean;
    canCaixa: boolean;
  } | null>(null);

  useEffect(() => {
    getToken()
      .then((t) =>
        setGate({
          kitchenOnly: isKitchenOnlyToken(t),
          canCaixa: canAccessCaixaToken(t),
        }),
      )
      .catch(() => setGate({ kitchenOnly: false, canCaixa: false }));
  }, []);

  // Leitura do token é local e rápida; evita montar as abas erradas por um frame.
  if (gate === null) return null;

  if (gate.kitchenOnly) return <KdsScreen />;

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
      {gate.canCaixa && (
        <Tab.Screen
          name="Caixa"
          component={CaixaScreen}
          options={{
            tabBarIcon: (props) => <CaixaIcon {...props} />,
          }}
        />
      )}
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
