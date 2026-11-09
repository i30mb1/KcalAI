"""Срез Open Food Facts для справочника: товары российских полок со штрих-кодом и КБЖУ.

Только стандартная библиотека, как и у build_seed.py.

Запуск:
    python3 off_extract.py cache/off-products.csv.gz --dump-date 2026-11-07

Дамп (CSV, ~1,5 ГБ) берётся отсюда и кладётся в cache/ — он в .gitignore:
    https://static.openfoodfacts.org/data/en.openfoodfacts.org.products.csv.gz

Результат — data/off_products.csv и data/off_products.meta. Оба коммитятся: так
сборка базы не требует качать полтора гигабайта, а срез, который едет в приложении,
лежит в репозитории целиком — этого требует ODbL от производной базы.
"""

from __future__ import annotations

import argparse
import csv
import gzip
import html
import sys
from pathlib import Path

HERE = Path(__file__).parent
OUT_CSV = HERE / "data" / "off_products.csv"
OUT_META = HERE / "data" / "off_products.meta"

# Где продаётся товар. Полки этих стран во многом общие: «Савушкин» и «Простоквашино»
# стоят и в Москве, и в Минске, и в Алматы.
COUNTRIES = ("en:russia", "en:belarus", "en:kazakhstan")

KCAL_MAX = 900
MACRO_MAX_G = 100.0
MASS_SLACK_G = 1.0
KJ_PER_KCAL = 4.184

# Калории на этикетке и калории из макросов расходятся из-за округления и клетчатки,
# но не на четверть: на четверть расходится калорийность порции, вписанная вместо ста грамм.
ENERGY_TOLERANCE = 0.25
ENERGY_SLACK_KCAL = 15

FIELDS = (
    "barcode", "name", "brand", "kcal100", "prot100", "fat100", "carb100",
    "fiber100", "serving_g", "popularity",
)


def checksum_ok(digits: str) -> bool:
    """Мод-10 с весами 3 и 1 от правого края — как `BarcodeValidator` в приложении."""
    body, check = digits[:-1], int(digits[-1])
    total = sum(int(c) * (3 if i % 2 == 0 else 1) for i, c in enumerate(reversed(body)))
    return (10 - total % 10) % 10 == check


def expand_upce(code: str) -> str | None:
    """UPC-E -> EAN-13, та же схема, что `BarcodeValidator.expandUpcE`."""
    if code[0] not in "01":
        return None
    number, d, check = code[0], code[1:7], code[7]
    last = d[5]
    if last in "012":
        body = d[0:2] + last + "0000" + d[2:5]
    elif last == "3":
        body = d[0:3] + "00000" + d[3:5]
    elif last == "4":
        body = d[0:4] + "00000" + d[4]
    else:
        body = d[0:5] + "0000" + last
    upca = number + body + check
    return "0" + upca if checksum_ok(upca) else None


def normalize_gtin(raw: str) -> str | None:
    """Код в том виде, в каком его ищет приложение: EAN-8 как есть, UPC-A и UPC-E — в EAN-13."""
    digits = raw.strip()
    if not digits.isdigit():
        return None
    if len(digits) == 8:
        return digits if checksum_ok(digits) else expand_upce(digits)
    if len(digits) == 12:
        return "0" + digits if checksum_ok(digits) else None
    if len(digits) == 13:
        return digits if checksum_ok(digits) else None
    return None


def number(value: str) -> float | None:
    value = value.strip()
    if not value:
        return None
    try:
        parsed = float(value)
    except ValueError:
        return None
    return parsed if parsed == parsed else None  # NaN


def clean_name(raw: str) -> str | None:
    # В названиях OFF встречается HTML-разметка кавычек: «Пельмени &quot;Домашние&quot;».
    name = " ".join(html.unescape(raw).split())
    if len(name) < 2 or sum(c.isalpha() for c in name) < 2:
        return None
    return name


def energy_ok(kcal: float, prot: float, fat: float, carb: float, alcohol: float) -> bool:
    computed = 4 * prot + 9 * fat + 4 * carb + 7 * alcohol
    return abs(kcal - computed) <= max(ENERGY_TOLERANCE * max(kcal, computed), ENERGY_SLACK_KCAL)


class Rejects:
    def __init__(self) -> None:
        self.counts: dict[str, int] = {}

    def __call__(self, reason: str) -> None:
        self.counts[reason] = self.counts.get(reason, 0) + 1


def parse_row(row: dict[str, str], reject: Rejects) -> dict | None:
    gtin = normalize_gtin(row["code"])
    if gtin is None:
        return reject("код не EAN/UPC")
    name = clean_name(row["product_name"])
    if name is None:
        return reject("нет названия")

    kcal = number(row["energy-kcal_100g"])
    if kcal is None:
        kj = number(row["energy-kj_100g"]) or number(row["energy_100g"])
        kcal = kj / KJ_PER_KCAL if kj is not None else None
    prot, fat, carb = (number(row[k]) for k in ("proteins_100g", "fat_100g", "carbohydrates_100g"))
    if kcal is None or prot is None or fat is None or carb is None:
        return reject("КБЖУ неполное")

    if not 0 <= kcal <= KCAL_MAX or not all(0 <= v <= MACRO_MAX_G for v in (prot, fat, carb)):
        return reject("число вне диапазона")
    if prot + fat + carb > MACRO_MAX_G + MASS_SLACK_G:
        return reject("макросов больше 100 г")
    if kcal == 0 and prot == fat == carb == 0:
        return reject("одни нули")
    alcohol = number(row["alcohol_100g"]) or 0.0
    if not energy_ok(kcal, prot, fat, carb, alcohol):
        return reject("ккал не сходятся с макросами")

    fiber = number(row["fiber_100g"])
    if fiber is not None and not 0 <= fiber <= MACRO_MAX_G:
        fiber = None
    serving = number(row["serving_quantity"])
    serving_g = round(serving) if serving is not None and 1 <= serving <= 2000 else None
    scans = number(row["unique_scans_n"])

    return {
        "barcode": gtin,
        "name": name,
        "brand": (html.unescape(row["brands"]).split(",")[0].strip() or None),
        "kcal100": round(kcal),
        "prot100": round(prot * 100),
        "fat100": round(fat * 100),
        "carb100": round(carb * 100),
        "fiber100": round(fiber * 100) if fiber is not None else None,
        "serving_g": serving_g,
        "popularity": int(scans) if scans is not None and scans > 0 else 0,
        "_modified": int(number(row["last_modified_t"]) or 0),
    }


def extract(dump: Path) -> tuple[list[dict], Rejects, int]:
    reject = Rejects()
    best: dict[str, dict] = {}
    regional = 0
    csv.field_size_limit(sys.maxsize)
    with gzip.open(dump, "rt", encoding="utf-8", errors="replace", newline="") as f:
        header = f.readline().rstrip("\r\n").split("\t")
        width = len(header)
        for line in f:
            # Дамп — четыре с лишним миллиона строк, из них наших — доли процента.
            # Дешёвая проверка подстроки до разбора экономит минуты.
            if not any(country in line for country in COUNTRIES):
                continue
            values = line.rstrip("\r\n").split("\t")
            if len(values) != width:
                reject("строка дампа битая")
                continue
            row = dict(zip(header, values))
            tags = row["countries_tags"].split(",")
            if not any(country in tags for country in COUNTRIES):
                continue
            regional += 1
            product = parse_row(row, reject)
            if product is None:
                continue
            # Один код встречается в дампе несколько раз (UPC и EAN одной пачки).
            # Выигрывает свежая правка: её последней видел живой человек.
            seen = best.get(product["barcode"])
            if seen is None or product["_modified"] > seen["_modified"]:
                best[product["barcode"]] = product
    products = sorted(best.values(), key=lambda p: (-p["popularity"], p["barcode"]))
    return products, reject, regional


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("dump", type=Path, help="en.openfoodfacts.org.products.csv.gz")
    parser.add_argument("--dump-date", required=True, help="дата дампа, YYYY-MM-DD (Last-Modified файла)")
    args = parser.parse_args()

    products, reject, regional = extract(args.dump)

    with OUT_CSV.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDS, extrasaction="ignore", lineterminator="\n")
        writer.writeheader()
        writer.writerows(products)
    OUT_META.write_text(f"dump_date={args.dump_date}\nrows={len(products)}\n", encoding="utf-8")

    print(f"товаров с наших полок: {regional}, принято: {len(products)}")
    for reason, count in sorted(reject.counts.items(), key=lambda kv: -kv[1]):
        print(f"  отброшено — {reason}: {count}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
