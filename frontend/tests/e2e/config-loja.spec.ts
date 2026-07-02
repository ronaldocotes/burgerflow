import { expect, test } from "@playwright/test";

// E2E da area "Minha Loja" (/configuracoes/loja). Segue o padrao de critical-flows:
// roda contra o ambiente vivo (E2E_BASE_URL) e depende do backend Fase CONFIG-A
// deployado. So executa via `npm run test:e2e`.

const tenant = process.env.E2E_TENANT ?? "demo";
const email = process.env.E2E_EMAIL ?? "admin@demo.com";
const password = process.env.E2E_PASSWORD ?? "Demo@1234";

async function login(page: import("@playwright/test").Page) {
  await page.goto("/login");
  await page.getByLabel("Restaurante").fill(tenant);
  await page.getByLabel("E-mail").fill(email);
  await page.getByLabel("Senha").fill(password);
  await page.getByRole("button", { name: /entrar/i }).click();
  await expect(page).toHaveURL(/\/pdv/);
}

test.describe("Minha Loja (Config de Loja)", () => {
  test("abre a pagina e mostra as 6 secoes", async ({ page }) => {
    await login(page);
    await page.goto("/configuracoes/loja");
    await expect(page.getByRole("heading", { name: "Minha Loja" })).toBeVisible();
    for (const secao of [
      "Endereco da loja",
      "Horario de funcionamento",
      "Formas de pagamento",
      "Tempo de entrega e preparo",
      "Motivos de cancelamento",
      "Links e QR do cardapio",
    ]) {
      await expect(page.getByRole("heading", { name: secao })).toBeVisible();
    }
  });

  test("link publico invalido mostra 404 amigavel", async ({ page }) => {
    await page.goto(`/l/${tenant}/slug-que-nao-existe-999`);
    await expect(page.getByText(/Link nao encontrado/i)).toBeVisible();
  });
});
