// Regra de atraso/aging do KDS — extraída de app/kds/page.tsx (refactor
// behavior-preserving) para ser reutilizada pelo dashboard (Fase 3, faixa
// "Operação agora") sem duplicar a regra de negócio em dois lugares.
//
// Aging: verde → âmbar → vermelho baseado em estimatedPrepTimeMinutes.
// Padrão kohli.design: usado como barra colorida no topo do card do KDS
// (mais legível em cozinha com luz forte) e como badge de atraso no dashboard.

import { KdsOrder } from "@/types/kds";

export const DEFAULT_PREP_TIME_MINUTES = 15;

export type AgingTone = "good" | "warn" | "bad";

type AgingInput = Pick<KdsOrder, "createdAt" | "estimatedPrepTimeMinutes">;

/** Minutos decorridos desde a criação do pedido, no instante `now` (epoch ms). */
export function elapsedMinutes(order: Pick<KdsOrder, "createdAt">, now: number): number {
  return (now - new Date(order.createdAt).getTime()) / 60_000;
}

/** Tom de aging do pedido: verde (dentro do prazo) → âmbar (perto do limite) → vermelho (atrasado). */
export function agingTone(order: AgingInput, now: number): AgingTone {
  const elapsed = elapsedMinutes(order, now);
  const limit = order.estimatedPrepTimeMinutes ?? DEFAULT_PREP_TIME_MINUTES;
  if (elapsed >= limit) return "bad";
  if (elapsed >= limit * 0.75) return "warn";
  return "good";
}

const TONE_BG_CLASS: Record<AgingTone, string> = {
  good: "bg-success",
  warn: "bg-warning",
  bad: "bg-error",
};

/** Classe de fundo da barra de aging no topo do card do KDS. */
export function agingBarClass(order: AgingInput, now: number): string {
  return TONE_BG_CLASS[agingTone(order, now)];
}

/**
 * Pedido atrasado: tempo decorrido >= tempo estimado de preparo (ou o default
 * de 15min quando o produto/pedido não tem estimativa). Mesmo critério usado
 * para a barra vermelha do KDS — aqui exposto como boolean para o dashboard.
 */
export function isLate(order: AgingInput, now: number): boolean {
  return agingTone(order, now) === "bad";
}

/** Rótulo mm:ss do tempo decorrido — usado no cartão do KDS. */
export function elapsedLabel(order: Pick<KdsOrder, "createdAt">, now: number): string {
  const secs = Math.floor((now - new Date(order.createdAt).getTime()) / 1000);
  const m = Math.floor(secs / 60);
  const s = secs % 60;
  return `${m}:${s.toString().padStart(2, "0")}`;
}
