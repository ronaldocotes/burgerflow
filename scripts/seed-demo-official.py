#!/usr/bin/env python3
"""Seed oficial do tenant demo do MenuFlow.

Uso:
  MF_API_URL=https://menuflow.duckdns.org/api/v1 \
  MF_TENANT=demo MF_EMAIL=admin@demo.com MF_PASSWORD='Demo@1234' \
  python scripts/seed-demo-official.py

O script é idempotente por nome de categoria, SKU de produto e nome de insumo.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
import uuid

IDEMPOTENCY_NS = uuid.UUID("6ba7b810-9dad-11d1-80b4-00c04fd430c8")  # DNS namespace
from typing import Any


API_URL = os.getenv("MF_API_URL", "http://localhost:8080/api/v1").rstrip("/")
TENANT = os.getenv("MF_TENANT", "demo")
EMAIL = os.getenv("MF_EMAIL", "admin@demo.com")
PASSWORD = os.getenv("MF_PASSWORD", "Demo@1234")

# (name, description, displayOrder, colorCode)
CATEGORIES = [
    ("Lanches", "", 1, None),
    ("Pizzas", "Pizzas artesanais com massa fresca e muito recheio", 6, None),
    ("Combos da Casa", "Combos completos com burger, acompanhamento e bebida.", 10, "#047857"),
    ("Hamburgueres Artesanais", "Burgers autorais com blend da casa e pao brioche.", 20, "#0f766e"),
    ("Smash Burgers", "Smash prensado na chapa, crosta dourada e muito queijo.", 30, "#dc2626"),
    ("Frango e Vegetarianos", "Opcoes de frango crocante e vegetarianas.", 40, "#16a34a"),
    ("Porcoes e Entradas", "Entradas para compartilhar antes do burger.", 50, "#f59e0b"),
    ("Batatas e Acompanhamentos", "Batatas, aneis de cebola e acompanhamentos.", 60, "#ca8a04"),
    ("Molhos Extras", "Molhos da casa e adicionais avulsos.", 70, "#7c3aed"),
    ("Bebidas", "Refrigerantes, agua, sucos e milkshakes.", 80, "#2563eb"),
    ("Sobremesas", "Doces para fechar o pedido.", 90, "#db2777"),
    ("Promocoes", "Ofertas com preco promocional e alta saida.", 100, "#059669"),
]

# (category, sku, name, description, price, cost, order, featured, image_url, promo_price, promo_start, promo_end)
PRODUCTS = [
    ("Combos da Casa",          "COMBO-CLASSIC",       "Combo Classic",           "Burger Classic + batata P + refrigerante lata.",                                                        3690,  1780,  1,  True,  None,                                                                          3390, "2026-06-01T00:00:00Z", "2026-12-31T23:59:59Z"),
    ("Pizzas",                  "PIZZA-TRAD",           "Pizza Tradicional",       "Inteira ou meia a meia. Escolha tamanho, sabor(es) e borda.",                                           2990,  None,  1,  True,  "https://images.unsplash.com/photo-1565299624946-b28f40a0ae38?w=600&q=80", None, None, None),
    ("Sobremesas",              "PTG001",               "Petit Gateau",            "Bolinho de chocolate com centro derretido quente, servido com sorvete de baunilha e frutas vermelhas",  2800,  None,  2,  False, "https://images.unsplash.com/photo-1511133-48a1de7a5c44?w=600&q=80",       None, None, None),
    ("Pizzas",                  "PIZZA-ESPEC",          "Pizza Especial",          "Ingredientes premium. Meia a meia disponivel. Bordas exclusivas.",                                      4990,  None,  2,  False, "https://images.unsplash.com/photo-1513104890138-7c749659a591?w=600&q=80",  None, None, None),
    ("Combos da Casa",          "COMBO-DUPLO",          "Combo Duplo Cheddar",     "Duplo cheddar + batata bacon cheddar + bebida.",                                                        4990,  2380,  2,  True,  None,                                                                          4590, "2026-06-01T00:00:00Z", "2026-12-31T23:59:59Z"),
    ("Bebidas",                 "MSM001",               "Milk-Shake Morango",      "Milk-shake de morango fresco com sorvete de creme, calda de frutas vermelhas e chantilly",             2200,  None,  3,  False, "https://images.unsplash.com/photo-1579954115545-a95591f28bfc?w=600&q=80",  None, None, None),
    ("Combos da Casa",          "COMBO-FAMILIA",        "Combo Familia MenuFlow",  "4 burgers, 2 batatas grandes, onion rings e 4 bebidas.",                                                14990, 6900,  3,  True,  None,                                                                          None, None, None),
    ("Combos da Casa",          "COMBO-KIDS",           "Combo Kids",              "Mini burger, batata pequena e suco.",                                                                   2990,  1320,  4,  False, None,                                                                          None, None, None),
    ("Combos da Casa",          "COMBO-PIZZA-REFRI",    "Combo Pizza + 2 Refris",  "Pizza Media Tradicional (sabor a escolha) + 2 Coca-Cola Lata. Economia garantida!",                    7990,  None,  5,  True,  "https://images.unsplash.com/photo-1593560708920-61dd98c46a4e?w=600&q=80",  None, None, None),
    ("Lanches",                 "HDG001",               "Hot Dog Gourmet",         "Salsicha artesanal defumada, cebola crispy, queijo derretido, mostarda dijon e ketchup artesanal",     2800,  None,  6,  False, "https://images.unsplash.com/photo-1619538189873-3f511db5fec6?w=600&q=80",  None, None, None),
    ("Bebidas",                 "CLN001",               "Cerveja Long Neck",       "Cerveja long neck 355ml gelada, perfeita para acompanhar o seu lanche favorito",                       1600,  None,  6,  False, "https://images.unsplash.com/photo-1608270586620-248524c67de9?w=600&q=80",  None, None, None),
    ("Hamburgueres Artesanais", "BURGER-CLASSIC",       "MenuFlow Classic",        "Blend 160g, cheddar, alface, tomate, picles e molho especial no brioche.",                             2890,  1260,  10, True,  None,                                                                          None, None, None),
    ("Hamburgueres Artesanais", "BURGER-BACON-BBQ",     "Bacon BBQ",               "Blend 160g, cheddar duplo, bacon crocante, cebola caramelizada e barbecue.",                           3490,  1580,  11, True,  None,                                                                          None, None, None),
    ("Hamburgueres Artesanais", "BURGER-DUPLO-CHEDDAR", "Duplo Cheddar",           "Dois blends 160g, cheddar triplo, picles e molho especial.",                                           4290,  2150,  12, True,  None,                                                                          None, None, None),
    ("Hamburgueres Artesanais", "BURGER-BLUE",          "Blue Cheese",             "Blend 160g, queijo azul, cebola caramelizada e rucula.",                                               3890,  1760,  13, False, None,                                                                          None, None, None),
    ("Hamburgueres Artesanais", "BURGER-JALAPENO",      "Jalapeno Fire",           "Blend 160g, cheddar, jalapeno, maionese picante e cebola roxa.",                                       3690,  1650,  14, False, None,                                                                          None, None, None),
    ("Hamburgueres Artesanais", "BURGER-COSTELA",       "Costela Desfiada",        "Blend 160g, costela desfiada, queijo prato e barbecue.",                                               4490,  2320,  15, True,  None,                                                                          None, None, None),
    ("Smash Burgers",           "SMASH-SIMPLES",        "Smash Simples",           "Smash 90g, cheddar, picles e molho da casa.",                                                          1990,  880,   20, True,  None,                                                                          None, None, None),
    ("Smash Burgers",           "SMASH-DUPLO",          "Smash Duplo",             "Dois smash 90g, cheddar duplo, cebola e molho.",                                                       2790,  1320,  21, True,  None,                                                                          2490, "2026-06-01T00:00:00Z", "2026-12-31T23:59:59Z"),
    ("Smash Burgers",           "SMASH-TRIPLO",         "Smash Triplo",            "Tres smash 90g, cheddar triplo e bacon.",                                                              3690,  1890,  22, True,  None,                                                                          None, None, None),
    ("Smash Burgers",           "SMASH-OKLAHOMA",       "Oklahoma Smash",          "Smash prensado com cebola na chapa, cheddar e picles.",                                                2990,  1390,  23, False, None,                                                                          None, None, None),
    ("Frango e Vegetarianos",   "FRANGO-CRISPY",        "Chicken Crispy",          "Frango empanado crocante, queijo prato, alface e maionese verde.",                                     2990,  1390,  30, False, None,                                                                          None, None, None),
    ("Frango e Vegetarianos",   "VEGGIE-GRAO",          "Veggie Grao-de-bico",     "Burger vegetariano, queijo prato, tomate, alface e molho especial.",                                   3190,  1420,  31, False, None,                                                                          None, None, None),
    ("Porcoes e Entradas",      "PORCAO-DADINHO",       "Dadinho de Tapioca",      "Cubos de tapioca com queijo coalho e geleia agridoce.",                                                2490,  980,   40, False, None,                                                                          None, None, None),
    ("Porcoes e Entradas",      "PORCAO-NUGGETS",       "Nuggets Artesanais",      "10 unidades de frango empanado com molho a escolha.",                                                  2690,  1180,  41, False, None,                                                                          None, None, None),
    ("Batatas e Acompanhamentos","BATATA-P",             "Batata Frita Pequena",    "Porcao individual de batata crocante.",                                                                1490,  520,   50, False, None,                                                                          None, None, None),
    ("Batatas e Acompanhamentos","BATATA-G",             "Batata Frita Grande",     "Porcao grande para compartilhar.",                                                                     2390,  920,   51, True,  None,                                                                          None, None, None),
    ("Batatas e Acompanhamentos","BATATA-CHEDDAR-BACON", "Batata Cheddar e Bacon",  "Batata grande com cheddar cremoso e bacon crocante.",                                                  3190,  1380,  52, True,  None,                                                                          None, None, None),
    ("Batatas e Acompanhamentos","ONION-RINGS",          "Onion Rings",             "Aneis de cebola empanados e sequinhos.",                                                               2290,  850,   53, False, None,                                                                          None, None, None),
    ("Molhos Extras",           "MOLHO-AIOLI",          "Aioli da Casa",           "Pote 40ml de maionese aioli.",                                                                         390,   80,    60, False, None,                                                                          None, None, None),
    ("Molhos Extras",           "MOLHO-BBQ",            "Barbecue Defumado",       "Pote 40ml de barbecue.",                                                                               390,   80,    61, False, None,                                                                          None, None, None),
    ("Molhos Extras",           "MOLHO-CHEDDAR",        "Cheddar Cremoso",         "Pote 40ml de cheddar cremoso.",                                                                        590,   120,   62, False, None,                                                                          None, None, None),
    ("Bebidas",                 "BEB-COCA",             "Coca-Cola Lata 350ml",    "Refrigerante lata gelado.",                                                                            690,   360,   70, False, None,                                                                          None, None, None),
    ("Bebidas",                 "BEB-GUARANA",          "Guarana Lata 350ml",      "Refrigerante lata gelado.",                                                                            650,   330,   71, False, None,                                                                          None, None, None),
    ("Bebidas",                 "BEB-AGUA",             "Agua Mineral 500ml",      "Agua mineral sem gas.",                                                                                490,   180,   72, False, None,                                                                          None, None, None),
    ("Bebidas",                 "BEB-SUCO",             "Suco Natural de Laranja", "Suco feito na hora, 400ml.",                                                                           1190,  420,   73, False, None,                                                                          None, None, None),
    ("Bebidas",                 "BEB-MILKSHAKE",        "Milkshake Chocolate",     "Milkshake cremoso 400ml com calda de chocolate.",                                                      1890,  710,   74, True,  None,                                                                          None, None, None),
    ("Sobremesas",              "SOB-BROWNIE",          "Brownie com Sorvete",     "Brownie quente com sorvete de creme e calda.",                                                         2190,  890,   80, True,  None,                                                                          None, None, None),
    ("Sobremesas",              "SOB-PUDIM",            "Pudim da Casa",           "Pudim cremoso com calda de caramelo.",                                                                 1290,  430,   81, False, None,                                                                          None, None, None),
    ("Promocoes",               "PROMO-SEGUNDA",        "Segunda do Smash",        "Smash duplo com preco especial nas segundas.",                                                         2790,  1320,  90, True,  None,                                                                          2190, "2026-06-01T00:00:00Z", "2026-12-31T23:59:59Z"),
]

INGREDIENTS = [
    ("Blend bovino 160 g", "UNIT", 820, 120, 20),
    ("Pão brioche", "UNIT", 190, 180, 30),
    ("Queijo cheddar", "GRAM", 45, 8000, 1500),
    ("Bacon", "GRAM", 62, 4000, 800),
    ("Batata palito", "KILOGRAM", 890, 35, 8),
    ("Frango empanado", "UNIT", 720, 80, 15),
    ("Burger vegetal", "UNIT", 980, 60, 10),
    ("Mussarela", "GRAM", 48, 9000, 1800),
    ("Calabresa", "GRAM", 39, 5000, 1000),
    ("Refrigerante lata", "UNIT", 320, 180, 36),
    ("Refrigerante 2 L", "UNIT", 760, 80, 20),
    ("Brownie pronto", "UNIT", 620, 50, 10),
]


def request(method: str, path: str, token: str | None = None, body: Any | None = None) -> Any:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if method == "POST" and path == "/products":
        sku = (body or {}).get("sku", "")
        headers["Idempotency-Key"] = str(uuid.uuid5(IDEMPOTENCY_NS, f"menuflow.product.{sku}"))
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(f"{API_URL}{path}", data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as res:
            text = res.read().decode("utf-8")
            return json.loads(text) if text else None
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {path} -> HTTP {exc.code}: {detail}") from exc


def page(path: str, token: str) -> list[dict[str, Any]]:
    data = request("GET", f"{path}?size=500", token)
    return data.get("content", data if isinstance(data, list) else [])


def main() -> int:
    token = request("POST", "/auth/login", body={"tenantSlug": TENANT, "email": EMAIL, "password": PASSWORD})["token"]

    request(
        "PATCH",
        "/config",
        token,
        {
            "autoAcceptOrders": True,
            "pixKey": "pix@menuflow.demo",
            "restaurantName": "MenuFlow Demo",
            "logoUrl": "/brand/menuflow-demo-logo.svg",
            "coverUrl": "/brand/menuflow-demo-cover.svg",
            "address": "Rua das Palmeiras, 120 - Centro",
            "openingHours": "Aberto hoje ate 23h",
            "merchantCity": "Macapa",
        },
    )

    existing_categories = {c["name"]: c for c in page("/categories", token)}
    category_ids: dict[str, str] = {}
    for name, description, order, color in CATEGORIES:
        body = {"name": name, "description": description, "displayOrder": order, "colorCode": color, "iconUrl": None}
        if name in existing_categories:
            saved = request("PUT", f"/categories/{existing_categories[name]['id']}", token, body)
        else:
            saved = request("POST", "/categories", token, body)
        category_ids[name] = saved["id"]

    existing_products = {p["sku"]: p for p in page("/products", token)}
    for (category, sku, name, description, price, cost, prep, featured, image_url, promo_price, promo_start, promo_end) in PRODUCTS:
        body = {
            "categoryId": category_ids[category],
            "sku": sku,
            "name": name,
            "description": description,
            "priceCents": price,
            "costPriceCents": cost,
            "imageUrl": image_url,
            "isAvailable": True,
            "displayOrder": prep,
            "preparationTimeMinutes": 10,
            "isFeatured": featured,
            "promoPriceCents": promo_price,
            "promoStartsAt": promo_start,
            "promoEndsAt": promo_end,
        }
        if sku in existing_products:
            saved = request("PUT", f"/products/{existing_products[sku]['id']}", token, body)
        else:
            saved = request("POST", "/products", token, body)
        request("PUT", f"/products/{saved['id']}/availability", token, {"channels": ["COUNTER", "DINE_IN", "DELIVERY", "ONLINE"], "windows": []})

    existing_ingredients = {i["name"]: i for i in request("GET", "/ingredients", token)}
    for name, unit, cost, stock, minimum in INGREDIENTS:
        body = {
            "name": name,
            "description": "",
            "unit": unit,
            "unitCostCents": cost,
            "stockQuantity": stock,
            "minStock": minimum,
            "isAllergen": False,
        }
        if name in existing_ingredients:
            request("PUT", f"/ingredients/{existing_ingredients[name]['id']}", token, body)
        else:
            request("POST", "/ingredients", token, body)

    print(f"Seed demo oficial concluido: {len(CATEGORIES)} categorias, {len(PRODUCTS)} produtos, {len(INGREDIENTS)} insumos.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"Erro no seed demo: {exc}", file=sys.stderr)
        raise SystemExit(1)
