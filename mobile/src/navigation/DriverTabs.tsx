// Abas do MOTOBOY (Fase 6.2): Inicio (turno + ofertas), Entregas (stack com
// detalhe) e Ganhos. Envolvidas pelo DriverProvider (perfil, entregas, GPS).
import React from 'react';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import type { NavigatorScreenParams } from '@react-navigation/native';
import { Moped, Power, Wallet } from 'phosphor-react-native';

import { DriverProvider } from '@/context/DriverContext';
import DriverHomeScreen from '@/screens/driver/DriverHomeScreen';
import DriverDeliveriesScreen from '@/screens/driver/DriverDeliveriesScreen';
import DriverDeliveryDetailScreen from '@/screens/driver/DriverDeliveryDetailScreen';
import DriverEarningsScreen from '@/screens/driver/DriverEarningsScreen';
import { theme } from '@/theme/colors';

// --- Tipos de navegacao ---
export type DriverDeliveriesStackParamList = {
  DeliveriesList: undefined;
  DeliveryDetail: { orderId: string };
};

export type DriverTabParamList = {
  Inicio: undefined;
  Entregas: NavigatorScreenParams<DriverDeliveriesStackParamList> | undefined;
  Ganhos: undefined;
};

// --- Tokens da tab bar (mesmos do AppTabs) ---
const COLOR_ACTIVE = theme.brand;
const COLOR_INACTIVE = '#64748B';
const COLOR_BG = '#FFFFFF';
const COLOR_BORDER = '#E2E8F0';

const Tab = createBottomTabNavigator<DriverTabParamList>();
const Stack = createNativeStackNavigator<DriverDeliveriesStackParamList>();

type TabIconProps = { color: string; size: number; focused: boolean };

function HomeIcon({ color, size, focused }: TabIconProps) {
  return <Power color={color} size={size} weight={focused ? 'fill' : 'regular'} />;
}

function DeliveriesIcon({ color, size, focused }: TabIconProps) {
  return <Moped color={color} size={size} weight={focused ? 'fill' : 'regular'} />;
}

function EarningsIcon({ color, size, focused }: TabIconProps) {
  return <Wallet color={color} size={size} weight={focused ? 'fill' : 'regular'} />;
}

function DeliveriesStack() {
  return (
    <Stack.Navigator screenOptions={{ headerShown: false }}>
      <Stack.Screen name="DeliveriesList" component={DriverDeliveriesScreen} />
      <Stack.Screen name="DeliveryDetail" component={DriverDeliveryDetailScreen} />
    </Stack.Navigator>
  );
}

export default function DriverTabs() {
  return (
    <DriverProvider>
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
          name="Inicio"
          component={DriverHomeScreen}
          options={{
            title: 'Início',
            tabBarIcon: (props) => <HomeIcon {...props} />,
          }}
        />
        <Tab.Screen
          name="Entregas"
          component={DeliveriesStack}
          options={{
            tabBarIcon: (props) => <DeliveriesIcon {...props} />,
          }}
        />
        <Tab.Screen
          name="Ganhos"
          component={DriverEarningsScreen}
          options={{
            tabBarIcon: (props) => <EarningsIcon {...props} />,
          }}
        />
      </Tab.Navigator>
    </DriverProvider>
  );
}
