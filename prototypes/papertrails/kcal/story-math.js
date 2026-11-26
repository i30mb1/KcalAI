/** Постояли столько секунд — привал. */
export const CAMP_AFTER = 45;
/** А столько — задремали. */
export const DOZE_AFTER = 300;
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
