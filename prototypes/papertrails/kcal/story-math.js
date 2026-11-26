/** Постояли столько секунд — привал. */
export const CAMP_AFTER = 20;
/** А столько — задремали. */
export const DOZE_AFTER = 180;
/** Клетка мира, единиц (TILE_SIZE). */
const CELL = 2;
/** Дальше этого от героя (в клетках) домик уже не виден. */
const REACH = 2;

/** Что делают друзья: 'walk' — шагают следом, 'camp' — привал у костра, 'doze' — дремлют. */
export function stage(walking, stopped) {
  if (walking || stopped < CAMP_AFTER) return 'walk';
  return stopped < DOZE_AFTER ? 'camp' : 'doze';
}

/** Ближайший к герою домик: середина его клетки в единицах мира; рядом нет — `null`. */
export function nearestHouse(hero, isHouse) {
  const hx = Math.round(hero.x / CELL);
  const hz = Math.round(hero.z / CELL);
  let best = null;
  let bestAt = Infinity;
  for (let cx = hx - REACH; cx <= hx + REACH; cx++) {
    for (let cz = hz - REACH; cz <= hz + REACH; cz++) {
      const away = Math.hypot(cx * CELL - hero.x, cz * CELL - hero.z);
      if (away < bestAt && isHouse(cx, cz)) {
        best = { x: cx * CELL, z: cz * CELL };
        bestAt = away;
      }
    }
  }
  return best;
}

/** Что может случиться здесь: домик и пруд рядом, а ещё — по землям (src/core/biomes.js). */
export function placeStories({ biome, house = false, pond = false }) {
  const byLand = { snow: 'snowball', pinewood: 'scare', desert: 'picnic', heartland: 'picnic', blossom: 'picnic' };
  return [house && 'visit', pond && 'fishing', byLand[biome]].filter(Boolean);
}

/** На ходу не отстают далеко: догонялки и чехарда — везде, снежки и испуг — по земле. */
export function walkStories({ biome }) {
  const here = placeStories({ biome }).filter((s) => s === 'snowball' || s === 'scare');
  return ['race', 'leapfrog', ...here];
}

/** Один из возможных; прошлый повторяется, только если другого здесь нет. */
export function pickStory(stories, last, rand = Math.random) {
  const fresh = stories.filter((s) => s !== last);
  const pool = fresh.length ? fresh : stories;
  return pool.length ? pool[Math.min(pool.length - 1, Math.floor(rand() * pool.length))] : null;
}
