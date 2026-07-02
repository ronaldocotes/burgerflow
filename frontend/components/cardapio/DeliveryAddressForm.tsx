"use client";

import { useId, useRef, useState } from "react";
import { Lock } from "lucide-react";

export interface DeliveryAddress {
  zip: string;
  street: string;
  number: string;
  complement: string;
  neighborhood: string;
  city: string;
  reference: string;
}

export const EMPTY_DELIVERY_ADDRESS: DeliveryAddress = {
  zip: "",
  street: "",
  number: "",
  complement: "",
  neighborhood: "",
  city: "",
  reference: "",
};

export interface DeliveryFieldErrors {
  zip?: string;
  street?: string;
  number?: string;
  neighborhood?: string;
}

interface ViaCepResult {
  logradouro?: string;
  bairro?: string;
  localidade?: string;
  erro?: boolean;
}

interface Props {
  value: DeliveryAddress;
  onChange: (addr: DeliveryAddress) => void;
  errors?: DeliveryFieldErrors;
}

function formatCep(raw: string): string {
  const digits = raw.replace(/\D/g, "").slice(0, 8);
  if (digits.length > 5) return digits.slice(0, 5) + "-" + digits.slice(5);
  return digits;
}

export function DeliveryAddressForm({ value, onChange, errors = {} }: Props) {
  const uid = useId();

  const [cepDisplay, setCepDisplay] = useState<string>(
    value.zip.length === 8
      ? value.zip.slice(0, 5) + "-" + value.zip.slice(5)
      : value.zip,
  );
  const [lookingUp, setLookingUp] = useState(false);
  const [cepLookupError, setCepLookupError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  function update(field: keyof DeliveryAddress, val: string) {
    onChange({ ...value, [field]: val });
  }

  function handleCepInput(raw: string) {
    const formatted = formatCep(raw);
    setCepDisplay(formatted);
    setCepLookupError(null);
    onChange({ ...value, zip: formatted.replace(/\D/g, "") });
  }

  async function lookupCep() {
    const digits = value.zip.replace(/\D/g, "");
    if (digits.length !== 8) return;
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    setLookingUp(true);
    setCepLookupError(null);
    try {
      const res = await fetch(
        "https://viacep.com.br/ws/" + digits + "/json/",
        { signal: ctrl.signal },
      );
      if (!res.ok) throw new Error("not_found");
      const data = (await res.json()) as ViaCepResult;
      if (data.erro) throw new Error("not_found");
      onChange({
        ...value,
        zip: digits,
        street: data.logradouro ?? value.street,
        neighborhood: data.bairro ?? value.neighborhood,
        city: data.localidade ?? value.city,
      });
    } catch (err) {
      if ((err as Error).name === "AbortError") return;
      setCepLookupError("CEP nao encontrado. Verifique e tente novamente.");
      onChange({ ...value, zip: digits, street: "", neighborhood: "", city: "" });
    } finally {
      setLookingUp(false);
    }
  }

  const zipHasError = !!(errors.zip || cepLookupError);
  return (
    <fieldset className="border-0 p-0 m-0">
      <legend className="sr-only">Endereco de entrega</legend>

      <div className="form-group">
        <label className="form-label" htmlFor={uid + "-zip"}>
          CEP{" "}
          <span className="text-error" aria-hidden="true">*</span>
          <span className="sr-only">(obrigatorio)</span>
        </label>
        <div className="relative">
          <input
            id={uid + "-zip"}
            type="text"
            inputMode="numeric"
            className={
              "input-field pr-10" +
              (zipHasError ? " border-red-500" : "") +
              (lookingUp ? " opacity-60" : "")
            }
            placeholder="00000-000"
            value={cepDisplay}
            onChange={(e) => handleCepInput(e.target.value)}
            onBlur={() => { void lookupCep(); }}
            autoComplete="postal-code"
            maxLength={9}
            disabled={lookingUp}
            aria-invalid={zipHasError}
            aria-describedby={
              cepLookupError
                ? uid + "-cep-lookup-error"
                : errors.zip
                ? uid + "-zip-error"
                : undefined
            }
          />
          {lookingUp && (
            <span
              className="absolute right-3 top-1/2 -translate-y-1/2 pointer-events-none"
              aria-label="Buscando CEP..."
            >
              <svg
                className="animate-spin h-4 w-4 text-text-muted"
                viewBox="0 0 24 24"
                fill="none"
                aria-hidden="true"
              >
                <circle
                  className="opacity-25"
                  cx="12"
                  cy="12"
                  r="10"
                  stroke="currentColor"
                  strokeWidth="4"
                />
                <path
                  className="opacity-75"
                  fill="currentColor"
                  d="M4 12a8 8 0 018-8v8H4z"
                />
              </svg>
            </span>
          )}
        </div>
        {cepLookupError && (
          <p
            id={uid + "-cep-lookup-error"}
            className="text-error text-xs mt-1"
            role="alert"
          >
            {cepLookupError}
          </p>
        )}
        {errors.zip && !cepLookupError && (
          <p id={uid + "-zip-error"} className="text-error text-xs mt-1" role="alert">
            {errors.zip}
          </p>
        )}
      </div>

      <div className="form-group">
        <label className="form-label" htmlFor={uid + "-street"}>
          Rua{" "}
          <span className="text-error" aria-hidden="true">*</span>
          <span className="sr-only">(obrigatorio)</span>
        </label>
        <input
          id={uid + "-street"}
          type="text"
          className={"input-field" + (errors.street ? " border-red-500" : "")}
          placeholder="Nome da rua, avenida, travessa..."
          value={value.street}
          onChange={(e) => update("street", e.target.value)}
          autoComplete="street-address"
          disabled={lookingUp}
          aria-invalid={!!errors.street}
          aria-describedby={errors.street ? uid + "-street-error" : undefined}
        />
        {errors.street && (
          <p id={uid + "-street-error"} className="text-error text-xs mt-1" role="alert">
            {errors.street}
          </p>
        )}
      </div>

      <div className="grid grid-cols-2 gap-3">
        <div className="form-group">
          <label className="form-label" htmlFor={uid + "-number"}>
            Numero{" "}
            <span className="text-error" aria-hidden="true">*</span>
            <span className="sr-only">(obrigatorio)</span>
          </label>
          <input
            id={uid + "-number"}
            type="text"
            inputMode="numeric"
            className={"input-field" + (errors.number ? " border-red-500" : "")}
            placeholder="123"
            value={value.number}
            onChange={(e) => update("number", e.target.value)}
            autoComplete="address-line2"
            disabled={lookingUp}
            aria-invalid={!!errors.number}
            aria-describedby={errors.number ? uid + "-number-error" : undefined}
          />
          {errors.number && (
            <p id={uid + "-number-error"} className="text-error text-xs mt-1" role="alert">
              {errors.number}
            </p>
          )}
        </div>
        <div className="form-group">
          <label className="form-label" htmlFor={uid + "-complement"}>
            Complemento{" "}
            <span className="text-text-muted text-xs">(opcional)</span>
          </label>
          <input
            id={uid + "-complement"}
            type="text"
            className="input-field"
            placeholder="Apto, bloco..."
            value={value.complement}
            onChange={(e) => update("complement", e.target.value)}
            autoComplete="address-line3"
            disabled={lookingUp}
          />
        </div>
      </div>

      <div className="form-group">
        <label className="form-label" htmlFor={uid + "-neighborhood"}>
          Bairro{" "}
          <span className="text-error" aria-hidden="true">*</span>
          <span className="sr-only">(obrigatorio)</span>
        </label>
        <input
          id={uid + "-neighborhood"}
          type="text"
          className={"input-field" + (errors.neighborhood ? " border-red-500" : "")}
          placeholder="Nome do bairro"
          value={value.neighborhood}
          onChange={(e) => update("neighborhood", e.target.value)}
          autoComplete="address-level3"
          disabled={lookingUp}
          aria-invalid={!!errors.neighborhood}
          aria-describedby={errors.neighborhood ? uid + "-neighborhood-error" : undefined}
        />
        {errors.neighborhood && (
          <p id={uid + "-neighborhood-error"} className="text-error text-xs mt-1" role="alert">
            {errors.neighborhood}
          </p>
        )}
      </div>

      <div className="form-group">
        <label className="form-label flex items-center gap-1" htmlFor={uid + "-city"}>
          Cidade
          <Lock className="h-3 w-3 text-text-muted" aria-hidden="true" />
        </label>
        <input
          id={uid + "-city"}
          type="text"
          className="input-field bg-bg-secondary text-text-muted cursor-default"
          value={value.city}
          placeholder="Preenchida automaticamente pelo CEP"
          readOnly
          tabIndex={-1}
        />
      </div>

      <div className="form-group">
        <label className="form-label" htmlFor={uid + "-reference"}>
          Referencia{" "}
          <span className="text-text-muted text-xs">(opcional)</span>
        </label>
        <input
          id={uid + "-reference"}
          type="text"
          className="input-field"
          placeholder="Ex: portao azul, proximo a padaria..."
          value={value.reference}
          onChange={(e) => update("reference", e.target.value)}
        />
      </div>
    </fieldset>
  );
}
