// Helpers de horario de funcionamento (issue #6). Formato de cada dia: "HH:mm-HH:mm"
// ou null = fechado. Espelha computeOpenNow do PublicMenuController (America/Sao_Paulo),
// so que client-side, para o chip "Agora: Aberto" dar feedback antes de salvar.

export const WEEKDAY_KEYS = [
  "openingHoursMonday",
  "openingHoursTuesday",
  "openingHoursWednesday",
  "openingHoursThursday",
  "openingHoursFriday",
  "openingHoursSaturday",
  "openingHoursSunday",
] as const;

export type WeekdayKey = (typeof WEEKDAY_KEYS)[number];

export const WEEKDAY_LABELS: Record<WeekdayKey, string> = {
  openingHoursMonday: "Segunda",
  openingHoursTuesday: "Terca",
  openingHoursWednesday: "Quarta",
  openingHoursThursday: "Quinta",
  openingHoursFriday: "Sexta",
  openingHoursSaturday: "Sabado",
  openingHoursSunday: "Domingo",
};

/** JS getDay(): 0=domingo..6=sabado. Mapeia para o indice do nosso array (0=segunda). */
function jsDayToIndex(jsDay: number): number {
  return (jsDay + 6) % 7;
}

/** Converte "HH:mm" em minutos desde 00:00. Retorna null se invalido. */
export function parseHm(hm: string): number | null {
  const m = /^(\d{2}):(\d{2})$/.exec(hm);
  if (!m) return null;
  const h = Number(m[1]);
  const min = Number(m[2]);
  if (h > 23 || min > 59) return null;
  return h * 60 + min;
}

/** Divide "HH:mm-HH:mm" em [abre, fecha] ("HH:mm"). null se vazio/invalido. */
export function splitRange(range: string | null | undefined): [string, string] | null {
  if (!range) return null;
  const parts = range.split("-");
  if (parts.length !== 2) return null;
  if (parseHm(parts[0]) === null || parseHm(parts[1]) === null) return null;
  return [parts[0], parts[1]];
}

/** true quando fecha < abre => o expediente vira a madrugada (ex.: "18:00-02:00"). */
export function crossesMidnight(open: string, close: string): boolean {
  const o = parseHm(open);
  const c = parseHm(close);
  if (o === null || c === null) return false;
  return c < o;
}

export interface OpenStatus {
  open: boolean;
  /** "HH:mm" de fechamento quando aberto agora. */
  closesAt?: string;
}

/**
 * Calcula aberto/fechado AGORA a partir dos 7 campos, considerando virada de meia-noite.
 * Retorna null quando o dia atual nao tem horario (indeterminado). now injetavel p/ teste.
 */
export function computeOpenNow(
  hours: Partial<Record<WeekdayKey, string | null>>,
  now: Date = new Date(),
): OpenStatus | null {
  const idxToday = jsDayToIndex(now.getDay());
  const nowMin = now.getHours() * 60 + now.getMinutes();

  // 1) Expediente que comecou ONTEM e virou a madrugada (fecha <= agora de hoje).
  const idxYesterday = (idxToday + 6) % 7;
  const yRange = splitRange(hours[WEEKDAY_KEYS[idxYesterday]]);
  if (yRange) {
    const [yOpen, yClose] = yRange;
    if (crossesMidnight(yOpen, yClose)) {
      const c = parseHm(yClose)!;
      if (nowMin < c) return { open: true, closesAt: yClose };
    }
  }

  // 2) Expediente de hoje.
  const tRange = splitRange(hours[WEEKDAY_KEYS[idxToday]]);
  if (!tRange) return { open: false };
  const [tOpen, tClose] = tRange;
  const o = parseHm(tOpen)!;
  const c = parseHm(tClose)!;
  if (crossesMidnight(tOpen, tClose)) {
    // abre hoje e fecha amanha => aberto de o..24h
    if (nowMin >= o) return { open: true, closesAt: tClose };
  } else {
    if (nowMin >= o && nowMin < c) return { open: true, closesAt: tClose };
  }
  return { open: false };
}
