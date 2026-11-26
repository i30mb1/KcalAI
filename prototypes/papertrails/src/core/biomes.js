// FROZEN contract (owner: orchestrator): which BIOME a cell belongs to. Pure, deterministic, allocation-free
// lookups shared by the WFC (tile choice), the world, weather / ambient life and the grade.
//
// Layout (KcalAI): bands across the walk to the north, BIOME_BAND tiles each, with a gently wobbling border.
// Which band comes first is decided by the day (kcal/scenes.js); see setScenes() below.
//
//   heartland  the original warm autumn farmland            (leaves drifting, clear)
//   desert     sand, dunes, cacti, tents, oasis              (heat haze, dust motes, hard sun)
//   snow       snowy pines, cabins, frozen ponds             (snowfall, cool light)
//   blossom    spring green, pink blossom trees, gardens     (petals, butterflies)
//   pinewood   dark misty conifer forest, mushrooms, marsh   (light rain / mist, fireflies at night)

export const BIOMES = ['heartland', 'desert', 'snow', 'blossom', 'pinewood'];
export const BIOME_INDEX = { heartland: 0, desert: 1, snow: 2, blossom: 3, pinewood: 4 };

function h2(seed, x, y, k) {
  let h = (Math.imul(x | 0, 0x9e3779b1) ^ Math.imul(y | 0, 0x85ebca6b) ^ Math.imul((seed | 0) + k * 0x632be5ab, 0xc2b2ae35)) >>> 0;
  h ^= h >>> 15; h = Math.imul(h, 0x2c1b3c6d) >>> 0; h ^= h >>> 12; h = Math.imul(h, 0x297a2d39) >>> 0; h ^= h >>> 15;
  return (h >>> 0) / 4294967296;
}

// KcalAI: сцены прогулки — полосами поперёк пути на север. Сцена — зона (BIOMES) и вид её печати (skin: 0 — исходный
// атлас, 1 — Поле Зёрнышка, kcal/field-prints.js). Какие сцены идут, выбирает человек, с какой начинается день —
// календарь (kcal/scenes.js): цель шагов делится между ними поровну, дальше они идут по кругу. Та же раскладка —
// на тропе дня (kcal/trail-math.js). Граница волнистая, но всегда поперёк дороги: идти вдоль шва нельзя.
/** Клеток дороги на шаг: шаг — 0,7 единицы мира, клетка — 2. */
const TILES_PER_STEP = 0.35;
const DEFAULT_GOAL = 8000;
/** Старт — чуть внутри первой сцены, чтобы позади героя была она же. */
const BAND_START = 10;
let SCENES = [{ biome: 0, skin: 0 }, { biome: 1, skin: 0 }, { biome: 2, skin: 0 }, { biome: 3, skin: 0 }, { biome: 4, skin: 0 }];
/** Длина сцены вдоль пути, клеток. */
export let BIOME_BAND = (DEFAULT_GOAL * TILES_PER_STEP) / SCENES.length;
/** Задать дорогу дня — до создания мира. [list] — { biome: индекс в BIOMES, skin } по порядку; пустой — все зоны. */
export function setScenes(list, goal = DEFAULT_GOAL) {
  if (list && list.length) SCENES = list.map((sc) => ({ biome: sc.biome | 0, skin: sc.skin | 0 }));
  BIOME_BAND = (goal * TILES_PER_STEP) / SCENES.length;
}
/** Сцена полосы k (0 — у старта, дальше на север — следующие): индекс в SCENES. */
function bandScene(k) {
  return ((k % SCENES.length) + SCENES.length) % SCENES.length;
}
// smooth value noise for the domain warp
function vnoise(seed, x, y, k) {
  const xi = Math.floor(x); const yi = Math.floor(y);
  const fx = x - xi; const fy = y - yi;
  const ux = fx * fx * (3 - 2 * fx); const uy = fy * fy * (3 - 2 * fy);
  const a = h2(seed, xi, yi, k); const b = h2(seed, xi + 1, yi, k);
  const c = h2(seed, xi, yi + 1, k); const d = h2(seed, xi + 1, yi + 1, k);
  return a + (b - a) * ux + (c - a) * uy + (a - b - c + d) * ux * uy;
}

const _d = new Float64Array(BIOMES.length); // nearest site distance per biome (scratch)

/** Полоса пути под точкой (x, z) в клетках: с волнистой границей поперёк пути. */
function bandAt(seed, x, z) {
  const wz = z + (vnoise(seed, x / 5.5, z / 5.5, 12) - 0.5) * 3.2;
  return { wz, k: Math.floor((BAND_START - wz) / BIOME_BAND) };
}

/** Fills _d with the distance (tiles) from (x, z) to the nearest band of each biome. x, z in TILE units (floats ok). */
function distances(seed, x, z) {
  const { wz, k: k0 } = bandAt(seed, x, z);
  for (let i = 0; i < _d.length; i++) _d[i] = 1e9;
  for (let k = k0 - 2; k <= k0 + 2; k++) {
    const d = Math.abs(wz - (BAND_START - (k + 0.5) * BIOME_BAND));
    const b = SCENES[bandScene(k)].biome;
    if (d < _d[b]) _d[b] = d;
  }
}

/** Сцена клетки: индекс в выбранных (kcal/scenes.js). */
export function sceneIndexAt(seed, cx, cz) { return bandScene(bandAt(seed, cx, cz).k); }

/** Вид печати клетки: 0 — исходный атлас, 1 — Поле Зёрнышка. */
export function skinAt(seed, cx, cz) { return SCENES[sceneIndexAt(seed, cx, cz)].skin; }

/** Biome index (into BIOMES) of the cell / tile-space point. */
export function biomeIndexAt(seed, cx, cz) {
  distances(seed, cx, cz);
  let best = 0;
  for (let i = 1; i < _d.length; i++) if (_d[i] < _d[best]) best = i;
  return best;
}

/** Biome id ('heartland' | 'desert' | ...) of cell (cx, cz). */
export function biomeAt(seed, cx, cz) { return BIOMES[biomeIndexAt(seed, cx, cz)]; }

/**
 * Biome + how deep inside it the point is.
 * @returns {{ id: string, index: number, edge: number }} edge = approx. distance (tiles) to the nearest other biome.
 *          The SAME object is returned on every call (copy the fields you keep).
 */
const _info = { id: 'heartland', index: 0, edge: 0 };
export function biomeInfo(seed, cx, cz) {
  distances(seed, cx, cz);
  let best = 0;
  for (let i = 1; i < _d.length; i++) if (_d[i] < _d[best]) best = i;
  let second = 1e9;
  for (let i = 0; i < _d.length; i++) if (i !== best && _d[i] < second) second = _d[i];
  _info.id = BIOMES[best]; _info.index = best; _info.edge = (second - _d[best]) / 2;
  return _info;
}

/**
 * Smooth blend weights (sum = 1) of every biome around a point, for weather / light / sound cross-fades.
 * @param {number} x tile-space X (world x / TILE_SIZE)  @param {number} z tile-space Z
 * @param {Float32Array|number[]} out length BIOMES.length  @param {number} [soft=1.6] blend half-width in tiles
 */
export function biomeWeights(seed, x, z, out, soft = 1.6) {
  distances(seed, x, z);
  let min = 1e9;
  for (let i = 0; i < _d.length; i++) if (_d[i] < min) min = _d[i];
  let sum = 0;
  for (let i = 0; i < _d.length; i++) { const w = Math.exp(-(_d[i] - min) / soft); out[i] = w; sum += w; }
  for (let i = 0; i < _d.length; i++) out[i] /= sum;
  return out;
}
