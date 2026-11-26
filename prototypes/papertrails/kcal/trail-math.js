/**
 * Где мы на тропе дня: цель шагов делится между [lands] землями поровну.
 * @returns {{next: number|null, left: number}|null} next — следующая земля (null — впереди финиш), left — шагов
 *          до неё; null — цель взята или показывать нечего
 */
export function routeSpot(lands, goal, steps) {
  if (lands < 1 || goal <= 0 || steps >= goal) return null;
  const length = goal / lands;
  const at = Math.floor(Math.max(0, steps) / length);
  const left = Math.round((at + 1) * length - Math.max(0, steps));
  return { next: at + 1 < lands ? at + 1 : null, left };
}
