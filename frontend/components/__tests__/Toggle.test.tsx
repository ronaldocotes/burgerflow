import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Toggle } from "@/components/ui/Toggle";

describe("Toggle", () => {
  it("expoe role switch com aria-checked refletindo o estado", () => {
    render(<Toggle id="t" checked={true} onChange={() => {}} label="Pix ativa" />);
    const sw = screen.getByRole("switch", { name: "Pix ativa" });
    expect(sw).toHaveAttribute("aria-checked", "true");
  });

  it("chama onChange com o valor invertido ao clicar", async () => {
    const onChange = jest.fn();
    render(<Toggle id="t" checked={false} onChange={onChange} label="Pix inativa" />);
    await userEvent.click(screen.getByRole("switch"));
    expect(onChange).toHaveBeenCalledWith(true);
  });

  it("nao dispara quando desabilitado", async () => {
    const onChange = jest.fn();
    render(<Toggle id="t" checked={false} disabled onChange={onChange} label="x" />);
    await userEvent.click(screen.getByRole("switch"));
    expect(onChange).not.toHaveBeenCalled();
  });
});
