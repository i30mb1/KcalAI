// KcalAI: демо прогулки — адрес страницы с `?demo`. Кнопками показывает то, чего на дорожке пришлось бы ждать:
// ход и стоянки, события, сюжеты, движения Зёрнышка, вехи и все земли. Инструмент разработчика: без `?demo`
// не загружается, в дневник ничего не пишет.

const GROUPS = {
  'Идём': [['Стоим', () => speed(0)], ['Шаг', () => speed(3)], ['Бег', () => speed(6)], ['Привал', () => state('camp')], ['Дрёма', () => state('doze')], ['День / ночь', () => window.KcalNight.toggle()]],
  'События': [['Посылка', 'gift'], ['Ягоды', 'berries'], ['Птицы', 'birds'], ['Шар', 'balloon'], ['Танцы', 'dance'], ['Радуга', 'rainbow'], ['Бабочки', 'butterflies'], ['Утята', 'ducks'], ['Змей', 'kite'], ['Тучка', 'rain']]
    .map(([label, name]) => [label, () => window.KcalEvents.happen(name)]),
  'Сюжеты': [['В гости', 'visit'], ['Рыбалка', 'fishing'], ['Снежки', 'snowball'], ['Пикник', 'picnic'], ['Испуг', 'scare'], ['Догонялки', 'race'], ['Чехарда', 'leapfrog']]
    .map(([label, name]) => [label, () => {
      window.KcalStory.set(null);
      if (!window.KcalStory.play(name)) return 'Рядом нет домика или пруда — пройди дальше';
      return null;
    }]),
  'Зёрнышко': [['Машет', 'wave'], ['Озирается', 'look'], ['Прыг', 'hop'], ['Тянется', 'stretch'], ['Кружится', 'spin']]
    .map(([label, name]) => [label, () => {
      speed(0);
      window.KcalStory.set(null);
      setTimeout(() => window.KcalStory.act(name), 300);
    }]),
  'Вехи': [['Тысяча', () => window.KcalEvents.cheer(6000)], ['Цель!', () => window.KcalEvents.cheer('goal')]],
};

function speed(kmh) {
  window.KcalStory && window.KcalStory.set(null);
  window.KcalTune.speed = kmh;
}

function state(name) {
  window.KcalTune.speed = 0;
  // Кадр на то, чтобы мир заметил остановку.
  setTimeout(() => window.KcalStory.set(name), 100);
}

/** Перенести героя в середину участка земли [index] на дороге дня. */
function goTo(index) {
  const route = window.KcalRoute;
  const pt = window.__pt;
  const steps = ((index + 0.5) * route.goal) / route.lands.length;
  const z = -steps * 0.7;
  import('./lane.js').then(({ laneX }) => {
    pt.traveller.teleport(laneX(z, pt.wfc.seed), z, 0, -1);
    pt.follow.snap(pt.traveller);
  });
}

function start() {
  const panel = document.createElement('div');
  panel.id = 'walk-demo';
  panel.style.cssText = 'position:fixed;left:8px;top:320px;bottom:64px;width:230px;z-index:4;overflow:auto;padding:10px;border-radius:14px;'
    + 'background:#fdf8eaf2;color:#2c2618;border:2px solid #d9c9a3;box-shadow:0 8px 18px #3d300c55;font:700 12px/1.3 system-ui,sans-serif';
  const note = document.createElement('div');
  note.style.cssText = 'min-height:16px;margin:4px 0;color:#9c3b2b';
  const group = (title, buttons) => {
    const head = document.createElement('div');
    head.textContent = title;
    head.style.cssText = 'margin:8px 0 4px;font-weight:900';
    const row = document.createElement('div');
    row.style.cssText = 'display:flex;flex-wrap:wrap;gap:4px';
    for (const [label, run] of buttons) {
      const button = document.createElement('button');
      button.type = 'button';
      button.textContent = label;
      button.style.cssText = 'font:700 12px system-ui,sans-serif;border:1px solid #b78d4d;border-radius:8px;padding:4px 8px;background:#fff;color:inherit;cursor:pointer';
      button.addEventListener('click', () => {
        note.textContent = run() || '';
      });
      row.appendChild(button);
    }
    panel.append(head, row);
  };
  for (const [title, buttons] of Object.entries(GROUPS)) group(title, buttons);
  group('Земли', window.KcalRoute.lands.map((land, i) => [land, () => goTo(i)]));
  panel.appendChild(note);
  document.body.appendChild(panel);
}

const wait = setInterval(() => {
  if (!window.__pt || !window.KcalStory || !window.KcalEvents || !window.KcalRoute) return;
  clearInterval(wait);
  start();
}, 500);
