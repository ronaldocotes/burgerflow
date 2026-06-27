import React from 'react';
import {
  Pressable,
  StyleSheet,
  Text,
  View,
  ActivityIndicator,
} from 'react-native';
import { palette, semantic, theme } from '@/theme/colors';
import { formatBRL } from '@/utils/money';
import type { Product } from '@/types/menu';

interface ProductCardProps {
  product: Product;
  loading?: boolean;
  onPress: (product: Product) => void;
}

// Card de produto na grade 2 colunas do PDV.
// Persona: Ana/Atendente — velocidade; toque claro; preco em destaque.
// Pressable com android_ripple; minHeight 120; placeholder colorido se sem imagem.
export function ProductCard({ product, loading = false, onPress }: ProductCardProps) {
  const unavailable = !product.isAvailable;
  const initial = product.name.trim()[0]?.toUpperCase() ?? '?';

  return (
    <Pressable
      onPress={() => onPress(product)}
      disabled={unavailable || loading}
      android_ripple={{ color: palette.primary[200] }}
      style={({ pressed }) => [
        styles.card,
        pressed && styles.cardPressed,
        unavailable && styles.cardUnavailable,
      ]}
      accessibilityLabel={`${product.name}, ${formatBRL(product.effectivePriceCents)}${unavailable ? ', indisponivel' : ''}`}
      accessibilityRole="button"
    >
      {/* Placeholder de imagem com inicial do produto */}
      <View style={styles.imagePlaceholder}>
        <Text style={styles.imagePlaceholderText}>{initial}</Text>
        {product.onPromo && (
          <View style={styles.promoBadge}>
            <Text style={styles.promoBadgeText}>PROMO</Text>
          </View>
        )}
      </View>

      {/* Conteudo textual */}
      <View style={styles.body}>
        <Text style={styles.name} numberOfLines={2}>
          {product.name}
        </Text>

        <View style={styles.priceRow}>
          <Text style={styles.price}>{formatBRL(product.effectivePriceCents)}</Text>
          {product.onPromo && product.promoPriceCents != null && (
            <Text style={styles.priceOld}>{formatBRL(product.priceCents)}</Text>
          )}
        </View>

        {unavailable && (
          <View style={styles.unavailableBadge}>
            <Text style={styles.unavailableBadgeText}>INDISPONIVEL</Text>
          </View>
        )}
      </View>

      {/* Loading overlay */}
      {loading && (
        <View style={styles.loadingOverlay}>
          <ActivityIndicator size="small" color={theme.brand} />
        </View>
      )}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  card: {
    flex: 1,
    backgroundColor: theme.bg.primary,
    borderRadius: 12,
    overflow: 'hidden',
    minHeight: 120,
    borderWidth: 1,
    borderColor: theme.border.light,
    elevation: 1,
  },
  cardPressed: {
    opacity: 0.85,
  },
  cardUnavailable: {
    opacity: 0.5,
  },
  imagePlaceholder: {
    height: 64,
    backgroundColor: palette.primary[100],
    alignItems: 'center',
    justifyContent: 'center',
  },
  imagePlaceholderText: {
    fontSize: 24,
    fontWeight: '700',
    color: theme.brand,
  },
  promoBadge: {
    position: 'absolute',
    top: 6,
    right: 6,
    backgroundColor: semantic.error.DEFAULT,
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 4,
  },
  promoBadgeText: {
    fontSize: 9,
    fontWeight: '800',
    color: theme.text.onBrand,
    letterSpacing: 0.5,
  },
  body: {
    padding: 8,
    flex: 1,
  },
  name: {
    fontSize: 13,
    fontWeight: '600',
    color: theme.text.primary,
    lineHeight: 17,
    marginBottom: 4,
  },
  priceRow: {
    flexDirection: 'row',
    alignItems: 'baseline',
    gap: 4,
  },
  price: {
    fontSize: 14,
    fontWeight: '700',
    color: theme.brand,
  },
  priceOld: {
    fontSize: 11,
    color: theme.text.muted,
    textDecorationLine: 'line-through',
  },
  unavailableBadge: {
    marginTop: 4,
    alignSelf: 'flex-start',
    backgroundColor: semantic.error.light,
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 4,
    borderWidth: 1,
    borderColor: semantic.error.DEFAULT,
  },
  unavailableBadgeText: {
    fontSize: 9,
    fontWeight: '700',
    color: semantic.error.dark,
  },
  loadingOverlay: {
    position: 'absolute', top: 0, left: 0, right: 0, bottom: 0,
    backgroundColor: 'rgba(255,255,255,0.7)',
    alignItems: 'center',
    justifyContent: 'center',
  },
});
