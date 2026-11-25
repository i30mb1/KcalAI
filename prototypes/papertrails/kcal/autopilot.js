// KcalAI: путник papertrails идёт сам — по дорогам мира, в темпе беговой дорожки со страницы дневника.
// Читает window.KcalWalk (скорость S.vis км/ч), отдаёт направление через window.__ptInput (см. src/main.js).
const T = 2;                                   // клетка мира — 2 единицы (TILE_SIZE)
const DX = [0, 1, 0, -1], DZ = [-1, 0, 1, 0], SIDES = ['n', 'e', 's', 'w'];
const out = { x: 0, z: 0 };
let cell = null, dir = 0, target = null, stuckT = 0, lastD = 1e9;
const seen = new Map();                        // сколько раз проходили клетку: на развилке — туда, где ещё не были
const key = (cx, cz) => cx + ',' + cz;

function pathSides(pt, cx, cz) {
  const s = pt.wfc.get(cx, cz).sockets;
  return SIDES.map((d) => s[d] === 'path');
}
// ближайшая клетка с дорогой (на старте путник может стоять у фонаря, а не на тропе)
function nearestPath(pt, cx, cz) {
  for (let r = 0; r <= 6; r++) for (let dz = -r; dz <= r; dz++) for (let dx = -r; dx <= r; dx++) {
    if (Math.max(Math.abs(dx), Math.abs(dz)) !== r) continue;
    if (pathSides(pt, cx + dx, cz + dz).some(Boolean)) return [cx + dx, cz + dz];
  }
  return [cx, cz];
}
function pickDir(pt, cx, cz, from) {
  const p = pathSides(pt, cx, cz), back = (from + 2) % 4;
  let opts = [0, 1, 2, 3].filter((d) => p[d] && d !== back);
  if (!opts.length) opts = p[back] ? [back] : [];   // тупик — обратно
  if (!opts.length) return null;
  const visits = (d) => seen.get(key(cx + DX[d], cz + DZ[d])) || 0;
  opts.sort((a, b) => (visits(a) - visits(b)) || ((a === from ? 0 : 1) - (b === from ? 0 : 1)) || (Math.random() - 0.5));
  return opts[0];
}
function goTo(cx, cz) { target = { cx, cz, x: cx * T, z: cz * T }; lastD = 1e9; stuckT = 0; }

// камера за героем: встаёт позади по сглаженному направлению хода — герой всегда идёт вверх по экрану,
// мир разворачивается навстречу; на зигзагах тропы не дёргается, на поворотах доворачивает за ~1,5 с
let camYaw = 0;
const CAM = { pitchDeg: 36, distance: 10, screenUp: 1.1 };
const wrap = (a) => Math.atan2(Math.sin(a), Math.cos(a));
function followBehind(pt, dt) {
  const t = pt.traveller, mx = t.moveDir.x, mz = t.moveDir.z;
  if (t.speed > 0.2 && Math.hypot(mx, mz) > 0.5) {
    const want = Math.atan2(-mx, -mz);                 // камера — с противоположной ходу стороны
    camYaw = wrap(camYaw + wrap(want - camYaw) * (1 - Math.exp(-dt / 1.5)));
  }
  pt.follow.setPose({ ...CAM, yawDeg: camYaw * 180 / Math.PI });
}

window.__ptInput = (dt) => {
  const pt = window.__pt, S = window.KcalWalk;
  if (!pt || !S) return null;
  followBehind(pt, dt);
  out.x = 0; out.z = 0;
  if (S.vis <= 0.05) return out;                       // дорожка стоит — стоит и путник
  pt.state.speedMul = Math.min(1.8, Math.max(0.35, S.vis / 4));   // 4 км/ч — его обычный шаг
  const p = pt.traveller.position;
  if (!target) {
    const [cx, cz] = nearestPath(pt, Math.round(p.x / T), Math.round(p.z / T));
    cell = null; goTo(cx, cz);
  }
  const dx = target.x - p.x, dz = target.z - p.z, d = Math.hypot(dx, dz);
  if (d < 0.3) {                                       // дошёл до середины клетки — выбрать, куда дальше по дороге
    seen.set(key(target.cx, target.cz), (seen.get(key(target.cx, target.cz)) || 0) + 1);
    if (seen.size > 4000) seen.clear();
    const from = cell ? dir : 0;
    cell = target;
    const nd = pickDir(pt, cell.cx, cell.cz, from);
    if (nd === null) { target = null; return out; }
    dir = nd; goTo(cell.cx + DX[nd], cell.cz + DZ[nd]);
    return out;
  }
  // застрял (обходит препятствие слишком долго) — заново от ближайшей дороги
  if (d < lastD - 0.02) { lastD = d; stuckT = 0; } else if ((stuckT += dt) > 3) target = null;
  out.x = dx / d; out.z = dz / d;
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
  window.__pt.daynight.set(window.KcalNight.auto(), true);
  // на широком экране путник идёт слева от ленты: кадр сдвинут, камера по-прежнему держит его в центре своего окна
  const frame = () => {
    const cam = window.__pt.follow.camera, W = innerWidth, H = innerHeight;
    if (W > 1100) cam.setViewOffset(W, H, W / 2 - (W - 760) / 4, 0, W, H); else cam.clearViewOffset();
    cam.updateProjectionMatrix();
  };
  frame(); addEventListener('resize', frame);
}, 200);
