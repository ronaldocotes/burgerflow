// Espelho client-side do backend util/ColorContrast.kt (issue #12). Portado fiel
// para calcular o contraste WCAG ao vivo enquanto o dono edita a cor, sem precisar
// salvar — o backend só calcula themeContrast da cor JÁ salva (GET /config), não há
// endpoint de "simular". Ao carregar e após salvar, reconciliar com themeContrast do
// servidor (fonte canônica). Mantém as mesmas fórmulas para não divergir do backend.

export const WHITE = "#FFFFFF";
export const BLACK = "#000000";

const HEX = /^#([0-9a-fA-F]{6})$/;

/** true se a string é um hex "#RRGGBB" válido (mesma regex do backend). */
export function isValidHex(hex: string): boolean {
  return HEX.test(hex.trim());
}

/** Normaliza para "#RRGGBB" maiúsculo. Lança se inválido. */
export function normalizeHex(hex: string): string {
  const t = hex.trim();
  if (!isValidHex(t)) throw new Error("cor deve ser um hex no formato #RRGGBB");
  return t.toUpperCase();
}

function channel(value: number): number {
  const c = value / 255;
  return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
}

/** Luminância relativa (0..1) conforme WCAG. */
export function relativeLuminance(hex: string): number {
  const h = normalizeHex(hex).slice(1);
  const r = channel(parseInt(h.slice(0, 2), 16));
  const g = channel(parseInt(h.slice(2, 4), 16));
  const b = channel(parseInt(h.slice(4, 6), 16));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** Razão de contraste entre duas cores (>= 1.0), simétrica. */
export function contrastRatio(a: string, b: string): number {
  const la = relativeLuminance(a);
  const lb = relativeLuminance(b);
  const lighter = Math.max(la, lb);
  const darker = Math.min(la, lb);
  return (lighter + 0.05) / (darker + 0.05);
}

function round2(v: number): number {
  return Math.round(v * 100) / 100;
}

export interface ContrastInfo {
  primaryColor: string;
  ratioOnWhite: number;
  ratioOnBlack: number;
  recommendedTextColor: string;
  meetsAA: boolean;
}

/**
 * null quando o hex é inválido/vazio (mesma semântica de ThemeContrastInfo.of).
 * Como Cw * Cb = 21 para qualquer cor válida, max(Cw, Cb) >= 4.58 > 4.5 SEMPRE —
 * meetsAA do par recomendado é sempre true (decisão da issue #12: informar, não
 * bloquear). O dado útil é recommendedTextColor + as duas razões.
 */
export function computeContrast(primaryColor: string | null | undefined): ContrastInfo | null {
  if (!primaryColor || !isValidHex(primaryColor)) return null;
  const color = normalizeHex(primaryColor);
  const onWhite = contrastRatio(color, WHITE);
  const onBlack = contrastRatio(color, BLACK);
  const recommended = onWhite >= onBlack ? WHITE : BLACK;
  return {
    primaryColor: color,
    ratioOnWhite: round2(onWhite),
    ratioOnBlack: round2(onBlack),
    recommendedTextColor: recommended,
    meetsAA: Math.max(onWhite, onBlack) >= 4.5,
  };
}

/** Cor de marca padrão do MenuFlow (verde), usada quando o tenant não configurou cor. */
export const DEFAULT_PRIMARY = "#047857";

/**
 * Presets curados para food service — todos com ratioOnWhite >= 4.5 (validados em
 * lib/__tests__/contrast.test.ts). Preset é atalho, nunca limite: o picker e o campo
 * hex continuam livres. aria-label usa o nome.
 */
export const THEME_PRESETS: { name: string; hex: string }[] = [
  { name: "Verde", hex: "#047857" },
  { name: "Vermelho tomate", hex: "#B91C1C" },
  { name: "Laranja", hex: "#C2410C" },
  { name: "Azul", hex: "#1D4ED8" },
  { name: "Roxo", hex: "#6D28D9" },
  { name: "Rosa", hex: "#BE185D" },
  { name: "Marrom café", hex: "#78350F" },
  { name: "Grafite", hex: "#1F2937" },
];
