import React, {
  forwardRef,
  useCallback,
  useImperativeHandle,
  useRef,
  useState,
} from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import {
  BottomSheetModal,
  BottomSheetScrollView,
} from '@gorhom/bottom-sheet';
import { palette, semantic, theme } from '@/theme/colors';
import { formatBRL, uuid } from '@/utils/money';
import type { CartLine, OrderItemInput } from '@/types/cart';
import type {
  CRUST_LABELS,
  DOUGH_TYPES,
  Product,
  ProductCrustPrice,
  ProductFlavor,
  ProductOptionGroup,
  ProductSize,
  ProductVariations,
} from '@/types/menu';
import { renderBackdrop } from './sheetUtils';

// Constantes de labels (importadas do tipos mas declaradas localmente para evitar
// importacao circular; espelham CRUST_LABELS e DOUGH_TYPES do frontend).
const CRUST_LABELS_LOCAL: Record<string, string> = {
  TRADICIONAL: 'Tradicional',
  CATUPIRY: 'Catupiry',
  CHEDDAR: 'Cheddar',
  CHOCOLATE: 'Chocolate',
  CREAM_CHEESE: 'Cream cheese',
  SEM_BORDA: 'Sem borda',
};

const DOUGH_TYPES_LOCAL = [
  { value: 'FINA', label: 'Fina' },
  { value: 'GROSSA', label: 'Grossa' },
  { value: 'INTEGRAL', label: 'Integral' },
  { value: 'AMERICANA', label: 'Americana' },
  { value: 'NAPOLITANA', label: 'Napolitana' },
];

export interface CustomizeSheetRef {
  open: (product: Product, variations: ProductVariations) => void;
}

interface CustomizeSheetProps {
  onAdd: (line: CartLine) => void;
}

// Bottom sheet modal de personalizacao: pizza (tamanho+sabor+borda+massa) e
// complementos (grupos com min/max). Abre via ref.open(product, variations).
// Persona: Ana/Atendente - rapida selecao; bloqueio ao max de complementos.
export const CustomizeSheet = forwardRef<CustomizeSheetRef, CustomizeSheetProps>(
  ({ onAdd }, ref) => {
    const sheetRef = useRef<BottomSheetModal>(null);
    const [product, setProduct] = useState<Product | null>(null);
    const [variations, setVariations] = useState<ProductVariations | null>(null);

    // Estado de selecao
    const [sizeId, setSizeId] = useState<string | null>(null);
    const [flavor1Id, setFlavor1Id] = useState<string | null>(null);
    const [flavor2Id, setFlavor2Id] = useState<string | null>(null);
    const [crustType, setCrustType] = useState<string | null>(null);
    const [doughType, setDoughType] = useState<string | null>(null);
    const [selected, setSelected] = useState<Record<string, string[]>>({});
    const [quantity, setQuantity] = useState(1);
    const [notes, setNotes] = useState('');

    const resetState = useCallback(() => {
      setSizeId(null);
      setFlavor1Id(null);
      setFlavor2Id(null);
      setCrustType(null);
      setDoughType(null);
      setSelected({});
      setQuantity(1);
      setNotes('');
    }, []);

    useImperativeHandle(ref, () => ({
      open(p, v) {
        resetState();
        setProduct(p);
        setVariations(v);
        sheetRef.current?.present();
      },
    }));

    const handleDismiss = useCallback(() => {
      setProduct(null);
      setVariations(null);
      resetState();
    }, [resetState]);

    function toggleOption(group: ProductOptionGroup, optionId: string) {
      setSelected((prev) => {
        const current = prev[group.id] ?? [];
        const has = current.includes(optionId);
        let next: string[];
        if (has) {
          next = current.filter((id) => id !== optionId);
        } else if (group.maxSelect === 1) {
          next = [optionId];
        } else if (current.length >= group.maxSelect) {
          return prev;
        } else {
          next = [...current, optionId];
        }
        return { ...prev, [group.id]: next };
      });
    }

    // Validacao espelha o servidor (fonte da verdade).
    const errors: string[] = [];
    const groups = variations?.groups ?? [];
    const sizes = variations?.sizes ?? [];
    const flavors = variations?.flavors ?? [];
    const crusts = variations?.crusts ?? [];
    const isPizza = sizes.length > 0 || flavors.length > 0 || crusts.length > 0;

    if (sizes.length > 0 && !sizeId) errors.push('Escolha o tamanho.');
    if (flavors.length > 0 && !flavor1Id) errors.push('Escolha o sabor.');
    if (flavor2Id && flavor2Id === flavor1Id)
      errors.push('O 2o sabor deve ser diferente do 1o.');
    for (const g of groups) {
      const count = (selected[g.id] ?? []).length;
      const min = g.required ? Math.max(1, g.minSelect) : g.minSelect;
      if (count < min) {
        errors.push(
          `"${g.name}": escolha ${min === g.maxSelect ? min : `pelo menos ${min}`}.`,
        );
      }
    }
    const valid = errors.length === 0;

    function buildLine(): CartLine {
      if (!product) throw new Error('product is null');
      const optionIds = groups.flatMap((g) => selected[g.id] ?? []);
      const item: OrderItemInput = {
        productId: product.id,
        quantity,
        sizeId: sizeId ?? undefined,
        flavor1Id: flavor1Id ?? undefined,
        flavor2Id: flavor2Id ?? undefined,
        crustType: crustType ?? undefined,
        doughType: doughType ?? undefined,
        optionIds: optionIds.length > 0 ? optionIds : undefined,
        notes: notes.trim() || undefined,
      };
      const parts: string[] = [];
      const size = sizes.find((s) => s.id === sizeId);
      if (size) parts.push(size.name);
      const f1 = flavors.find((f) => f.id === flavor1Id);
      const f2 = flavors.find((f) => f.id === flavor2Id);
      if (f1) parts.push(f2 ? `${f1.name} / ${f2.name}` : f1.name);
      if (crustType) parts.push(`Borda ${CRUST_LABELS_LOCAL[crustType] ?? crustType}`);
      if (doughType) {
        const dt = DOUGH_TYPES_LOCAL.find((d) => d.value === doughType);
        parts.push(`Massa ${dt?.label ?? doughType}`);
      }
      for (const g of groups) {
        const names = (selected[g.id] ?? [])
          .map((id) => g.options.find((o) => o.id === id)?.name)
          .filter(Boolean) as string[];
        parts.push(...names);
      }
      if (notes.trim()) parts.push(`Obs: ${notes.trim()}`);
      return {
        lineId: uuid(),
        productId: product.id,
        productName: product.name,
        quantity,
        item,
        label: parts.join(' - '),
      };
    }

    function handleAdd() {
      if (!valid || !product) return;
      const line = buildLine();
      onAdd(line);
      sheetRef.current?.dismiss();
    }

    if (!product || !variations) {
      return (
        <BottomSheetModal
          ref={sheetRef}
          snapPoints={['75%', '95%']}
          backdropComponent={renderBackdrop}
          onDismiss={handleDismiss}
          enableDynamicSizing={false}
        >
          <></>
        </BottomSheetModal>
      );
    }

    return (
      <BottomSheetModal
        ref={sheetRef}
        snapPoints={['75%', '95%']}
        backdropComponent={renderBackdrop}
        onDismiss={handleDismiss}
        enableDynamicSizing={false}
      >
        {/* Header */}
        <View style={styles.header}>
          <Text style={styles.headerTitle}>{product.name}</Text>
          <Pressable
            onPress={() => sheetRef.current?.dismiss()}
            hitSlop={8}
            accessibilityLabel="Fechar"
          >
            <Text style={styles.closeBtn}>x</Text>
          </Pressable>
        </View>

        <BottomSheetScrollView
          contentContainerStyle={styles.scrollContent}
          keyboardShouldPersistTaps="handled"
        >
          {/* Tamanho */}
          {sizes.length > 0 && (
            <Section title="Tamanho" required>
              <View style={styles.choiceGrid}>
                {sizes.map((s) => (
                  <ChoiceButton
                    key={s.id}
                    selected={sizeId === s.id}
                    onPress={() => setSizeId(s.id)}
                    label={s.name}
                    priceCents={s.promoPriceCents ?? s.priceCents}
                  />
                ))}
              </View>
            </Section>
          )}

          {/* Sabores */}
          {flavors.length > 0 && (
            <>
              <Section title="Sabor" required>
                <View style={styles.choiceGrid}>
                  {flavors.map((f) => (
                    <ChoiceButton
                      key={f.id}
                      selected={flavor1Id === f.id}
                      onPress={() => setFlavor1Id(f.id)}
                      label={f.name}
                    />
                  ))}
                </View>
              </Section>
              <Section title="2o sabor (meia a meia)">
                <View style={styles.choiceGrid}>
                  <ChoiceButton
                    selected={flavor2Id === null}
                    onPress={() => setFlavor2Id(null)}
                    label="Apenas 1 sabor"
                  />
                  {flavors.map((f) => (
                    <ChoiceButton
                      key={f.id}
                      selected={flavor2Id === f.id}
                      onPress={() => setFlavor2Id(f.id)}
                      label={f.name}
                    />
                  ))}
                </View>
              </Section>
            </>
          )}

          {/* Borda */}
          {crusts.length > 0 && (
            <Section title="Borda">
              <View style={styles.choiceGrid}>
                <ChoiceButton
                  selected={crustType === null}
                  onPress={() => setCrustType(null)}
                  label="Sem borda"
                />
                {crusts.map((c) => (
                  <ChoiceButton
                    key={c.id}
                    selected={crustType === c.crustType}
                    onPress={() => setCrustType(c.crustType)}
                    label={CRUST_LABELS_LOCAL[c.crustType] ?? c.crustType}
                    priceCents={c.priceCents}
                  />
                ))}
              </View>
            </Section>
          )}

          {/* Massa */}
          {isPizza && (
            <Section title="Massa">
              <View style={styles.choiceGrid}>
                <ChoiceButton
                  selected={doughType === null}
                  onPress={() => setDoughType(null)}
                  label="Padrao"
                />
                {DOUGH_TYPES_LOCAL.map((d) => (
                  <ChoiceButton
                    key={d.value}
                    selected={doughType === d.value}
                    onPress={() => setDoughType(d.value)}
                    label={d.label}
                  />
                ))}
              </View>
            </Section>
          )}

          {/* Complementos */}
          {groups.map((g) => {
            const count = (selected[g.id] ?? []).length;
            const atMax = count >= g.maxSelect;
            return (
              <Section
                key={g.id}
                title={g.name}
                required={g.required}
                hint={
                  g.maxSelect > 1
                    ? `${count}/${g.maxSelect}${atMax ? ' - max' : g.minSelect > 0 ? ` (min. ${g.minSelect})` : ''}`
                    : undefined
                }
              >
                {g.options
                  .filter((o) => o.active)
                  .map((o) => {
                    const checked = (selected[g.id] ?? []).includes(o.id);
                    const disabled = !checked && atMax && g.maxSelect > 1;
                    return (
                      <Pressable
                        key={o.id}
                        style={[
                          styles.optionRow,
                          checked && styles.optionRowChecked,
                          disabled && styles.optionRowDisabled,
                        ]}
                        onPress={() => !disabled && toggleOption(g, o.id)}
                        disabled={disabled}
                        accessibilityRole="checkbox"
                        accessibilityState={{ checked, disabled }}
                      >
                        <View
                          style={[
                            styles.checkbox,
                            checked && styles.checkboxChecked,
                          ]}
                        >
                          {checked && <Text style={styles.checkboxMark}>v</Text>}
                        </View>
                        <Text
                          style={[
                            styles.optionName,
                            disabled && styles.optionNameDisabled,
                          ]}
                        >
                          {o.name}
                        </Text>
                        {o.priceCents > 0 && (
                          <Text style={styles.optionPrice}>
                            +{formatBRL(o.priceCents)}
                          </Text>
                        )}
                      </Pressable>
                    );
                  })}
              </Section>
            );
          })}

          {/* Observacoes */}
          <Section title="Observacao (opcional)">
            <TextInput
              style={styles.notesInput}
              value={notes}
              onChangeText={setNotes}
              placeholder="Ex.: sem cebola, bem passado..."
              placeholderTextColor={theme.text.muted}
              maxLength={200}
              multiline
            />
          </Section>
        </BottomSheetScrollView>

        {/* Footer */}
        <View style={styles.footer}>
          {errors.length > 0 && (
            <Text style={styles.errorText} accessibilityRole="alert">
              {errors[0]}
            </Text>
          )}
          <View style={styles.footerRow}>
            <View style={styles.qtyControl}>
              <Pressable
                style={styles.qtyBtn}
                onPress={() => setQuantity((q) => Math.max(1, q - 1))}
                accessibilityLabel="Diminuir quantidade"
              >
                <Text style={styles.qtyBtnText}>-</Text>
              </Pressable>
              <Text style={styles.qtyValue}>{quantity}</Text>
              <Pressable
                style={styles.qtyBtn}
                onPress={() => setQuantity((q) => Math.min(99, q + 1))}
                accessibilityLabel="Aumentar quantidade"
              >
                <Text style={styles.qtyBtnText}>+</Text>
              </Pressable>
            </View>
            <Pressable
              style={[styles.addBtn, !valid && styles.addBtnDisabled]}
              onPress={handleAdd}
              disabled={!valid}
              accessibilityRole="button"
            >
              <Text style={styles.addBtnText}>
                Adicionar{quantity > 1 ? ` (${quantity})` : ''}
              </Text>
            </Pressable>
          </View>
        </View>
      </BottomSheetModal>
    );
  },
);

CustomizeSheet.displayName = 'CustomizeSheet';

// --- Sub-componentes locais ---

function Section({
  title,
  required,
  hint,
  children,
}: {
  title: string;
  required?: boolean;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <View style={styles.section}>
      <View style={styles.sectionHeader}>
        <Text style={styles.sectionTitle}>
          {title}
          {required ? <Text style={styles.required}> *</Text> : null}
        </Text>
        {hint ? <Text style={styles.sectionHint}>{hint}</Text> : null}
      </View>
      {children}
    </View>
  );
}

function ChoiceButton({
  selected,
  onPress,
  label,
  priceCents,
}: {
  selected: boolean;
  onPress: () => void;
  label: string;
  priceCents?: number;
}) {
  return (
    <Pressable
      style={[styles.choiceBtn, selected && styles.choiceBtnSelected]}
      onPress={onPress}
      accessibilityRole="radio"
      accessibilityState={{ selected }}
    >
      <Text
        style={[styles.choiceBtnText, selected && styles.choiceBtnTextSelected]}
      >
        {label}
      </Text>
      {priceCents != null && priceCents > 0 && (
        <Text
          style={[
            styles.choiceBtnPrice,
            selected && styles.choiceBtnPriceSelected,
          ]}
        >
          {formatBRL(priceCents)}
        </Text>
      )}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 20,
    paddingVertical: 16,
    borderBottomWidth: 1,
    borderBottomColor: theme.border.light,
  },
  headerTitle: {
    fontSize: 18,
    fontWeight: '700',
    color: theme.text.primary,
    flex: 1,
  },
  closeBtn: {
    fontSize: 20,
    color: theme.text.muted,
    paddingHorizontal: 8,
    paddingVertical: 4,
    fontWeight: '600',
  },
  scrollContent: {
    padding: 20,
    paddingBottom: 8,
    gap: 20,
  },
  section: {
    gap: 10,
  },
  sectionHeader: {
    flexDirection: 'row',
    alignItems: 'baseline',
    justifyContent: 'space-between',
  },
  sectionTitle: {
    fontSize: 15,
    fontWeight: '700',
    color: theme.text.primary,
  },
  required: {
    color: semantic.error.DEFAULT,
  },
  sectionHint: {
    fontSize: 12,
    color: theme.text.muted,
  },
  choiceGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  choiceBtn: {
    paddingHorizontal: 14,
    paddingVertical: 10,
    borderRadius: 10,
    borderWidth: 1.5,
    borderColor: theme.border.medium,
    backgroundColor: theme.bg.secondary,
    minWidth: '45%',
  },
  choiceBtnSelected: {
    backgroundColor: theme.brand,
    borderColor: theme.brand,
  },
  choiceBtnText: {
    fontSize: 14,
    fontWeight: '600',
    color: theme.text.primary,
  },
  choiceBtnTextSelected: {
    color: theme.text.onBrand,
  },
  choiceBtnPrice: {
    fontSize: 12,
    color: theme.text.muted,
    marginTop: 2,
  },
  choiceBtnPriceSelected: {
    color: theme.text.onBrand,
  },
  optionRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 10,
    paddingHorizontal: 12,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: theme.border.light,
    gap: 10,
  },
  optionRowChecked: {
    borderColor: palette.primary[500],
    backgroundColor: palette.primary[50],
  },
  optionRowDisabled: {
    opacity: 0.4,
  },
  checkbox: {
    width: 22,
    height: 22,
    borderRadius: 6,
    borderWidth: 1.5,
    borderColor: theme.border.medium,
    alignItems: 'center',
    justifyContent: 'center',
  },
  checkboxChecked: {
    backgroundColor: theme.brand,
    borderColor: theme.brand,
  },
  checkboxMark: {
    fontSize: 13,
    fontWeight: '700',
    color: theme.text.onBrand,
    lineHeight: 16,
  },
  optionName: {
    flex: 1,
    fontSize: 14,
    color: theme.text.primary,
    fontWeight: '500',
  },
  optionNameDisabled: {
    color: theme.text.muted,
  },
  optionPrice: {
    fontSize: 13,
    color: theme.text.secondary,
  },
  notesInput: {
    borderWidth: 1,
    borderColor: theme.border.medium,
    borderRadius: 10,
    padding: 12,
    fontSize: 14,
    color: theme.text.primary,
    backgroundColor: theme.bg.secondary,
    minHeight: 60,
    textAlignVertical: 'top',
  },
  footer: {
    padding: 20,
    borderTopWidth: 1,
    borderTopColor: theme.border.light,
    gap: 10,
  },
  errorText: {
    fontSize: 13,
    color: semantic.error.DEFAULT,
    textAlign: 'center',
  },
  footerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
  },
  qtyControl: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  qtyBtn: {
    width: 44,
    height: 44,
    borderRadius: 10,
    backgroundColor: theme.bg.tertiary,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: theme.border.light,
  },
  qtyBtnText: {
    fontSize: 20,
    fontWeight: '600',
    color: theme.text.primary,
    lineHeight: 22,
  },
  qtyValue: {
    width: 32,
    textAlign: 'center',
    fontSize: 16,
    fontWeight: '700',
    color: theme.text.primary,
    fontVariant: ['tabular-nums'],
  },
  addBtn: {
    flex: 1,
    minHeight: 52,
    backgroundColor: theme.brand,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  addBtnDisabled: {
    opacity: 0.4,
  },
  addBtnText: {
    fontSize: 16,
    fontWeight: '700',
    color: theme.text.onBrand,
  },
});
