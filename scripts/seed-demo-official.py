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
from typing import Any


API_URL = os.getenv("MF_API_URL", "http://localhost:8080/api/v1").rstrip("/")
TENANT = os.getenv("MF_TENANT", "demo")
EMAIL = os.getenv("MF_EMAIL", "admin@demo.com")
PASSWORD = os.getenv("MF_PASSWORD", "Demo@1234")

CATEGORIES = [
    ("Hambúrgueres Artesanais", "Clássicos da casa com blend bovino e pão brioche.", 10, "#047857"),
    ("Smash Burgers", "Burgers prensados, queijo derretido e preparo rápido.", 20, "#0f766e"),
    ("Frango e Vegetarianos", "Opções sem carne bovina e alternativas leves.", 30, "#16a34a"),
    ("Pizzas", "Pizzas tradicionais e especiais para salão e delivery.", 40, "#b45309"),
    ("Porções e Entradas", "Itens para compartilhar antes do prato principal.", 50, "#ca8a04"),
    ("Batatas e Acompanhamentos", "Batatas, anéis e guarnições avulsas.", 60, "#d97706"),
    ("Molhos Extras", "Molhos avulsos para venda adicional.", 70, "#be123c"),
    ("Bebidas", "Refrigerantes, sucos, água e bebidas quentes.", 80, "#2563eb"),
    ("Sobremesas", "Finalizações doces para balcão e delivery.", 90, "#c026d3"),
    ("Combos da Casa", "Combinações com bebida e acompanhamento.", 100, "#7c3aed"),
    ("Promoções", "Ofertas comerciais temporárias.", 110, "#dc2626"),
    ("Lanches", "Itens rápidos e avulsos para balcão.", 120, "#0891b2"),
]

PRODUCTS = [
    ("Hambúrgueres Artesanais", "MF-CLASSIC", "MenuFlow Classic", "Blend 160 g, queijo, alface, tomate e molho da casa.", 2990, 1450, 12, True),
    ("Hambúrgueres Artesanais", "MF-BACON", "Bacon Supreme", "Blend 180 g, bacon crocante, cheddar e cebola caramelizada.", 3890, 1890, 14, True),
    ("Hambúrgueres Artesanais", "MF-BBQ", "Barbecue Onion", "Blend bovino, queijo prato, onion rings e barbecue.", 3690, 1750, 14, False),
    ("Hambúrgueres Artesanais", "MF-CHEDDAR", "Cheddar Melt", "Blend 160 g, cheddar cremoso e picles.", 3290, 1520, 12, False),
    ("Hambúrgueres Artesanais", "MF-SALAD", "Burger Salada", "Blend bovino, queijo, salada fresca e maionese verde.", 3190, 1500, 12, False),
    ("Hambúrgueres Artesanais", "MF-KIDS", "Mini Burger Kids", "Burger menor com queijo e batata pequena.", 2490, 1200, 10, False),
    ("Smash Burgers", "SMASH-SIMPLES", "Smash Simples", "Smash 90 g, queijo americano e molho especial.", 2190, 980, 8, True),
    ("Smash Burgers", "SMASH-DUPLO", "Smash Duplo", "Dois smashs, dobro de queijo e picles.", 3190, 1450, 9, True),
    ("Smash Burgers", "SMASH-TRIPLO", "Smash Triplo", "Três smashs, queijo americano e molho da casa.", 4190, 1980, 10, False),
    ("Smash Burgers", "SMASH-PIMENTA", "Smash Pepper", "Smash duplo com jalapeño e maionese picante.", 3490, 1600, 9, False),
    ("Frango e Vegetarianos", "FRANGO-CRISPY", "Chicken Crispy", "Frango empanado, queijo, salada e molho honey mustard.", 3290, 1500, 12, False),
    ("Frango e Vegetarianos", "VEG-GRILL", "Veggie Grill", "Burger vegetal, queijo, rúcula e tomate.", 3490, 1750, 12, False),
    ("Pizzas", "PIZZA-TRAD", "Pizza Tradicional", "Mussarela, tomate, orégano e massa artesanal.", 4990, 2400, 22, True),
    ("Pizzas", "PIZZA-CALABRESA", "Pizza Calabresa", "Calabresa, cebola, mussarela e orégano.", 5490, 2600, 22, False),
    ("Porções e Entradas", "ONION-RINGS", "Onion Rings", "Anéis de cebola crocantes com molho da casa.", 2490, 980, 8, False),
    ("Porções e Entradas", "NUGGETS-12", "Nuggets 12 unidades", "Nuggets de frango com molho barbecue.", 2690, 1200, 8, False),
    ("Batatas e Acompanhamentos", "FRIES-P", "Batata Pequena", "Batata frita crocante individual.", 1290, 520, 6, False),
    ("Batatas e Acompanhamentos", "FRIES-G", "Batata Grande", "Porção grande de batata frita.", 2290, 850, 7, True),
    ("Batatas e Acompanhamentos", "FRIES-CHEDDAR", "Batata Cheddar Bacon", "Batata com cheddar cremoso e bacon.", 2890, 1250, 8, False),
    ("Batatas e Acompanhamentos", "MANDIOCA", "Mandioca Frita", "Mandioca crocante com sal da casa.", 2390, 900, 9, False),
    ("Molhos Extras", "MOLHO-VERDE", "Maionese Verde", "Pote extra de maionese verde.", 390, 120, 1, False),
    ("Molhos Extras", "MOLHO-BBQ", "Molho Barbecue", "Pote extra de barbecue.", 390, 130, 1, False),
    ("Molhos Extras", "MOLHO-PICANTE", "Molho Picante", "Pote extra de molho picante.", 390, 130, 1, False),
    ("Bebidas", "REFRI-LATA", "Refrigerante Lata", "Lata 350 ml sabores variados.", 690, 320, 1, False),
    ("Bebidas", "REFRI-600", "Refrigerante 600 ml", "Garrafa 600 ml.", 990, 480, 1, False),
    ("Bebidas", "REFRI-2L", "Refrigerante 2 L", "Garrafa 2 litros.", 1490, 760, 1, False),
    ("Bebidas", "AGUA", "Água Mineral", "Garrafa 500 ml.", 490, 180, 1, False),
    ("Bebidas", "SUCO-LARANJA", "Suco de Laranja", "Suco natural 300 ml.", 990, 420, 3, False),
    ("Bebidas", "CHA-GELADO", "Chá Gelado", "Chá gelado 300 ml.", 890, 360, 2, False),
    ("Bebidas", "CAFE", "Café Espresso", "Café espresso curto.", 590, 180, 2, False),
    ("Sobremesas", "BROWNIE", "Brownie", "Brownie de chocolate com calda.", 1590, 620, 5, True),
    ("Sobremesas", "MILKSHAKE", "Milkshake", "Milkshake 400 ml sabores variados.", 1890, 820, 5, False),
    ("Sobremesas", "PUDIM", "Pudim", "Fatia de pudim tradicional.", 1290, 460, 3, False),
    ("Combos da Casa", "COMBO-CLASSIC", "Combo Classic", "MenuFlow Classic, batata pequena e refrigerante lata.", 4290, 2150, 13, True),
    ("Combos da Casa", "COMBO-DUPLO", "Combo Duplo Cheddar", "Dois Cheddar Melt, batata grande e refrigerante 2 L.", 7990, 3900, 15, True),
    ("Combos da Casa", "COMBO-SMASH", "Combo Smash", "Smash Duplo, batata pequena e bebida.", 4390, 2100, 10, False),
    ("Combos da Casa", "COMBO-FAMILIA", "Combo Família", "Quatro burgers, duas batatas grandes e refrigerante 2 L.", 14990, 7200, 18, False),
    ("Combos da Casa", "COMBO-PIZZA-REFRI", "Combo Pizza + Refri", "Pizza tradicional e refrigerante 2 L.", 6490, 3200, 24, False),
    ("Promoções", "SEGUNDA-SMASH", "Segunda do Smash", "Smash simples promocional para início da semana.", 1990, 980, 8, True),
    ("Lanches", "HOTDOG", "Hot Dog", "Pão, salsicha, molho, milho, batata palha e queijo.", 1790, 760, 8, False),
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
        headers["Idempotency-Key"] = str(uuid.uuid4())
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
    for order, (category, sku, name, description, price, cost, prep, featured) in enumerate(PRODUCTS, start=1):
        body = {
            "categoryId": category_ids[category],
            "sku": sku,
            "name": name,
            "description": description,
            "priceCents": price,
            "costPriceCents": cost,
            "imageUrl": None,
            "isAvailable": True,
            "displayOrder": order,
            "preparationTimeMinutes": prep,
            "isFeatured": featured,
            "promoPriceCents": None,
            "promoStartsAt": None,
            "promoEndsAt": None,
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

    print(f"Seed demo oficial concluído: {len(CATEGORIES)} categorias, {len(PRODUCTS)} produtos, {len(INGREDIENTS)} insumos.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"Erro no seed demo: {exc}", file=sys.stderr)
        raise SystemExit(1)
