"use client";

interface RestaurantInfo {
  name: string | null;
  logoUrl: string | null;
  coverUrl: string | null;
  address: string | null;
  openingHours: string | null;
}

export function RestaurantHero({ restaurantInfo }: { restaurantInfo: RestaurantInfo }) {
  const { name, logoUrl, coverUrl, address, openingHours } = restaurantInfo;

  return (
    <div className="relative mb-12 bg-bg-primary">
      {/* Imagem de capa */}
      {coverUrl ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={coverUrl}
          alt={name ? `Capa de ${name}` : "Capa do restaurante"}
          className="w-full h-40 object-cover"
        />
      ) : (
        <div className="w-full h-40 bg-gradient-to-br from-primary-700 to-primary-900" />
      )}

      {/* Logo centralizado na juncao capa/conteudo: top-40 = 160px (fim da capa),
          -translate-y-1/2 = sobe 40px (metade do h-20), resultado: logo centrado no limite */}
      <div className="absolute left-1/2 top-40 -translate-x-1/2 -translate-y-1/2 z-10">
        {logoUrl ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={logoUrl}
            alt={name ? `Logo de ${name}` : "Logo do restaurante"}
            className="w-20 h-20 rounded-full border-4 border-white shadow-lg object-cover"
          />
        ) : (
          <div
            className="w-20 h-20 rounded-full border-4 border-white shadow-lg bg-bg-tertiary flex items-center justify-center text-4xl"
            aria-hidden="true"
          >
            🍔
          </div>
        )}
      </div>

      {/* Nome, endereco e horario — pt-12 (48px) acomoda metade do logo (40px) + folga */}
      <div className="pt-12 pb-4 px-4 text-center">
        {name && (
          <h2 className="text-xl font-bold text-text-primary">{name}</h2>
        )}
        {address && (
          <p className="text-sm text-text-secondary mt-1">{address}</p>
        )}
        {openingHours && (
          <span className="inline-block mt-2 bg-green-100 text-green-800 text-xs px-2 py-0.5 rounded-full">
            {openingHours}
          </span>
        )}
      </div>
    </div>
  );
}
