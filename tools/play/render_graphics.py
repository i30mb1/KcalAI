"""
Графика страницы в Google Play: tools/play/graphics.html → иконка 512×512 и баннер 1024×500.

Рендерит headless-браузер на Chromium (Chrome или Edge). Путь к браузеру — аргументом,
иначе ищется Edge.

    python tools/play/render_graphics.py ["C:/path/to/chrome.exe"]
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PAGE = ROOT / "tools/play/graphics.html"
OUT = ROOT / "fastlane/metadata/android/ru-RU/images"
EDGE = Path("C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe")

SHOTS = {
    "icon.png": ("icon", 512, 512),
    "featureGraphic.png": ("feature", 1024, 500),
}


def main() -> None:
    browser = Path(sys.argv[1]) if len(sys.argv) > 1 else EDGE
    OUT.mkdir(parents=True, exist_ok=True)
    for name, (frame, width, height) in SHOTS.items():
        target = OUT / name
        subprocess.run(
            [
                str(browser),
                "--headless=new",
                "--disable-gpu",
                "--hide-scrollbars",
                "--force-device-scale-factor=1",
                f"--window-size={width},{height}",
                "--virtual-time-budget=3000",
                f"--screenshot={target}",
                f"{PAGE.as_uri()}#{frame}",
            ],
            check=True,
            capture_output=True,
        )
        print(target)


if __name__ == "__main__":
    main()
