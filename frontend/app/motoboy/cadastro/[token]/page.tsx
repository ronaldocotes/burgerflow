"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useParams, useSearchParams } from "next/navigation";

// ---------------------------------------------------------------------------
// Config
// ---------------------------------------------------------------------------

const API_BASE =
  process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080/api/v1";
const DEFAULT_TENANT = process.env.NEXT_PUBLIC_TENANT_SLUG ?? "demo";

// ---------------------------------------------------------------------------
// Tipos
// ---------------------------------------------------------------------------

type PixKeyType = "CPF" | "TELEFONE" | "EMAIL" | "ALEATORIA";
type CnhCategory = "A" | "AB" | "B" | "C";
type VehicleType = "MOTO" | "CARRO" | "VAN";

interface PreloadData {
  name: string;
  phoneMasked: string;
  provisional: boolean;
  alreadyCompleted: boolean;
}

interface FormData {
  name: string;
  cpf: string;
  cnhCategory: CnhCategory | "";
  vehicleType: VehicleType | "";
  licensePlate: string;
  pixKeyType: PixKeyType | "";
  pixKey: string;
  acceptedTerms: boolean;
}

type FormErrors = Partial<Record<keyof FormData, string>>;
type FormTouched = Partial<Record<keyof FormData, boolean>>;

type PageState =
  | { kind: "loading" }
  | { kind: "error" }
  | { kind: "not_found" }
  | { kind: "already_completed" }
  | { kind: "form"; preload: PreloadData }
  | { kind: "success" };

// ---------------------------------------------------------------------------
// Helpers de mascara
// ---------------------------------------------------------------------------

function maskCpf(value: string): string {
  const d = value.replace(/\D/g, "").slice(0, 11);
  if (d.length <= 3) return d;
  if (d.length <= 6) return d.slice(0, 3) + "." + d.slice(3);
  if (d.length <= 9)
    return d.slice(0, 3) + "." + d.slice(3, 6) + "." + d.slice(6);
  return (
    d.slice(0, 3) +
    "." +
    d.slice(3, 6) +
    "." +
    d.slice(6, 9) +
    "-" +
    d.slice(9)
  );
}

function maskPhone(value: string): string {
  const d = value.replace(/\D/g, "").slice(0, 11);
  if (d.length === 0) return "";
  if (d.length <= 2) return "(" + d;
  if (d.length <= 7) return "(" + d.slice(0, 2) + ") " + d.slice(2);
  return (
    "(" + d.slice(0, 2) + ") " + d.slice(2, 7) + "-" + d.slice(7)
  );
}

function maskPlate(value: string): string {
  return value
    .toUpperCase()
    .replace(/[^A-Z0-9]/g, "")
    .slice(0, 7);
}

// ---------------------------------------------------------------------------
// Validacao
// ---------------------------------------------------------------------------

function validateCpfDigits(value: string): boolean {
  const d = value.replace(/\D/g, "");
  return d.length === 11 && !/^(\d)\1+$/.test(d);
}

function validatePlate(value: string): boolean {
  const c = value.replace(/[^A-Z0-9]/gi, "").toUpperCase();
  // Mercosul: ABC1D23 | Antiga: ABC1234
  return /^[A-Z]{3}[0-9][A-Z0-9][0-9]{2}$/.test(c);
}

function validateForm(f: FormData): FormErrors {
  const e: FormErrors = {};
  if (!f.name.trim()) {
    e.name = "Nome e obrigatorio";
  } else if (f.name.trim().length > 100) {
    e.name = "Nome muito longo (max 100 caracteres)";
  }
  if (f.cpf && !validateCpfDigits(f.cpf)) {
    e.cpf = "CPF invalido";
  }
  if (!f.licensePlate.trim()) {
    e.licensePlate = "Placa e obrigatoria";
  } else if (!validatePlate(f.licensePlate)) {
    e.licensePlate = "Placa invalida. Exemplo: ABC1D23 ou ABC1234";
  }
  if (!f.pixKeyType) {
    e.pixKeyType = "Selecione o tipo de chave PIX";
  }
  if (!f.pixKey.trim()) {
    e.pixKey = "Chave PIX e obrigatoria";
  } else if (f.pixKeyType === "CPF" && !validateCpfDigits(f.pixKey)) {
    e.pixKey = "CPF invalido";
  } else if (
    f.pixKeyType === "EMAIL" &&
    !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(f.pixKey)
  ) {
    e.pixKey = "E-mail invalido";
  } else if (f.pixKeyType === "TELEFONE") {
    const digits = f.pixKey.replace(/\D/g, "");
    if (digits.length < 10) e.pixKey = "Telefone invalido";
  }
  if (!f.acceptedTerms) {
    e.acceptedTerms = "Voce precisa aceitar os termos";
  }
  return e;
}

// ---------------------------------------------------------------------------
// Loading skeleton
// ---------------------------------------------------------------------------

function LoadingSkeleton() {
  return (
    <div
      className="min-h-screen bg-bg-primary px-6 py-10"
      aria-busy="true"
      aria-label="Carregando formulario de cadastro"
    >
      <div className="max-w-md mx-auto space-y-6 animate-pulse">
        <div className="h-10 bg-bg-tertiary rounded-xl w-3/4 mx-auto" />
        <div className="h-4 bg-bg-tertiary rounded w-1/2 mx-auto" />
        <div className="h-16 bg-bg-tertiary rounded-2xl" />
        <div className="space-y-4 pt-2">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="space-y-2">
              <div className="h-3 bg-bg-tertiary rounded w-1/3" />
              <div className="h-11 bg-bg-tertiary rounded-xl" />
            </div>
          ))}
        </div>
        <div className="h-12 bg-bg-tertiary rounded-xl" />
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Modal de termos
// ---------------------------------------------------------------------------

function TermsModal({ onClose }: { onClose: () => void }) {
  const panelRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = panelRef.current;
    if (el) el.focus();

    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = prevOverflow;
    };
  }, [onClose]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-end sm:items-center justify-center bg-black/50 px-4 pb-4 sm:pb-0"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="terms-modal-title"
        tabIndex={-1}
        className="bg-bg-primary rounded-t-2xl sm:rounded-2xl w-full max-w-md max-h-[80vh] overflow-y-auto p-6 focus:outline-none shadow-xl"
      >
        <h2
          id="terms-modal-title"
          className="text-text-primary font-bold text-lg mb-4"
        >
          Termos de uso e privacidade
        </h2>
        <p className="text-text-secondary text-sm leading-relaxed">
          Ao se cadastrar, voce autoriza o compartilhamento do seu nome e
          placa com os clientes para fins de entrega, conforme a Lei Geral de
          Protecao de Dados (LGPD - Lei 13.709/2018). Seus dados de
          pagamento (PIX) sao utilizados exclusivamente para transferencias
          realizadas pelo restaurante.
        </p>
        <button
          type="button"
          onClick={onClose}
          className="btn-primary w-full mt-6 rounded-xl"
        >
          Entendido
        </button>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Pagina principal
// ---------------------------------------------------------------------------

export default function MotoboySignupPage() {
  const params = useParams<{ token: string }>();
  const searchParams = useSearchParams();
  const token = params?.token ?? "";
  const tenantSlug = searchParams?.get("tenant") ?? DEFAULT_TENANT;

  const [state, setState] = useState<PageState>({ kind: "loading" });
  const [form, setForm] = useState<FormData>({
    name: "",
    cpf: "",
    cnhCategory: "",
    vehicleType: "",
    licensePlate: "",
    pixKeyType: "",
    pixKey: "",
    acceptedTerms: false,
  });
  const [errors, setErrors] = useState<FormErrors>({});
  const [touched, setTouched] = useState<FormTouched>({});
  const [submitting, setSubmitting] = useState(false);
  const [showTerms, setShowTerms] = useState(false);
  const isMountedRef = useRef(true);

  // ---- Carregamento inicial ----

  const fetchPreload = useCallback(
    async (signal?: AbortSignal) => {
      if (!token) return;
      const url =
        API_BASE +
        "/public/" +
        tenantSlug +
        "/motoboy/cadastro/" +
        token;
      try {
        const res = await fetch(url, { signal });
        if (!isMountedRef.current) return;
        if (res.status === 404) {
          setState({ kind: "not_found" });
          return;
        }
        if (!res.ok) {
          setState({ kind: "error" });
          return;
        }
        const data: PreloadData = await res.json();
        if (!isMountedRef.current) return;
        if (data.alreadyCompleted) {
          setState({ kind: "already_completed" });
          return;
        }
        setState({ kind: "form", preload: data });
        setForm((prev) => ({ ...prev, name: data.name ?? "" }));
      } catch (err: unknown) {
        if (err instanceof DOMException && err.name === "AbortError") return;
        if (!isMountedRef.current) return;
        setState({ kind: "error" });
      }
    },
    [token, tenantSlug]
  );

  useEffect(() => {
    isMountedRef.current = true;
    const controller = new AbortController();
    fetchPreload(controller.signal);
    return () => {
      isMountedRef.current = false;
      controller.abort();
    };
  }, [fetchPreload]);

  // ---- Handlers de formulario ----

  function handleChange<K extends keyof FormData>(
    field: K,
    value: FormData[K]
  ) {
    setForm((prev) => {
      const next = { ...prev, [field]: value };
      if (touched[field]) setErrors(validateForm(next));
      return next;
    });
  }

  function handleBlur(field: keyof FormData) {
    setTouched((prev) => ({ ...prev, [field]: true }));
    setErrors(validateForm(form));
  }

  function handlePixKeyChange(raw: string) {
    let masked = raw;
    if (form.pixKeyType === "CPF") masked = maskCpf(raw);
    else if (form.pixKeyType === "TELEFONE") masked = maskPhone(raw);
    handleChange("pixKey", masked);
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    // Marcar todos os campos como tocados para exibir erros
    const allTouched = Object.keys(form).reduce(
      (acc, k) => ({ ...acc, [k]: true }),
      {} as FormTouched
    );
    setTouched(allTouched);
    const errs = validateForm(form);
    setErrors(errs);
    if (Object.keys(errs).length > 0) return;

    setSubmitting(true);
    try {
      const url =
        API_BASE +
        "/public/" +
        tenantSlug +
        "/motoboy/cadastro/" +
        token;
      const body = {
        name: form.name.trim(),
        cpf: form.cpf ? form.cpf.replace(/\D/g, "") : undefined,
        cnhCategory: form.cnhCategory || undefined,
        vehicleType: form.vehicleType || undefined,
        licensePlate: maskPlate(form.licensePlate),
        pixKey: form.pixKey.trim(),
        pixKeyType: form.pixKeyType,
        acceptedTerms: true,
      };
      const res = await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      if (!isMountedRef.current) return;
      if (res.status === 409) {
        setState({ kind: "already_completed" });
        return;
      }
      if (!res.ok) {
        let msg = "Erro ao enviar. Tente novamente.";
        try {
          const text = await res.text();
          if (text) {
            const data = JSON.parse(text) as { message?: string };
            if (data.message) msg = data.message;
          }
        } catch (_parseErr) {
          // ignora erro de parse da resposta de erro
        }
        setErrors({ name: msg });
        return;
      }
      setState({ kind: "success" });
    } catch (_networkErr) {
      setErrors({
        name: "Sem conexao. Verifique sua internet e tente novamente.",
      });
    } finally {
      if (isMountedRef.current) setSubmitting(false);
    }
  }

  // ---- Estados de tela ----

  if (state.kind === "loading") return <LoadingSkeleton />;

  if (state.kind === "not_found") {
    return (
      <div
        className="min-h-screen bg-bg-primary flex flex-col items-center justify-center px-6 text-center"
        role="main"
      >
        <span className="text-5xl mb-4" role="img" aria-label="Link invalido">
          🔗
        </span>
        <h1 className="text-text-primary text-xl font-bold mb-2">
          Link invalido ou expirado
        </h1>
        <p className="text-text-secondary text-sm">
          Peca um novo link ao restaurante.
        </p>
      </div>
    );
  }

  if (state.kind === "error") {
    return (
      <div
        className="min-h-screen bg-bg-primary flex flex-col items-center justify-center px-6 text-center"
        role="main"
      >
        <span className="text-5xl mb-4" role="img" aria-label="Erro">
          ⚠️
        </span>
        <h1 className="text-text-primary text-xl font-bold mb-2">
          Nao foi possivel carregar
        </h1>
        <p className="text-text-secondary text-sm mb-6">
          Verifique sua conexao e tente novamente.
        </p>
        <button
          type="button"
          onClick={() => {
            setState({ kind: "loading" });
            fetchPreload();
          }}
          className="btn-primary px-8 rounded-xl"
        >
          Tentar novamente
        </button>
      </div>
    );
  }

  if (state.kind === "already_completed") {
    return (
      <div
        className="min-h-screen bg-bg-primary flex flex-col items-center justify-center px-6 text-center"
        role="main"
      >
        <span
          className="text-5xl mb-4"
          role="img"
          aria-label="Cadastro concluido"
        >
          ✅
        </span>
        <h1 className="text-text-primary text-xl font-bold mb-2">
          Cadastro ja realizado
        </h1>
        <p className="text-text-secondary text-sm">
          Obrigado por fazer parte da equipe!
        </p>
      </div>
    );
  }

  if (state.kind === "success") {
    return (
      <div
        className="min-h-screen bg-bg-primary flex flex-col items-center justify-center px-6 text-center"
        role="main"
      >
        <span className="text-5xl mb-4" role="img" aria-label="Sucesso">
          🎉
        </span>
        <h1 className="text-text-primary text-xl font-bold mb-2">
          Cadastro concluido!
        </h1>
        <p className="text-text-secondary text-sm mb-6">
          Em breve voce recebera corridas com prioridade.
        </p>
        <a
          href="/motoboy/app"
          className="btn-primary min-h-12 rounded-xl px-8 text-base font-semibold"
        >
          Baixe o app do entregador
        </a>
      </div>
    );
  }

  // state.kind === "form"
  const { preload } = state;

  return (
    <>
      {showTerms && <TermsModal onClose={() => setShowTerms(false)} />}

      <main
        role="main"
        aria-label="Formulario de cadastro de entregador"
        className="min-h-screen bg-bg-primary py-10 px-6"
      >
        <div className="max-w-md mx-auto">
          {/* Cabecalho */}
          <div className="text-center mb-8">
            <span className="text-5xl" role="img" aria-hidden="true">
              🛵
            </span>
            <h1 className="text-text-primary text-2xl font-bold mt-3">
              Cadastro de Entregador
            </h1>
            {preload.phoneMasked && (
              <p className="text-text-muted text-sm mt-1">
                {preload.phoneMasked}
              </p>
            )}
          </div>

          {/* Introducao */}
          <div className="bg-bg-secondary rounded-2xl p-5 mb-8 text-sm text-text-secondary leading-relaxed">
            Ola! Obrigado pela entrega. Complete seu cadastro para receber
            corridas com prioridade e suas transferencias via PIX.
          </div>

          {/* Formulario */}
          <form onSubmit={handleSubmit} noValidate className="space-y-8">
            {/* Secao: Seus Dados */}
            <fieldset>
              <legend className="text-text-primary font-semibold text-base mb-4">
                Seus Dados
              </legend>
              <div className="space-y-4">
                {/* Nome */}
                <div>
                  <label
                    htmlFor="mf-name"
                    className="block text-sm font-medium text-text-secondary mb-1"
                  >
                    Nome{" "}
                    <span aria-hidden="true" className="text-red-500">
                      *
                    </span>
                    <span className="sr-only">(obrigatorio)</span>
                  </label>
                  <input
                    id="mf-name"
                    type="text"
                    autoComplete="name"
                    maxLength={100}
                    value={form.name}
                    onChange={(e) => handleChange("name", e.target.value)}
                    onBlur={() => handleBlur("name")}
                    className="input-field rounded-xl"
                    aria-required="true"
                    aria-invalid={!!(errors.name && touched.name)}
                    aria-describedby={
                      errors.name && touched.name ? "err-name" : undefined
                    }
                  />
                  {errors.name && touched.name && (
                    <p
                      id="err-name"
                      role="alert"
                      className="text-red-600 text-xs mt-1"
                    >
                      {errors.name}
                    </p>
                  )}
                </div>

                {/* CPF */}
                <div>
                  <label
                    htmlFor="mf-cpf"
                    className="block text-sm font-medium text-text-secondary mb-1"
                  >
                    CPF{" "}
                    <span className="text-text-muted text-xs font-normal">
                      (opcional)
                    </span>
                  </label>
                  <input
                    id="mf-cpf"
                    type="text"
                    inputMode="numeric"
                    autoComplete="off"
                    placeholder="000.000.000-00"
                    value={form.cpf}
                    onChange={(e) =>
                      handleChange("cpf", maskCpf(e.target.value))
                    }
                    onBlur={() => handleBlur("cpf")}
                    className="input-field rounded-xl"
                    aria-invalid={!!(errors.cpf && touched.cpf)}
                    aria-describedby={
                      errors.cpf && touched.cpf ? "err-cpf" : undefined
                    }
                  />
                  {errors.cpf && touched.cpf && (
                    <p
                      id="err-cpf"
                      role="alert"
                      className="text-red-600 text-xs mt-1"
                    >
                      {errors.cpf}
                    </p>
                  )}
                </div>

                {/* CNH Categoria */}
                <div>
                  <label
                    htmlFor="mf-cnh"
                    className="block text-sm font-medium text-text-secondary mb-1"
                  >
                    Categoria CNH{" "}
                    <span className="text-text-muted text-xs font-normal">
                      (opcional)
                    </span>
                  </label>
                  <select
                    id="mf-cnh"
                    value={form.cnhCategory}
                    onChange={(e) =>
                      handleChange(
                        "cnhCategory",
                        e.target.value as CnhCategory | ""
                      )
                    }
                    className="input-field rounded-xl"
                  >
                    <option value="">Selecione</option>
                    <option value="A">A</option>
                    <option value="AB">AB</option>
                    <option value="B">B</option>
                    <option value="C">C</option>
                  </select>
                </div>
              </div>
            </fieldset>

            {/* Secao: Seu Veiculo */}
            <fieldset>
              <legend className="text-text-primary font-semibold text-base mb-4">
                Seu Veiculo
              </legend>
              <div className="space-y-4">
                {/* Tipo de veiculo */}
                <div>
                  <label
                    htmlFor="mf-vehicle"
                    className="block text-sm font-medium text-text-secondary mb-1"
                  >
                    Tipo de veiculo{" "}
                    <span className="text-text-muted text-xs font-normal">
                      (opcional)
                    </span>
                  </label>
                  <select
                    id="mf-vehicle"
                    value={form.vehicleType}
                    onChange={(e) =>
                      handleChange(
                        "vehicleType",
                        e.target.value as VehicleType | ""
                      )
                    }
                    className="input-field rounded-xl"
                  >
                    <option value="">Selecione</option>
                    <option value="MOTO">Moto</option>
                    <option value="CARRO">Carro</option>
                    <option value="VAN">Van</option>
                  </select>
                </div>

                {/* Placa */}
                <div>
                  <label
                    htmlFor="mf-plate"
                    className="block text-sm font-medium text-text-secondary mb-1"
                  >
                    Placa{" "}
                    <span aria-hidden="true" className="text-red-500">
                      *
                    </span>
                    <span className="sr-only">(obrigatoria)</span>
                  </label>
                  <input
                    id="mf-plate"
                    type="text"
                    inputMode="text"
                    autoCapitalize="characters"
                    autoComplete="off"
                    placeholder="ABC1D23"
                    maxLength={8}
                    value={form.licensePlate}
                    onChange={(e) =>
                      handleChange(
                        "licensePlate",
                        maskPlate(e.target.value)
                      )
                    }
                    onBlur={() => handleBlur("licensePlate")}
                    className="input-field rounded-xl font-mono"
                    aria-required="true"
                    aria-invalid={
                      !!(errors.licensePlate && touched.licensePlate)
                    }
                    aria-describedby={
                      errors.licensePlate && touched.licensePlate
                        ? "err-plate"
                        : undefined
                    }
                  />
                  {errors.licensePlate && touched.licensePlate && (
                    <p
                      id="err-plate"
                      role="alert"
                      className="text-red-600 text-xs mt-1"
                    >
                      {errors.licensePlate}
                    </p>
                  )}
                </div>
              </div>
            </fieldset>

            {/* Secao: Recebimento PIX */}
            <fieldset>
              <legend className="text-text-primary font-semibold text-base mb-4">
                Recebimento (PIX)
              </legend>
              <div className="space-y-4">
                {/* Tipo de chave */}
                <div>
                  <label
                    htmlFor="mf-pix-type"
                    className="block text-sm font-medium text-text-secondary mb-1"
                  >
                    Tipo de chave{" "}
                    <span aria-hidden="true" className="text-red-500">
                      *
                    </span>
                    <span className="sr-only">(obrigatorio)</span>
                  </label>
                  <select
                    id="mf-pix-type"
                    value={form.pixKeyType}
                    onChange={(e) => {
                      handleChange(
                        "pixKeyType",
                        e.target.value as PixKeyType | ""
                      );
                      handleChange("pixKey", "");
                    }}
                    onBlur={() => handleBlur("pixKeyType")}
                    className="input-field rounded-xl"
                    aria-required="true"
                    aria-invalid={
                      !!(errors.pixKeyType && touched.pixKeyType)
                    }
                    aria-describedby={
                      errors.pixKeyType && touched.pixKeyType
                        ? "err-pix-type"
                        : undefined
                    }
                  >
                    <option value="">Selecione</option>
                    <option value="CPF">CPF</option>
                    <option value="TELEFONE">Telefone</option>
                    <option value="EMAIL">E-mail</option>
                    <option value="ALEATORIA">Chave aleatoria</option>
                  </select>
                  {errors.pixKeyType && touched.pixKeyType && (
                    <p
                      id="err-pix-type"
                      role="alert"
                      className="text-red-600 text-xs mt-1"
                    >
                      {errors.pixKeyType}
                    </p>
                  )}
                </div>

                {/* Chave PIX */}
                <div>
                  <label
                    htmlFor="mf-pix-key"
                    className="block text-sm font-medium text-text-secondary mb-1"
                  >
                    Chave PIX{" "}
                    <span aria-hidden="true" className="text-red-500">
                      *
                    </span>
                    <span className="sr-only">(obrigatoria)</span>
                  </label>
                  <input
                    id="mf-pix-key"
                    type={form.pixKeyType === "EMAIL" ? "email" : "text"}
                    inputMode={
                      form.pixKeyType === "CPF" ||
                      form.pixKeyType === "TELEFONE"
                        ? "numeric"
                        : "text"
                    }
                    autoComplete="off"
                    placeholder={
                      form.pixKeyType === "CPF"
                        ? "000.000.000-00"
                        : form.pixKeyType === "TELEFONE"
                        ? "(00) 00000-0000"
                        : form.pixKeyType === "EMAIL"
                        ? "seu@email.com"
                        : form.pixKeyType === "ALEATORIA"
                        ? "Cole sua chave aleatoria"
                        : "Selecione o tipo acima"
                    }
                    value={form.pixKey}
                    onChange={(e) => handlePixKeyChange(e.target.value)}
                    onBlur={() => handleBlur("pixKey")}
                    disabled={!form.pixKeyType}
                    className="input-field rounded-xl disabled:opacity-50 disabled:cursor-not-allowed"
                    aria-required="true"
                    aria-invalid={!!(errors.pixKey && touched.pixKey)}
                    aria-describedby={
                      errors.pixKey && touched.pixKey
                        ? "err-pix-key"
                        : undefined
                    }
                  />
                  {errors.pixKey && touched.pixKey && (
                    <p
                      id="err-pix-key"
                      role="alert"
                      className="text-red-600 text-xs mt-1"
                    >
                      {errors.pixKey}
                    </p>
                  )}
                </div>
              </div>
            </fieldset>

            {/* Termos */}
            <div>
              <label className="flex items-start gap-3 cursor-pointer">
                <input
                  type="checkbox"
                  checked={form.acceptedTerms}
                  onChange={(e) => {
                    handleChange("acceptedTerms", e.target.checked);
                    handleBlur("acceptedTerms");
                  }}
                  className="mt-0.5 h-5 w-5 shrink-0 accent-primary-700 cursor-pointer"
                  aria-required="true"
                  aria-invalid={
                    !!(errors.acceptedTerms && touched.acceptedTerms)
                  }
                  aria-describedby={
                    errors.acceptedTerms && touched.acceptedTerms
                      ? "err-terms"
                      : undefined
                  }
                />
                <span className="text-sm text-text-secondary select-none">
                  Li e aceito os{" "}
                  <button
                    type="button"
                    onClick={() => setShowTerms(true)}
                    className="text-primary-700 underline underline-offset-2 font-medium"
                  >
                    termos de uso e privacidade
                  </button>
                </span>
              </label>
              {errors.acceptedTerms && touched.acceptedTerms && (
                <p
                  id="err-terms"
                  role="alert"
                  className="text-red-600 text-xs mt-2 ml-8"
                >
                  {errors.acceptedTerms}
                </p>
              )}
            </div>

            {/* Botao de submit */}
            <button
              type="submit"
              disabled={submitting}
              aria-busy={submitting}
              className="btn-primary w-full min-h-12 rounded-xl text-base font-semibold"
            >
              {submitting ? "Enviando..." : "Concluir Cadastro"}
            </button>
          </form>

          {/* Rodape LGPD */}
          <p className="text-text-muted text-xs text-center mt-8 leading-relaxed pb-6">
            Seus dados sao protegidos pela LGPD (Lei 13.709/2018).
          </p>
        </div>
      </main>
    </>
  );
}
