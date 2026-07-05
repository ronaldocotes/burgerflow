// Chip de status de entrega: SEMPRE icone + texto (nunca so cor).
import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import type { IconProps } from 'phosphor-react-native';
import {
  CheckCircle,
  MapPin,
  Moped,
  Package,
  Storefront,
  Timer,
  XCircle,
} from 'phosphor-react-native';

import { STATUS_COLOR, STATUS_LABEL } from '@/utils/deliveryStatus';
import { theme } from '@/theme/colors';
import type { DeliveryStatus } from '@/types/delivery';

const ICON_BY_STATUS: Record<DeliveryStatus, React.ComponentType<IconProps>> = {
  PENDING: Timer,
  OFFERED: Timer,
  ACCEPTED: Package,
  ASSIGNED: Package,
  ARRIVED_AT_STORE: Storefront,
  PICKED_UP: Package,
  OUT_FOR_DELIVERY: Moped,
  ARRIVED_AT_CUSTOMER: MapPin,
  DELIVERED: CheckCircle,
  FAILED: XCircle,
};

interface StatusChipProps {
  status: DeliveryStatus | null;
  size?: 'md' | 'lg';
}

export function StatusChip({ status, size = 'md' }: StatusChipProps) {
  if (!status) return null;
  const color = STATUS_COLOR[status];
  const label = STATUS_LABEL[status];
  const Icon = ICON_BY_STATUS[status];
  const lg = size === 'lg';

  return (
    <View
      accessibilityLabel={`Status: ${label}`}
      style={[styles.chip, lg && styles.chipLg, { borderColor: color }]}
    >
      <Icon color={color} size={lg ? 20 : 16} weight="fill" />
      <Text style={[styles.label, lg && styles.labelLg, { color }]}>{label}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  chip: {
    flexDirection: 'row',
    alignItems: 'center',
    alignSelf: 'flex-start',
    gap: 6,
    borderWidth: 1.5,
    borderRadius: 999,
    paddingHorizontal: 10,
    paddingVertical: 4,
    backgroundColor: theme.bg.primary,
  },
  chipLg: { paddingHorizontal: 14, paddingVertical: 7 },
  label: { fontSize: 13, fontWeight: '700' },
  labelLg: { fontSize: 16 },
});
