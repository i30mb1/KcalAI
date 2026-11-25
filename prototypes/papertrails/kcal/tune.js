// KcalAI: панель настроек прогулки поверх страницы — всё, что меняется на лету (window.KcalTune в autopilot.js).
// Видна, пока прогулку включает адрес со ?walk. Значения запоминаются в браузере; «Скопировать» отдаёт их
// одной строкой, чтобы зашить в код.
const KEY = 'kcal.walk.tune';
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
  box.innerHTML = '<b style="font-size:13px">Прогулка — настройки</b>';
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
    box.appendChild(row);
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
  box.appendChild(buttons);
  document.body.appendChild(box);
  tune.apply();
}

if (/[?&]walk\b/.test(location.search)) {
  const wait = setInterval(() => { if (window.__pt && window.KcalTune) { clearInterval(wait); start(); } }, 300);
}
