"use client";

import { useEffect, useMemo } from "react";
import { MapContainer, TileLayer, Marker, Circle, useMapEvents, useMap } from "react-leaflet";
import L from "leaflet";
import "leaflet/dist/leaflet.css";

// Mapa de segmentacao geografica da campanha de anuncio (Fase 8.2): pin arrastavel
// (centro do publico) + circulo do raio em km. Carregado via next/dynamic ssr:false
// pelo CampaignWizard (Leaflet toca window/document). Clique no mapa reposiciona o
// pin — alternativa de ponteiro unico da WCAG 2.5.7 (mesmo padrao do MapPinPicker).

interface Props {
  lat: number | null;
  lng: number | null;
  radiusKm: number;
  /** Centro usado quando ainda nao ha pin (ex.: endereco do restaurante). */
  fallbackCenter: [number, number];
  onChange: (lat: number, lng: number) => void;
}

// Icone proprio (divIcon SVG) para nao depender dos assets de marker do Leaflet,
// que quebram com bundlers (mesmo padrao do MapPinPicker do Minha Loja).
const pinIcon = L.divIcon({
  className: "",
  html:
    '<svg xmlns="http://www.w3.org/2000/svg" width="32" height="40" viewBox="0 0 24 32" aria-hidden="true">' +
    '<path fill="#047857" stroke="#ffffff" stroke-width="1.5" d="M12 1C6.5 1 2 5.5 2 11c0 7 10 19 10 19s10-12 10-19c0-5.5-4.5-10-10-10z"/>' +
    '<circle cx="12" cy="11" r="3.5" fill="#ffffff"/></svg>',
  iconSize: [32, 40],
  iconAnchor: [16, 40],
});

/** Zoom aproximado para o circulo caber na altura do mapa. */
function zoomForRadius(radiusKm: number): number {
  if (radiusKm <= 2) return 13;
  if (radiusKm <= 5) return 12;
  if (radiusKm <= 10) return 11;
  if (radiusKm <= 20) return 10;
  if (radiusKm <= 40) return 9;
  return 8;
}

function ClickToPlace({ onChange }: { onChange: (lat: number, lng: number) => void }) {
  useMapEvents({
    click(e) {
      onChange(e.latlng.lat, e.latlng.lng);
    },
  });
  return null;
}

// Recentraliza/reenquadra quando o pin ou o raio mudam por fora (paste de coordenadas,
// prefill do endereco da loja, campo de raio).
function Recenter({ center, zoom }: { center: [number, number]; zoom: number }) {
  const map = useMap();
  useEffect(() => {
    map.setView(center, zoom, { animate: true });
  }, [center, zoom, map]);
  return null;
}

export default function GeoRadiusMap({ lat, lng, radiusKm, fallbackCenter, onChange }: Props) {
  const hasPin = lat != null && lng != null;
  const center = useMemo<[number, number]>(
    () => (hasPin ? [lat as number, lng as number] : fallbackCenter),
    [hasPin, lat, lng, fallbackCenter],
  );
  const zoom = hasPin ? zoomForRadius(radiusKm) : 4;

  return (
    <div
      role="application"
      aria-label="Mapa para escolher o centro e o raio do publico do anuncio"
      className="h-64 w-full overflow-hidden rounded-xl border border-border-light"
    >
      <MapContainer
        center={center}
        zoom={zoom}
        scrollWheelZoom={false}
        style={{ height: "100%", width: "100%" }}
      >
        <TileLayer
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
          url="https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        />
        <ClickToPlace onChange={onChange} />
        <Recenter center={center} zoom={zoom} />
        {hasPin && (
          <>
            <Circle
              center={[lat as number, lng as number]}
              radius={radiusKm * 1000}
              pathOptions={{ color: "#047857", weight: 2, fillColor: "#047857", fillOpacity: 0.12 }}
            />
            <Marker
              position={[lat as number, lng as number]}
              icon={pinIcon}
              draggable
              eventHandlers={{
                dragend(e) {
                  const p = (e.target as L.Marker).getLatLng();
                  onChange(p.lat, p.lng);
                },
              }}
            />
          </>
        )}
      </MapContainer>
    </div>
  );
}
