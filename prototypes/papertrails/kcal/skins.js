// KcalAI: виды печати сцен в одном листе. Каждый вид (1 — Поле Зёрнышка, 2 — Молочный край, …) перепечатывает
// часть мест атласа; все перепечатанные места всех отмеченных видов раскладываются полками в один компактный лист,
// а таблица говорит, куда переехало место атласа в каждом виде. Плитка сцены несёт номер вида в цвете экземпляра,
// вершинный шейдер по таблице переносит её координаты печати в лист (src/world/world.js). Исходный атлас не
// трогается: им печатаются исходные сцены и всё, что вид не перепечатал. Лист вместо копии атласа на вид — в разы
// меньше памяти видеокарты, сколько бы видов ни было отмечено.
import { paintSprite, rng } from './print-kit.js';
import { FIELD } from './field-prints.js';
import { DAIRY } from './dairy-prints.js';

/** Виды по номерам: номер едет в цвете экземпляра плитки (kcal/scenes.js -> src/core/biomes.js). */
export const SKINS = { 1: FIELD, 2: DAIRY };
/** Мест в одном виде — не больше (ширина таблицы — вдвое: место и сдвиг). */
export const SKIN_MAX = 64;
const SHEET_W = 4096;
const PAD = 4;

/** Описание атласа (места, контуры) — один раз на загрузку. */
let metaP = null;
export function atlasMeta() {
  metaP ||= fetch(new URL('../public/textures/art/atlas.json', import.meta.url)).then((r) => (r.ok ? r.json() : null)).catch(() => null);
  return metaP;
}

/**
 * Пиксели печати для листа: волокно бумаги по закрашенному и цвет, растянутый на [pad] в прозрачное вокруг (как у
 * самого атласа), — иначе на мелких мипах края темнеют. Холст хранит цвет только под непрозрачным, поэтому растяжка
 * — тут: волной от края, каждый прозрачный пиксель — один раз.
 */
export function finish(cv, pad) {
  const w = cv.width + pad * 2, h = cv.height + pad * 2;
  const big = document.createElement('canvas'); big.width = w; big.height = h;
  const g = big.getContext('2d', { willReadFrequently: true });
  g.drawImage(cv, pad, pad);
  const img = g.getImageData(0, 0, w, h); const d = img.data;
  const R = rng(w * 31 + h);
  const filled = new Uint8Array(w * h);
  for (let y = 0, i = 0; y < h; y++) {
    const wy = Math.sin(y * 0.09), sy = Math.sin(y * 0.05) * 3;
    for (let x = 0; x < w; x++, i++) {
      if (d[i * 4 + 3] === 0) continue;
      filled[i] = 1;
      const f = 1 + (R() - 0.5) * 0.07 + 0.04 * Math.sin(x * 0.11 + sy) * wy;
      d[i * 4] = Math.min(255, d[i * 4] * f); d[i * 4 + 1] = Math.min(255, d[i * 4 + 1] * f); d[i * 4 + 2] = Math.min(255, d[i * 4 + 2] * f);
    }
  }
  // волна растяжки: прозрачные соседи закрашенных, слой за слоем
  let front = [];
  const near = (i) => { const x = i % w; return (x > 0 && filled[i - 1]) || (x < w - 1 && filled[i + 1]) || (i >= w && filled[i - w]) || (i < w * (h - 1) && filled[i + w]); };
  for (let i = 0; i < w * h; i++) if (!filled[i] && near(i)) front.push(i);
  for (let pass = 0; pass < 10 && front.length; pass++) {
    for (const i of front) {
      const x = i % w; let r = 0, gg = 0, b = 0, n = 0;
      for (const j of [x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, i - w, i + w]) {
        if (j < 0 || j >= w * h || filled[j] !== 1) continue;
        r += d[j * 4]; gg += d[j * 4 + 1]; b += d[j * 4 + 2]; n++;
      }
      if (n) { d[i * 4] = r / n; d[i * 4 + 1] = gg / n; d[i * 4 + 2] = b / n; }
    }
    for (const i of front) filled[i] = 1;            // закрашены этим слоем — источник для следующего
    const next = [];
    for (const i of front) {
      const x = i % w;
      for (const j of [x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, i - w, i + w]) if (j >= 0 && j < w * h && filled[j] === 0) { filled[j] = 2; next.push(j); }
    }
    front = next;
  }
  return { data: d, width: w, height: h };
}

/**
 * Лист видов [skins] (номера) и таблица переездов — для шейдера плиток.
 * @return { sheet: DataTexture, table: DataTexture, scale: [атлас w, h, лист w, h] } или null, если нечего печатать
 */
export async function buildSkins(skins, THREE, art) {
  const meta = await atlasMeta();
  if (!meta || !skins.length) return null;
  const t0 = performance.now();
  const items = [];
  for (const skin of skins) for (const [name, draw] of Object.entries(SKINS[skin] || {})) {
    const s = meta.sprites[name]; if (!s) continue;
    const [x, y, w, h] = s.rect;
    items.push({ skin, x, y, w, h, px: finish(paintSprite(draw, w, h, s.outline), PAD) });
  }
  if (!items.length) return null;
  // полки: по высоте сверху вниз, слева направо
  items.sort((a, b) => b.px.height - a.px.height);
  let cx = 0, cy = 0, row = 0;
  for (const it of items) {
    if (cx + it.px.width > SHEET_W) { cx = 0; cy += row; row = 0; }
    it.dx = cx; it.dy = cy; cx += it.px.width; row = Math.max(row, it.px.height);
  }
  const H = cy + row;
  const buf = new Uint8Array(SHEET_W * H * 4);
  for (const it of items) {
    const { data, width, height } = it.px;
    for (let r = 0; r < height; r++) buf.set(data.subarray(r * width * 4, (r + 1) * width * 4), ((it.dy + r) * SHEET_W + it.dx) * 4);
  }
  const sheet = new THREE.DataTexture(buf, SHEET_W, H, THREE.RGBAFormat);
  for (const k of ['colorSpace', 'wrapS', 'wrapT', 'anisotropy']) if (art) sheet[k] = art[k];
  sheet.flipY = false; sheet.generateMipmaps = true;
  sheet.minFilter = THREE.LinearMipmapLinearFilter; sheet.magFilter = THREE.LinearFilter;
  sheet.needsUpdate = true;
  // таблица: строка вида (номер - 1), по месту — два текселя: прямоугольник в атласе (px) и сдвиг в лист (px)
  const rows = Math.max(...skins);
  const tab = new Float32Array(SKIN_MAX * 2 * rows * 4);
  const used = new Array(rows).fill(0);
  for (const it of items) {
    const r = it.skin - 1, i = used[r]++;
    if (i >= SKIN_MAX) continue;
    const o = (r * SKIN_MAX * 2 + i * 2) * 4;
    tab.set([it.x, it.y, it.x + it.w, it.y + it.h, it.dx + PAD - it.x, it.dy + PAD - it.y, 0, 0], o);
  }
  const table = new THREE.DataTexture(tab, SKIN_MAX * 2, rows, THREE.RGBAFormat, THREE.FloatType);
  table.minFilter = table.magFilter = THREE.NearestFilter; table.generateMipmaps = false; table.needsUpdate = true;
  console.info(`[kcal] виды печати ${skins.join(', ')}: ${items.length} мест, лист ${SHEET_W}x${H}, ${Math.round(performance.now() - t0)} мс`);
  return { sheet, table, scale: [meta.size[0], meta.size[1], SHEET_W, H] };
}
