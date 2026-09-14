"""Сборка seed.db — справочника, который едет в assets приложения.

Только стандартная библиотека: скрипт должен запускаться без установки зависимостей.

Запуск:
    python3 build_seed.py                  # -> out/seed.db + проверка инвариантов
    python3 build_seed.py --install        # то же + копия в app/src/main/assets/
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import shutil
import sqlite3
import sys
from datetime import date
from pathlib import Path

SCHEMA_VERSION = "1"
HERE = Path(__file__).parent
ASSETS = HERE.parent.parent / "app" / "src" / "main" / "assets"

KCAL_MAX = 900          # ккал на 100 г; выше физически невозможно
MACRO_MAX_CG = 10_000   # 100.00 г на 100 г

# У этих позиций калории приходят не только из Б/Ж/У: этанол даёт 7 ккал/г,
# но макросом не является и в таблицу не попадает. Сверка энергобаланса для них
# всегда будет ложно срабатывать, поэтому они из неё исключены явным списком —
# молчаливое ослабление порога скрыло бы настоящие ошибки в остальных строках.
ENERGY_CHECK_EXEMPT = ("beer_%", "wine_%", "vodka_%", "%_alcohol%", "cider_%", "champagne_%")

# Единственный словарь порционных единиц. Парсер в `n7.kcalai.resolver.Units`
# знает ровно этот набор: единица, которой тут нет, в тексте не распознается,
# и продукт молча получит типичную порцию вместо названного веса.
CANONICAL_UNITS = frozenset({
    "шт", "ст.л.", "ч.л.", "стакан", "кусок", "горсть",
    "пачка", "банка", "бутылка", "шарик", "шампур", "пучок",
})

# Синонимы схлопываются в канон на сборке, а не в парсере: так вариант написания
# существует ровно в одном месте.
UNIT_ALIASES = {
    "ломтик": "кусок",
    "кусочек": "кусок",
    "долька": "кусок",
    "кружка": "стакан",
    "бокал": "стакан",
    "шейкер": "стакан",
    "баночка": "банка",
    "бутылочка": "бутылка",
}


def normalize(text: str) -> str:
    """Нормализация алиасов и поисковых запросов.

    Приводит к строчным и складывает ё в е. Правило обязано совпадать с
    `n7.kcalai.fooddb.normalizeForSearch` в Kotlin — иначе индекс и запрос
    разойдутся, и половина алиасов перестанет находиться.
    """
    return text.strip().lower().replace("ё", "е")


def build_seed(out_path: Path, data_dir: Path) -> None:
    """Создаёт seed.db с нуля. Существующий файл перезаписывается."""
    out_path.parent.mkdir(parents=True, exist_ok=True)
    if out_path.exists():
        out_path.unlink()

    schema = (HERE / "schema_seed.sql").read_text(encoding="utf-8")

    conn = sqlite3.connect(out_path)
    try:
        conn.executescript(schema)
        _load_generic(conn, data_dir / "generic.csv")
        # generic_alias.csv генерируется целиком, generic_alias_extra.csv правится руками.
        # Раздельно — чтобы пересборка первого не стирала курированные «голые» слова.
        _load_aliases(
            conn,
            data_dir / "generic_alias.csv",
            data_dir / "generic_alias_extra.csv",
        )
        _load_portion_units(conn, data_dir / "portion_unit.csv")
        _fill_meta(conn)
        conn.commit()
        conn.execute("VACUUM")
        conn.commit()
    finally:
        conn.close()


def _rows(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as fh:
        return list(csv.DictReader(fh))


def _load_generic(conn: sqlite3.Connection, path: Path) -> None:
    conn.executemany(
        "INSERT INTO generic (id, name_key, name_ru, kcal100, prot100, fat100, carb100, "
        "default_portion_g) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        [
            (
                int(r["id"]),
                r["name_key"].strip(),
                r["name_ru"].strip(),
                int(r["kcal100"]),
                int(r["prot100"]),
                int(r["fat100"]),
                int(r["carb100"]),
                int(r["default_portion_g"]),
            )
            for r in _rows(path)
        ],
    )


def _load_aliases(conn: sqlite3.Connection, *paths: Path) -> None:
    seen: set[tuple[int, str, str]] = set()
    rows: list[tuple[int, str, str]] = []
    for path in paths:
        if not path.exists():
            continue
        for r in _rows(path):
            row = (int(r["generic_id"]), normalize(r["alias"]), r["lang"].strip())
            if not row[1] or row in seen:
                continue
            seen.add(row)
            rows.append(row)

    conn.executemany(
        "INSERT INTO generic_alias (generic_id, alias, lang) VALUES (?, ?, ?)", rows
    )
    conn.executemany(
        "INSERT INTO generic_fts (alias, generic_id) VALUES (?, ?)",
        [(alias, gid) for gid, alias, _lang in rows],
    )


def _load_portion_units(conn: sqlite3.Connection, path: Path) -> None:
    seen: set[tuple[int, str]] = set()
    rows: list[tuple[int, str, int]] = []
    for r in _rows(path):
        unit = normalize(r["unit"])
        unit = UNIT_ALIASES.get(unit, unit)
        key = (int(r["generic_id"]), unit)
        # Схлопывание синонимов может столкнуть «кусок» и «ломтик» одного продукта.
        # Выигрывает первый — порядок в CSV стабилен, значит и результат тоже.
        if key in seen:
            continue
        seen.add(key)
        rows.append((key[0], key[1], int(r["grams"])))

    conn.executemany(
        "INSERT INTO portion_unit (generic_id, unit, grams) VALUES (?, ?, ?)", rows
    )


def _fill_meta(conn: sqlite3.Connection) -> None:
    conn.executemany(
        "INSERT INTO meta (key, value) VALUES (?, ?)",
        [
            ("schema_version", SCHEMA_VERSION),
            ("built_at", date.today().isoformat()),
            ("source", "Open Food Facts (product_seed) + curated generic table"),
            ("license", "ODbL-1.0"),
            ("off_dump_date", ""),
        ],
    )


def verify_seed(db_path: Path) -> list[str]:
    """Возвращает список нарушенных инвариантов. Пустой список означает, что база здорова."""
    problems: list[str] = []
    conn = sqlite3.connect(db_path)
    try:
        orphans = conn.execute(
            "SELECT count(*) FROM generic_alias a "
            "LEFT JOIN generic g ON g.id = a.generic_id WHERE g.id IS NULL"
        ).fetchone()[0]
        if orphans:
            problems.append(f"generic_alias: {orphans} записей ссылаются на несуществующий generic")

        orphan_units = conn.execute(
            "SELECT count(*) FROM portion_unit u "
            "LEFT JOIN generic g ON g.id = u.generic_id WHERE g.id IS NULL"
        ).fetchone()[0]
        if orphan_units:
            problems.append(f"portion_unit: {orphan_units} записей ссылаются на несуществующий generic")

        bad_kcal = conn.execute(
            "SELECT count(*) FROM generic WHERE kcal100 < 0 OR kcal100 > ?", (KCAL_MAX,)
        ).fetchone()[0]
        if bad_kcal:
            problems.append(f"generic.kcal100: {bad_kcal} значений вне диапазона 0..{KCAL_MAX}")

        for column in ("prot100", "fat100", "carb100"):
            bad = conn.execute(
                f"SELECT count(*) FROM generic WHERE {column} < 0 OR {column} > ?",
                (MACRO_MAX_CG,),
            ).fetchone()[0]
            if bad:
                problems.append(f"generic.{column}: {bad} значений вне диапазона 0..{MACRO_MAX_CG}")

        bad_portion = conn.execute(
            "SELECT count(*) FROM generic WHERE default_portion_g < 1 OR default_portion_g > 2000"
        ).fetchone()[0]
        if bad_portion:
            problems.append(
                f"generic.default_portion_g: {bad_portion} значений вне диапазона 1..2000"
            )

        no_alias = conn.execute(
            "SELECT count(*) FROM generic g "
            "WHERE NOT EXISTS (SELECT 1 FROM generic_alias a WHERE a.generic_id = g.id)"
        ).fetchone()[0]
        if no_alias:
            problems.append(f"generic: {no_alias} позиций без единого алиаса — их нельзя найти поиском")

        # Один алиас на два продукта — поиск станет недетерминированным.
        collisions = conn.execute(
            "SELECT alias, count(DISTINCT generic_id) c FROM generic_alias "
            "GROUP BY alias HAVING c > 1"
        ).fetchall()
        if collisions:
            shown = ", ".join(f"{a} ({c})" for a, c in collisions[:5])
            problems.append(f"generic_alias: {len(collisions)} алиасов ведут к разным продуктам: {shown}")

        # Единица вне канона молча не сработает в парсере — ловим на сборке.
        stray = conn.execute(
            "SELECT DISTINCT unit FROM portion_unit WHERE unit NOT IN "
            f"({','.join('?' * len(CANONICAL_UNITS))})",
            tuple(CANONICAL_UNITS),
        ).fetchall()
        if stray:
            names = ", ".join(repr(u) for (u,) in stray)
            problems.append(
                f"portion_unit: {len(stray)} единиц вне канона парсера ({names}) — "
                "добавь их в CANONICAL_UNITS и в n7.kcalai.resolver, либо в UNIT_ALIASES"
            )

        fts_count = conn.execute("SELECT count(*) FROM generic_fts").fetchone()[0]
        alias_count = conn.execute("SELECT count(*) FROM generic_alias").fetchone()[0]
        if fts_count != alias_count:
            problems.append(f"generic_fts: {fts_count} строк против {alias_count} алиасов")

        # Энергетический баланс: ккал против макросов. Сотые грамма -> граммы.
        exempt = " AND ".join(["name_key NOT LIKE ?"] * len(ENERGY_CHECK_EXEMPT))
        skewed = conn.execute(
            "SELECT name_key, kcal100, "
            "  (prot100 * 4 + fat100 * 9 + carb100 * 4) / 100.0 AS computed "
            "FROM generic "
            "WHERE kcal100 > 20 "
            f"  AND {exempt} "
            "  AND abs(kcal100 - (prot100 * 4 + fat100 * 9 + carb100 * 4) / 100.0) > kcal100 * 0.25",
            ENERGY_CHECK_EXEMPT,
        ).fetchall()
        if skewed:
            shown = ", ".join(f"{k} ({kc} против {c:.0f})" for k, kc, c in skewed[:5])
            problems.append(f"generic: у {len(skewed)} позиций ккал расходится с макросами >25%: {shown}")
    finally:
        conn.close()

    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description="Сборка seed.db")
    parser.add_argument(
        "--install", action="store_true", help="скопировать результат в app/src/main/assets/"
    )
    args = parser.parse_args()

    out = HERE / "out" / "seed.db"
    build_seed(out, HERE / "data")

    found = verify_seed(out)
    if found:
        for problem in found:
            print(f"ПРОВАЛ: {problem}")
        return 1

    conn = sqlite3.connect(out)
    generics = conn.execute("SELECT count(*) FROM generic").fetchone()[0]
    aliases = conn.execute("SELECT count(*) FROM generic_alias").fetchone()[0]
    units = conn.execute("SELECT count(*) FROM portion_unit").fetchone()[0]
    conn.close()

    size_kb = out.stat().st_size // 1024
    print(f"ОК: {out} — {generics} продуктов, {aliases} алиасов, {units} порций, {size_kb} КБ")

    if args.install:
        ASSETS.mkdir(parents=True, exist_ok=True)
        shutil.copy2(out, ASSETS / "seed.db")

        # Рядом кладём хеш содержимого. По размеру отличать версии нельзя:
        # SQLite выравнивает файл по страницам, и правка текста внутри строки
        # его не двигает — приложение молча осталось бы на старой копии.
        digest = hashlib.sha256(out.read_bytes()).hexdigest()
        (ASSETS / "seed.db.sha256").write_text(digest, encoding="utf-8")
        print(f"ОК: скопировано в {ASSETS / 'seed.db'} (sha256 {digest[:12]}…)")

        # Та же сборка уезжает и на локальный сервер: телефон докачает её без релиза APK.
        # Версия — просто счётчик установок; клиент сравнивает хеш, а версия для глаз.
        server_seed = HERE.parent.parent / "server" / "data" / "seed"
        server_seed.mkdir(parents=True, exist_ok=True)
        shutil.copy2(out, server_seed / "seed.db")
        (server_seed / "seed.db.sha256").write_text(digest, encoding="utf-8")
        version_file = server_seed / "version.txt"
        version = int(version_file.read_text().strip() or 0) + 1 if version_file.exists() else 1
        version_file.write_text(str(version), encoding="utf-8")
        print(f"ОК: сервер получит версию {version} в {server_seed}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
