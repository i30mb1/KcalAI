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
