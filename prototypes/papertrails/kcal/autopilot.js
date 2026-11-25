// KcalAI: герой papertrails идёт сам — прямо на север по дороге прогулки (клетки x = 0, её прокладывает
// src/world/wfc.js), в темпе беговой дорожки со страницы дневника. Камера не вращается: север всегда вверху.
// Читает window.KcalWalk (скорость S.vis км/ч), отдаёт направление через window.__ptInput (см. src/main.js).
const T = 2;                                   // клетка мира — 2 единицы (TILE_SIZE)
const LANE_X = 0;                              // дорога прогулки — колонна клеток x = 0
const out = { x: 0, z: 0 };

window.__ptInput = () => {
  const pt = window.__pt, S = window.KcalWalk;
  if (!pt || !S) return null;
  out.x = 0; out.z = 0;
  if (S.vis <= 0.05) return out;                       // дорожка стоит — стоит и герой
  pt.state.speedMul = Math.min(1.8, Math.max(0.35, S.vis / 4));   // 4 км/ч — его обычный шаг
  // на север, мягко подтягиваясь к середине дороги (обходя прохожих, он может с неё сойти)
  const side = Math.max(-0.6, Math.min(0.6, (LANE_X * T - pt.traveller.position.x) * 1.5));
  const l = Math.hypot(side, 1);
  out.x = side / l; out.z = -1 / l;
  return out;
};

// день и ночь: по настоящим часам; кнопка симулятора и клавиша N переключают как в оригинале
window.KcalNight = {
  auto() { const h = new Date().getHours(); return h >= 20 || h < 6; },
  toggle() { return window.__pt ? window.__pt.daynight.toggle() : false; },
};
const waitWorld = setInterval(() => {
  if (!window.__pt) return;
  clearInterval(waitWorld);
  const pt = window.__pt;
  pt.daynight.set(window.KcalNight.auto(), true);
  // камера чуть ниже обычного — дорога видна вперёд; на широком экране герой идёт слева от ленты
  pt.follow.setPose({ pitchDeg: 36, distance: 10, screenUp: 1.1, yawDeg: 0 });
  const frame = () => {
    const cam = pt.follow.camera, W = innerWidth, H = innerHeight;
    if (W > 1100) cam.setViewOffset(W, H, W / 2 - (W - 760) / 4, 0, W, H); else cam.clearViewOffset();
    cam.updateProjectionMatrix();
  };
  frame(); addEventListener('resize', frame);
}, 200);
