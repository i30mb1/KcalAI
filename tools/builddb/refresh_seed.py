"""Обновить справочник перед релизом: всё, что телефон может найти без сети.

Только стандартная библиотека, как и у build_seed.py.

Запуск (нужен JDK 21 для отпечатков, см. README):
    python3 refresh_seed.py                # свежий дамп OFF, опубликованное с сервера, сборка, --install
    python3 refresh_seed.py --no-download  # дамп из cache/ как есть

Шаги:
1. Дамп Open Food Facts качается в cache/, только если на сайте он новее, чем
   взятый в прошлый раз (сверка по Last-Modified).
2. off_extract.py режет его до товаров наших полок.
3. server_extract.py выгружает опубликованное в панели сервера.
4. build_seed.py --install собирает seed.db, кладёт в приложение и на сервер.

Обновлённые data/off_products.csv, data/server_products.csv и seed.db коммитятся
до сборки релиза: релиз собирается из чистой копии репозитория.
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import urllib.request
from email.utils import parsedate_to_datetime
from pathlib import Path

HERE = Path(__file__).parent
DUMP_URL = "https://static.openfoodfacts.org/data/en.openfoodfacts.org.products.csv.gz"
DUMP = HERE / "cache" / "off-products.csv.gz"
DUMP_DATE = HERE / "cache" / "off-products.date"
USER_AGENT = "KcalAI-builddb/1.0"


def remote_date() -> str:
    request = urllib.request.Request(DUMP_URL, method="HEAD", headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return parsedate_to_datetime(response.headers["Last-Modified"]).date().isoformat()


def download(date: str) -> None:
    DUMP.parent.mkdir(exist_ok=True)
    part = DUMP.with_suffix(".gz.part")
    request = urllib.request.Request(DUMP_URL, headers={"User-Agent": USER_AGENT})
    print(f"качаю дамп Open Food Facts от {date} (~1,5 ГБ)…")
    with urllib.request.urlopen(request, timeout=60) as response, part.open("wb") as out:
        shutil.copyfileobj(response, out, length=1024 * 1024)
    part.replace(DUMP)
    DUMP_DATE.write_text(date, encoding="utf-8")


def run(*args: str) -> None:
    subprocess.run([sys.executable, *args], cwd=HERE, check=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--no-download", action="store_true", help="взять дамп из cache/ без сверки с сайтом")
    args = parser.parse_args()

    local = DUMP_DATE.read_text(encoding="utf-8").strip() if DUMP_DATE.exists() else None
    if args.no_download:
        if not DUMP.exists():
            print(f"нет дампа {DUMP}")
            return 1
        date = local or (HERE / "data" / "off_products.meta").read_text(encoding="utf-8").split("dump_date=")[1].split()[0]
    else:
        date = remote_date()
        if DUMP.exists() and local == date:
            print(f"дамп Open Food Facts от {date} уже в cache/")
        else:
            download(date)

    run("off_extract.py", str(DUMP), "--dump-date", date)
    run("server_extract.py")
    run("build_seed.py", "--install")
    return 0


if __name__ == "__main__":
    sys.exit(main())
