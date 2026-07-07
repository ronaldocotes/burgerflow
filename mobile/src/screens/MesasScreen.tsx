// Tela de Mesas: board visual com STOMP + fallback polling.
// Grid 2 colunas de TableCards. 4 estados obrigatorios: loading, vazio, erro, live.
// Persona: Ana/Atendente -- acao rapida em <2 toques.

import React, { useCallback, useRef, useState } from 'react';
import {
  FlatList,
  Pressable,
  SafeAreaView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { palette, theme } from '@/theme/colors';
import { useTablesFeed } from '@/hooks/useTablesFeed';
import { StatusBanner } from '@/components/ui/StatusBanner';
import { TableCard } from '@/components/mesas/TableCard';
import {
  TableActionsSheet,
  type TableActionsSheetRef,
} from '@/components/mesas/TableActionsSheet';
import {
  TableBillSheet,
  type TableBillSheetRef,
} from '@/components/mesas/TableBillSheet';
import {
  TableQrSheet,
  type TableQrSheetRef,
} from '@/components/mesas/TableQrSheet';
import type { TableDto } from '@/types/tables';

// -- Skeleton de carregamento (4 cards placeholders) --------------------------

function SkeletonCard() {
  return (
    <View style={skeletonSt.card} accessibilityElementsHidden>
      <View style={skeletonSt.line1} />
      <View style={skeletonSt.line2} />
      <View style={skeletonSt.line3} />
      <View style={skeletonSt.btn} />
    </View>
  );
}

const skeletonSt = StyleSheet.create({
  card: {
    flex: 1,
    margin: 5,
    borderRadius: 14,
    backgroundColor: theme.bg.secondary,
    overflow: 'hidden',
    minHeight: 140,
    padding: 14,
    gap: 8,
  },
  line1: { height: 20, width: '60%', borderRadius: 6, backgroundColor: theme.bg.tertiary },
  line2: { height: 12, width: '40%', borderRadius: 6, backgroundColor: theme.bg.tertiary },
  line3: { height: 14, width: '50%', borderRadius: 6, backgroundColor: theme.bg.tertiary },
  btn: { height: 40, borderRadius: 8, backgroundColor: theme.bg.tertiary, marginTop: 8 },
});

// -- Estado vazio --------------------------------------------------------------

function EmptyState() {
  return (
    <View style={styles.centered}>
      <Text style={styles.emptyTitle}>Nenhuma mesa cadastrada.</Text>
      <Text style={styles.emptyDesc}>Configure as mesas no painel administrativo.</Text>
    </View>
  );
}

// -- Estado de erro -----------------------------------------------------------

function ErrorState({ onRetry }: { onRetry: () => void }) {
  return (
    <View style={styles.centered}>
      <Text style={styles.errorTitle}>Nao foi possivel carregar as mesas.</Text>
      <Pressable
        onPress={onRetry}
        android_ripple={{ color: 'rgba(0,0,0,0.06)' }}
        style={styles.retryBtn}
        accessibilityRole="button"
        accessibilityLabel="Tentar novamente"
      >
        <Text style={styles.retryBtnText}>Tentar novamente</Text>
      </Pressable>
    </View>
  );
}

// -- Header -------------------------------------------------------------------

interface HeaderProps {
  occupiedCount: number;
  freeCount: number;
  onRefresh: () => void;
}

function Header({ occupiedCount, freeCount, onRefresh }: HeaderProps) {
  const total = occupiedCount + freeCount;
  const occupiedStr = occupiedCount !== 1 ? 'ocupadas' : 'ocupada';
  const freeStr = freeCount !== 1 ? 'livres' : 'livre';
  const countLabel =
    total > 0 ? `${occupiedCount} ${occupiedStr} · ${freeCount} ${freeStr}` : '';

  return (
    <View style={styles.header}>
      <View style={styles.headerLeft}>
        <Text style={styles.headerTitle}>Mesas</Text>
        {countLabel ? <Text style={styles.headerCount}>{countLabel}</Text> : null}
      </View>
      <Pressable
        onPress={onRefresh}
        hitSlop={8}
        style={styles.refreshBtn}
        accessibilityRole="button"
        accessibilityLabel="Atualizar mesas"
      >
        <Text style={styles.refreshBtnText}>Atualizar</Text>
      </Pressable>
    </View>
  );
}

// -- MesasScreen (tela principal) ---------------------------------------------

export default function MesasScreen() {
  const { tables, feedStatus, refresh } = useTablesFeed();
  const [fetchError, setFetchError] = useState(false);
  const [initialLoaded, setInitialLoaded] = useState(false);

  const actionsRef = useRef<TableActionsSheetRef>(null);
  const billRef = useRef<TableBillSheetRef>(null);
  const qrRef = useRef<TableQrSheetRef>(null);

  // Registra primeiro carregamento bem-sucedido
  if (!initialLoaded && tables.length > 0) {
    setInitialLoaded(true);
    setFetchError(false);
  }

  const activeTables = tables.filter((t) => t.active);
  const occupiedCount = activeTables.filter((t) => t.session).length;
  const freeCount = activeTables.filter((t) => !t.session).length;

  const isLoading = !initialLoaded && feedStatus === 'connecting' && tables.length === 0;
  const isEmpty =
    !isLoading &&
    !fetchError &&
    (feedStatus === 'live' || feedStatus === 'polling') &&
    activeTables.length === 0;

  const sorted = activeTables.slice().sort((a, b) => a.sortOrder - b.sortOrder);

  const handleRetry = useCallback(async () => {
    setFetchError(false);
    try {
      await refresh();
    } catch {
      setFetchError(true);
    }
  }, [refresh]);

  const handleCardPress = useCallback((table: TableDto) => {
    actionsRef.current?.open(table);
  }, []);

  const handleBillPress = useCallback((table: TableDto) => {
    billRef.current?.open(table);
  }, []);

  const handleQrPress = useCallback((table: TableDto) => {
    qrRef.current?.open(table);
  }, []);

  const handleActionSuccess = useCallback(async () => {
    await refresh();
  }, [refresh]);

  const renderItem = useCallback(
    ({ item }: { item: TableDto }) => (
      <View style={styles.cardWrapper}>
        <TableCard
          table={item}
          onCardPress={handleCardPress}
          onQrPress={handleQrPress}
          onActionSuccess={handleActionSuccess}
        />
      </View>
    ),
    [handleCardPress, handleQrPress, handleActionSuccess],
  );

  function renderLoading() {
    return (
      <View
        style={styles.skeletonGrid}
        accessibilityLabel="Carregando mesas"
        accessibilityState={{ busy: true }}
      >
        {Array.from({ length: 4 }).map((_, i) => (
          <View key={i} style={styles.cardWrapper}>
            <SkeletonCard />
          </View>
        ))}
      </View>
    );
  }

  return (
    <SafeAreaView style={styles.root}>
      <StatusBanner feedStatus={feedStatus} />

      <Header
        occupiedCount={occupiedCount}
        freeCount={freeCount}
        onRefresh={() => void refresh()}
      />

      {/* 4 estados obrigatorios */}
      {isLoading && renderLoading()}
      {fetchError && <ErrorState onRetry={() => void handleRetry()} />}
      {isEmpty && <EmptyState />}

      {!isLoading && !fetchError && sorted.length > 0 && (
        <FlatList
          data={sorted}
          keyExtractor={(item) => item.id}
          renderItem={renderItem}
          numColumns={2}
          contentContainerStyle={styles.grid}
          columnWrapperStyle={styles.row}
          showsVerticalScrollIndicator={false}
          accessibilityLabel="Lista de mesas"
        />
      )}

      {/* Modais fora do FlatList para evitar unmount prematuro */}
      <TableActionsSheet
        ref={actionsRef}
        onSuccess={handleActionSuccess}
        onViewBill={handleBillPress}
        onShowQr={handleQrPress}
      />
      <TableBillSheet ref={billRef} />
      <TableQrSheet ref={qrRef} />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: theme.bg.secondary,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: theme.bg.primary,
    borderBottomWidth: 1,
    borderBottomColor: theme.border.light,
  },
  headerLeft: {
    gap: 2,
  },
  headerTitle: {
    fontSize: 18,
    fontWeight: '800',
    color: theme.text.primary,
  },
  headerCount: {
    fontSize: 12,
    color: theme.text.secondary,
  },
  refreshBtn: {
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: theme.border.light,
    minHeight: 36,
    alignItems: 'center',
    justifyContent: 'center',
  },
  refreshBtnText: {
    fontSize: 13,
    color: theme.text.secondary,
    fontWeight: '500',
  },
  grid: {
    padding: 12,
    paddingBottom: 32,
  },
  row: {
    gap: 10,
    marginBottom: 10,
  },
  cardWrapper: {
    flex: 1,
  },
  skeletonGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    padding: 12,
    gap: 10,
  },
  centered: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
    gap: 12,
  },
  emptyTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: theme.text.secondary,
    textAlign: 'center',
  },
  emptyDesc: {
    fontSize: 13,
    color: theme.text.muted,
    textAlign: 'center',
  },
  errorTitle: {
    fontSize: 16,
    fontWeight: '600',
    color: theme.text.secondary,
    textAlign: 'center',
  },
  retryBtn: {
    minHeight: 48,
    paddingHorizontal: 24,
    paddingVertical: 12,
    backgroundColor: palette.primary[700],
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  retryBtnText: {
    fontSize: 15,
    fontWeight: '700',
    color: theme.text.onBrand,
  },
});
