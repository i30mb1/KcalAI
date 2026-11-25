// KcalAI: панель настроек прогулки поверх страницы — всё, что меняется на лету (window.KcalTune в autopilot.js).
// Каждый может подогнать вид под себя: панель спрятана под шестерёнку в углу, пока загружен мир прогулки.
// Значения запоминаются в браузере; «Скопировать» отдаёт их одной строкой, «Сбросить» — к умолчаниям.
import { biomeAt } from '../src/core/biomes.js';

const KEY = 'kcal.walk.tune';
const OPEN_KEY = 'kcal.walk.tune.open';
const ROWS = [
  ['Камера: наклон, °', 'pitch', 15, 80, 1],
  ['Камера: удаление', 'distance', 5, 25, 0.5],
  ['Камера: угол обзора, °', 'fov', 15, 60, 1],
  ['Герой ниже ↔ выше в кадре', 'screenUp', -2, 4, 0.1],
  ['Герой: центр ↔ слева от ленты', 'heroLeft', 0, 1, 0.05],
  ['Герой: размер', 'heroScale', 0.6, 2, 0.05],
  ['Герой: держится от карточек', 'radius', 0.08, 0.4, 0.01],
  ['Круг оживания: ± клеток к 5', 'reach', -4, 6, 0.5],
  ['Скорость, км/ч (0 — как дорожка)', 'speed', 0, 8, 0.5],
  ['Стекло дневника: плотность', 'glassTint', 0, 1, 0.02],
  ['Стекло дневника: размытие, px', 'glassBlur', 0, 30, 1],
  ['Размытие вдали', 'farBlur', 0, 3, 0.05],
  ['Размытие: сила, px', 'blurPx', 0, 25, 0.5],
  ['Резкая полоса вокруг героя', 'sharpBand', 0.1, 0.9, 0.02],
];

function start() {
  const tune = window.KcalTune;
  try { Object.assign(tune, JSON.parse(localStorage.getItem(KEY) || '{}')); } catch { /* нет сохранённого — по умолчанию */ }
  const save = () => { try { localStorage.setItem(KEY, JSON.stringify(values())); } catch { /* приватное окно */ } };
  const values = () => Object.fromEntries(ROWS.map(([, k]) => [k, tune[k]]));

  const box = document.createElement('div');
  box.id = 'walk-tune';
  box.style.cssText = 'position:fixed;left:12px;bottom:12px;z-index:3;width:250px;padding:12px 14px;border-radius:14px;'
    + 'background:rgba(28,25,20,.86);color:#f4efe4;font:12px/1.35 system-ui,sans-serif;display:grid;gap:8px;'
    + 'backdrop-filter:blur(8px);box-shadow:0 6px 24px rgba(0,0,0,.3)';
  // заголовок сворачивает панель в плашку и раскрывает обратно; состояние запоминается
  const head = document.createElement('b');
  head.style.cssText = 'font-size:13px;cursor:pointer;display:flex;justify-content:space-between;gap:12px';
  box.appendChild(head);
  const body = document.createElement('div');
  body.style.cssText = 'display:grid;gap:8px';
  box.appendChild(body);
  const setOpen = (open) => {
    body.style.display = open ? 'grid' : 'none';
    box.style.width = open ? '250px' : '40px';
    box.style.height = open ? 'auto' : '40px';
    box.style.padding = open ? '12px 14px' : '0';
    box.style.borderRadius = open ? '14px' : '20px';
    head.style.justifyContent = open ? 'space-between' : 'center';
    head.style.alignItems = 'center';
    head.style.height = open ? 'auto' : '40px';
    head.title = open ? '' : 'Вид прогулки';
    head.innerHTML = open ? '<span>Вид прогулки</span><span style="font-size:16px">×</span>' : '<span style="font-size:20px;line-height:1">⚙</span>';
    try { localStorage.setItem(OPEN_KEY, open ? '1' : '0'); } catch { /* приватное окно */ }
  };
  head.addEventListener('click', () => setOpen(body.style.display === 'none'));
  // НА ВРЕМЯ РАЗРАБОТКИ: слайдер сцен — герой встаёт туда, докуда довели бы N шагов, чтобы посмотреть все зоны.
  // Не сохраняется и не копируется; следующая загрузка ставит героя по настоящим шагам дня.
  const ZONES = { heartland: 'поле', desert: 'пустыня', snow: 'снег', blossom: 'сад', pinewood: 'лес' };
  const scene = document.createElement('label');
  scene.style.cssText = 'display:grid;gap:3px;padding-bottom:6px;border-bottom:1px solid #3a3328';
  const now = Math.max(0, (window.KcalWalk && window.KcalWalk.steps) | 0);
  scene.innerHTML = `<span style="display:flex;justify-content:space-between;color:#e8c47a"><span>Сцена (разработка): шаги</span><b style="color:#f4efe4" data-v></b></span>`
    + `<input type="range" min="0" max="12000" step="20" value="${now}" style="width:100%;accent-color:#e8c47a">`;
  const sceneInput = scene.querySelector('input'), sceneShown = scene.querySelector('[data-v]');
  const zoneOf = (steps) => ZONES[biomeAt(window.__pt.state.seed, 0, Math.round(-steps * 0.35))] || '';
  const showScene = () => { const v = Number(sceneInput.value); sceneShown.textContent = `${v} · ${zoneOf(v)}`; };
  sceneInput.addEventListener('input', () => { showScene(); window.KcalPlace && window.KcalPlace(Number(sceneInput.value)); });
  showScene();
  body.appendChild(scene);
  for (const [label, key, min, max, step] of ROWS) {
    const row = document.createElement('label');
    row.style.cssText = 'display:grid;gap:3px';
    const val = tune[key] ?? 0;
    row.innerHTML = `<span style="display:flex;justify-content:space-between;color:#b5ab96"><span>${label}</span><b style="color:#f4efe4" data-v>${val}</b></span>`
      + `<input type="range" min="${min}" max="${max}" step="${step}" value="${val}" style="width:100%;accent-color:#7fbf8c">`;
    const input = row.querySelector('input'), shown = row.querySelector('[data-v]');
    input.addEventListener('input', () => {
      const v = Number(input.value);
      tune[key] = key === 'speed' && v === 0 ? null : v;
      shown.textContent = v;
      tune.apply(); save();
    });
    body.appendChild(row);
  }
  const buttons = document.createElement('div');
  buttons.style.cssText = 'display:grid;grid-template-columns:1fr 1fr 1fr;gap:6px';
  const btn = (text, onClick) => {
    const b = document.createElement('button');
    b.textContent = text;
    b.style.cssText = 'border:0;border-radius:8px;padding:7px 4px;background:#332d23;color:#f4efe4;font:600 12px system-ui;cursor:pointer';
    b.addEventListener('click', onClick);
    buttons.appendChild(b);
    return b;
  };
  btn('День / ночь', () => window.KcalNight && window.KcalNight.toggle());
  const copy = btn('Скопировать', () => {
    const text = JSON.stringify(values());
    navigator.clipboard?.writeText(text).then(() => { copy.textContent = 'Скопировано'; }, () => { copy.textContent = text; });
  });
  btn('Сбросить', () => { try { localStorage.removeItem(KEY); } catch { /* ничего */ } location.reload(); });
  body.appendChild(buttons);
  document.body.appendChild(box);
  let open = false;
  try { open = localStorage.getItem(OPEN_KEY) === '1'; } catch { /* по умолчанию спрятана */ }
  setOpen(open);
  tune.apply();
}

const wait = setInterval(() => { if (window.__pt && window.KcalTune) { clearInterval(wait); start(); } }, 300);
