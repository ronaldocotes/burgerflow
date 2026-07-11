"use client";

import { useEffect, useMemo } from "react";
import { MapContainer, TileLayer, Marker, Circle, useMap } from "react-leaflet";
import L from "leaflet";
import "leaflet/dist/leaflet.css";

// Mapa das zonas de entrega por raio (issue #2): pin fixo no restaurante + N círculos
// concêntricos (um por anel), cada um com cor própria e uma etiqueta na borda norte
// (raio + preço). Carregado via next/dynamic ssr:false pela página (Leaflet toca
// window/document) — mesmo padrão do GeoRadiusMap/MapPinPicker, estendido de 1 para
// N círculos. Somente leitura: os raios são editados no formulário ao lado e o mapa
// reflete ao vivo.

export interface ZoneRing {
  radiusKm: number;
  color: string;
  /** Texto da etiqueta na borda do anel (ex.: "Até 2 km · R$ 8,00"). */
  label: string;
}

interface Props {
  center: [number, number];
  rings: ZoneRing[];
}

// Ícone próprio (divIcon SVG) para não depender dos assets de marker do Leaflet,
// que quebram com bundlers (mesmo padrão do MapPinPicker do Minha Loja).
const storeIcon = L.divIcon({
  className: "",
  html:
    '<svg xmlns="http://www.w3.org/2000/svg" width="32" height="40" viewBox="0 0 24 32" aria-hidden="true">' +
    '<path fill="#047857" stroke="#ffffff" stroke-width="1.5" d="M12 1C6.5 1 2 5.5 2 11c0 7 10 19 10 19s10-12 10-19c0-5.5-4.5-10-10-10z"/>' +
    '<circle cx="12" cy="11" r="3.5" fill="#ffffff"/></svg>',
  iconSize: [32, 40],
  iconAnchor: [16, 40],
});

// Etiquetas vêm de nome digitado pelo admin — escapar antes de injetar no divIcon.
function escapeHtml(s: string): string {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function ringLabelIcon(text: string, color: string): L.DivIcon {
  return L.divIcon({
    className: "",
    html:
      `<span style="display:inline-block;transform:translate(-50%,-50%);white-space:nowrap;` +
      `background:#ffffff;color:#1f2937;border:2px solid ${color};border-radius:9999px;` +
      `padding:2px 8px;font-size:11px;font-weight:600;box-shadow:0 1px 3px rgba(0,0,0,.25)">` +
      `${escapeHtml(text)}</span>`,
    iconSize: undefined,
    iconAnchor: [0, 0],
  });
}

/** Graus de latitude por km (aprox. suficiente para posicionar a etiqueta na borda). */
const DEG_PER_KM_LAT = 1 / 110.574;

// Reenquadra para o maior anel caber no mapa sempre que os raios mudam.
function FitRings({ center, maxRadiusKm }: { center: [number, number]; maxRadiusKm: number }) {
  const map = useMap();
  useEffect(() => {
    if (maxRadiusKm <= 0) {
      map.setView(center, 15, { animate: true });
      return;
    }
    const bounds = L.latLng(center).toBounds(maxRadiusKm * 2 * 1000);
    map.fitBounds(bounds, { padding: [28, 28], animate: true });
  }, [center, maxRadiusKm, map]);
  return null;
}

export default function DeliveryZonesMap({ center, rings }: Props) {
  // Desenha do maior para o menor: anéis internos ficam por cima e visíveis.
  const ordered = useMemo(
    () => [...rings].sort((a, b) => b.radiusKm - a.radiusKm),
    [rings],
  );
  const maxRadiusKm = ordered.length > 0 ? ordered[0].radiusKm : 0;

  return (
    <div
      role="img"
      aria-label={
        rings.length > 0
          ? `Mapa das zonas de entrega: ${rings.map((r) => r.label).join("; ")}`
          : "Mapa da área de entrega, sem zonas configuradas"
      }
      className="h-80 w-full overflow-hidden rounded-xl border border-border-light lg:h-[28rem]"
    >
      <MapContainer
        center={center}
        zoom={14}
        scrollWheelZoom={false}
        style={{ height: "100%", width: "100%" }}
      >
        <TileLayer
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
          url="https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        />
        <FitRings center={center} maxRadiusKm={maxRadiusKm} />
        {ordered.map((ring, i) => (
          <Circle
            key={`${ring.radiusKm}-${i}`}
            center={center}
            radius={ring.radiusKm * 1000}
            pathOptions={{
              color: ring.color,
              weight: 2,
              fillColor: ring.color,
              fillOpacity: 0.08,
            }}
          />
        ))}
        {ordered.map((ring, i) => (
          <Marker
            key={`label-${ring.radiusKm}-${i}`}
            position={[center[0] + ring.radiusKm * DEG_PER_KM_LAT, center[1]]}
            icon={ringLabelIcon(ring.label, ring.color)}
            interactive={false}
            keyboard={false}
          />
        ))}
        <Marker position={center} icon={storeIcon} interactive={false} keyboard={false} />
      </MapContainer>
    </div>
  );
}
