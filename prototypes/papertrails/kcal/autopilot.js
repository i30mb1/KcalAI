// KcalAI: герой papertrails идёт сам — на север по петляющей дороге прогулки (kcal/lane.js, её прокладывает
// src/world/wfc.js), в темпе беговой дорожки со страницы дневника. Камера не вращается: север всегда вверху.
// Читает window.KcalWalk (скорость S.vis км/ч), отдаёт направление через window.__ptInput (см. src/main.js).
import { laneX } from './lane.js';
const out = { x: 0, z: 0 };
/** Насколько герой отходит от середины тропы, виляя. */
const SWAY = 0.38;

/**
 * Всё, что в прогулке настраивается на лету. Значения по умолчанию — здесь (подобраны на странице дневника
 * 2026-11-25); панель (kcal/tune.js) меняет их и зовёт apply(). speed: null — темп дорожки; heroLeft: 0 — герой
 * по центру, 1 — слева от колонки дневника.
 */
window.KcalTune = {
  pitch: 40, distance: 16, fov: 24, screenUp: 0.2, speed: null, reach: -1.5, radius: 0.17, heroScale: 1.1, heroLeft: 0,
  glassTint: 0, glassBlur: 0,
  farBlur: 1.0, blurPx: 12.5, sharpBand: 0.36,
  apply() {
    const root = document.documentElement.style;                 // стекло колонки дневника над миром (index.html)
    root.setProperty('--glass-tint', this.glassTint);
    root.setProperty('--glass-blur', this.glassBlur + 'px');
    const pt = window.__pt;
    if (!pt) return;
    pt.follow.setPose({ pitchDeg: this.pitch, distance: this.distance, fov: this.fov, screenUp: this.screenUp, yawDeg: 0 });
    pt.state.viewReach = this.reach;                     // ± клеток к кругу (5), где встаёт окружение
    pt.CONFIG.player.radius = this.radius;               // насколько герой держится от карточек
    pt.CONFIG.dof.farGain = this.farBlur;                // размытие вдали (tilt-shift, src/render/post.js)
    pt.CONFIG.dof.maxBlurPx = this.blurPx;               // сила размытия, радиус при 1080p
    pt.CONFIG.dof.focusBand = this.sharpBand;            // доля кадра вокруг героя, что остаётся резкой
    pt.traveller.model.scale.setScalar(this.heroScale);
    const cam = pt.follow.camera, W = innerWidth, H = innerHeight;
    const target = W / 2 + ((W > 1100 ? (W - 760) / 4 : W / 2) - W / 2) * this.heroLeft;
    if (target !== W / 2) cam.setViewOffset(W, H, W / 2 - target, 0, W, H); else cam.clearViewOffset();
    cam.updateProjectionMatrix();
  },
};

window.__ptInput = () => {
  const pt = window.__pt, S = window.KcalWalk;
  if (!pt || !S) return null;
  out.x = 0; out.z = 0;
  const kmh = window.KcalTune.speed ?? S.vis;
  if (kmh <= 0.05) return out;                          // дорожка стоит — стоит и герой
  pt.state.speedMul = Math.min(1.8, Math.max(0.35, kmh / 4));   // 4 км/ч — его обычный шаг
  // на север, мягко подтягиваясь к середине дороги чуть впереди (обходя карточки и прохожих, может с неё сойти)
  // Идут не по струнке, а змейкой по ширине тропы.
  const z = pt.traveller.position.z - 0.7;
  const ahead = laneX(z, pt.wfc.seed) + SWAY * Math.sin(z * 1.35);
  const side = Math.max(-1, Math.min(1, (ahead - pt.traveller.position.x) * 1.5));
  const l = Math.hypot(side, 1);
  out.x = side / l; out.z = -1 / l;
  return out;
};

// день и ночь: по настоящим часам; кнопка и клавиша N переключают как в оригинале
window.KcalNight = {
  auto() { const h = new Date().getHours(); return h >= 20 || h < 6; },
  toggle() { return window.__pt ? window.__pt.daynight.toggle() : false; },
};
const waitWorld = setInterval(() => {
  if (!window.__pt) return;
  clearInterval(waitWorld);
  window.__pt.daynight.set(window.KcalNight.auto(), true);
  window.KcalTune.apply();
  addEventListener('resize', () => window.KcalTune.apply());
  placeByProgress();
}, 200);

/**
 * Пройденное за день — расстояние по дороге: герой встаёт там, докуда дошёл бы по шагам дня, и мир показывает
 * прогресс, даже когда стоишь. Шаг — столько дороги, сколько герой проходит за шаг на ходу (его скорость при
 * 4 км/ч — 1,15 единицы в секунду, а это ~1,6 шага), так что на ходу шаги и путь героя не расходятся.
 * Ставится один раз за загрузку — как только телефон назвал шаги дня; дальше герой идёт сам.
 */
const STEP_UNITS = 0.7;
function placeByProgress() {
  const wait = setInterval(() => {
    const pt = window.__pt, S = window.KcalWalk;
    if (!pt || !S || !(S.steps >= 0)) return;          // -1 — телефон ещё не назвал шаги дня
    clearInterval(wait);
    if (S.steps > 0) placeAt(S.steps);
  }, 300);
}
/** Поставить героя туда, докуда довели бы [steps] шагов от старта. */
function placeAt(steps) {
  const pt = window.__pt;
  if (!pt) return;
  pt.traveller.teleport(laneX(-steps * STEP_UNITS, pt.wfc.seed), -steps * STEP_UNITS, 0, -1);
  pt.follow.snap(pt.traveller);
}
