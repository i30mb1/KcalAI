// KcalAI: Зёрнышко вместо путника papertrails — бумажная модель, сложенная из граней, как фигурка по развёртке.
// Части названы так, как их ищет src/player/traveller.js ('body','head','leg_l','leg_r','arm_l','arm_r','lantern'):
// вся анимация papertrails (шаг, подскок, наклон, крен, качание фонаря, дыхание) работает на нём как есть.
// Модель смотрит на +Z, рост — CONFIG.TRAVELLER_HEIGHT (0,6). Вызывается из src/assets/loader.js.
import * as THREE from 'three';

const C = {
  grain: '#5fb874', light: '#8fd49c', dark: '#4c8f5a', vein: '#3e7a4b', ink: '#2c2618', blush: '#f3b5c8', glint: '#fff8ea',
  pack: '#c8513b', packDark: '#9c3b2b', strap: '#f3e6c8', frame: '#3a2a1c',
};
const T = 0.006;                                   // толщина наклеек: лицо, прожилка, листики
const TAU = Math.PI * 2;

const rrect = (x, y, w, h, r) => {
  const s = new THREE.Shape();
  s.moveTo(x + r, y); s.lineTo(x + w - r, y); s.quadraticCurveTo(x + w, y, x + w, y + r); s.lineTo(x + w, y + h - r);
  s.quadraticCurveTo(x + w, y + h, x + w - r, y + h); s.lineTo(x + r, y + h); s.quadraticCurveTo(x, y + h, x, y + h - r);
  s.lineTo(x, y + r); s.quadraticCurveTo(x, y, x + r, y); return s;
};
const ellipse = (cx, cy, rx, ry, rot = 0) => { const s = new THREE.Shape(); s.absellipse(cx, cy, rx, ry, 0, TAU, false, rot); return s; };
const smileShape = (cx, cy, r, th) => {
  const s = new THREE.Shape();
  s.absarc(cx, cy, r, Math.PI + 0.45, TAU - 0.45, false);
  s.absarc(cx, cy, r - th, TAU - 0.45, Math.PI + 0.45, true);
  s.closePath(); return s;
};
// грани: каждая — плоский лист, как у сложенной бумажной фигурки
const faceted = (g) => { const n = g.index ? g.toNonIndexed() : g; n.computeVertexNormals(); return n; };

export function buildGrainHero({ materials, height = 0.6 }) {
  const mat = (color, extra = {}) => materials.paper({ color, side: THREE.DoubleSide, variant: 'traveller', ...extra });
  const mesh = (geo, color, extra) => { const m = new THREE.Mesh(geo, mat(color, extra)); m.castShadow = true; m.receiveShadow = true; return m; };
  const sticker = (shape, color, z, extra) => {
    const g = new THREE.ExtrudeGeometry(shape, { depth: T, bevelEnabled: false, curveSegments: 18 });
    g.translate(0, 0, z - T / 2); return mesh(g, color, extra);
  };

  const k = height / 0.6;
  const root = new THREE.Group(); root.name = 'traveller';
  const BW = 0.3 * k, BH = 0.37 * k, BY = 0.17 * k, HIP = 0.2 * k, SQ = 0.82, SEG = 10;

  // ---- тело-зерно: гранёный «лимон» из десяти долек, чуть приплюснут спереди назад ----
  const rAt = (y) => { const u = (y - (BY + BH / 2)) / (BH / 2); return Math.max(0, BW / 2 * Math.sqrt(Math.max(0, 1 - u * u)) * (1 - 0.2 * Math.max(0, u))); };
  const prof = [];
  for (let i = 0; i <= 8; i++) { const y = BY + BH * i / 8; prof.push(new THREE.Vector2(i === 0 || i === 8 ? 0.0001 : rAt(y), y)); }
  const bodyGeo = faceted(new THREE.LatheGeometry(prof, SEG, Math.PI / SEG, TAU)); bodyGeo.scale(1, 1, SQ);
  const body = new THREE.Group(); body.name = 'body'; root.add(body);
  const shell = mesh(bodyGeo, C.grain); shell.name = 'body_shell'; body.add(shell);
  const front = (y) => rAt(y) * Math.cos(Math.PI / SEG) * SQ;       // передняя грань по центру — на этой глубине

  // наклейки на переднюю грань: блик, прожилка, лицо
  const face = BY + BH * 0.46;
  body.add(sticker(ellipse(-0.04 * k, BY + BH * 0.62, 0.05 * k, 0.07 * k, 0.3), C.light, front(BY + BH * 0.62) + 0.002 * k));
  body.add(sticker(rrect(-0.005 * k, BY + 0.035 * k, 0.01 * k, BH * 0.22, 0.005 * k), C.vein, front(BY + 0.08 * k) + 0.002 * k));
  const fz = front(face) + 0.004 * k;
  for (const sx of [-1, 1]) {
    body.add(sticker(ellipse(sx * 0.086 * k, face - 0.034 * k, 0.02 * k, 0.012 * k), C.blush, fz - 0.006 * k));
    body.add(sticker(ellipse(sx * 0.043 * k, face, 0.018 * k, 0.023 * k), C.ink, fz));
    body.add(sticker(ellipse(sx * 0.043 * k - 0.005 * k, face + 0.007 * k, 0.0055 * k, 0.0065 * k), C.glint, fz + T));
  }
  body.add(sticker(smileShape(0, face - 0.03 * k, 0.026 * k, 0.008 * k), C.ink, fz));

  // ---- рюкзачок: сложенная коробочка с клапаном, пряжкой и лямками ----
  const back = -front(BY + BH * 0.42);
  const pack = new THREE.Group(); pack.name = 'pack'; body.add(pack);
  const box = faceted(new THREE.BoxGeometry(0.15 * k, 0.15 * k, 0.06 * k)); box.translate(0, BY + 0.165 * k, back - 0.02 * k);
  pack.add(mesh(box, C.pack));
  const flap = faceted(new THREE.BoxGeometry(0.158 * k, 0.07 * k, 0.066 * k)); flap.translate(0, BY + 0.21 * k, back - 0.02 * k);
  pack.add(mesh(flap, C.packDark));
  pack.add(sticker(rrect(-0.012 * k, BY + 0.165 * k, 0.024 * k, 0.03 * k, 0.006 * k), C.strap, back - 0.054 * k));
  for (const sx of [-1, 1]) {
    const strap = new THREE.Mesh(new THREE.BoxGeometry(0.016 * k, 0.15 * k, 0.012 * k), mat(C.packDark));
    strap.position.set(sx * 0.07 * k, BY + 0.24 * k, front(BY + 0.24 * k) * 0.55); strap.rotation.x = -0.35; strap.castShadow = true; body.add(strap);
  }

  // ---- хохолок как «голова»: три листика крестом из двух карточек — покачивается на ходу ----
  const head = new THREE.Group(); head.name = 'head'; head.position.set(0, BY + BH - 0.012 * k, 0); body.add(head);
  const tuftShapes = () => {
    const out = [rrect(-0.03 * k, 0, 0.06 * k, 0.02 * k, 0.008 * k)];
    for (const [x, a, l] of [[-0.018, -0.5, 0.075], [0, 0, 0.095], [0.018, 0.5, 0.075]]) {
      const tx = (x + Math.sin(a) * l) * k, ty = Math.cos(a) * l * k;
      const stem = new THREE.Shape(); stem.moveTo((x - 0.007) * k, 0.01 * k); stem.lineTo((x + 0.007) * k, 0.01 * k); stem.lineTo(tx + 0.006 * k, ty); stem.lineTo(tx - 0.006 * k, ty); stem.closePath();
      out.push(stem, ellipse(tx, ty, 0.019 * k, 0.027 * k, -a));
    }
    return out;
  };
  for (const turn of [0, Math.PI / 2]) for (const s of tuftShapes()) {
    const g = new THREE.ExtrudeGeometry(s, { depth: T * 1.4, bevelEnabled: false, curveSegments: 14 }); g.translate(0, 0, -T * 0.7);
    const m = mesh(g, turn ? C.vein : C.dark); m.rotation.y = turn; head.add(m);
  }

  // ---- лапки: гранёные столбики, ступни — сложенные «лодочки» ----
  for (const [name, sx] of [['leg_l', 1], ['leg_r', -1]]) {
    const leg = new THREE.Group(); leg.name = name; leg.position.set(sx * 0.058 * k, HIP, 0);
    const shin = faceted(new THREE.CylinderGeometry(0.02 * k, 0.022 * k, HIP - 0.02 * k, 6)); shin.translate(0, -(HIP - 0.02 * k) / 2, 0);
    leg.add(mesh(shin, C.grain));
    const foot = faceted(new THREE.IcosahedronGeometry(0.03 * k, 0)); foot.scale(1, 0.55, 1.45); foot.translate(0, -HIP + 0.014 * k, 0.012 * k);
    leg.add(mesh(foot, C.dark));
    root.add(leg);
  }

  // ---- ручки: гранёные, сбоку от тела; в правой — фонарик, ночью светится ----
  const arms = {};
  for (const [name, sx] of [['arm_l', 1], ['arm_r', -1]]) {
    const arm = new THREE.Group(); arm.name = name; arm.position.set(sx * (rAt(BY + BH * 0.56) + 0.016 * k), BY + BH * 0.56, 0);
    const up = faceted(new THREE.CylinderGeometry(0.017 * k, 0.015 * k, 0.1 * k, 6)); up.translate(0, -0.05 * k, 0);
    arm.add(mesh(up, C.grain));
    const hand = faceted(new THREE.IcosahedronGeometry(0.019 * k, 0)); hand.translate(0, -0.105 * k, 0);
    arm.add(mesh(hand, C.light));
    body.add(arm); arms[name] = arm;
  }
  const lantern = new THREE.Group(); lantern.name = 'lantern'; lantern.position.set(0, -0.118 * k, 0.01 * k);
  const hook = new THREE.Mesh(new THREE.BoxGeometry(0.005 * k, 0.026 * k, 0.005 * k), mat(C.frame)); hook.position.y = -0.013 * k; lantern.add(hook);
  const cage = faceted(new THREE.CylinderGeometry(0.02 * k, 0.026 * k, 0.05 * k, 6)); cage.translate(0, -0.052 * k, 0);
  lantern.add(mesh(cage, '#ffffff', { emissive: '#ff8d1a', emissiveIntensity: 1.4, fibre: 'none', vary: false }));
  const cap = faceted(new THREE.ConeGeometry(0.03 * k, 0.022 * k, 6)); cap.translate(0, -0.018 * k, 0);
  lantern.add(mesh(cap, C.frame));
  const base = faceted(new THREE.CylinderGeometry(0.028 * k, 0.028 * k, 0.008 * k, 6)); base.translate(0, -0.08 * k, 0);
  lantern.add(mesh(base, C.frame));
  arms.arm_r.add(lantern);
  return root;
}

window.__ptHero = buildGrainHero;
