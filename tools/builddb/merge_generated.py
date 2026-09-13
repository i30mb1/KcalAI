"""Слияние сгенерированных позиций в CSV генерик-таблицы.

Вход — JSON-массив объектов вида
    {id, name_key, name_ru, kcal100, prot100, fat100, carb100,
     default_portion_g, aliases_ru[], aliases_en[], portion_units[{unit, grams}]}

Отбраковка идёт здесь, а не на сборке, потому что здесь ещё известно, какую именно
позицию выбрасываем и почему. build_seed.py проверяет инварианты уже готовой базы
и может только сказать «что-то не так».

    python3 merge_generated.py items.json
    python3 merge_generated.py items.json --dry-run
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path

from build_seed import CANONICAL_UNITS, UNIT_ALIASES, normalize

HERE = Path(__file__).parent
DATA = HERE / "data"

KCAL_MAX = 900
MACRO_MAX_CG = 10_000
PORTION_MIN, PORTION_MAX = 1, 2000
ENERGY_TOLERANCE = 0.25


def _read(name: str) -> list[dict[str, str]]:
    path = DATA / name
    if not path.exists():
        return []
    with path.open(encoding="utf-8", newline="") as fh:
        return list(csv.DictReader(fh))


def _write(name: str, rows: list[dict], fields: list[str]) -> None:
    with (DATA / name).open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def _energy_ok(item: dict) -> bool:
    computed = (item["prot100"] * 4 + item["fat100"] * 9 + item["carb100"] * 4) / 100
    if item["kcal100"] <= 20:
        return True
    return abs(item["kcal100"] - computed) <= item["kcal100"] * ENERGY_TOLERANCE


def merge(items: list[dict], dry_run: bool = False) -> dict[str, int]:
    generic = _read("generic.csv")
    aliases = _read("generic_alias.csv")
    units = _read("portion_unit.csv")

    taken_ids = {int(r["id"]) for r in generic}
    taken_keys = {r["name_key"] for r in generic}
    # Одно блюдо, попавшее в две категории, приходит под разными ключами
    # («olives» и «olives_canned»), и проверка по ключу его пропускает.
    # В списке подсказок это два одинаковых чипса с разными цифрами.
    taken_names = {normalize(r["name_ru"]) for r in generic}
    # Алиас уже занят — новая позиция его не отбирает: существующие данные
    # курировались дольше и на них уже завязано поведение поиска.
    taken_aliases = {normalize(r["alias"]) for r in aliases}
    taken_aliases |= {normalize(r["name_ru"]) for r in generic}

    stats = {k: 0 for k in (
        "добавлено", "дубль_id", "дубль_ключа", "дубль_имени", "битые_числа",
        "нет_алиасов", "плохой_энергобаланс", "алиасов", "единиц", "единиц_отброшено",
    )}
    rejected: list[str] = []

    for item in items:
        try:
            item_id = int(item["id"])
            for field in ("kcal100", "prot100", "fat100", "carb100", "default_portion_g"):
                item[field] = int(item[field])
        except (KeyError, TypeError, ValueError):
            stats["битые_числа"] += 1
            continue

        if item_id in taken_ids:
            stats["дубль_id"] += 1
            rejected.append(f"id {item_id} занят: {item.get('name_ru')}")
            continue
        if item["name_key"] in taken_keys:
            stats["дубль_ключа"] += 1
            rejected.append(f"ключ {item['name_key']} занят: {item.get('name_ru')}")
            continue
        if normalize(item["name_ru"]) in taken_names:
            stats["дубль_имени"] += 1
            rejected.append(f"имя «{item['name_ru']}» уже занято другой позицией")
            continue

        if not (0 <= item["kcal100"] <= KCAL_MAX):
            stats["битые_числа"] += 1
            rejected.append(f"{item['name_ru']}: ккал {item['kcal100']}")
            continue
        if any(not (0 <= item[m] <= MACRO_MAX_CG) for m in ("prot100", "fat100", "carb100")):
            stats["битые_числа"] += 1
            rejected.append(f"{item['name_ru']}: макрос вне диапазона")
            continue
        if not (PORTION_MIN <= item["default_portion_g"] <= PORTION_MAX):
            stats["битые_числа"] += 1
            rejected.append(f"{item['name_ru']}: порция {item['default_portion_g']} г")
            continue
        if not _energy_ok(item):
            stats["плохой_энергобаланс"] += 1
            computed = (item["prot100"] * 4 + item["fat100"] * 9 + item["carb100"] * 4) / 100
            rejected.append(f"{item['name_ru']}: {item['kcal100']} ккал против {computed:.0f} по макросам")
            continue

        # Алиасы, уже занятые другой позицией, молча отбрасываются: два продукта
        # на один алиас сделали бы поиск недетерминированным.
        fresh: list[tuple[str, str]] = []
        for alias in dict.fromkeys(list(item.get("aliases_ru") or []) + [item["name_ru"]]):
            n = normalize(alias)
            if n and n not in taken_aliases:
                taken_aliases.add(n)
                fresh.append((n, "ru"))
        for alias in dict.fromkeys(item.get("aliases_en") or []):
            n = normalize(alias)
            if n and n not in taken_aliases:
                taken_aliases.add(n)
                fresh.append((n, "en"))

        if not fresh:
            # Ни одного своего алиаса — позицию нельзя будет найти поиском.
            stats["нет_алиасов"] += 1
            rejected.append(f"{item['name_ru']}: все алиасы уже заняты")
            continue

        taken_ids.add(item_id)
        taken_keys.add(item["name_key"])
        taken_names.add(normalize(item["name_ru"]))

        generic.append({
            "id": str(item_id),
            "name_key": item["name_key"],
            "name_ru": item["name_ru"],
            "kcal100": str(item["kcal100"]),
            "prot100": str(item["prot100"]),
            "fat100": str(item["fat100"]),
            "carb100": str(item["carb100"]),
            "default_portion_g": str(item["default_portion_g"]),
        })
        for alias, lang in fresh:
            aliases.append({"generic_id": str(item_id), "alias": alias, "lang": lang})
        stats["алиасов"] += len(fresh)

        seen_units: set[str] = set()
        for unit in item.get("portion_units") or []:
            name = normalize(str(unit.get("unit", "")))
            name = UNIT_ALIASES.get(name, name)
            if name not in CANONICAL_UNITS or name in seen_units:
                stats["единиц_отброшено"] += 1
                continue
            try:
                grams = int(unit["grams"])
            except (KeyError, TypeError, ValueError):
                stats["единиц_отброшено"] += 1
                continue
            if not (1 <= grams <= PORTION_MAX):
                stats["единиц_отброшено"] += 1
                continue
            seen_units.add(name)
            units.append({"generic_id": str(item_id), "unit": name, "grams": str(grams)})
            stats["единиц"] += 1

        stats["добавлено"] += 1

    if not dry_run:
        _write("generic.csv", generic, ["id", "name_key", "name_ru", "kcal100",
                                       "prot100", "fat100", "carb100", "default_portion_g"])
        _write("generic_alias.csv", aliases, ["generic_id", "alias", "lang"])
        _write("portion_unit.csv", units, ["generic_id", "unit", "grams"])

    stats["_rejected"] = rejected
    return stats


def main() -> int:
    parser = argparse.ArgumentParser(description="Слияние сгенерированных позиций")
    parser.add_argument("source", type=Path, help="JSON-массив позиций")
    parser.add_argument("--dry-run", action="store_true", help="не писать CSV")
    parser.add_argument("--show", type=int, default=15, help="сколько отбракованных показать")
    args = parser.parse_args()

    items = json.loads(args.source.read_text(encoding="utf-8"))
    stats = main_stats = merge(items, dry_run=args.dry_run)
    rejected = main_stats.pop("_rejected")

    print(f"на входе: {len(items)}")
    for key, value in stats.items():
        print(f"  {key}: {value}")
    if rejected:
        print(f"\nотбраковано ({len(rejected)}), первые {args.show}:")
        for line in rejected[:args.show]:
            print(f"  - {line}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
