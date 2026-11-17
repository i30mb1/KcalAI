"""Опубликованное в панели сервера — в справочник приложения.

Только стандартная библиотека, как и у build_seed.py.

Запуск:
    python3 server_extract.py                      # server/data/kcal-server.db -> data/server_products.csv
    python3 server_extract.py путь/к/kcal-server.db

Результат коммитится, как и срез Open Food Facts: релиз собирается из чистой копии
репозитория, где базы сервера нет. В CSV — только товары, которые разработчик
проверил и опубликовал; голоса и всё, что их сопровождает, остаются на сервере.
"""

from __future__ import annotations

import argparse
import csv
import sqlite3
import sys
from pathlib import Path

HERE = Path(__file__).parent
SERVER_DB = HERE.parent.parent / "server" / "data" / "kcal-server.db"
OUT_CSV = HERE / "data" / "server_products.csv"

FIELDS = (
    "barcode", "name", "brand", "kcal100", "prot100", "fat100", "carb100",
    "fiber100", "serving_g", "popularity",
)


def extract(server_db: Path, out: Path) -> int:
    """Пишет опубликованные товары в CSV; популярность — сколько людей прислали этот код."""
    conn = sqlite3.connect(f"file:{server_db}?mode=ro", uri=True)
    try:
        rows = conn.execute(
            """
            SELECT p.gtin, p.name, p.brand, p.kcal100, p.prot100, p.fat100, p.carb100, p.fiber100, p.serving_g,
              (SELECT count(*) FROM contribution c WHERE c.gtin = p.gtin)
            FROM product p ORDER BY p.gtin
            """
        ).fetchall()
    finally:
        conn.close()

    with out.open("w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f, lineterminator="\n")
        writer.writerow(FIELDS)
        for gtin, name, brand, kcal, prot, fat, carb, fiber, serving, votes in rows:
            fiber = "" if fiber is None else fiber
            writer.writerow([gtin, name, brand or "", kcal, prot, fat, carb, fiber, serving or "", votes])
    return len(rows)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("server_db", type=Path, nargs="?", default=SERVER_DB)
    args = parser.parse_args()
    if not args.server_db.is_file():
        print(f"нет базы сервера: {args.server_db} — data/server_products.csv не тронут")
        return 0
    count = extract(args.server_db, OUT_CSV)
    print(f"опубликованных товаров: {count} -> {OUT_CSV}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
