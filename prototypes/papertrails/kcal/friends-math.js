/** Дальше этого от середины тропы (kcal/lane.js) уже лес и заборы. */
export const ROAD_HALF = 0.45;

const onRoad = (p, mid) => ({ x: Math.max(mid - ROAD_HALF, Math.min(mid + ROAD_HALF, p.x)), z: p.z });

/**
 * Где идти тому, кто отстаёт на [back]: по следам героя ([trail] — от старых к новым, [hero] — где он сейчас),
 * а где следов не хватает — прямо позади. Всегда на тропе: [lane] называет её середину в точке z.
 */
export function followPoint(trail, hero, back, lane = () => 0) {
  const put = (p) => onRoad(p, lane(p.z));
  let at = hero;
  let left = back;
  for (let i = trail.length - 1; i >= 0; i--) {
    const next = trail[i];
    const length = Math.hypot(next.x - at.x, next.z - at.z);
    if (length >= left && length > 0) {
      const t = left / length;
      return put({ x: at.x + (next.x - at.x) * t, z: at.z + (next.z - at.z) * t });
    }
    left -= length;
    at = next;
  }
  // Север — это -z: позади значит южнее.
  return put({ x: at.x, z: at.z + left });
}
