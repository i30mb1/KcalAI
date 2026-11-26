// KcalAI: сцены прогулки — какие места проходит герой за день. Человек отмечает нужные в панели вида (tune.js),
// дневная дистанция делится между отмеченными поровну (src/core/biomes.js). Сцена — зона мира и вид её печати:
// новые сцены по теме приложения не заменяют исходные, а встают рядом с ними.
import { BIOME_INDEX } from '../src/core/biomes.js';

const KEY = 'kcal.walk.scenes';

/** Все сцены в порядке выбора: первая отмеченная — у старта. skin — вид печати (skins.js): 1 — Поле, 2 — Молочный край. */
export const SCENES = [
  { id: 'field', label: 'Поле Зёрнышка', short: 'Поле', biome: 'heartland', skin: 1 },
  { id: 'autumn', label: 'Осенние поля', short: 'Осень', biome: 'heartland', skin: 0 },
  { id: 'desert', label: 'Пустыня', short: 'Пустыня', biome: 'desert', skin: 0 },
  { id: 'dairy', label: 'Молочный край', short: 'Молоко', biome: 'snow', skin: 2 },
  { id: 'snow', label: 'Снег', short: 'Снег', biome: 'snow', skin: 0 },
  { id: 'blossom', label: 'Цветущий сад', short: 'Сад', biome: 'blossom', skin: 0 },
  { id: 'pinewood', label: 'Хвойный лес', short: 'Лес', biome: 'pinewood', skin: 0 },
];
const DEFAULT = ['field', 'dairy', 'desert', 'blossom', 'pinewood'];

/** Отмеченные сцены (id) в порядке SCENES; ничего не отмечено или не сохранено — по умолчанию. */
export function chosenIds() {
  let ids = null;
  try { ids = JSON.parse(localStorage.getItem(KEY) || 'null'); } catch { /* приватное окно — по умолчанию */ }
  const known = SCENES.filter((sc) => (Array.isArray(ids) ? ids : DEFAULT).includes(sc.id)).map((sc) => sc.id);
  return known.length ? known : DEFAULT;
}

/** Запомнить отмеченные сцены; мир собирается по ним при следующей загрузке. */
export function chooseIds(ids) {
  try { localStorage.setItem(KEY, JSON.stringify(ids)); } catch { /* приватное окно */ }
}

const GOAL_KEY = 'kcal.walk.goal';
const DEFAULT_GOAL = 8000;

/**
 * Цель шагов, по которой строится дорога дня: от телефона (window.KcalWalk), пока он не назвал — последняя
 * известная этому браузеру.
 */
export function dayGoal() {
  const live = (window.KcalWalk && window.KcalWalk.goal) || 0;
  try {
    if (live > 0) localStorage.setItem(GOAL_KEY, String(live));
    return live || Number(localStorage.getItem(GOAL_KEY)) || DEFAULT_GOAL;
  } catch {
    return live || DEFAULT_GOAL;
  }
}

/** Дорога сегодняшнего дня: отмеченные сцены по кругу, и каждый день начинается со следующей. */
export function todayScenes() {
  // Демо (?demo) показывает все земли сразу, по порядку.
  if (/[?&]demo/.test(location.search)) return SCENES.map((sc) => ({ ...sc, biomeIndex: BIOME_INDEX[sc.biome] }));
  const scenes = chosenScenes();
  const now = new Date();
  const day = Math.floor((now.getTime() - now.getTimezoneOffset() * 60000) / 86400000);
  const from = day % scenes.length;
  return [...scenes.slice(from), ...scenes.slice(0, from)];
}

/** Отмеченные сцены целиком — для мира (зона индексом) и подписей. */
export function chosenScenes() {
  const ids = chosenIds();
  return SCENES.filter((sc) => ids.includes(sc.id)).map((sc) => ({ ...sc, biomeIndex: BIOME_INDEX[sc.biome] }));
}
