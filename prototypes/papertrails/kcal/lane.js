// KcalAI: дорога прогулки петляет. Одно описание на двоих: по нему её кладёт мир (src/world/wfc.js) и по нему
// идёт герой (kcal/autopilot.js). В каждом участке (8 клеток вдоль пути) дорога может сделать один поворот на
// соседнюю колонку: прямо, уголок, уголок, прямо. Колонки — -1, 0 и +1; у старта всегда прямо.
import { CONFIG } from '../src/core/config.js';
import { hash2 } from '../src/core/rng.js';

const CHUNK = 8;
const OZ = CONFIG.wfc.originLocal[1];
/** Клетка мира, единиц (TILE_SIZE). */
const CELL = 2;
/** На сколько клеток до и после поворота герой плавно переходит на соседнюю колонку. */
const EASE = 0.8;

/** Колонка дороги на северной границе участка [cz]; участок cz = 0 — стартовый, он и его южный сосед — прямые. */
function borderShift(cz, seed) {
  // Через раз дорога возвращается в середину: за участок она смещается не больше чем на колонку.
  if (cz === 0 || cz === 1 || cz % 2 !== 0) return 0;
  return (hash2(7, cz, (seed + 961) | 0) % 3) - 1;
}

/** Участок дороги: колонка на юге и на севере и ряд внутри участка (0 — северный), где поворот. */
export function chunkLane(cz, seed) {
  return { north: borderShift(cz, seed), south: borderShift(cz + 1, seed), jog: 2 + (hash2(11, cz, (seed + 963) | 0) % 4) };
}

/** Колонка, по которой дорога уходит на север из ряда [z] (клетки мира). */
export function laneShift(z, seed) {
  const cz = Math.floor((z + OZ) / CHUNK);
  const { north, south, jog } = chunkLane(cz, seed);
  return z + OZ - cz * CHUNK <= jog ? north : south;
}

/** Где середина дороги в точке [z] — в единицах мира, с плавным переходом через поворот. */
export function laneX(z, seed) {
  const row = z / CELL + OZ;
  const cz = Math.floor((row + 0.5) / CHUNK);
  const { north, south, jog } = chunkLane(cz, seed);
  const at = Math.max(0, Math.min(1, (jog + EASE - (row - cz * CHUNK)) / (2 * EASE)));
  return (south + (north - south) * at * at * (3 - 2 * at)) * CELL;
}
