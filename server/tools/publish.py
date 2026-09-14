"""Перенос вкладов в таблицу товаров.

    python publish.py 4600699500001            # медиана по всем вкладам этого GTIN
    python publish.py 4600699500001 --show     # только показать вклады
    python publish.py 4600699500001 --name "Молоко 3,2%" --kcal 59 --prot 290 --fat 320 --carb 470 --serving 250

Порога согласия у сервера нет намеренно: публикует человек, посмотрев на вклады.
Единицы — как в контракте: ккал целые, макросы в сотых грамма.
"""
import argparse
import sqlite3
import statistics
import time
from pathlib import Path

DB = Path(__file__).parent.parent / "data" / "kcal-server.db"


def main() -> int:
    parser = argparse.ArgumentParser(description="Публикация вкладов в product")
    parser.add_argument("gtin")
    parser.add_argument("--show", action="store_true")
    parser.add_argument("--name")
    parser.add_argument("--brand")
    parser.add_argument("--kcal", type=int)
    parser.add_argument("--prot", type=int)
    parser.add_argument("--fat", type=int)
    parser.add_argument("--carb", type=int)
    parser.add_argument("--serving", type=int)
    args = parser.parse_args()

    conn = sqlite3.connect(DB)
    rows = conn.execute(
        "SELECT name, kcal100, prot100, fat100, carb100, serving_g, received_at "
        "FROM contribution WHERE gtin = ? ORDER BY id",
        (args.gtin,),
    ).fetchall()

    for name, kcal, prot, fat, carb, serving, received in rows:
        print(f"{name!r:40} {kcal:4} ккал  Б {prot/100:5.2f}  Ж {fat/100:5.2f}  У {carb/100:5.2f}  порция {serving}")
    if args.show:
        return 0
    if not rows and args.kcal is None:
        print("вкладов нет, а значения не заданы")
        return 1

    def pick(index, override):
        if override is not None:
            return override
        return int(statistics.median(r[index] for r in rows))

    name = args.name or rows[-1][0]
    serving = args.serving if args.serving is not None else next((r[5] for r in reversed(rows) if r[5]), None)
    values = (
        args.gtin, name, args.brand,
        pick(1, args.kcal), pick(2, args.prot), pick(3, args.fat), pick(4, args.carb),
        serving, int(time.time() * 1000),
    )
    conn.execute(
        "INSERT INTO product (gtin, name, brand, kcal100, prot100, fat100, carb100, serving_g, updated_at) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(gtin) DO UPDATE SET name = excluded.name, "
        "brand = excluded.brand, kcal100 = excluded.kcal100, prot100 = excluded.prot100, "
        "fat100 = excluded.fat100, carb100 = excluded.carb100, serving_g = excluded.serving_g, "
        "updated_at = excluded.updated_at",
        values,
    )
    conn.commit()
    print(f"ОК: {args.gtin} -> {name!r} {values[3]} ккал")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
