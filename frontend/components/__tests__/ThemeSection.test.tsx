import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ThemeSection } from "@/components/config/ThemeSection";
import { api } from "@/lib/api";
import type { ThemeConfig, ThemeDraft } from "@/types/personalization";

const SAVED: ThemeConfig = {
  autoAcceptOrders: true,
  themePrimaryColor: "#B91C1C",
  themeShowPrices: true,
  themeShowDescriptions: true,
  themeShowPhotos: true,
  themeContrast: null,
};

function draft(over: Partial<ThemeDraft> = {}): ThemeDraft {
  return { primaryColor: "#B91C1C", showPrices: true, showDescriptions: true, showPhotos: true, ...over };
}

describe("ThemeSection", () => {
  let patchSpy: jest.SpyInstance;

  beforeEach(() => {
    patchSpy = jest.spyOn(api, "patch").mockResolvedValue(SAVED);
  });
  afterEach(() => patchSpy.mockRestore());

  it("exibe o aviso de cor muito clara quando ratioOnWhite < 3:1", () => {
    render(
      <ThemeSection
        value={draft({ primaryColor: "#FFEB3B" })}
        onChange={() => {}}
        dirty={false}
        autoAcceptOrders={true}
        onSaved={() => {}}
        showToast={() => {}}
      />,
    );
    expect(screen.getByText(/cor muito clara/i)).toBeInTheDocument();
  });

  it("salva enviando autoAcceptOrders (passthrough) + a cor de marca", async () => {
    render(
      <ThemeSection
        value={draft()}
        onChange={() => {}}
        dirty={true}
        autoAcceptOrders={true}
        onSaved={() => {}}
        showToast={() => {}}
      />,
    );
    await userEvent.click(screen.getByRole("button", { name: /salvar aparência/i }));
    expect(patchSpy).toHaveBeenCalledWith(
      "/config",
      expect.objectContaining({ autoAcceptOrders: true, themePrimaryColor: "#B91C1C" }),
    );
  });

  it("Restaurar padrão limpa a cor (onChange com primaryColor vazio)", async () => {
    const onChange = jest.fn();
    render(
      <ThemeSection
        value={draft()}
        onChange={onChange}
        dirty={false}
        autoAcceptOrders={true}
        onSaved={() => {}}
        showToast={() => {}}
      />,
    );
    await userEvent.click(screen.getByRole("button", { name: "Restaurar padrão" }));
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ primaryColor: "" }));
  });
});
