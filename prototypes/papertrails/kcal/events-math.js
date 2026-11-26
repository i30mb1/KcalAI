/** Что случается по дороге (kcal/events.js): посылка, ягоды, птицы, шар, танцы, радуга, бабочки, утята, змей, дождик. */
export const EVENTS = ['gift', 'berries', 'birds', 'balloon', 'dance', 'rainbow', 'butterflies', 'ducks', 'kite', 'rain'];

/** Следующее событие — любое, кроме только что бывшего. */
export function pickEvent(last, rand = Math.random) {
  const pool = EVENTS.filter((e) => e !== last);
  return pool[Math.min(pool.length - 1, Math.floor(rand() * pool.length))];
}

/** Пауза до следующего, секунды: на ходу — чаще, стоя мир тоже не замирает. */
export function pause(walking, rand = Math.random) {
  const [from, to] = walking ? [10, 22] : [20, 40];
  return from + (to - from) * rand();
}

/** Больше этого за раз — не шаг, а свежие данные (открыли страницу, вернулась связь): задним числом не празднуют. */
const LEAP = 400;

/** Что только что взято: 'goal', круглая тысяча шагов или `null`. */
export function milestone(before, now, goal) {
  if (before < 0 || now - before > LEAP || now <= before) return null;
  if (goal > 0 && before < goal && now >= goal) return 'goal';
  const passed = Math.floor(now / 1000);
  return passed > Math.floor(before / 1000) ? passed * 1000 : null;
}

/**
 * Насколько отойти вбок от героя, чтобы оказаться за кадром: [screenX] — где на экране (−1..1) точка в d шагах от
 * него. С запасом на размер самой вещи.
 */
export function offscreen(screenX, margin = 1.2) {
  let d = 1;
  while (d < 30 && Math.abs(screenX(d)) <= margin) d += 0.5;
  return d;
}

/** За сколько шагов веха встаёт на дороге: около сорока клеток вперёд — далеко за краем экрана. */
const HORIZON = 60;

/** Что пора поставить впереди: { what: 'goal' | круглая тысяча, left: сколько до неё шагов } или `null`. */
export function upcoming(steps, goal) {
  if (steps < 0) return null;
  const round = (Math.floor(steps / 1000) + 1) * 1000;
  const goalFirst = goal > steps && goal <= round;
  const left = (goalFirst ? goal : round) - steps;
  return left <= HORIZON ? { what: goalFirst ? 'goal' : round, left } : null;
}
