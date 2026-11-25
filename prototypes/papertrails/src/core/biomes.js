// FROZEN contract (owner: orchestrator): which BIOME a cell belongs to. Pure, deterministic, allocation-free
// lookups shared by the WFC (tile choice), the world, weather / ambient life and the grade.
//
// Layout (KcalAI): bands across the walk to the north, BIOME_BAND tiles each, with a gently wobbling border.
// The start is always 'heartland'; see journey() below.
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

// KcalAI: зоны — полосами поперёк пути на север, по зоне на ~1 600 шагов: пять зон — дневная цель 8 000 шагов.
// Начинается всегда с полей (у старта мост и домик), остальные четыре идут в своём порядке у каждого мира (seed).
// Граница между полосами волнистая, но всегда поперёк дороги: идти вдоль шва двух зон нельзя.
/** Длина зоны вдоль пути, клеток: 1 600 шагов по 0,7 при клетке 2. */
export const BIOME_BAND = 560;
/** Старт — чуть внутри полей, чтобы позади героя тоже было поле. */
const BAND_START = 10;
const ORDERS = new Map();
function journey(seed) {
  let order = ORDERS.get(seed);
  if (!order) {
    const rest = [1, 2, 3, 4];                    // пустыня, снег, цветущий сад, хвойный лес
    for (let i = rest.length - 1; i > 0; i--) { const j = Math.floor(h2(seed, i, 0, 77) * (i + 1)); [rest[i], rest[j]] = [rest[j], rest[i]]; }
    ORDERS.set(seed, (order = [0, ...rest]));
  }
  return order;
}
/** Зона полосы k (0 — поля у старта, дальше на север — следующие). */
function bandBiome(seed, k) {
  const order = journey(seed);
  return order[((k % order.length) + order.length) % order.length];
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

/** Fills _d with the distance (tiles) from (x, z) to the nearest band of each biome. x, z in TILE units (floats ok). */
function distances(seed, x, z) {
  const wz = z + (vnoise(seed, x / 5.5, z / 5.5, 12) - 0.5) * 3.2; // волнистая граница поперёк пути
  const k0 = Math.floor((BAND_START - wz) / BIOME_BAND);
  for (let i = 0; i < _d.length; i++) _d[i] = 1e9;
  for (let k = k0 - 2; k <= k0 + 2; k++) {
    const d = Math.abs(wz - (BAND_START - (k + 0.5) * BIOME_BAND));
    const b = bandBiome(seed, k);
    if (d < _d[b]) _d[b] = d;
  }
}

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
