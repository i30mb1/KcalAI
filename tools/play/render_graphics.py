"""
Графика страницы в Google Play: иконка 512×512 и баннер 1024×500.

Геометрия иконки та же, что у адаптивной иконки в app/src/main/res/drawable
(холст 108dp), — здесь она просто нарисована в пикселях. Правите там — правьте здесь.

    python tools/play/render_graphics.py
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "fastlane/metadata/android/ru-RU/images"
FONT = ROOT / "core/ui/src/main/res/font/manrope.ttf"

BUBBLE = "#2C2618"
CREAM = "#F4EFE4"
TEXT2 = "#B5AB96"
PROTEIN, FAT, CARB = "#C2456F", "#C8862B", "#4C8F5A"

# Сглаживание — рисуем крупнее и уменьшаем.
SS = 4


def draw_mark(d: ImageDraw.ImageDraw, ox: float, oy: float, k: float) -> None:
    """Пузырёк с тремя барами; (ox, oy) — угол холста 108dp, k — пикселей на dp."""
    p = lambda x, y: (ox + x * k, oy + y * k)
    d.rounded_rectangle([p(29, 32), p(79, 70)], radius=10 * k, fill=CREAM)
    d.polygon([p(32, 60), p(46, 70), p(34, 79)], fill=CREAM)
    for (top, right, color) in ((40, 71, PROTEIN), (48.5, 62, FAT), (57, 66, CARB)):
        d.rounded_rectangle([p(37, top), p(right, top + 5)], radius=2.5 * k, fill=color)


def font(size: int, weight: str) -> ImageFont.FreeTypeFont:
    f = ImageFont.truetype(str(FONT), size)
    f.set_variation_by_name(weight)
    return f


def icon() -> None:
    s = 512 * SS
    img = Image.new("RGB", (s, s), BUBBLE)
    # В лаунчере знак сидит в безопасном круге маски; у Play своя маска со скруглением
    # поменьше, и в масштабе 1:1 знак там выглядит мелким.
    k = s / 108 * 1.4
    draw_mark(ImageDraw.Draw(img), s / 2 - 54 * k, s / 2 - 55.5 * k, k)
    img.resize((512, 512), Image.LANCZOS).save(OUT / "icon.png")


def feature_graphic() -> None:
    w, h = 1024 * SS, 500 * SS
    img = Image.new("RGB", (w, h), BUBBLE)
    d = ImageDraw.Draw(img)
    mark = 360 * SS
    draw_mark(d, 40 * SS, (h - mark) / 2, mark / 108)
    x = 400 * SS
    d.text((x, 150 * SS), "Kcal AI", font=font(96 * SS, "Bold"), fill=CREAM)
    d.text((x, 280 * SS), "«гречка 200» — и КБЖУ", font=font(40 * SS, "Medium"), fill=CREAM)
    d.text((x, 336 * SS), "посчитано. Офлайн.", font=font(40 * SS, "Medium"), fill=TEXT2)
    img.resize((1024, 500), Image.LANCZOS).save(OUT / "featureGraphic.png")


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    icon()
    feature_graphic()
