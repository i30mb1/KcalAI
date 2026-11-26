/** Тропа прогулки — колонна x = 0 (kcal/autopilot.js); дальше этого от середины уже лес и заборы. */
export const ROAD_HALF = 0.25;

const onRoad = (p) => ({ x: Math.max(-ROAD_HALF, Math.min(ROAD_HALF, p.x)), z: p.z });

/**
 * Где идти тому, кто отстаёт на [back]: по следам героя ([trail] — от старых к новым, [hero] — где он сейчас),
 * а где следов не хватает — прямо позади. Всегда на тропе.
 */
export function followPoint(trail, hero, back) {
  let at = hero;
  let left = back;
  for (let i = trail.length - 1; i >= 0; i--) {
    const next = trail[i];
    const length = Math.hypot(next.x - at.x, next.z - at.z);
    if (length >= left && length > 0) {
      const t = left / length;
      return onRoad({ x: at.x + (next.x - at.x) * t, z: at.z + (next.z - at.z) * t });
    }
    left -= length;
    at = next;
  }
  // Север — это -z: позади значит южнее.
  return onRoad({ x: at.x, z: at.z + left });
}
