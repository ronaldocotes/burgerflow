import { useCallback, useState } from 'react';
import { CartLine, OrderItemInput } from '@/types/cart';
import { uuid } from '@/utils/money';
import { Product } from '@/types/menu';

// Detecta se uma linha e "simples" (sem variacoes): permite fundir qtd ao adicionar
// o mesmo produto novamente.
export function isSimpleLine(item: OrderItemInput): boolean {
  return (
    !item.sizeId &&
    !item.flavor1Id &&
    !item.flavor2Id &&
    !item.crustType &&
    !item.doughType &&
    (item.optionIds?.length ?? 0) === 0
  );
}

export function buildSimpleLine(product: Product): CartLine {
  return {
    lineId: uuid(),
    productId: product.id,
    productName: product.name,
    quantity: 1,
    item: { productId: product.id, quantity: 1 },
    label: '',
  };
}

export function useCart() {
  const [lines, setLines] = useState<CartLine[]>([]);

  // Adiciona uma linha. Se for simples e ja existe linha simples do mesmo produto,
  // incrementa a quantidade em vez de criar duplicata.
  const addLine = useCallback((line: CartLine) => {
    setLines((prev) => {
      if (isSimpleLine(line.item)) {
        const idx = prev.findIndex(
          (l) => l.productId === line.productId && isSimpleLine(l.item),
        );
        if (idx >= 0) {
          const next = [...prev];
          next[idx] = { ...next[idx], quantity: next[idx].quantity + 1 };
          return next;
        }
      }
      return [...prev, line];
    });
  }, []);

  // Atualiza quantidade; qty <= 0 remove a linha.
  const updateQty = useCallback((lineId: string, qty: number) => {
    setLines((prev) =>
      qty <= 0
        ? prev.filter((l) => l.lineId !== lineId)
        : prev.map((l) => (l.lineId === lineId ? { ...l, quantity: qty } : l)),
    );
  }, []);

  const removeLine = useCallback((lineId: string) => {
    setLines((prev) => prev.filter((l) => l.lineId !== lineId));
  }, []);

  const clearCart = useCallback(() => {
    setLines([]);
  }, []);

  return { lines, addLine, updateQty, removeLine, clearCart };
}
