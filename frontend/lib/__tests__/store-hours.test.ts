import {
  splitRange,
  crossesMidnight,
  computeOpenNow,
  WEEKDAY_KEYS,
} from "@/lib/store-hours";

describe("splitRange", () => {
  it("divide um range valido", () => {
    expect(splitRange("18:00-23:30")).toEqual(["18:00", "23:30"]);
  });
  it("retorna null para vazio/invalido", () => {
    expect(splitRange(null)).toBeNull();
    expect(splitRange("")).toBeNull();
    expect(splitRange("25:00-10:00")).toBeNull();
    expect(splitRange("18:00")).toBeNull();
  });
});

describe("crossesMidnight", () => {
  it("detecta virada da madrugada (fecha < abre)", () => {
    expect(crossesMidnight("18:00", "02:00")).toBe(true);
    expect(crossesMidnight("18:00", "23:30")).toBe(false);
  });
});

describe("computeOpenNow", () => {
  function hoursWith(overrides: Partial<Record<string, string | null>>) {
    const base: Record<string, string | null> = {};
    WEEKDAY_KEYS.forEach((k) => (base[k] = null));
    return { ...base, ...overrides };
  }

  it("aberto dentro do expediente do dia", () => {
    // 2026-07-06 e uma segunda-feira
    const monday2000 = new Date(2026, 6, 6, 20, 0);
    const status = computeOpenNow(hoursWith({ openingHoursMonday: "18:00-23:30" }), monday2000);
    expect(status).toEqual({ open: true, closesAt: "23:30" });
  });

  it("fechado fora do expediente", () => {
    const monday2359 = new Date(2026, 6, 6, 23, 59);
    const status = computeOpenNow(hoursWith({ openingHoursMonday: "18:00-23:30" }), monday2359);
    expect(status).toEqual({ open: false });
  });

  it("aberto na madrugada por causa do expediente do dia anterior", () => {
    // 2026-07-04 e um sabado; sexta (03/07) vira a madrugada ate 02:00
    const saturday0100 = new Date(2026, 6, 4, 1, 0);
    const status = computeOpenNow(hoursWith({ openingHoursFriday: "18:00-02:00" }), saturday0100);
    expect(status).toEqual({ open: true, closesAt: "02:00" });
  });

  it("dia sem horario => fechado", () => {
    const monday2000 = new Date(2026, 6, 6, 20, 0);
    expect(computeOpenNow(hoursWith({}), monday2000)).toEqual({ open: false });
  });
});
