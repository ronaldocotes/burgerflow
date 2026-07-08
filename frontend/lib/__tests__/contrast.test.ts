import {
  computeContrast,
  contrastRatio,
  isValidHex,
  THEME_PRESETS,
  WHITE,
} from "@/lib/contrast";

describe("contrast (espelho do ColorContrast.kt)", () => {
  it("valida o formato #RRGGBB", () => {
    expect(isValidHex("#047857")).toBe(true);
    expect(isValidHex("#B91C1C")).toBe(true);
    expect(isValidHex("047857")).toBe(false);
    expect(isValidHex("#fff")).toBe(false);
    expect(isValidHex("#GGGGGG")).toBe(false);
  });

  it("todos os 8 presets curados tem contraste >= 4.5:1 sobre branco", () => {
    for (const p of THEME_PRESETS) {
      expect(contrastRatio(p.hex, WHITE)).toBeGreaterThanOrEqual(4.5);
    }
  });

  it("computeContrast retorna null para hex invalido/vazio", () => {
    expect(computeContrast("")).toBeNull();
    expect(computeContrast("xyz")).toBeNull();
    expect(computeContrast(null)).toBeNull();
    expect(computeContrast(undefined)).toBeNull();
  });

  it("meetsAA e sempre true e recomenda a cor de texto legivel", () => {
    const dark = computeContrast("#1F2937")!;
    expect(dark.meetsAA).toBe(true);
    expect(dark.recommendedTextColor).toBe("#FFFFFF");

    const light = computeContrast("#FFEB3B")!;
    expect(light.meetsAA).toBe(true); // algebra do WCAG: sempre passa em preto OU branco
    expect(light.recommendedTextColor).toBe("#000000");
    // cor clara dispara o aviso de "botoes somem no fundo branco" (< 3:1)
    expect(light.ratioOnWhite).toBeLessThan(3);
  });

  it("Cw * Cb ~= 21 (invariante do WCAG) para qualquer cor", () => {
    const c = computeContrast("#6D28D9")!;
    expect(c.ratioOnWhite * c.ratioOnBlack).toBeCloseTo(21, 0);
  });
});
