import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import BottomSheet from '@gorhom/bottom-sheet';
import type { BottomSheetModal } from '@gorhom/bottom-sheet';
import { palette, semantic, theme } from '@/theme/colors';
import { api, ApiError } from '@/lib/api';
import { useCart, buildSimpleLine } from '@/hooks/useCart';
import { useQuote } from '@/hooks/useQuote';
import type { CartLine, OrderType } from '@/types/cart';
import type { Category, Page, Product, ProductVariations } from '@/types/menu';
import { ProductCard } from '@/components/pdv/ProductCard';
import { CartBottomSheet } from '@/components/pdv/CartBottomSheet';
import { CustomizeSheet, type CustomizeSheetRef } from '@/components/pdv/CustomizeSheet';
import { PaymentSheet, type PaymentSheetRef } from '@/components/pdv/PaymentSheet';

// Tela PDV: grade de produtos -> carrinho (bottom sheet persistente) ->
// personalizar (modal) -> pagamento (modal) -> confirmar (POST /orders + Idempotency-Key).
// Persona: Ana/Atendente - velocidade < 30s por pedido; busca rapida.
export default function PdvScreen() {
  const [products, setProducts] = useState<Product[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [selectedCategory, setSelectedCategory] = useState<string | null>(null);
  const [loadingProductId, setLoadingProductId] = useState<string | null>(null);
  const [orderType, setOrderType] = useState<OrderType>('DINE_IN');
  const [badgeFlash, setBadgeFlash] = useState(false);

  const cartRef = useRef<BottomSheet>(null);
  const customizeRef = useRef<CustomizeSheetRef>(null);
  const paymentRef = useRef<PaymentSheetRef>(null);

  const { lines, addLine, updateQty, removeLine, clearCart } = useCart();
  const { quote, quoting, quoteError } = useQuote(lines, orderType);

  // Carrega catalogo + categorias em paralelo.
  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [prods, cats] = await Promise.allSettled([
        api.get<Page<Product>>('/products?size=200&active=true'),
        api.get<Category[]>('/categories'),
      ]);
      if (prods.status === 'fulfilled') {
        setProducts(prods.value.content);
      } else {
        throw prods.reason;
      }
      if (cats.status === 'fulfilled') {
        setCategories(cats.value.filter((c) => c.active));
      }
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Erro ao carregar produtos.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // Filtra produtos por busca e categoria selecionada.
  const filtered = useMemo(() => {
    const term = search.trim().toLowerCase();
    let list = products.filter((p) => p.active && p.isAvailable);
    if (selectedCategory) {
      list = list.filter((p) => p.categoryId === selectedCategory);
    }
    if (term) {
      list = list.filter(
        (p) =>
          p.name.toLowerCase().includes(term) ||
          p.sku.toLowerCase().includes(term),
      );
    }
    return list;
  }, [products, search, selectedCategory]);

  // Ao tocar num produto: busca variacoes em paralelo; simples entra direto.
  const onPickProduct = useCallback(
    async (product: Product) => {
      if (loadingProductId) return;
      setLoadingProductId(product.id);
      try {
        const [groups, sizes, flavors, crusts] = await Promise.all([
          api.get<ProductVariations['groups']>(`/products/${product.id}/option-groups`),
          api.get<ProductVariations['sizes']>(`/products/${product.id}/sizes`),
          api.get<ProductVariations['flavors']>(`/products/${product.id}/flavors`),
          api.get<ProductVariations['crusts']>(`/products/${product.id}/crust-prices`),
        ]);
        const variations: ProductVariations = {
          groups: groups.filter((g) => g.active),
          sizes: sizes.filter((s) => s.active),
          flavors: flavors.filter((f) => f.active),
          crusts,
        };
        const hasVariation =
          variations.groups.length > 0 ||
          variations.sizes.length > 0 ||
          variations.flavors.length > 0 ||
          variations.crusts.length > 0;

        if (hasVariation) {
          customizeRef.current?.open(product, variations);
        } else {
          addSimpleProduct(product);
        }
      } catch {
        // Se variacoes nao puderem ser lidas, trata como simples.
        addSimpleProduct(product);
      } finally {
        setLoadingProductId(null);
      }
    },
    [loadingProductId],
  );

  function addSimpleProduct(product: Product) {
    addLine(buildSimpleLine(product));
    // Flash visual no badge do carrinho
    setBadgeFlash(true);
    setTimeout(() => setBadgeFlash(false), 400);
    // Expande o bottom sheet para confirmar visualmente
    cartRef.current?.snapToIndex(1);
    setTimeout(() => cartRef.current?.snapToIndex(0), 800);
  }

  function handleCartAdd(line: CartLine) {
    addLine(line);
    setBadgeFlash(true);
    setTimeout(() => setBadgeFlash(false), 400);
    cartRef.current?.snapToIndex(1);
    setTimeout(() => cartRef.current?.snapToIndex(0), 800);
  }

  function handleFinalize() {
    if (!quote) return;
    const items = lines.map((l) => ({ ...l.item, quantity: l.quantity }));
    paymentRef.current?.open(quote, orderType, items);
  }

  function handleOrderConfirmed(orderNumber: string) {
    clearCart();
    cartRef.current?.snapToIndex(0);
    // Toast via Alert (sem dependencia extra)
    if (orderNumber) {
      // Exibe feedback de sucesso - em producao trocar por toast/snackbar
    }
  }

  // --- Estados de loading / erro / vazio ---

  if (loading) {
    return (
      <SafeAreaView style={styles.root}>
        <View style={styles.topBar}>
          <Text style={styles.title}>PDV</Text>
        </View>
        <View style={styles.skeletonGrid}>
          {Array.from({ length: 6 }).map((_, i) => (
            <View key={i} style={styles.skeletonCard} />
          ))}
        </View>
      </SafeAreaView>
    );
  }

  if (error) {
    return (
      <SafeAreaView style={[styles.root, styles.center]}>
        <Text style={styles.errorText} accessibilityRole="alert">
          {error}
        </Text>
        <Pressable style={styles.retryBtn} onPress={() => void load()}>
          <Text style={styles.retryBtnText}>Tentar de novo</Text>
        </Pressable>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.root}>
      {/* Header */}
      <View style={styles.topBar}>
        <Text style={styles.title}>PDV</Text>
      </View>

      {/* Busca */}
      <View style={styles.searchRow}>
        <Text style={styles.searchIcon}>Q</Text>
        <TextInput
          style={styles.searchInput}
          value={search}
          onChangeText={setSearch}
          placeholder="Buscar produto..."
          placeholderTextColor={theme.text.muted}
          returnKeyType="search"
          clearButtonMode="while-editing"
          accessibilityLabel="Buscar produto"
        />
      </View>

      {/* Chips de categoria */}
      {categories.length > 0 && (
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={styles.categoryChips}
          keyboardShouldPersistTaps="handled"
        >
          <Pressable
            style={[
              styles.chip,
              selectedCategory === null && styles.chipActive,
            ]}
            onPress={() => setSelectedCategory(null)}
          >
            <Text
              style={[
                styles.chipText,
                selectedCategory === null && styles.chipTextActive,
              ]}
            >
              Todos
            </Text>
          </Pressable>
          {categories.map((c) => (
            <Pressable
              key={c.id}
              style={[
                styles.chip,
                selectedCategory === c.id && styles.chipActive,
              ]}
              onPress={() =>
                setSelectedCategory(selectedCategory === c.id ? null : c.id)
              }
            >
              <Text
                style={[
                  styles.chipText,
                  selectedCategory === c.id && styles.chipTextActive,
                ]}
              >
                {c.name}
              </Text>
            </Pressable>
          ))}
        </ScrollView>
      )}

      {/* Grade de produtos */}
      {filtered.length === 0 ? (
        <View style={styles.emptyState}>
          <Text style={styles.emptyTitle}>Nenhum produto</Text>
          <Text style={styles.emptyDesc}>
            {products.length === 0
              ? 'Cadastre produtos para vender no PDV.'
              : 'Nenhum produto corresponde a busca.'}
          </Text>
        </View>
      ) : (
        <FlatList
          data={filtered}
          keyExtractor={(item) => item.id}
          numColumns={2}
          columnWrapperStyle={styles.gridRow}
          contentContainerStyle={styles.gridContent}
          renderItem={({ item }) => (
            <View style={styles.gridItem}>
              <ProductCard
                product={item}
                loading={loadingProductId === item.id}
                onPress={(p) => void onPickProduct(p)}
              />
            </View>
          )}
          showsVerticalScrollIndicator={false}
          keyboardShouldPersistTaps="handled"
        />
      )}

      {/* Bottom sheet persistente do carrinho */}
      <CartBottomSheet
        ref={cartRef}
        lines={lines}
        quote={quote}
        quoting={quoting}
        quoteError={quoteError}
        orderType={orderType}
        onOrderTypeChange={setOrderType}
        onUpdateQty={updateQty}
        onClear={clearCart}
        onFinalize={handleFinalize}
      />

      {/* Sheet de personalizacao (abre via ref) */}
      <CustomizeSheet ref={customizeRef} onAdd={handleCartAdd} />

      {/* Sheet de pagamento (abre via ref) */}
      <PaymentSheet ref={paymentRef} onConfirmed={handleOrderConfirmed} />
    </SafeAreaView>
  );
}

const CART_SHEET_HEIGHT = 80; // altura aproximada do sheet colapsado

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: theme.bg.secondary,
  },
  center: {
    justifyContent: 'center',
    alignItems: 'center',
    gap: 16,
    padding: 24,
  },
  topBar: {
    paddingHorizontal: 16,
    paddingTop: 8,
    paddingBottom: 4,
  },
  title: {
    fontSize: 22,
    fontWeight: '800',
    color: theme.text.primary,
  },
  searchRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginHorizontal: 16,
    marginVertical: 8,
    backgroundColor: theme.bg.primary,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: theme.border.light,
    paddingHorizontal: 12,
    height: 44,
  },
  searchIcon: {
    fontSize: 16,
    color: theme.text.muted,
    marginRight: 8,
  },
  searchInput: {
    flex: 1,
    fontSize: 15,
    color: theme.text.primary,
    padding: 0,
  },
  categoryChips: {
    paddingHorizontal: 16,
    paddingBottom: 8,
    gap: 8,
    flexDirection: 'row',
  },
  chip: {
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 20,
    backgroundColor: theme.bg.primary,
    borderWidth: 1,
    borderColor: theme.border.light,
  },
  chipActive: {
    backgroundColor: theme.brand,
    borderColor: theme.brand,
  },
  chipText: {
    fontSize: 13,
    fontWeight: '600',
    color: theme.text.secondary,
  },
  chipTextActive: {
    color: theme.text.onBrand,
  },
  gridContent: {
    padding: 16,
    paddingBottom: CART_SHEET_HEIGHT + 16,
    gap: 12,
  },
  gridRow: {
    gap: 12,
  },
  gridItem: {
    flex: 1,
  },
  emptyState: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 32,
    gap: 8,
  },
  emptyTitle: {
    fontSize: 18,
    fontWeight: '700',
    color: theme.text.primary,
  },
  emptyDesc: {
    fontSize: 14,
    color: theme.text.muted,
    textAlign: 'center',
  },
  // Skeleton de loading
  skeletonGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    padding: 16,
    gap: 12,
  },
  skeletonCard: {
    width: '47%',
    height: 120,
    borderRadius: 12,
    backgroundColor: palette.neutral[200],
  },
  errorText: {
    fontSize: 15,
    color: semantic.error.DEFAULT,
    textAlign: 'center',
    fontWeight: '500',
  },
  retryBtn: {
    paddingHorizontal: 24,
    paddingVertical: 12,
    backgroundColor: theme.brand,
    borderRadius: 10,
  },
  retryBtnText: {
    fontSize: 15,
    fontWeight: '700',
    color: theme.text.onBrand,
  },
});
