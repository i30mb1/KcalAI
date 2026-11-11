"""Опубликованное в панели сервера едет в seed.db: без сети и с приоритетом над OFF.

    python -m unittest test_server_products   # из tools/builddb
"""

import csv
import shutil
import sqlite3
import tempfile
import unittest
from pathlib import Path

import build_seed
import server_extract

HERE = Path(__file__).parent
FIELDS = ("barcode", "name", "brand", "kcal100", "prot100", "fat100", "carb100", "fiber100", "serving_g", "popularity")


class ServerProductsTest(unittest.TestCase):

    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp())
        self.data = self.tmp / "data"
        shutil.copytree(HERE / "data", self.data)

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp, ignore_errors=True)

    def write_server_csv(self, rows: list[dict]) -> None:
        with (self.data / "server_products.csv").open("w", encoding="utf-8", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=FIELDS, lineterminator="\n")
            writer.writeheader()
            writer.writerows(rows)

    def build(self) -> sqlite3.Connection:
        out = self.tmp / "seed.db"
        build_seed.build_seed(out, self.data)
        self.assertEqual([], build_seed.verify_seed(out))
        return sqlite3.connect(out)

    def test_опубликованный_товар_находится_по_коду_и_по_названию(self) -> None:
        self.write_server_csv([{
            "barcode": "4810285017077", "name": "Купляйце белое", "brand": "Савушкин",
            "kcal100": 120, "prot100": 300, "fat100": 500, "carb100": 1500,
            "fiber100": "", "serving_g": 200, "popularity": 2,
        }])

        conn = self.build()

        row = conn.execute("SELECT name, kcal100, serving_g FROM product_seed WHERE barcode = '4810285017077'").fetchone()
        self.assertEqual(("Купляйце белое", 120, 200), row)
        found = conn.execute("SELECT barcode FROM product_seed_fts WHERE product_seed_fts MATCH 'купляйце'").fetchall()
        self.assertEqual([("4810285017077",)], found)

    def test_проверенное_в_панели_перекрывает_OFF(self) -> None:
        # Творожная паста «Савушкин» есть в срезе OFF; разработчик поправил её в панели.
        self.write_server_csv([{
            "barcode": "4810268023323", "name": "Паста творожная ваниль", "brand": "Савушкин",
            "kcal100": 115, "prot100": 700, "fat100": 350, "carb100": 1350,
            "fiber100": "", "serving_g": "", "popularity": 1,
        }])

        conn = self.build()

        rows = conn.execute("SELECT name, kcal100 FROM product_seed WHERE barcode = '4810268023323'").fetchall()
        self.assertEqual([("Паста творожная ваниль", 115)], rows)
        fts = conn.execute("SELECT count(*) FROM product_seed_fts WHERE barcode = '4810268023323'").fetchone()[0]
        self.assertEqual(1, fts)

    def test_выгрузка_берёт_опубликованное_с_числом_голосов(self) -> None:
        server_db = self.tmp / "kcal-server.db"
        conn = sqlite3.connect(server_db)
        conn.executescript(
            """
            CREATE TABLE product (gtin TEXT PRIMARY KEY, name TEXT NOT NULL, brand TEXT, kcal100 INTEGER NOT NULL,
              prot100 INTEGER NOT NULL, fat100 INTEGER NOT NULL, carb100 INTEGER NOT NULL, serving_g INTEGER, updated_at INTEGER NOT NULL);
            CREATE TABLE contribution (id INTEGER PRIMARY KEY AUTOINCREMENT, gtin TEXT NOT NULL, name TEXT NOT NULL,
              kcal100 INTEGER NOT NULL, prot100 INTEGER NOT NULL, fat100 INTEGER NOT NULL, carb100 INTEGER NOT NULL,
              serving_g INTEGER, created_at INTEGER NOT NULL, received_at INTEGER NOT NULL);
            INSERT INTO product VALUES ('4810285017077', 'Купляйце белое', NULL, 120, 300, 500, 1500, NULL, 1);
            INSERT INTO contribution (gtin, name, kcal100, prot100, fat100, carb100, created_at, received_at)
              VALUES ('4810285017077', 'Купляйце', 120, 300, 500, 1500, 1, 1), ('4810285017077', 'Купляйце бел', 121, 300, 500, 1500, 2, 2);
            """
        )
        conn.commit()
        conn.close()
        out = self.tmp / "server_products.csv"

        count = server_extract.extract(server_db, out)

        self.assertEqual(1, count)
        with out.open(encoding="utf-8", newline="") as f:
            rows = list(csv.DictReader(f))
        self.assertEqual("Купляйце белое", rows[0]["name"])
        self.assertEqual("", rows[0]["brand"])
        self.assertEqual("2", rows[0]["popularity"])


if __name__ == "__main__":
    unittest.main()
