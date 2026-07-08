"use client";

import { useEffect, useMemo } from "react";
import { MapContainer, TileLayer, Marker, useMapEvents, useMap } from "react-leaflet";
import L from "leaflet";
import "leaflet/dist/leaflet.css";

// Selecao do pin da loja no mapa (issue #7). Carregado via next/dynamic ssr:false
// pelo StoreAddressSection (Leaflet toca window/document). Pin arrastavel E clique
// no mapa reposicionam — o clique e a alternativa de ponteiro unico da WCAG 2.5.7.

interface Props {
  lat: number | null;
  lng: number | null;
  /** Centro usado quando ainda nao ha pin (ex.: centro da cidade). */
  fallbackCenter: [number, number];
  onChange: (lat: number, lng: number) => void;
}

// Icone proprio (divIcon SVG) para nao depender dos assets de marker do Leaflet,
// que quebram com bundlers. 32x40 — passa na excecao "Essential" de pins de mapa.
const pinIcon = L.divIcon({
  className: "",
  html:
    '<svg xmlns="http://www.w3.org/2000/svg" width="32" height="40" viewBox="0 0 24 32" aria-hidden="true">' +
    '<path fill="#b91c1c" stroke="#ffffff" stroke-width="1.5" d="M12 1C6.5 1 2 5.5 2 11c0 7 10 19 10 19s10-12 10-19c0-5.5-4.5-10-10-10z"/>' +
    '<circle cx="12" cy="11" r="3.5" fill="#ffffff"/></svg>',
  iconSize: [32, 40],
  iconAnchor: [16, 40],
});

function ClickToPlace({ onChange }: { onChange: (lat: number, lng: number) => void }) {
  useMapEvents({
    click(e) {
      onChange(e.latlng.lat, e.latlng.lng);
    },
  });
  return null;
}

// Recentraliza o mapa quando o pin muda por fora (ex.: geocode "Localizar pelo endereco").
function Recenter({ center }: { center: [number, number] }) {
  const map = useMap();
  useEffect(() => {
    map.setView(center, map.getZoom(), { animate: true });
  }, [center, map]);
  return null;
}

export default function MapPinPicker({ lat, lng, fallbackCenter, onChange }: Props) {
  const hasPin = lat != null && lng != null;
  const center = useMemo<[number, number]>(
    () => (hasPin ? [lat as number, lng as number] : fallbackCenter),
    [hasPin, lat, lng, fallbackCenter],
  );

  return (
    <div
      role="application"
      aria-label="Mapa para ajustar a posicao da loja"
      className="h-64 w-full overflow-hidden rounded-xl border border-border-light"
    >
      <MapContainer
        center={center}
        zoom={16}
        scrollWheelZoom={false}
        style={{ height: "100%", width: "100%" }}
      >
        <TileLayer
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
          url="https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        />
        <ClickToPlace onChange={onChange} />
        <Recenter center={center} />
        {hasPin && (
          <Marker
            position={[lat as number, lng as number]}
            icon={pinIcon}
            draggable
            eventHandlers={{
              dragend(e) {
                const p = e.target.getLatLng();
                onChange(p.lat, p.lng);
              },
            }}
          />
        )}
      </MapContainer>
    </div>
  );
}
