"""
Скриншоты страницы в Google Play: tools/play/screenshots.html → 1080×1920 PNG.

Рендерит headless-браузер на Chromium (Chrome или Edge): кадр 360×640 CSS px
с масштабом 3. Путь к браузеру — аргументом, иначе ищется Edge.

    python tools/play/render_screenshots.py ["C:/path/to/chrome.exe"]
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PAGE = ROOT / "tools/play/screenshots.html"
OUT = ROOT / "fastlane/metadata/android/ru-RU/images/phoneScreenshots"
SCREENS = 6
EDGE = Path("C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe")


def main() -> None:
    browser = Path(sys.argv[1]) if len(sys.argv) > 1 else EDGE
    OUT.mkdir(parents=True, exist_ok=True)
    for n in range(1, SCREENS + 1):
        target = OUT / f"{n}.png"
        subprocess.run(
            [
                str(browser),
                "--headless=new",
                "--disable-gpu",
                "--hide-scrollbars",
                "--force-device-scale-factor=3",
                "--window-size=360,640",
                # Скрипт кадра рисует по хэшу; без паузы снимок успевает раньше шрифтов.
                "--virtual-time-budget=3000",
                f"--screenshot={target}",
                f"{PAGE.as_uri()}#{n}",
            ],
            check=True,
            capture_output=True,
        )
        print(target)


if __name__ == "__main__":
    main()
