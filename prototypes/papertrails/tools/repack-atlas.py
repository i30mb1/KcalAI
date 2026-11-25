"""KcalAI: плотная перекладка атласа печати мира — меньше видеопамяти без потери качества.

Атлас 4096 x 4096 (~85 МБ на видеокарте с мипами) заполнен картинками на ~56 %, а плитки мира используют лишь
часть их (животные, облака, дым печатаются не отсюда). Скрипт берёт только места, на которые смотрят координаты
печати (TEXCOORD_1) моделей, раскладывает их полками в лист шириной 4096 и минимальной высоты, переписывает
координаты в .glb и места в atlas.json. Каждое место — в исходном разрешении, с полями растянутого цвета вокруг.

Запуск из prototypes/papertrails:  python tools/repack-atlas.py
Повторный запуск безвреден: он перекладывает уже переложенное (места те же — те же координаты).
"""
import json
import struct
import sys

from PIL import Image

ATLAS = 'public/textures/art/atlas.webp'
META = 'public/textures/art/atlas.json'
MODELS = ['public/models/tiles.glb', 'public/models/traveller.glb']
SHEET_W = 4096
PAD = 6          # поле вокруг места: копируется из исходного атласа вместе с растянутым цветом
TOL = 2          # координата на краю места (px) — всё ещё это место


def read_glb(path):
    b = open(path, 'rb').read()
    jl = struct.unpack('<I', b[12:16])[0]
    j = json.loads(b[20:20 + jl])
    bl = struct.unpack('<I', b[20 + jl:24 + jl])[0]
    binary = bytearray(b[28 + jl:28 + jl + bl])
    return j, binary


def write_glb(path, j, binary):
    js = json.dumps(j, separators=(',', ':')).encode()
    js += b' ' * ((4 - len(js) % 4) % 4)
    binary = bytes(binary) + b'\0' * ((4 - len(binary) % 4) % 4)
    total = 12 + 8 + len(js) + 8 + len(binary)
    out = struct.pack('<III', 0x46546C67, 2, total) + struct.pack('<II', len(js), 0x4E4F534A) + js + struct.pack('<II', len(binary), 0x004E4942) + binary
    open(path, 'wb').write(out)


def uv_slots(j):
    """(смещение в байтах, шаг) каждого TEXCOORD_1 — по одному разу на общий accessor."""
    seen = set()
    for m in j['meshes']:
        for p in m['primitives']:
            i = p['attributes'].get('TEXCOORD_1')
            if i is None or i in seen:
                continue
            seen.add(i)
            a = j['accessors'][i]
            assert a['componentType'] == 5126 and a['type'] == 'VEC2', a
            bv = j['bufferViews'][a['bufferView']]
            base = bv.get('byteOffset', 0) + a.get('byteOffset', 0)
            stride = bv.get('byteStride', 8)
            yield from (base + k * stride for k in range(a['count']))


def main():
    meta = json.load(open(META))
    W, H = meta['size']
    sprites = meta['sprites']
    rects = [(name, s['rect']) for name, s in sprites.items()]

    def sprite_at(px, py):
        for name, (x, y, w, h) in rects:
            if x - TOL <= px <= x + w + TOL and y - TOL <= py <= y + h + TOL:
                return name
        return None

    models = {path: read_glb(path) for path in MODELS}
    hits = {}                                   # (path, offset) -> место
    lost = 0
    for path, (j, binary) in models.items():
        for off in uv_slots(j):
            u, v = struct.unpack_from('<ff', binary, off)
            if u <= 0.0001:
                continue                        # не печать (-1, -1) или без координат
            name = sprite_at(u * W, v * H)
            if name is None:
                lost += 1
                continue
            hits[(path, off)] = name
    if lost:
        sys.exit(f'{lost} координат печати не попали ни в одно место атласа — перекладка остановлена')
    used = sorted(set(hits.values()), key=lambda n: -sprites[n]['rect'][3])

    # полки по высоте
    place, cx, cy, row = {}, 0, 0, 0
    for name in used:
        x, y, w, h = sprites[name]['rect']
        cw, ch = w + PAD * 2, h + PAD * 2
        if cx + cw > SHEET_W:
            cx, cy, row = 0, cy + row, 0
        place[name] = (cx + PAD, cy + PAD)
        cx += cw
        row = max(row, ch)
    NH = (cy + row + 15) // 16 * 16

    src = Image.open(ATLAS).convert('RGBA')
    sheet = Image.new('RGBA', (SHEET_W, NH), (0, 0, 0, 0))
    for name in used:
        x, y, w, h = sprites[name]['rect']
        nx, ny = place[name]
        sheet.paste(src.crop((x - PAD, y - PAD, x + w + PAD, y + h + PAD)), (nx - PAD, ny - PAD))

    for (path, off), name in hits.items():
        j, binary = models[path]
        x, y, _, _ = sprites[name]['rect']
        nx, ny = place[name]
        u, v = struct.unpack_from('<ff', binary, off)
        struct.pack_into('<ff', binary, off, (u * W - x + nx) / SHEET_W, (v * H - y + ny) / NH)
    for path, (j, binary) in models.items():
        write_glb(path, j, binary)

    meta['size'] = [SHEET_W, NH]
    meta['sprites'] = {name: {**sprites[name], 'rect': [*place[name], *sprites[name]['rect'][2:]]} for name in used}
    json.dump(meta, open(META, 'w'), ensure_ascii=False, separators=(',', ':'))
    sheet.save(ATLAS, 'WEBP', quality=95, method=6, exact=True)
    print(f'мест {len(used)} из {len(sprites)}, лист {SHEET_W}x{NH} вместо {W}x{H}, координат {len(hits)}')


if __name__ == '__main__':
    main()
