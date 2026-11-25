// KcalAI: сцены прогулки — какие места проходит герой за день. Человек отмечает нужные в панели вида (tune.js),
// дневная дистанция делится между отмеченными поровну (src/core/biomes.js). Сцена — зона мира и вид её печати:
// новые сцены по теме приложения не заменяют исходные, а встают рядом с ними.
import { BIOME_INDEX } from '../src/core/biomes.js';

const KEY = 'kcal.walk.scenes';

/** Все сцены в порядке выбора: первая отмеченная — у старта. skin — вид печати (skins.js): 1 — Поле, 2 — Молочный край. */
export const SCENES = [
  { id: 'field', label: 'Поле Зёрнышка', biome: 'heartland', skin: 1 },
  { id: 'autumn', label: 'Осенние поля', biome: 'heartland', skin: 0 },
  { id: 'desert', label: 'Пустыня', biome: 'desert', skin: 0 },
  { id: 'dairy', label: 'Молочный край', biome: 'snow', skin: 2 },
  { id: 'snow', label: 'Снег', biome: 'snow', skin: 0 },
  { id: 'blossom', label: 'Цветущий сад', biome: 'blossom', skin: 0 },
  { id: 'pinewood', label: 'Хвойный лес', biome: 'pinewood', skin: 0 },
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

/** Отмеченные сцены целиком — для мира (зона индексом) и подписей. */
export function chosenScenes() {
  const ids = chosenIds();
  return SCENES.filter((sc) => ids.includes(sc.id)).map((sc) => ({ ...sc, biomeIndex: BIOME_INDEX[sc.biome] }));
}
