"use client";

import { ChangeEvent, Dispatch, FormEvent, ReactNode, SetStateAction, useCallback, useEffect, useId, useMemo, useState, useSyncExternalStore } from "react";
import { useRouter } from "next/navigation";
import { CheckCircle, ImagePlus, Layers3, Package, Pencil, Plus, RefreshCw, Save, Trash2, Wheat, X, XCircle, type LucideIcon } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import { getToken } from "@/lib/auth";
import { Category, Ingredient, Page, Product, ProductAvailability, formatBRL } from "@/types/menu";

type Tab = "products" | "categories" | "ingredients";
type Notice = { type: "success" | "error"; message: string } | null;

const CHANNELS = [
  { value: "COUNTER", label: "Balcão" },
  { value: "DINE_IN", label: "Mesa" },
  { value: "DELIVERY", label: "Delivery" },
  { value: "ONLINE", label: "Cardápio digital" },
];

const EMPTY_PRODUCT = {
  id: "",
  categoryId: "",
  sku: "",
  name: "",
  description: "",
  priceCents: 0,
  costPriceCents: 0,
  imageUrl: "",
  isAvailable: true,
  displayOrder: 0,
  preparationTimeMinutes: 10,
  isFeatured: false,
  promoPriceCents: 0,
};

const EMPTY_CATEGORY = {
  id: "",
  name: "",
  description: "",
  displayOrder: 0,
  colorCode: "#047857",
  iconUrl: "",
};

const EMPTY_INGREDIENT = {
  id: "",
  name: "",
  description: "",
  unit: "UNIT",
  unitCostCents: 0,
  stockQuantity: 0,
  minStock: 0,
  isAllergen: false,
};

function centsInput(value: number): string {
  return (value / 100).toFixed(2);
}

function parseCents(value: string): number {
  const normalized = value.replace(/\./g, "").replace(",", ".");
  return Math.round((Number(normalized) || 0) * 100);
}

function selectedChannels(current: string[], value: string, checked: boolean): string[] {
  const next = new Set(current);
  if (checked) next.add(value);
  else next.delete(value);
  return Array.from(next);
}

const XL_QUERY = "(min-width: 1280px)";

function getXlMediaQuery(): MediaQueryList {
  return window.matchMedia(XL_QUERY);
}

function subscribeToXl(callback: () => void): () => void {
  const mq = getXlMediaQuery();
  mq.addEventListener("change", callback);
  return () => mq.removeEventListener("change", callback);
}

function getIsXlSnapshot(): boolean {
  return getXlMediaQuery().matches;
}

function getServerIsXlSnapshot(): boolean {
  return false;
}

export default function AdminCardapioPage() {
  const router = useRouter();
  const [tab, setTab] = useState<Tab>("products");
  const [notice, setNotice] = useState<Notice>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const [categories, setCategories] = useState<Category[]>([]);
  const [products, setProducts] = useState<Product[]>([]);
  const [ingredients, setIngredients] = useState<Ingredient[]>([]);

  const [productForm, setProductForm] = useState(EMPTY_PRODUCT);
  const [categoryForm, setCategoryForm] = useState(EMPTY_CATEGORY);
  const [ingredientForm, setIngredientForm] = useState(EMPTY_INGREDIENT);
  const [categoryFormOpen, setCategoryFormOpen] = useState(false);
  const [ingredientFormOpen, setIngredientFormOpen] = useState(false);
  const [availability, setAvailability] = useState<string[]>([]);
  const [uploading, setUploading] = useState(false);
  const [mobileFormOpen, setMobileFormOpen] = useState(false);
  const [deleteConfirm, setDeleteConfirm] = useState<{ path: string; label: string } | null>(null);
  const productDialogTitleId = useId();
  const isXl = useSyncExternalStore(subscribeToXl, getIsXlSnapshot, getServerIsXlSnapshot);

  const load = useCallback(async () => {
    setLoading(true);
    setNotice(null);
    try {
      const [catRes, prodRes, ingRes] = await Promise.all([
        api.get<Page<Category>>("/categories?size=200"),
        api.get<Page<Product>>("/products?size=300"),
        api.get<Ingredient[]>("/ingredients"),
      ]);
      setCategories(catRes.content);
      setProducts(prodRes.content);
      setIngredients(ingRes);
      setProductForm((prev) => ({
        ...prev,
        categoryId: prev.categoryId || catRes.content[0]?.id || "",
      }));
    } catch (err) {
      setNotice({ type: "error", message: err instanceof Error ? err.message : "Erro ao carregar dados." });
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!getToken()) {
      router.replace("/login");
      return;
    }
    queueMicrotask(() => {
      void load();
    });
  }, [load, router]);

  useEffect(() => {
    if (!mobileFormOpen) return;
    function closeOnEscape(event: KeyboardEvent) {
      if (event.key === "Escape") setMobileFormOpen(false);
    }
    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [mobileFormOpen]);

  const activeProducts = useMemo(() => products.filter((p) => p.active), [products]);
  const activeCategories = useMemo(() => categories.filter((c) => c.active), [categories]);
  const activeIngredients = useMemo(() => ingredients.filter((i) => i.active), [ingredients]);

  function resetProduct() {
    setProductForm({ ...EMPTY_PRODUCT, categoryId: activeCategories[0]?.id || "" });
    setAvailability([]);
  }

  function startNewProduct() {
    resetProduct();
    if (!isXl) setMobileFormOpen(true);
  }

  function startNewCategory() {
    setCategoryForm(EMPTY_CATEGORY);
    setCategoryFormOpen(true);
  }

  function editCategory(category: Category) {
    setCategoryForm({
      id: category.id,
      name: category.name,
      description: category.description,
      displayOrder: category.displayOrder,
      colorCode: category.colorCode ?? "",
      iconUrl: category.iconUrl ?? "",
    });
    setCategoryFormOpen(true);
  }

  function startNewIngredient() {
    setIngredientForm(EMPTY_INGREDIENT);
    setIngredientFormOpen(true);
  }

  function editIngredient(ingredient: Ingredient) {
    setIngredientForm({
      id: ingredient.id,
      name: ingredient.name,
      description: ingredient.description,
      unit: ingredient.unit,
      unitCostCents: ingredient.unitCostCents,
      stockQuantity: ingredient.stockQuantity,
      minStock: ingredient.minStock,
      isAllergen: ingredient.isAllergen,
    });
    setIngredientFormOpen(true);
  }

  async function editProduct(product: Product) {
    setTab("products");
    setProductForm({
      id: product.id,
      categoryId: product.categoryId,
      sku: product.sku,
      name: product.name,
      description: product.description,
      priceCents: product.priceCents,
      costPriceCents: product.costPriceCents ?? 0,
      imageUrl: product.imageUrl ?? "",
      isAvailable: product.isAvailable,
      displayOrder: product.displayOrder,
      preparationTimeMinutes: product.preparationTimeMinutes,
      isFeatured: product.isFeatured,
      promoPriceCents: product.promoPriceCents ?? 0,
    });
    try {
      const current = await api.get<ProductAvailability>(`/products/${product.id}/availability`);
      setAvailability(current.channels);
    } catch {
      setAvailability([]);
    }
    if (!isXl) setMobileFormOpen(true);
  }

  async function saveProduct(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!productForm.categoryId) {
      setNotice({ type: "error", message: "Crie uma categoria antes de cadastrar produtos." });
      return;
    }
    setSaving(true);
    try {
      const body = {
        categoryId: productForm.categoryId,
        sku: productForm.sku,
        name: productForm.name,
        description: productForm.description,
        priceCents: productForm.priceCents,
        costPriceCents: productForm.costPriceCents || null,
        imageUrl: productForm.imageUrl || null,
        isAvailable: productForm.isAvailable,
        displayOrder: productForm.displayOrder,
        preparationTimeMinutes: productForm.preparationTimeMinutes,
        isFeatured: productForm.isFeatured,
        promoPriceCents: productForm.promoPriceCents || null,
      };
      const saved = productForm.id
        ? await api.put<Product>(`/products/${productForm.id}`, body)
        : await api.post<Product>("/products", body, { "Idempotency-Key": crypto.randomUUID() });
      await api.put<ProductAvailability>(`/products/${saved.id}/availability`, { channels: availability, windows: [] });
      setNotice({ type: "success", message: "Produto salvo." });
      setMobileFormOpen(false);
      resetProduct();
      await load();
    } catch (err) {
      setNotice({ type: "error", message: err instanceof ApiError ? err.message : "Erro ao salvar produto." });
    } finally {
      setSaving(false);
    }
  }

  async function saveCategory(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    try {
      const body = {
        name: categoryForm.name,
        description: categoryForm.description,
        displayOrder: categoryForm.displayOrder,
        colorCode: categoryForm.colorCode || null,
        iconUrl: categoryForm.iconUrl || null,
      };
      categoryForm.id
        ? await api.put<Category>(`/categories/${categoryForm.id}`, body)
        : await api.post<Category>("/categories", body);
      setNotice({ type: "success", message: "Categoria salva." });
      setCategoryForm(EMPTY_CATEGORY);
      setCategoryFormOpen(false);
      await load();
    } catch (err) {
      setNotice({ type: "error", message: err instanceof ApiError ? err.message : "Erro ao salvar categoria." });
    } finally {
      setSaving(false);
    }
  }

  async function saveIngredient(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    try {
      const body = {
        name: ingredientForm.name,
        description: ingredientForm.description,
        unit: ingredientForm.unit,
        unitCostCents: ingredientForm.unitCostCents,
        stockQuantity: ingredientForm.stockQuantity,
        minStock: ingredientForm.minStock,
        isAllergen: ingredientForm.isAllergen,
      };
      ingredientForm.id
        ? await api.put<Ingredient>(`/ingredients/${ingredientForm.id}`, body)
        : await api.post<Ingredient>("/ingredients", body);
      setNotice({ type: "success", message: "Insumo salvo." });
      setIngredientForm(EMPTY_INGREDIENT);
      setIngredientFormOpen(false);
      await load();
    } catch (err) {
      setNotice({ type: "error", message: err instanceof ApiError ? err.message : "Erro ao salvar insumo." });
    } finally {
      setSaving(false);
    }
  }

  function remove(path: string, label: string) {
    setDeleteConfirm({ path, label });
  }

  async function confirmDelete() {
    if (!deleteConfirm) return;
    setSaving(true);
    try {
      await api.del(deleteConfirm.path);
      setNotice({ type: "success", message: `${deleteConfirm.label} removido.` });
      setDeleteConfirm(null);
      await load();
    } catch (err) {
      setNotice({ type: "error", message: err instanceof ApiError ? err.message : `Erro ao remover ${deleteConfirm.label}.` });
    } finally {
      setSaving(false);
    }
  }

  async function uploadImage(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) return;
    setUploading(true);
    try {
      const form = new FormData();
      form.append("file", file);
      const token = getToken() ?? "";
      const res = await fetch("/api/uploads/menu", {
        method: "POST",
        headers: token ? { Authorization: `Bearer ${token}` } : undefined,
        body: form,
      });
      const data = (await res.json()) as { url?: string; message?: string };
      if (!res.ok || !data.url) throw new Error(data.message ?? "Erro ao enviar imagem.");
      setProductForm((prev) => ({ ...prev, imageUrl: data.url ?? "" }));
      setNotice({ type: "success", message: "Imagem enviada." });
    } catch (err) {
      setNotice({ type: "error", message: err instanceof Error ? err.message : "Erro ao enviar imagem." });
    } finally {
      setUploading(false);
      event.target.value = "";
    }
  }

  return (
    <div className="min-h-screen bg-bg-secondary">
      <main className="mx-auto flex w-full max-w-7xl flex-col gap-5 px-4 py-6">
        <header className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h1 className="text-2xl font-bold text-text-primary">Administração do cardápio</h1>
            <p className="mt-1 text-sm text-text-secondary">
              Produtos, categorias, insumos, imagens e canais de venda.
            </p>
          </div>
          <button className="btn-secondary inline-flex items-center gap-2" onClick={() => void load()} disabled={loading}>
            <RefreshCw className="h-4 w-4" aria-hidden="true" />
            Atualizar
          </button>
        </header>

        {notice && (
          <div
            role={notice.type === "error" ? "alert" : "status"}
            className={[
              "rounded-lg px-4 py-3 text-sm font-medium",
              notice.type === "success" ? "bg-success/10 text-success" : "bg-error/10 text-error",
            ].join(" ")}
          >
            {notice.message}
          </div>
        )}

        <section className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <Metric icon={Package} label="Produtos ativos" value={activeProducts.length} />
          <Metric icon={Layers3} label="Categorias" value={activeCategories.length} />
          <Metric icon={Wheat} label="Insumos" value={activeIngredients.length} />
          <Metric icon={ImagePlus} label="Com imagem" value={activeProducts.filter((p) => p.imageUrl).length} note={`de ${activeProducts.length}`} />
        </section>

        <div role="tablist" aria-label="Seções do cardápio" className="grid grid-cols-3 gap-1 border-b border-border-light sm:flex sm:gap-2 sm:overflow-x-auto">
          <TabButton active={tab === "products"} onClick={() => setTab("products")} icon={Package} label="Produtos" />
          <TabButton active={tab === "categories"} onClick={() => setTab("categories")} icon={Layers3} label="Categorias" />
          <TabButton active={tab === "ingredients"} onClick={() => setTab("ingredients")} icon={Wheat} label="Insumos" />
        </div>

        {loading ? (
          <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_420px]">
            <div className="overflow-hidden rounded-lg bg-bg-primary shadow-card animate-pulse">
              <div className="h-12 border-b border-border-light" />
              {[...Array(5)].map((_, i) => (
                <div key={i} className="flex items-center gap-4 border-b border-border-light px-4 py-3">
                  <div className="h-4 w-1/3 rounded bg-bg-tertiary" />
                  <div className="h-4 w-1/5 rounded bg-bg-tertiary" />
                  <div className="h-4 w-1/6 rounded bg-bg-tertiary" />
                  <div className="h-4 w-1/6 rounded bg-bg-tertiary" />
                  <div className="ml-auto h-4 w-12 rounded bg-bg-tertiary" />
                </div>
              ))}
            </div>
            <div className="h-80 rounded-lg bg-bg-primary shadow-card animate-pulse" />
          </div>
        ) : (
          <>
            {tab === "products" && (
              <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_420px]">
                <section className="overflow-hidden rounded-lg bg-bg-primary shadow-card">
                  <TableHeader title="Produtos" actionLabel="Novo produto" onAction={startNewProduct} />
                  {activeProducts.length === 0 ? (
                    <p className="px-4 py-8 text-center text-sm text-text-muted">
                      Nenhum produto cadastrado. Clique em &ldquo;Novo produto&rdquo; para começar.
                    </p>
                  ) : (
                    <>
                      <div className="grid gap-3 p-3 md:hidden">
                        {activeProducts.map((product) => (
                          <ProductMobileCard
                            key={product.id}
                            product={product}
                            categoryName={categories.find((c) => c.id === product.categoryId)?.name ?? "-"}
                            onEdit={() => void editProduct(product)}
                            onDelete={() => void remove(`/products/${product.id}`, "produto")}
                          />
                        ))}
                      </div>
                      <div className="hidden overflow-x-auto md:block">
                        <table className="min-w-full text-sm">
                          <thead className="bg-bg-secondary text-left text-sm uppercase text-text-muted">
                            <tr>
                              <th scope="col" className="px-4 py-3">Produto</th>
                              <th scope="col" className="px-4 py-3">Categoria</th>
                              <th scope="col" className="px-4 py-3">Preço</th>
                              <th scope="col" className="px-4 py-3">Status</th>
                              <th scope="col" className="px-4 py-3 text-right">Ações</th>
                            </tr>
                          </thead>
                          <tbody className="divide-y divide-border-light">
                            {activeProducts.map((product) => (
                              <tr key={product.id} className="hover:bg-bg-secondary/70">
                                <td className="px-4 py-3">
	                                  <button className="inline-flex min-h-11 items-center text-left font-semibold text-text-primary hover:text-primary-700 hover:underline" onClick={() => void editProduct(product)}>
                                    {product.name}
                                  </button>
                                  <p className="text-sm text-text-muted">{product.sku}</p>
                                </td>
                                <td className="px-4 py-3 text-text-secondary">
                                  {categories.find((c) => c.id === product.categoryId)?.name ?? "-"}
                                </td>
                                <td className="px-4 py-3 text-text-primary">{formatBRL(product.effectivePriceCents)}</td>
                                <td className="px-4 py-3">
                                  <AvailabilityBadge available={product.isAvailable} />
                                </td>
                                <td className="px-4 py-3 text-right">
                                  <div className="inline-flex gap-1">
                                    <button className="icon-button" aria-label={`Editar ${product.name}`} onClick={() => void editProduct(product)}>
                                      <Pencil className="h-4 w-4" aria-hidden="true" />
                                    </button>
                                    <button className="icon-button text-error" aria-label={`Remover ${product.name}`} onClick={() => void remove(`/products/${product.id}`, "produto")}>
                                      <Trash2 className="h-4 w-4" aria-hidden="true" />
                                    </button>
                                  </div>
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </div>
                    </>
                  )}
                </section>

                <div className="hidden xl:block xl:sticky xl:top-6 xl:self-start">
                  <ProductForm
                    form={productForm}
                    categories={activeCategories}
                    availability={availability}
                    saving={saving}
                    uploading={uploading}
                    onSubmit={saveProduct}
                    onUpload={uploadImage}
                    setForm={setProductForm}
                    setAvailability={setAvailability}
                  />
                </div>
              </div>
            )}

            {tab === "categories" && (
              <CrudPanel title="Categorias" actionLabel="Nova categoria" onNew={startNewCategory}>
                {categoryFormOpen && (
                  <form onSubmit={saveCategory} className="grid gap-3 rounded-lg bg-bg-primary p-4 shadow-card sm:grid-cols-2">
                    <TextInput label="Nome" value={categoryForm.name} onChange={(v) => setCategoryForm((p) => ({ ...p, name: v }))} required />
                    <NumberInput label="Ordem" value={categoryForm.displayOrder} onChange={(v) => setCategoryForm((p) => ({ ...p, displayOrder: v }))} />
                    <label className="grid gap-1 text-sm font-medium text-text-secondary">
                      Cor
                      <div className="flex items-center gap-2">
                        <input type="color" value={categoryForm.colorCode || "#047857"} onChange={(e) => setCategoryForm((p) => ({ ...p, colorCode: e.target.value }))} className="h-10 w-14 cursor-pointer rounded border border-border-medium" />
                        <span className="text-sm text-text-muted">{categoryForm.colorCode}</span>
                      </div>
                    </label>
                    <TextInput label="Ícone/URL" value={categoryForm.iconUrl} onChange={(v) => setCategoryForm((p) => ({ ...p, iconUrl: v }))} />
                    <TextArea label="Descrição" value={categoryForm.description} onChange={(v) => setCategoryForm((p) => ({ ...p, description: v }))} />
                    <FormActions saving={saving} editing={!!categoryForm.id} onCancel={() => { setCategoryForm(EMPTY_CATEGORY); setCategoryFormOpen(false); }} />
                  </form>
                )}
                <ListItems
                  items={activeCategories}
                  title={(c) => c.name}
                  subtitle={(c) => c.description || `Ordem ${c.displayOrder}`}
                  onEdit={editCategory}
                  onDelete={(c) => void remove(`/categories/${c.id}`, "categoria")}
                />
              </CrudPanel>
            )}

            {tab === "ingredients" && (
              <CrudPanel title="Insumos" actionLabel="Novo insumo" onNew={startNewIngredient}>
                {ingredientFormOpen && (
                  <form onSubmit={saveIngredient} className="grid gap-3 rounded-lg bg-bg-primary p-4 shadow-card sm:grid-cols-2 lg:grid-cols-3">
                    <TextInput label="Nome" value={ingredientForm.name} onChange={(v) => setIngredientForm((p) => ({ ...p, name: v }))} required />
                    <label className="grid gap-1 text-sm font-medium text-text-secondary">
                      Unidade
                      <select className="input" value={ingredientForm.unit} onChange={(e) => setIngredientForm((p) => ({ ...p, unit: e.target.value }))}>
                        <option value="UNIT">UN (unidade)</option>
                        <option value="GRAM">Grama</option>
                        <option value="KILOGRAM">Kg</option>
                        <option value="LITER">Litro</option>
                        <option value="ML">ml</option>
                        <option value="BOX">Caixa</option>
                        <option value="PACK">Pacote</option>
                      </select>
                    </label>
                    <MoneyInput label="Custo unitário" cents={ingredientForm.unitCostCents} onChange={(v) => setIngredientForm((p) => ({ ...p, unitCostCents: v }))} />
                    <DecimalInput label="Estoque" value={ingredientForm.stockQuantity} onChange={(v) => setIngredientForm((p) => ({ ...p, stockQuantity: v }))} />
                    <DecimalInput label="Estoque mínimo" value={ingredientForm.minStock} onChange={(v) => setIngredientForm((p) => ({ ...p, minStock: v }))} />
                    <label className="flex min-h-11 items-center gap-2 text-sm leading-5 text-text-secondary">
                      <input className="h-5 w-5" type="checkbox" checked={ingredientForm.isAllergen} onChange={(e) => setIngredientForm((p) => ({ ...p, isAllergen: e.target.checked }))} />
                      Alérgeno
                    </label>
                    <TextArea label="Descrição" value={ingredientForm.description} onChange={(v) => setIngredientForm((p) => ({ ...p, description: v }))} />
                    <FormActions saving={saving} editing={!!ingredientForm.id} onCancel={() => { setIngredientForm(EMPTY_INGREDIENT); setIngredientFormOpen(false); }} />
                  </form>
                )}
                <ListItems
                  items={activeIngredients}
                  title={(i) => i.name}
                  subtitle={(i) => `${i.stockQuantity} ${i.unit} · ${formatBRL(i.unitCostCents)}`}
                  onEdit={editIngredient}
                  onDelete={(i) => void remove(`/ingredients/${i.id}`, "insumo")}
                />
              </CrudPanel>
            )}
          </>
        )}
      </main>

      {/* Overlay de formulário de produto — tablet (<xl) */}
      {mobileFormOpen && (
        <div className="fixed inset-0 z-50 overflow-y-auto bg-bg-secondary xl:hidden">
          <div
            role="dialog"
            aria-modal="true"
            aria-labelledby={productDialogTitleId}
            className="mx-auto min-h-full max-w-lg px-4 py-6"
          >
            <div className="sticky top-0 z-10 mb-4 flex items-center justify-between bg-bg-secondary py-2">
              <h2 id={productDialogTitleId} className="text-lg font-bold text-text-primary">
                {productForm.id ? "Editar produto" : "Novo produto"}
              </h2>
              <button
                type="button"
                aria-label="Fechar formulário"
                onClick={() => setMobileFormOpen(false)}
                className="icon-button"
              >
                <X className="h-5 w-5" aria-hidden="true" />
              </button>
            </div>
            <ProductForm
              form={productForm}
              categories={activeCategories}
              availability={availability}
              saving={saving}
              uploading={uploading}
              onSubmit={saveProduct}
              onUpload={uploadImage}
              setForm={setProductForm}
              setAvailability={setAvailability}
            />
          </div>
        </div>
      )}

      {/* Modal de confirmação de remoção */}
      {deleteConfirm && (
        <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/50 p-4">
          <div className="w-full max-w-sm rounded-2xl bg-bg-primary p-6 shadow-xl">
            <h3 className="mb-2 text-base font-bold text-text-primary">Confirmar remoção</h3>
            <p className="mb-6 text-sm text-text-secondary">
              Remover <strong>{deleteConfirm.label}</strong>? Esta ação não pode ser desfeita.
            </p>
            <div className="flex gap-3">
              <button
                className="btn-outline flex-1"
                onClick={() => setDeleteConfirm(null)}
                disabled={saving}
              >
                Cancelar
              </button>
              <button
                className="flex-1 rounded-lg bg-error px-4 py-2 font-medium text-white transition-colors hover:bg-red-700 active:bg-red-800 disabled:opacity-50"
                onClick={() => void confirmDelete()}
                disabled={saving}
              >
                {saving ? "Removendo…" : "Remover"}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function Metric({ icon: Icon, label, value, note }: { icon: LucideIcon; label: string; value: number; note?: string }) {
  return (
    <div className="rounded-lg bg-bg-primary p-4 shadow-card">
      <Icon className="mb-3 h-5 w-5 text-primary-700" aria-hidden="true" />
      <p className="text-2xl font-bold text-text-primary">
        {value}
        {note && <span className="ml-1 text-sm font-normal text-text-muted">{note}</span>}
      </p>
      <p className="text-sm text-text-secondary">{label}</p>
    </div>
  );
}

function TabButton({ active, onClick, icon: Icon, label }: { active: boolean; onClick: () => void; icon: LucideIcon; label: string }) {
  return (
    <button role="tab" aria-selected={active} onClick={onClick} className={["inline-flex min-w-0 items-center justify-center gap-1 px-2 py-3 text-sm font-semibold sm:gap-2 sm:px-4", active ? "border-b-2 border-primary-700 text-primary-700" : "text-text-secondary"].join(" ")}>
      <Icon className="h-4 w-4" aria-hidden="true" />
      <span className="truncate">{label}</span>
    </button>
  );
}

function AvailabilityBadge({ available }: { available: boolean }) {
  return available ? (
    <span className="inline-flex items-center gap-1 text-success">
      <CheckCircle className="h-3.5 w-3.5" aria-hidden="true" />
      Disponível
    </span>
  ) : (
    <span className="inline-flex items-center gap-1 text-error">
      <XCircle className="h-3.5 w-3.5" aria-hidden="true" />
      Indisponível
    </span>
  );
}

function ProductMobileCard({
  product,
  categoryName,
  onEdit,
  onDelete,
}: {
  product: Product;
  categoryName: string;
  onEdit: () => void;
  onDelete: () => void;
}) {
  return (
    <article className="overflow-hidden rounded-lg border border-border-light bg-bg-primary p-4">
      <div className="flex flex-col gap-2 min-[360px]:flex-row min-[360px]:items-start min-[360px]:justify-between">
	        <button className="min-h-11 w-full min-w-0 text-left min-[360px]:flex-1" onClick={onEdit}>
          <h3 className="truncate text-sm font-semibold text-text-primary">{product.name}</h3>
          <p className="truncate text-sm text-text-muted">{product.sku || "Sem SKU"}</p>
        </button>
        <span className="shrink-0 self-start whitespace-nowrap text-sm">
          <AvailabilityBadge available={product.isAvailable} />
        </span>
      </div>
      <div className="mt-3 grid grid-cols-2 gap-3 text-sm">
        <div className="min-w-0">
          <p className="text-sm uppercase text-text-muted">Categoria</p>
          <p className="truncate font-medium text-text-secondary">{categoryName}</p>
        </div>
        <div className="min-w-0 text-right">
          <p className="text-sm uppercase text-text-muted">Preço</p>
          <p className="truncate font-semibold text-text-primary">{formatBRL(product.effectivePriceCents)}</p>
        </div>
      </div>
      <div className="mt-3 flex justify-end gap-2">
        <button className="icon-button h-11 w-11" aria-label={`Editar ${product.name}`} onClick={onEdit}>
          <Pencil className="h-4 w-4" aria-hidden="true" />
        </button>
        <button className="icon-button h-11 w-11 text-error" aria-label={`Remover ${product.name}`} onClick={onDelete}>
          <Trash2 className="h-4 w-4" aria-hidden="true" />
        </button>
      </div>
    </article>
  );
}

function TableHeader({ title, actionLabel, onAction }: { title: string; actionLabel: string; onAction: () => void }) {
  return (
    <div className="flex items-center justify-between border-b border-border-light px-4 py-3">
      <h2 className="text-sm font-semibold text-text-primary">{title}</h2>
      <button className="btn-secondary inline-flex items-center gap-2" onClick={onAction}>
        <Plus className="h-4 w-4" aria-hidden="true" />
        {actionLabel}
      </button>
    </div>
  );
}

function ProductForm(props: {
  form: typeof EMPTY_PRODUCT;
  categories: Category[];
  availability: string[];
  saving: boolean;
  uploading: boolean;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onUpload: (event: ChangeEvent<HTMLInputElement>) => void;
  setForm: Dispatch<SetStateAction<typeof EMPTY_PRODUCT>>;
  setAvailability: Dispatch<SetStateAction<string[]>>;
}) {
  const { form, categories, availability, saving, uploading, onSubmit, onUpload, setForm, setAvailability } = props;
  return (
    <form onSubmit={onSubmit} className="rounded-lg bg-bg-primary p-4 shadow-card">
      <h2 className="mb-4 text-sm font-semibold text-text-primary">{form.id ? "Editar produto" : "Novo produto"}</h2>
      <div className="grid gap-4">
        <TextInput label="Nome" value={form.name} onChange={(v) => setForm((p) => ({ ...p, name: v }))} required />
        <TextInput label="SKU" value={form.sku} onChange={(v) => setForm((p) => ({ ...p, sku: v }))} required />
        <label className="grid gap-1 text-sm font-medium text-text-secondary">
          Categoria
          <select className="input" value={form.categoryId} onChange={(e) => setForm((p) => ({ ...p, categoryId: e.target.value }))} required>
            {categories.map((category) => (
              <option key={category.id} value={category.id}>{category.name}</option>
            ))}
          </select>
        </label>
        <TextArea label="Descrição" value={form.description} onChange={(v) => setForm((p) => ({ ...p, description: v }))} />
        <div className="grid gap-3 min-[380px]:grid-cols-2">
          <MoneyInput label="Preço" cents={form.priceCents} onChange={(v) => setForm((p) => ({ ...p, priceCents: v }))} />
          <MoneyInput label="Custo" cents={form.costPriceCents} onChange={(v) => setForm((p) => ({ ...p, costPriceCents: v }))} />
        </div>
        <div className="grid gap-3 min-[380px]:grid-cols-2">
          <MoneyInput label="Preço promo" cents={form.promoPriceCents} onChange={(v) => setForm((p) => ({ ...p, promoPriceCents: v }))} />
          <NumberInput label="Tempo (min)" value={form.preparationTimeMinutes} min={1} onChange={(v) => setForm((p) => ({ ...p, preparationTimeMinutes: v }))} />
        </div>
        <NumberInput label="Ordem" value={form.displayOrder} onChange={(v) => setForm((p) => ({ ...p, displayOrder: v }))} />
        <TextInput label="URL da imagem" value={form.imageUrl} onChange={(v) => setForm((p) => ({ ...p, imageUrl: v }))} />
        <p className="text-center text-xs text-text-muted">— ou —</p>
        <label className="inline-flex min-h-11 cursor-pointer items-center gap-2 rounded-lg border border-dashed border-border-medium px-3 py-2 text-sm text-text-secondary hover:bg-bg-secondary">
          <ImagePlus className="h-4 w-4" aria-hidden="true" />
          {uploading ? "Enviando..." : "Enviar imagem"}
          <input type="file" accept="image/png,image/jpeg,image/webp,image/gif" className="sr-only" onChange={onUpload} disabled={uploading} />
        </label>
        <div className="grid gap-2">
          <p className="text-sm font-medium text-text-secondary">Canais de venda</p>
          <div className="grid gap-2">
            {CHANNELS.map((channel) => (
              <label key={channel.value} className="flex min-h-11 items-center gap-2 text-sm leading-5 text-text-secondary">
                <input
                  className="h-5 w-5"
                  type="checkbox"
                  checked={availability.includes(channel.value)}
                  onChange={(e) => setAvailability((prev) => selectedChannels(prev, channel.value, e.target.checked))}
                />
                {channel.label}
              </label>
            ))}
          </div>
          <p className="text-xs text-text-muted">Nenhum canal marcado significa disponível em todos.</p>
        </div>
        <div className="flex flex-wrap gap-x-4 gap-y-2">
          <label className="flex min-h-11 items-center gap-2 text-sm leading-5 text-text-secondary">
            <input className="h-5 w-5" type="checkbox" checked={form.isAvailable} onChange={(e) => setForm((p) => ({ ...p, isAvailable: e.target.checked }))} />
            Disponível
          </label>
          <label className="flex min-h-11 items-center gap-2 text-sm leading-5 text-text-secondary">
            <input className="h-5 w-5" type="checkbox" checked={form.isFeatured} onChange={(e) => setForm((p) => ({ ...p, isFeatured: e.target.checked }))} />
            Destaque
          </label>
        </div>
        <SubmitButton saving={saving} editing={!!form.id} />
      </div>
    </form>
  );
}

function CrudPanel({ title, actionLabel = "Novo", onNew, children }: { title: string; actionLabel?: string; onNew: () => void; children: ReactNode }) {
  return (
    <section className="grid gap-5">
      <TableHeader title={title} actionLabel={actionLabel} onAction={onNew} />
      {children}
    </section>
  );
}

function ListItems<T extends { id: string }>({ items, title, subtitle, onEdit, onDelete }: { items: T[]; title: (item: T) => string; subtitle: (item: T) => string; onEdit: (item: T) => void; onDelete: (item: T) => void }) {
  return (
    <div className="grid gap-2 md:grid-cols-2 xl:grid-cols-3">
      {items.length === 0 && (
        <p className="text-sm text-text-muted col-span-full py-4 text-center">Nenhum item cadastrado.</p>
      )}
      {items.map((item) => (
        <div key={item.id} className="flex items-center justify-between gap-3 rounded-lg bg-bg-primary p-4 shadow-card">
          <button className="min-w-0 text-left" onClick={() => onEdit(item)}>
            <p className="truncate text-sm font-semibold text-text-primary">{title(item)}</p>
            <p className="truncate text-sm text-text-secondary">{subtitle(item)}</p>
          </button>
          <button className="icon-button" aria-label={`Remover ${title(item)}`} onClick={() => onDelete(item)}>
            <Trash2 className="h-4 w-4" aria-hidden="true" />
          </button>
        </div>
      ))}
    </div>
  );
}

function SubmitButton({ saving, editing }: { saving: boolean; editing: boolean }) {
  return (
    <button type="submit" className="btn-primary inline-flex min-h-11 items-center justify-center gap-2 self-start" disabled={saving}>
      <Save className="h-4 w-4" aria-hidden="true" />
      {saving ? "Salvando..." : editing ? "Salvar alterações" : "Criar"}
    </button>
  );
}

function FormActions({ saving, editing, onCancel }: { saving: boolean; editing: boolean; onCancel: () => void }) {
  return (
    <div className="flex flex-col gap-2 sm:col-span-full sm:flex-row">
      <SubmitButton saving={saving} editing={editing} />
      <button type="button" className="btn-secondary inline-flex items-center justify-center" onClick={onCancel} disabled={saving}>
        Cancelar
      </button>
    </div>
  );
}

function TextInput({ label, value, onChange, required = false }: { label: string; value: string; onChange: (value: string) => void; required?: boolean }) {
  return (
    <label className="grid gap-1 text-sm font-medium text-text-secondary">
      {label}
      <input className="input" value={value} onChange={(e) => onChange(e.target.value)} required={required} />
    </label>
  );
}

function TextArea({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) {
  return (
    <label className="grid gap-1 text-sm font-medium text-text-secondary sm:col-span-2 lg:col-span-3">
      {label}
      <textarea className="input min-h-20 resize-y" value={value} onChange={(e) => onChange(e.target.value)} />
    </label>
  );
}

function NumberInput({ label, value, onChange, min = 0 }: { label: string; value: number; onChange: (value: number) => void; min?: number }) {
  return (
    <label className="grid gap-1 text-sm font-medium text-text-secondary">
      {label}
      <input className="input" type="number" min={min} value={value} onChange={(e) => onChange(Number(e.target.value))} />
    </label>
  );
}

function DecimalInput({ label, value, onChange }: { label: string; value: number; onChange: (value: number) => void }) {
  return (
    <label className="grid gap-1 text-sm font-medium text-text-secondary">
      {label}
      <input className="input" type="number" step="0.001" min="0" value={value} onChange={(e) => onChange(Number(e.target.value))} />
    </label>
  );
}

function MoneyInput({ label, cents, onChange }: { label: string; cents: number; onChange: (value: number) => void }) {
  return (
    <label className="grid gap-1 text-sm font-medium text-text-secondary">
      {label}
      <input className="input" inputMode="decimal" min="0" value={centsInput(cents)} onChange={(e) => onChange(parseCents(e.target.value))} />
    </label>
  );
}
