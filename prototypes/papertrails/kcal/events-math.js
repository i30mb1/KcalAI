/** Что случается по дороге (kcal/events.js): посылка, ягоды на тропе, стая птиц, воздушный шар, танец друзей. */
export const EVENTS = ['gift', 'berries', 'birds', 'balloon', 'dance'];

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
