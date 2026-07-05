// Fonte unica de cor. Espelha frontend/tailwind.config.ts. Zero hex inline nos componentes.
export const palette = {
  primary: {
    50: '#ecfdf5', 100: '#d1fae5', 200: '#a7f3d0', 300: '#6ee7b7', 400: '#34d399',
    500: '#10b981', 600: '#059669', 700: '#047857', 800: '#065f46', 900: '#064e3b', 950: '#022c22',
  },
  neutral: {
    50: '#f8fafc', 100: '#f1f5f9', 200: '#e2e8f0', 300: '#cbd5e1', 400: '#94a3b8',
    500: '#64748b', 600: '#475569', 700: '#334155', 800: '#1e293b', 900: '#0f172a',
  },
} as const;

export const kds = {
  pending: '#fbbf24', preparing: '#3b82f6', ready: '#10b981',
  delivered: '#065f46', cancelled: '#ef4444',
} as const;

export const tableStatus = {
  free: '#9ca3af', open: '#10b981', billing: '#f59e0b',
} as const;

export const semantic = {
  success: { DEFAULT: '#10b981', light: '#d1fae5', dark: '#065f46' },
  warning: { DEFAULT: '#f59e0b', light: '#fef3c7', dark: '#92400e' },
  error: { DEFAULT: '#ef4444', light: '#fee2e2', dark: '#991b1b' },
} as const;

export const theme = {
  bg: { primary: '#ffffff', secondary: '#f8fafc', tertiary: '#f1f5f9' },
  text: { primary: '#0f172a', secondary: '#475569', muted: '#94a3b8', onBrand: '#ffffff' },
  border: { light: '#e2e8f0', medium: '#cbd5e1' },
  brand: '#047857',
} as const;

/**
 * Fase 6.2 — status de entrega (app do motoboy). Status NUNCA e comunicado so por
 * cor (sempre icone + texto); estes tokens dao o reforco visual.
 */
export const delivery = {
  pending: '#64748b',        // aguardando despacho
  offered: '#b45309',        // oferta no ar (amber-700: contraste AA sobre claro)
  accepted: '#1e40af',       // aceita / indo ao restaurante
  arrivedAtStore: '#1e40af', // no restaurante
  pickedUp: '#0369a1',       // pedido coletado
  outForDelivery: '#047857', // entregando
  arrivedAtCustomer: '#047857',
  delivered: '#065f46',
  failed: '#991b1b',
  online: '#047857',
  offline: '#475569',
} as const;
