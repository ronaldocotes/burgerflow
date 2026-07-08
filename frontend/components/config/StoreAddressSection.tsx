"use client";

import { useCallback, useId, useRef, useState } from "react";
import dynamic from "next/dynamic";
import { Lock } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import { formatCep, cepDigits, lookupCep } from "@/lib/cep";
import type { StoreConfigFull } from "@/types/store-config";
import { ConfigSection, SaveButton } from "./ConfigSection";

// Mapa carregado so no cliente (Leaflet toca window/document => ssr:false).
const MapPinPicker = dynamic(() => import("./MapPinPicker"), {
  ssr: false,
  loading: () => (
    <div className="flex h-64 w-full items-center justify-center rounded-xl border border-border-light bg-bg-secondary text-sm text-text-muted">
      Carregando mapa...
    </div>
  ),
});

// Centro default do mapa quando ainda nao ha pin nem geocode (Macapa-AP).
const DEFAULT_CENTER: [number, number] = [-0.0348, -51.0664];

interface Props {
  config: StoreConfigFull;
  showToast: (msg: string, type?: "success" | "error") => void;
}

export function StoreAddressSection({ config, showToast }: Props) {
  const uid = useId();
  const [cepDisplay, setCepDisplay] = useState(formatCep(config.postalCode ?? ""));
  const [street, setStreet] = useState(config.street ?? "");
  const [streetNumber, setStreetNumber] = useState(config.streetNumber ?? "");
  const [complement, setComplement] = useState(config.addressComplement ?? "");
  const [neighborhood, setNeighborhood] = useState(config.neighborhood ?? "");
  const [city, setCity] = useState(config.merchantCity ?? "");
  const [uf, setUf] = useState(config.stateUf ?? "");
  const [lat, setLat] = useState<number | null>(config.restaurantLat);
  const [lng, setLng] = useState<number | null>(config.restaurantLng);
  const [cityUnlocked, setCityUnlocked] = useState(false);

  const [lookingUp, setLookingUp] = useState(false);
  const [cepError, setCepError] = useState<string | null>(null);
  const [geocoding, setGeocoding] = useState(false);
  const [mapError, setMapError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const abortRef = useRef<AbortController | null>(null);

  const zip = cepDigits(cepDisplay);

  const doLookup = useCallback(async () => {
    if (zip.length !== 8) return;
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    setLookingUp(true);
    setCepError(null);
    try {
      const a = await lookupCep(zip, ctrl.signal);
      setStreet(a.street || street);
      setNeighborhood(a.neighborhood || neighborhood);
      setCity(a.city || city);
      setUf(a.uf || uf);
    } catch (err) {
      if ((err as Error).name === "AbortError") return;
      setCepError("CEP nao encontrado — preencha manualmente.");
    } finally {
      setLookingUp(false);
    }
  }, [zip, street, neighborhood, city, uf]);

  // Geocodifica o endereco atual via Nominatim/OSM (grátis; 1 req/s => so no clique).
  const geocode = useCallback(async () => {
    const parts = [
      [street, streetNumber].filter(Boolean).join(" "),
      neighborhood,
      city,
      uf,
      "Brasil",
    ].filter(Boolean);
    if (parts.length < 2) {
      setMapError("Preencha o endereco antes de localizar no mapa.");
      return;
    }
    setGeocoding(true);
    setMapError(null);
    try {
      const q = encodeURIComponent(parts.join(", "));
      const res = await fetch(
        `https://nominatim.openstreetmap.org/search?format=json&limit=1&q=${q}`,
        { headers: { "Accept-Language": "pt-BR" } },
      );
      if (!res.ok) throw new Error("geocode_failed");
      const data = (await res.json()) as Array<{ lat: string; lon: string }>;
      if (data.length === 0) {
        setMapError("Endereco nao localizado no mapa — ajuste o pin manualmente.");
        return;
      }
      setLat(Number(data[0].lat));
      setLng(Number(data[0].lon));
    } catch {
      setMapError("Mapa indisponivel agora — voce pode ajustar o pin depois.");
    } finally {
      setGeocoding(false);
    }
  }, [street, streetNumber, neighborhood, city, uf]);

  async function handleSave(e: React.FormEvent) {
    e.preventDefault();
    if (saving) return;
    setSaving(true);
    try {
      await api.patch("/config", {
        autoAcceptOrders: config.autoAcceptOrders, // passthrough obrigatorio do PATCH
        postalCode: zip || null,
        street: street.trim() || null,
        streetNumber: streetNumber.trim() || null,
        addressComplement: complement.trim() || null,
        neighborhood: neighborhood.trim() || null,
        merchantCity: city.trim() || null,
        stateUf: uf.trim().toUpperCase() || null,
        restaurantLat: lat,
        restaurantLng: lng,
      });
      showToast("Endereco salvo", "success");
    } catch (err) {
      showToast(err instanceof ApiError ? err.message : "Erro ao salvar endereco.", "error");
    } finally {
      setSaving(false);
    }
  }

  const fallbackCenter: [number, number] = lat != null && lng != null ? [lat, lng] : DEFAULT_CENTER;

  return (
    <ConfigSection
      id="endereco"
      title="Endereco da loja"
      description="Usado no cardapio publico, no raio de entrega e na rota do motoboy."
    >
      <form onSubmit={(e) => void handleSave(e)} className="space-y-4">
        {/* CEP */}
        <div className="form-group">
          <label className="form-label" htmlFor={uid + "-cep"}>
            CEP <span className="text-error" aria-hidden="true">*</span>
            <span className="sr-only">(obrigatorio)</span>
          </label>
          <input
            id={uid + "-cep"}
            type="text"
            inputMode="numeric"
            className={"input-field w-40" + (cepError ? " border-red-500" : "")}
            placeholder="00000-000"
            value={cepDisplay}
            onChange={(e) => {
              setCepDisplay(formatCep(e.target.value));
              setCepError(null);
            }}
            onBlur={() => void doLookup()}
            maxLength={9}
            autoComplete="postal-code"
            disabled={lookingUp}
            aria-invalid={!!cepError}
            aria-describedby={cepError ? uid + "-cep-error" : undefined}
            aria-busy={lookingUp}
          />
          <span className="ml-2 text-xs text-text-muted">busca automatica ao sair do campo</span>
          {cepError && (
            <p id={uid + "-cep-error"} className="mt-1 text-xs text-error" role="alert">
              {cepError}
            </p>
          )}
        </div>

        {/* Rua */}
        <div className="form-group">
          <label className="form-label" htmlFor={uid + "-street"}>
            Rua <span className="text-error" aria-hidden="true">*</span>
            <span className="sr-only">(obrigatorio)</span>
          </label>
          <input
            id={uid + "-street"}
            type="text"
            className="input-field"
            value={street}
            onChange={(e) => setStreet(e.target.value)}
            disabled={lookingUp}
            aria-busy={lookingUp}
            autoComplete="street-address"
          />
        </div>

        {/* Numero + complemento */}
        <div className="grid grid-cols-2 gap-3">
          <div className="form-group">
            <label className="form-label" htmlFor={uid + "-number"}>
              Numero <span className="text-error" aria-hidden="true">*</span>
              <span className="sr-only">(obrigatorio)</span>
            </label>
            <input
              id={uid + "-number"}
              type="text"
              inputMode="numeric"
              className="input-field"
              value={streetNumber}
              onChange={(e) => setStreetNumber(e.target.value)}
            />
          </div>
          <div className="form-group">
            <label className="form-label" htmlFor={uid + "-complement"}>
              Complemento <span className="text-xs text-text-muted">(opcional)</span>
            </label>
            <input
              id={uid + "-complement"}
              type="text"
              className="input-field"
              placeholder="Sala, bloco..."
              value={complement}
              onChange={(e) => setComplement(e.target.value)}
            />
          </div>
        </div>

        {/* Bairro */}
        <div className="form-group">
          <label className="form-label" htmlFor={uid + "-neighborhood"}>
            Bairro <span className="text-error" aria-hidden="true">*</span>
            <span className="sr-only">(obrigatorio)</span>
          </label>
          <input
            id={uid + "-neighborhood"}
            type="text"
            className="input-field"
            value={neighborhood}
            onChange={(e) => setNeighborhood(e.target.value)}
            disabled={lookingUp}
            aria-busy={lookingUp}
          />
        </div>

        {/* Cidade + UF (readonly com escape hatch) */}
        <div className="grid grid-cols-[1fr_5rem] gap-3">
          <div className="form-group">
            <label className="form-label flex items-center gap-1" htmlFor={uid + "-city"}>
              Cidade
              {!cityUnlocked && <Lock className="h-3 w-3 text-text-muted" aria-hidden="true" />}
            </label>
            <input
              id={uid + "-city"}
              type="text"
              className={
                "input-field" +
                (cityUnlocked ? "" : " bg-bg-secondary text-text-muted cursor-default")
              }
              value={city}
              onChange={(e) => setCity(e.target.value)}
              readOnly={!cityUnlocked}
              aria-busy={lookingUp}
            />
          </div>
          <div className="form-group">
            <label className="form-label flex items-center gap-1" htmlFor={uid + "-uf"}>
              UF
              {!cityUnlocked && <Lock className="h-3 w-3 text-text-muted" aria-hidden="true" />}
            </label>
            <input
              id={uid + "-uf"}
              type="text"
              maxLength={2}
              className={
                "input-field uppercase" +
                (cityUnlocked ? "" : " bg-bg-secondary text-text-muted cursor-default")
              }
              value={uf}
              onChange={(e) => setUf(e.target.value.toUpperCase())}
              readOnly={!cityUnlocked}
            />
          </div>
        </div>
        {!cityUnlocked && (
          <button
            type="button"
            className="text-xs text-primary-700 underline"
            onClick={() => setCityUnlocked(true)}
          >
            Editar cidade/UF mesmo assim
          </button>
        )}

        {/* Mapa */}
        <div className="pt-2">
          <p className="mb-2 text-sm font-semibold text-text-primary">Posicao no mapa</p>
          <MapPinPicker
            lat={lat}
            lng={lng}
            fallbackCenter={fallbackCenter}
            onChange={(la, ln) => {
              setLat(la);
              setLng(ln);
            }}
          />
          <p className="mt-1 text-xs text-text-muted">
            Toque no mapa ou arraste o pin para ajustar a posicao exata.
          </p>
          <div className="mt-2 flex flex-wrap items-center gap-3">
            <p className="text-sm text-text-secondary" role="status">
              {lat != null && lng != null
                ? `Lat: ${lat.toFixed(5)} · Lng: ${lng.toFixed(5)}`
                : "Posicione o pin no seu endereco."}
            </p>
            <button
              type="button"
              className="btn-outline min-h-11 text-sm"
              onClick={() => void geocode()}
              disabled={geocoding}
            >
              {geocoding ? "Localizando..." : "Localizar pelo endereco"}
            </button>
          </div>
          {mapError && (
            <p className="mt-1 text-xs text-warning-dark" role="status">
              {mapError}
            </p>
          )}
        </div>

        <div className="flex justify-end pt-2">
          <SaveButton saving={saving} label="Salvar endereco" />
        </div>
      </form>
    </ConfigSection>
  );
}
