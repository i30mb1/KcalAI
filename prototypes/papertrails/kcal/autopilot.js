// KcalAI: герой papertrails идёт сам — прямо на север по дороге прогулки (клетки x = 0, её прокладывает
// src/world/wfc.js), в темпе беговой дорожки со страницы дневника. Камера не вращается: север всегда вверху.
// Читает window.KcalWalk (скорость S.vis км/ч), отдаёт направление через window.__ptInput (см. src/main.js).
const T = 2;                                   // клетка мира — 2 единицы (TILE_SIZE)
const LANE_X = 0;                              // дорога прогулки — колонна клеток x = 0
const out = { x: 0, z: 0 };

/**
 * Всё, что в прогулке настраивается на лету. Значения по умолчанию — здесь; панель (kcal/tune.js) меняет их
 * и зовёт apply(). speed: null — темп дорожки со страницы; heroLeft: 1 — герой слева от ленты, 0 — по центру.
 */
window.KcalTune = {
  pitch: 36, distance: 10, fov: 27, screenUp: 1.1, speed: null, reach: 0, radius: 0.2, heroScale: 1, heroLeft: 1,
  glassTint: 0.62, glassBlur: 10,
  apply() {
    const root = document.documentElement.style;                 // стекло колонки дневника над миром (index.html)
    root.setProperty('--glass-tint', this.glassTint);
    root.setProperty('--glass-blur', this.glassBlur + 'px');
    const pt = window.__pt;
    if (!pt) return;
    pt.follow.setPose({ pitchDeg: this.pitch, distance: this.distance, fov: this.fov, screenUp: this.screenUp, yawDeg: 0 });
    pt.state.viewReach = this.reach;                     // ± клеток к кругу (5), где встаёт окружение
    pt.CONFIG.player.radius = this.radius;               // насколько герой держится от карточек
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
  // на север, мягко подтягиваясь к середине дороги (обходя карточки и прохожих, он может с неё сойти)
  const side = Math.max(-0.6, Math.min(0.6, (LANE_X * T - pt.traveller.position.x) * 1.5));
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
}, 200);
