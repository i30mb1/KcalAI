// KcalAI: печать еды прогулки — бумажный коллаж из вырезанных слоёв на листе 100 x 100 (низ — y = 97):
// основа с линией реза, слои с короткой тёплой тенью и светлой кромкой со стороны света, орнамент.
// Ею напечатаны подбираемые на карте предметы (src/game/scraps.js): в каждой зоне — своя еда.
const INK = '#3b2a1d';                          // линия реза — тёмно-коричневая, не чёрная
const LIP = 'rgba(255,247,226,0.55)';           // светлая кромка слоя со стороны света (свет слева сверху)

// ---- печать: каждый продукт — маленький коллаж из вырезанных слоёв на листе 100 x 100 ----------------------------
const P = () => new Path2D();
const ell = (x, y, rx, ry, rot = 0) => { const p = P(); p.ellipse(x, y, rx, ry, rot, 0, Math.PI * 2); return p; };
const poly = (...pts) => { const p = P(); p.moveTo(pts[0], pts[1]); for (let i = 2; i < pts.length; i += 2) p.lineTo(pts[i], pts[i + 1]); p.closePath(); return p; };
const rrect = (x, y, w, h, r) => { const p = P(); p.roundRect(x, y, w, h, r); return p; };
const union = (...ps) => { const p = P(); for (const q of ps) p.addPath(q); return p; };

/** Основа продукта: силуэт с линией реза по краю. */
function base(ctx, path, fill) {
  ctx.save();
  ctx.lineJoin = 'round'; ctx.lineCap = 'round';
  ctx.strokeStyle = INK; ctx.lineWidth = 3.4; ctx.stroke(path);
  ctx.fillStyle = fill; ctx.fill(path);
  ctx.restore();
  lip(ctx, path, 0.5);
}
/** Слой поверх: короткая мягкая тёплая тень на нижний лист и светлая кромка со стороны света. `clip` — внутри чего лежит. */
function layer(ctx, path, fill, { clip = null, shade = 1, edge = 0.55 } = {}) {
  ctx.save();
  if (clip) ctx.clip(clip);
  if (shade) { ctx.shadowColor = `rgba(84,46,18,${0.36 * shade})`; ctx.shadowBlur = 9; ctx.shadowOffsetX = 3; ctx.shadowOffsetY = 5; }
  ctx.fillStyle = fill; ctx.fill(path);
  ctx.restore();
  if (edge) { ctx.save(); if (clip) ctx.clip(clip); lip(ctx, path, edge); ctx.restore(); }
}
function lip(ctx, path, a) {
  ctx.save(); ctx.clip(path); ctx.translate(1.0, 1.1);
  ctx.strokeStyle = LIP.replace('0.55', String(a)); ctx.lineWidth = 1.5; ctx.stroke(path);
  ctx.restore();
}
const dots = (ctx, pts, r, fill, clip) => { const p = P(); for (let i = 0; i < pts.length; i += 2) { p.moveTo(pts[i] + r, pts[i + 1]); p.arc(pts[i], pts[i + 1], r, 0, Math.PI * 2); } layer(ctx, p, fill, { clip, shade: 0.6, edge: 0.4 }); };
const seedsAt = (ctx, pts, rx, ry, fill, clip, tilt = 0) => { const p = P(); for (let i = 0; i < pts.length; i += 2) p.addPath(ell(pts[i], pts[i + 1], rx, ry, tilt)); layer(ctx, p, fill, { clip, shade: 0.5, edge: 0.35 }); };
const leaf = (x, y, len, w, rot) => {
  const p = P(); const c = Math.cos(rot), s = Math.sin(rot);
  const at = (u, v) => [x + u * c - v * s, y + u * s + v * c];
  p.moveTo(...at(0, 0)); p.quadraticCurveTo(...at(len * 0.5, -w), ...at(len, 0)); p.quadraticCurveTo(...at(len * 0.5, w), ...at(0, 0)); p.closePath();
  return p;
};
const vein = (ctx, x, y, len, rot, color, clip) => { ctx.save(); if (clip) ctx.clip(clip); ctx.strokeStyle = color; ctx.lineWidth = 1.6; ctx.lineCap = 'round'; ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(x + Math.cos(rot) * len * 0.85, y + Math.sin(rot) * len * 0.85); ctx.stroke(); ctx.restore(); };

/** Продукты: печать на листе 100 x 100. Цвета приглушённые, бумажные: свет мира и сам дорисует. */
const FOODS = {
  apple: { draw(c) {
    const body = P(); body.moveTo(50, 34); body.bezierCurveTo(32, 20, 9, 30, 10, 56); body.bezierCurveTo(11, 82, 30, 98, 50, 92); body.bezierCurveTo(70, 98, 89, 82, 90, 56); body.bezierCurveTo(91, 30, 68, 20, 50, 34);
    base(c, rrect(47, 14, 6, 24, 3), '#7a5232');
    base(c, leaf(53, 24, 26, 9, -0.55), '#6f9a4a'); vein(c, 53, 24, 26, -0.55, '#557a37');
    base(c, body, '#c9533f');
    layer(c, ell(68, 66, 22, 28, 0.2), '#a8402f', { clip: body });
    layer(c, ell(32, 50, 11, 16, 0.4), '#e07a60', { clip: body });
    dots(c, [26, 70, 40, 80, 62, 46, 74, 80, 56, 70, 44, 60], 1.6, '#f3c9a0', body);
  } },
  carrot: { draw(c) {
    const body = P(); body.moveTo(32, 34); body.quadraticCurveTo(50, 28, 68, 34); body.quadraticCurveTo(60, 70, 52, 96); body.quadraticCurveTo(50, 99, 48, 96); body.quadraticCurveTo(40, 70, 32, 34); body.closePath();
    for (const [x, r] of [[40, -2.0], [50, -1.62], [60, -1.15]]) base(c, leaf(x, 34, 32, 8, r), '#6f9a4a');
    vein(c, 50, 34, 32, -1.62, '#557a37');
    base(c, body, '#e08a3c');
    layer(c, poly(52, 30, 70, 34, 56, 98), '#c66f2c', { clip: body, shade: 0.5 });
    layer(c, ell(41, 48, 4, 12, 0.15), '#f2a75c', { clip: body, shade: 0.4 });
    for (const y of [46, 58, 70, 82]) layer(c, rrect(41 + (y - 46) * 0.12, y, 13 - (y - 46) * 0.2, 2.6, 1.3), '#b5622a', { clip: body, shade: 0.4, edge: 0.3 });
  } },
  bread: { draw(c) {
    const body = P(); body.moveTo(6, 90); body.bezierCurveTo(4, 50, 30, 36, 50, 36); body.bezierCurveTo(70, 36, 96, 50, 94, 90); body.quadraticCurveTo(50, 98, 6, 90); body.closePath();
    base(c, body, '#c98a48');
    layer(c, rrect(4, 76, 92, 24, 8), '#a96b33', { clip: body });
    layer(c, ell(40, 50, 26, 10, -0.1), '#e2ad6c', { clip: body, shade: 0.4 });
    for (const x of [28, 48, 68]) layer(c, ell(x, 60, 4, 13, 0.6), '#f2d6a2', { clip: body });
    dots(c, [18, 66, 36, 46, 58, 44, 80, 62, 84, 72, 14, 78], 1.3, '#f6e7c8', body);
  } },
  cheese: { draw(c) {
    const front = poly(8, 58, 92, 40, 92, 94, 8, 94);
    const top = poly(8, 58, 92, 40, 72, 30, 8, 50);
    base(c, union(front, top), '#e6b84a');
    layer(c, top, '#f3d27a', { clip: union(front, top), shade: 0.4 });
    layer(c, poly(70, 44, 92, 40, 92, 94, 70, 94), '#cf9e35', { clip: front, shade: 0.4 });
    for (const [x, y, r] of [[26, 74, 7], [52, 62, 5], [58, 82, 8], [80, 58, 4], [36, 88, 3.5]]) {
      const h = ell(x, y, r, r * 0.85);
      layer(c, h, '#b8862a', { clip: front, shade: 0, edge: 0 });
      layer(c, ell(x + r * 0.25, y + r * 0.25, r * 0.75, r * 0.6), '#d9a940', { clip: h, shade: 0.8, edge: 0 });
    }
    seedsAt(c, [30, 46, 54, 40], 3.5, 1.6, '#c99a3a', top, -0.2);
  } },
  egg: { draw(c) {
    const body = P(); body.moveTo(50, 10); body.bezierCurveTo(74, 10, 86, 56, 84, 70); body.bezierCurveTo(82, 90, 66, 97, 50, 97); body.bezierCurveTo(34, 97, 18, 90, 16, 70); body.bezierCurveTo(14, 56, 26, 10, 50, 10);
    base(c, body, '#f1e4c8');
    layer(c, ell(68, 70, 20, 30, 0.2), '#dcc9a5', { clip: body });
    layer(c, ell(36, 40, 8, 15, 0.35), '#fbf3e1', { clip: body, shade: 0.3 });
    dots(c, [60, 30, 64, 52, 42, 74, 70, 84, 30, 62], 1.4, '#c4a882', body);
  } },
  broccoli: { draw(c) {
    const stem = P(); stem.moveTo(40, 56); stem.lineTo(60, 56); stem.lineTo(57, 96); stem.lineTo(43, 96); stem.closePath();
    const crown = union(ell(30, 46, 20, 18), ell(70, 46, 20, 18), ell(50, 30, 24, 22), ell(50, 52, 18, 12), ell(36, 24, 12, 11), ell(66, 25, 13, 11));
    base(c, stem, '#9cc06f');
    layer(c, poly(52, 56, 60, 56, 57, 96, 51, 96), '#86a95c', { clip: stem, shade: 0.4 });
    base(c, crown, '#4f8a45');
    for (const [x, y, r] of [[28, 44, 10], [72, 45, 10], [50, 28, 12], [38, 22, 6], [64, 24, 7], [50, 50, 8]]) layer(c, ell(x - 2, y - 2, r, r * 0.9), '#5f9e50', { clip: crown, shade: 0.7 });
    dots(c, [24, 40, 34, 48, 46, 22, 56, 32, 66, 40, 76, 48, 42, 34, 60, 20, 30, 30], 2.2, '#78b463', crown);
  } },
  strawberry: { draw(c) {
    const body = P(); body.moveTo(50, 30); body.bezierCurveTo(70, 24, 92, 32, 88, 52); body.bezierCurveTo(84, 74, 62, 94, 50, 97); body.bezierCurveTo(38, 94, 16, 74, 12, 52); body.bezierCurveTo(8, 32, 30, 24, 50, 30);
    base(c, body, '#d24a45');
    layer(c, ell(66, 60, 18, 28, 0.35), '#b13a37', { clip: body });
    layer(c, ell(32, 48, 9, 13, 0.5), '#e8706a', { clip: body, shade: 0.4 });
    const pts = []; for (let r = 0; r < 4; r++) for (let k = 0; k < 5 - r; k++) pts.push(26 + k * 12 + r * 6 + (r % 2) * 2, 44 + r * 13);
    seedsAt(c, pts, 1.5, 2.6, '#f2d27a', body);
    base(c, union(leaf(50, 30, 20, 7, -2.6), leaf(50, 30, 20, 7, -0.55), leaf(50, 30, 16, 6, -1.6), leaf(50, 30, 18, 6, -2.1), leaf(50, 30, 18, 6, -1.05)), '#5f8f45');
    base(c, rrect(48, 10, 4, 18, 2), '#5f8f45');
  } },
  pumpkin: { draw(c) {
    const body = union(ell(50, 64, 42, 32), ell(28, 64, 22, 30), ell(72, 64, 22, 30));
    base(c, rrect(46, 18, 8, 20, 3), '#6f5233');
    base(c, body, '#d9823a');
    layer(c, ell(50, 64, 16, 32), '#e99a52', { clip: body });
    layer(c, ell(80, 66, 14, 28), '#c06c2c', { clip: body });
    layer(c, ell(20, 66, 14, 28), '#c87532', { clip: body, shade: 0.6 });
    base(c, leaf(54, 30, 24, 9, -0.25), '#6f9a4a'); vein(c, 54, 30, 24, -0.25, '#557a37');
  } },
  milk: { draw(c) {
    const front = poly(22, 34, 64, 34, 64, 96, 22, 96);
    const side = poly(64, 34, 80, 28, 80, 90, 64, 96);
    const gable = poly(22, 34, 43, 12, 64, 34);
    const roof = poly(43, 12, 60, 7, 80, 28, 64, 34);
    base(c, union(front, side, gable, roof), '#f2ece0');
    layer(c, side, '#d3cbbb', { shade: 0.4 });
    layer(c, roof, '#c9d9e6', { shade: 0.4 });
    layer(c, rrect(41, 6, 4, 8, 1), '#c9d9e6', { shade: 0.3 });
    layer(c, rrect(22, 52, 42, 26, 2), '#6f9cc4', { clip: front });
    layer(c, poly(64, 52, 80, 46, 80, 72, 64, 78), '#5a85ab', { clip: side, shade: 0.4 });
    const drop = P(); drop.moveTo(43, 56); drop.bezierCurveTo(50, 64, 52, 68, 52, 70); drop.arc(43, 70, 9, 0, Math.PI, false); drop.bezierCurveTo(34, 68, 36, 64, 43, 56);
    layer(c, drop, '#f7f3ea', { clip: front });
  } },
  banana: { draw(c) {
    // спелый банан: изогнутый полумесяц с гранью посередине, зелёно-коричневая плодоножка и тёмный кончик
    c.save(); c.translate(50, 50); c.rotate(-0.42); c.translate(-50, -50);
    const body = P();
    body.moveTo(12, 40);
    body.bezierCurveTo(18, 78, 70, 88, 92, 46);
    body.bezierCurveTo(93, 42, 92, 38, 88, 37);
    body.bezierCurveTo(72, 66, 34, 64, 18, 34);
    body.bezierCurveTo(16, 31, 12, 33, 12, 40);
    body.closePath();
    base(c, poly(86, 40, 90, 22, 97, 23, 94, 41), '#8a8a3c');
    base(c, body, '#f0cf45');
    const belly = P(); belly.moveTo(12, 40); belly.bezierCurveTo(18, 78, 70, 88, 92, 46); belly.bezierCurveTo(70, 74, 28, 70, 14, 46); belly.closePath();
    layer(c, belly, '#d9ac2e', { clip: body, shade: 0.5 });
    c.save(); c.clip(body); c.strokeStyle = '#fbe58e'; c.lineWidth = 2.6; c.lineCap = 'round';
    c.beginPath(); c.moveTo(18, 40); c.bezierCurveTo(28, 62, 66, 68, 86, 43); c.stroke(); c.restore();
    base(c, ell(13.5, 38, 3.6, 4.6, 0.5), '#5c4228');
    dots(c, [40, 60, 58, 63, 70, 57], 1.1, '#b98a2a', body);
    c.restore();
  } },
  watermelon: { draw(c) {
    const body = P(); body.moveTo(50, 10); body.lineTo(92, 74); body.quadraticCurveTo(50, 104, 8, 74); body.closePath();
    base(c, body, '#5c8f48');
    const white = P(); white.moveTo(50, 10); white.lineTo(86, 70); white.quadraticCurveTo(50, 94, 14, 70); white.closePath();
    layer(c, white, '#e9eccb', { clip: body });
    const red = P(); red.moveTo(50, 12); red.lineTo(82, 66); red.quadraticCurveTo(50, 87, 18, 66); red.closePath();
    layer(c, red, '#dd5a54', { clip: body });
    layer(c, poly(50, 12, 82, 66, 60, 80), '#c84a46', { clip: red, shade: 0.4 });
    seedsAt(c, [40, 48, 58, 46, 32, 64, 50, 66, 68, 62, 50, 32], 1.7, 3, '#3b2a1d', red, 0.2);
  } },
  avocado: { draw(c) {
    const body = P(); body.moveTo(50, 8); body.bezierCurveTo(66, 8, 70, 30, 78, 46); body.bezierCurveTo(92, 70, 80, 97, 50, 97); body.bezierCurveTo(20, 97, 8, 70, 22, 46); body.bezierCurveTo(30, 30, 34, 8, 50, 8);
    base(c, body, '#4a6b35');
    const flesh = P(); flesh.moveTo(50, 15); flesh.bezierCurveTo(62, 15, 65, 33, 72, 48); flesh.bezierCurveTo(84, 70, 75, 91, 50, 91); flesh.bezierCurveTo(25, 91, 16, 70, 28, 48); flesh.bezierCurveTo(35, 33, 38, 15, 50, 15);
    layer(c, flesh, '#d9dc8a', { clip: body });
    layer(c, ell(50, 66, 26, 22), '#c3cf6c', { clip: flesh, shade: 0.4 });
    const pit = ell(50, 66, 15, 16);
    layer(c, pit, '#8a5a35', { clip: flesh });
    layer(c, ell(45, 61, 5, 6, 0.4), '#b07a4c', { clip: pit, shade: 0.3 });
  } },
  tomato: { draw(c) {
    const body = union(ell(50, 64, 40, 32), ell(36, 62, 22, 28), ell(64, 62, 22, 28));
    base(c, body, '#d4483c');
    layer(c, ell(68, 70, 20, 24, 0.3), '#b5392f', { clip: body });
    layer(c, ell(32, 54, 10, 12, 0.5), '#e9725f', { clip: body, shade: 0.4 });
    const star = P(); for (let i = 0; i < 5; i++) star.addPath(leaf(50, 36, 18, 5, -Math.PI / 2 + (i - 2) * 0.75 + (i > 2 ? Math.PI * 0.42 : 0)));
    base(c, star, '#5f8f45');
    base(c, rrect(48, 20, 4, 16, 2), '#5f8f45');
  } },
  cherries: { draw(c) {
    const st = P(); st.moveTo(52, 12); st.quadraticCurveTo(34, 30, 30, 62); st.moveTo(52, 12); st.quadraticCurveTo(62, 40, 70, 66);
    c.save(); c.strokeStyle = INK; c.lineWidth = 6; c.lineCap = 'round'; c.stroke(st); c.strokeStyle = '#6f8a3c'; c.lineWidth = 3; c.stroke(st); c.restore();
    base(c, leaf(52, 12, 26, 9, -0.2), '#6f9a4a'); vein(c, 52, 12, 26, -0.2, '#557a37');
    for (const [x, y] of [[30, 76], [70, 80]]) {
      const b = ell(x, y, 19, 18);
      base(c, b, '#a8323b');
      layer(c, ell(x + 7, y + 6, 12, 12), '#8a2630', { clip: b });
      layer(c, ell(x - 7, y - 6, 4, 5, 0.5), '#d0646a', { clip: b, shade: 0.3 });
    }
  } },
  corn: { draw(c) {
    const cob = ell(50, 50, 18, 40);
    base(c, cob, '#ecc552');
    const kern = P(); for (let y = 16; y < 88; y += 7.5) for (let x = 38; x < 64; x += 7.5) kern.addPath(rrect(x + ((y / 7.5) % 2) * 2 - 2, y, 6, 6, 2.4));
    layer(c, kern, '#f5d871', { clip: cob, shade: 0.7, edge: 0.4 });
    layer(c, ell(62, 50, 8, 40), 'rgba(196,150,52,0.55)', { clip: cob, shade: 0, edge: 0 });
    base(c, leaf(44, 97, 66, 13, -1.85), '#7fa654'); vein(c, 44, 97, 66, -1.85, '#5f8a3d');
    base(c, leaf(56, 97, 60, 12, -1.3), '#6f9a4a'); vein(c, 56, 97, 60, -1.3, '#557a37');
  } },
};
/** Все продукты — в порядке клеток атласа. */
export const FOOD_KINDS = Object.keys(FOODS);

// что растёт и лежит в какой зоне (id зон — src/core/biomes.js)
export const THEME = {
  heartland: ['apple', 'pumpkin', 'bread', 'carrot', 'corn', 'cheese'],
  blossom: ['strawberry', 'cherries', 'egg', 'tomato', 'apple'],
  pinewood: ['broccoli', 'apple', 'carrot', 'bread', 'cherries'],
  snow: ['milk', 'cheese', 'egg', 'bread'],
  desert: ['watermelon', 'banana', 'avocado', 'corn'],
};

/** Напечатать продукт [kind] на [ctx] в лист 100 x 100 (масштаб — у вызывающего). */
export function drawFood(ctx, kind) {
  FOODS[kind].draw(ctx);
}
