// KcalAI: своя еда вдоль дороги прогулки — бумажные карточки с напечатанными продуктами. Стоят на обочине дороги
// и грядками на открытых полянах рядом; набор у каждой зоны свой (поля — яблоки и тыквы, снег — молоко и сыр...).
// Встают и ложатся вместе со своей клеткой мира и на той же пружине, что окружение (src/world/fold.js): перехлёст,
// дрожь, мягкий шлепок о страницу. Раскладка — от seed мира: другой мир — другая еда, тот же мир — та же.
import * as THREE from 'three';
import { createFoldInstance } from '../src/world/fold.js';

const T = 2;                                    // клетка мира — 2 единицы
const AHEAD = 18, BEHIND = 8;                   // ряды клеток с едой: впереди героя и позади
const PX = 512, GRID = 4;                       // печать: клетка атласа 512 px, атлас 4 x 4
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

/**
 * Продукты: size — высота карточки в единицах мира (герой 0,6), draw — печать на листе 100 x 100 (низ — y = 97).
 * Цвета приглушённые, бумажные: свет мира и сам дорисует.
 */
const FOODS = {
  apple: { size: 0.36, draw(c) {
    const body = P(); body.moveTo(50, 34); body.bezierCurveTo(32, 20, 9, 30, 10, 56); body.bezierCurveTo(11, 82, 30, 98, 50, 92); body.bezierCurveTo(70, 98, 89, 82, 90, 56); body.bezierCurveTo(91, 30, 68, 20, 50, 34);
    base(c, rrect(47, 14, 6, 24, 3), '#7a5232');
    base(c, leaf(53, 24, 26, 9, -0.55), '#6f9a4a'); vein(c, 53, 24, 26, -0.55, '#557a37');
    base(c, body, '#c9533f');
    layer(c, ell(68, 66, 22, 28, 0.2), '#a8402f', { clip: body });
    layer(c, ell(32, 50, 11, 16, 0.4), '#e07a60', { clip: body });
    dots(c, [26, 70, 40, 80, 62, 46, 74, 80, 56, 70, 44, 60], 1.6, '#f3c9a0', body);
  } },
  carrot: { size: 0.5, draw(c) {
    const body = P(); body.moveTo(32, 34); body.quadraticCurveTo(50, 28, 68, 34); body.quadraticCurveTo(60, 70, 52, 96); body.quadraticCurveTo(50, 99, 48, 96); body.quadraticCurveTo(40, 70, 32, 34); body.closePath();
    for (const [x, r] of [[40, -2.0], [50, -1.62], [60, -1.15]]) base(c, leaf(x, 34, 32, 8, r), '#6f9a4a');
    vein(c, 50, 34, 32, -1.62, '#557a37');
    base(c, body, '#e08a3c');
    layer(c, poly(52, 30, 70, 34, 56, 98), '#c66f2c', { clip: body, shade: 0.5 });
    layer(c, ell(41, 48, 4, 12, 0.15), '#f2a75c', { clip: body, shade: 0.4 });
    for (const y of [46, 58, 70, 82]) layer(c, rrect(41 + (y - 46) * 0.12, y, 13 - (y - 46) * 0.2, 2.6, 1.3), '#b5622a', { clip: body, shade: 0.4, edge: 0.3 });
  } },
  bread: { size: 0.34, draw(c) {
    const body = P(); body.moveTo(6, 90); body.bezierCurveTo(4, 50, 30, 36, 50, 36); body.bezierCurveTo(70, 36, 96, 50, 94, 90); body.quadraticCurveTo(50, 98, 6, 90); body.closePath();
    base(c, body, '#c98a48');
    layer(c, rrect(4, 76, 92, 24, 8), '#a96b33', { clip: body });
    layer(c, ell(40, 50, 26, 10, -0.1), '#e2ad6c', { clip: body, shade: 0.4 });
    for (const x of [28, 48, 68]) layer(c, ell(x, 60, 4, 13, 0.6), '#f2d6a2', { clip: body });
    dots(c, [18, 66, 36, 46, 58, 44, 80, 62, 84, 72, 14, 78], 1.3, '#f6e7c8', body);
  } },
  cheese: { size: 0.34, draw(c) {
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
  egg: { size: 0.32, draw(c) {
    const body = P(); body.moveTo(50, 10); body.bezierCurveTo(74, 10, 86, 56, 84, 70); body.bezierCurveTo(82, 90, 66, 97, 50, 97); body.bezierCurveTo(34, 97, 18, 90, 16, 70); body.bezierCurveTo(14, 56, 26, 10, 50, 10);
    base(c, body, '#f1e4c8');
    layer(c, ell(68, 70, 20, 30, 0.2), '#dcc9a5', { clip: body });
    layer(c, ell(36, 40, 8, 15, 0.35), '#fbf3e1', { clip: body, shade: 0.3 });
    dots(c, [60, 30, 64, 52, 42, 74, 70, 84, 30, 62], 1.4, '#c4a882', body);
  } },
  broccoli: { size: 0.5, draw(c) {
    const stem = P(); stem.moveTo(40, 56); stem.lineTo(60, 56); stem.lineTo(57, 96); stem.lineTo(43, 96); stem.closePath();
    const crown = union(ell(30, 46, 20, 18), ell(70, 46, 20, 18), ell(50, 30, 24, 22), ell(50, 52, 18, 12), ell(36, 24, 12, 11), ell(66, 25, 13, 11));
    base(c, stem, '#9cc06f');
    layer(c, poly(52, 56, 60, 56, 57, 96, 51, 96), '#86a95c', { clip: stem, shade: 0.4 });
    base(c, crown, '#4f8a45');
    for (const [x, y, r] of [[28, 44, 10], [72, 45, 10], [50, 28, 12], [38, 22, 6], [64, 24, 7], [50, 50, 8]]) layer(c, ell(x - 2, y - 2, r, r * 0.9), '#5f9e50', { clip: crown, shade: 0.7 });
    dots(c, [24, 40, 34, 48, 46, 22, 56, 32, 66, 40, 76, 48, 42, 34, 60, 20, 30, 30], 2.2, '#78b463', crown);
  } },
  strawberry: { size: 0.32, draw(c) {
    const body = P(); body.moveTo(50, 30); body.bezierCurveTo(70, 24, 92, 32, 88, 52); body.bezierCurveTo(84, 74, 62, 94, 50, 97); body.bezierCurveTo(38, 94, 16, 74, 12, 52); body.bezierCurveTo(8, 32, 30, 24, 50, 30);
    base(c, body, '#d24a45');
    layer(c, ell(66, 60, 18, 28, 0.35), '#b13a37', { clip: body });
    layer(c, ell(32, 48, 9, 13, 0.5), '#e8706a', { clip: body, shade: 0.4 });
    const pts = []; for (let r = 0; r < 4; r++) for (let k = 0; k < 5 - r; k++) pts.push(26 + k * 12 + r * 6 + (r % 2) * 2, 44 + r * 13);
    seedsAt(c, pts, 1.5, 2.6, '#f2d27a', body);
    base(c, union(leaf(50, 30, 20, 7, -2.6), leaf(50, 30, 20, 7, -0.55), leaf(50, 30, 16, 6, -1.6), leaf(50, 30, 18, 6, -2.1), leaf(50, 30, 18, 6, -1.05)), '#5f8f45');
    base(c, rrect(48, 10, 4, 18, 2), '#5f8f45');
  } },
  pumpkin: { size: 0.4, draw(c) {
    const body = union(ell(50, 64, 42, 32), ell(28, 64, 22, 30), ell(72, 64, 22, 30));
    base(c, rrect(46, 18, 8, 20, 3), '#6f5233');
    base(c, body, '#d9823a');
    layer(c, ell(50, 64, 16, 32), '#e99a52', { clip: body });
    layer(c, ell(80, 66, 14, 28), '#c06c2c', { clip: body });
    layer(c, ell(20, 66, 14, 28), '#c87532', { clip: body, shade: 0.6 });
    base(c, leaf(54, 30, 24, 9, -0.25), '#6f9a4a'); vein(c, 54, 30, 24, -0.25, '#557a37');
  } },
  milk: { size: 0.56, draw(c) {
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
  banana: { size: 0.32, draw(c) {
    const body = P(); body.moveTo(8, 40); body.bezierCurveTo(16, 84, 70, 96, 94, 58); body.bezierCurveTo(90, 54, 88, 54, 86, 56); body.bezierCurveTo(64, 80, 30, 74, 18, 38); body.closePath();
    base(c, body, '#ecc84e');
    const under = P(); under.moveTo(12, 52); under.bezierCurveTo(26, 86, 72, 92, 92, 60); under.bezierCurveTo(70, 98, 20, 90, 12, 52);
    layer(c, under, '#cfa63a', { clip: body, shade: 0.5 });
    layer(c, ell(46, 70, 20, 3, 0.35), '#f7de85', { clip: body, shade: 0.3 });
    base(c, rrect(4, 34, 14, 9, 3), '#6f5233');
    layer(c, ell(90, 57, 4, 4), '#5c4228', { clip: body, shade: 0.3 });
  } },
  watermelon: { size: 0.42, draw(c) {
    const body = P(); body.moveTo(50, 10); body.lineTo(92, 74); body.quadraticCurveTo(50, 104, 8, 74); body.closePath();
    base(c, body, '#5c8f48');
    const white = P(); white.moveTo(50, 10); white.lineTo(86, 70); white.quadraticCurveTo(50, 94, 14, 70); white.closePath();
    layer(c, white, '#e9eccb', { clip: body });
    const red = P(); red.moveTo(50, 12); red.lineTo(82, 66); red.quadraticCurveTo(50, 87, 18, 66); red.closePath();
    layer(c, red, '#dd5a54', { clip: body });
    layer(c, poly(50, 12, 82, 66, 60, 80), '#c84a46', { clip: red, shade: 0.4 });
    seedsAt(c, [40, 48, 58, 46, 32, 64, 50, 66, 68, 62, 50, 32], 1.7, 3, '#3b2a1d', red, 0.2);
  } },
  avocado: { size: 0.4, draw(c) {
    const body = P(); body.moveTo(50, 8); body.bezierCurveTo(66, 8, 70, 30, 78, 46); body.bezierCurveTo(92, 70, 80, 97, 50, 97); body.bezierCurveTo(20, 97, 8, 70, 22, 46); body.bezierCurveTo(30, 30, 34, 8, 50, 8);
    base(c, body, '#4a6b35');
    const flesh = P(); flesh.moveTo(50, 15); flesh.bezierCurveTo(62, 15, 65, 33, 72, 48); flesh.bezierCurveTo(84, 70, 75, 91, 50, 91); flesh.bezierCurveTo(25, 91, 16, 70, 28, 48); flesh.bezierCurveTo(35, 33, 38, 15, 50, 15);
    layer(c, flesh, '#d9dc8a', { clip: body });
    layer(c, ell(50, 66, 26, 22), '#c3cf6c', { clip: flesh, shade: 0.4 });
    const pit = ell(50, 66, 15, 16);
    layer(c, pit, '#8a5a35', { clip: flesh });
    layer(c, ell(45, 61, 5, 6, 0.4), '#b07a4c', { clip: pit, shade: 0.3 });
  } },
  tomato: { size: 0.32, draw(c) {
    const body = union(ell(50, 64, 40, 32), ell(36, 62, 22, 28), ell(64, 62, 22, 28));
    base(c, body, '#d4483c');
    layer(c, ell(68, 70, 20, 24, 0.3), '#b5392f', { clip: body });
    layer(c, ell(32, 54, 10, 12, 0.5), '#e9725f', { clip: body, shade: 0.4 });
    const star = P(); for (let i = 0; i < 5; i++) star.addPath(leaf(50, 36, 18, 5, -Math.PI / 2 + (i - 2) * 0.75 + (i > 2 ? Math.PI * 0.42 : 0)));
    base(c, star, '#5f8f45');
    base(c, rrect(48, 20, 4, 16, 2), '#5f8f45');
  } },
  cherries: { size: 0.36, draw(c) {
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
  corn: { size: 0.5, draw(c) {
    const cob = ell(50, 50, 18, 40);
    base(c, cob, '#ecc552');
    const kern = P(); for (let y = 16; y < 88; y += 7.5) for (let x = 38; x < 64; x += 7.5) kern.addPath(rrect(x + ((y / 7.5) % 2) * 2 - 2, y, 6, 6, 2.4));
    layer(c, kern, '#f5d871', { clip: cob, shade: 0.7, edge: 0.4 });
    layer(c, ell(62, 50, 8, 40), 'rgba(196,150,52,0.55)', { clip: cob, shade: 0, edge: 0 });
    base(c, leaf(44, 97, 66, 13, -1.85), '#7fa654'); vein(c, 44, 97, 66, -1.85, '#5f8a3d');
    base(c, leaf(56, 97, 60, 12, -1.3), '#6f9a4a'); vein(c, 56, 97, 60, -1.3, '#557a37');
  } },
};
const KINDS = Object.keys(FOODS);

// что растёт и лежит в какой зоне (id зон — src/core/biomes.js)
const THEME = {
  heartland: ['apple', 'pumpkin', 'bread', 'carrot', 'corn', 'cheese'],
  blossom: ['strawberry', 'cherries', 'egg', 'tomato', 'apple'],
  pinewood: ['broccoli', 'apple', 'carrot', 'bread', 'cherries'],
  snow: ['milk', 'cheese', 'egg', 'bread'],
  desert: ['watermelon', 'banana', 'avocado', 'corn'],
};
const GIANT = 1.7;                              // еда как в детской книжке: яблоко почти с героя

function hash(seed, a, b, k) {
  let h = (Math.imul(a | 0, 0x9e3779b1) ^ Math.imul(b | 0, 0x85ebca6b) ^ Math.imul((seed | 0) + k * 0x632be5ab, 0xc2b2ae35)) >>> 0;
  h ^= h >>> 15; h = Math.imul(h, 0x2c1b3c6d) >>> 0; h ^= h >>> 12; h = Math.imul(h, 0x297a2d39) >>> 0; h ^= h >>> 15;
  return (h >>> 0) / 4294967296;
}

function printAtlas() {
  const cv = document.createElement('canvas');
  cv.width = cv.height = PX * GRID;
  const ctx = cv.getContext('2d');
  KINDS.forEach((k, i) => {
    ctx.save();
    ctx.translate((i % GRID) * PX, Math.floor(i / GRID) * PX);
    ctx.scale(PX / 100, PX / 100);
    FOODS[k].draw(ctx);
    ctx.restore();
  });
  const tex = new THREE.CanvasTexture(cv);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.anisotropy = 8;
  return tex;
}

function start() {
  const pt = window.__pt;
  const { scene, world, wfc, materials, state } = pt;
  const tex = printAtlas();
  const face = materials.paper({ map: tex, alphaTest: 0.5, variant: 'kcal-food' });
  const back = materials.paper({ map: tex, alphaTest: 0.5, color: '#6b4c33', variant: 'kcal-food-edge' });
  const depth = new THREE.MeshDepthMaterial({ map: tex, alphaTest: 0.5 });
  // карточки смотрят в камеру (она не вращается: на юг), а свет — сверху сбоку: нормаль чуть к небу, как у зверей
  const nUp = new THREE.Vector3(0, 0.7, 0.72).normalize();
  const geo = new Map();
  KINDS.forEach((k, i) => {
    const s = FOODS[k].size * GIANT;
    const g = new THREE.PlaneGeometry(s, s);
    g.translate(0, s / 2 - s * 0.03, 0);       // нижние 3 % листа — поле печати под основанием
    const u0 = (i % GRID) / GRID, v0 = 1 - (Math.floor(i / GRID) + 1) / GRID;
    const uv = g.getAttribute('uv');
    for (let j = 0; j < uv.count; j++) uv.setXY(j, u0 + uv.getX(j) / GRID, v0 + uv.getY(j) / GRID);
    const n = g.getAttribute('normal');
    for (let j = 0; j < n.count; j++) n.setXYZ(j, nUp.x, nUp.y, nUp.z);
    geo.set(k, g);
  });

  /** Одна карточка: шарнир у основания, лицо и тыльный лист за ним — толщина картона, видна сверху тёмной кромкой. */
  function card(kind, x, z, scale, cell, delay) {
    const root = new THREE.Group();
    root.position.set(x, world.heightAt(x, z) + 0.003, z);
    const hinge = new THREE.Group();
    hinge.userData = { hinge: true, foldAngle: -Math.PI / 2, delay: 0, size: Math.min(1, FOODS[kind].size * GIANT * scale / T) };
    hinge.scale.setScalar(scale);
    const f = new THREE.Mesh(geo.get(kind), face);
    const b = new THREE.Mesh(geo.get(kind), back);
    b.position.z = -0.014;
    for (const m of [f, b]) { m.castShadow = true; m.receiveShadow = true; m.customDepthMaterial = depth; hinge.add(m); }
    root.add(hinge);
    const fold = createFoldInstance(root, { tileId: 'kcal-food:' + kind });
    fold.snap(0);
    root.visible = false;
    scene.add(root);
    return { root, fold, cell, delay };
  }

  const rows = new Map();                       // ряд клеток cz -> карточки в нём

  function makeRow(cz) {
    const seed = state.seed, out = [];
    const dens = window.KcalTune?.food ?? 1;
    const lane = wfc.get(0, cz);
    const theme = THEME[lane.biome] || THEME.heartland;
    const pick = (k) => theme[Math.floor(hash(seed, cz, k, 5) * theme.length)];
    const free = (x, z, r) => world.clearance(x, z) > r;
    // герой идёт сбоку от колонки дневника, она закрывает мир по другую сторону дороги: еда — больше на виду
    const wide = innerWidth > 1100, heroLeft = window.KcalTune?.heroLeft ?? 1;
    const sideOf = (k) => (hash(seed, cz, 0, k) < (wide ? 0.5 + 0.45 * heroLeft : 0.5) ? -1 : 1);
    // на обочине прямой дороги, не на самой дороге
    if (lane.model === 'path_straight' && hash(seed, cz, 0, 1) < 0.6 * dens) {
      const side = sideOf(2);
      const x = side * (0.72 + 0.2 * hash(seed, cz, 0, 3));
      const z = cz * T + (hash(seed, cz, 0, 4) - 0.5) * 1.2;
      if (free(x, z, 0.1)) out.push(card(pick(1), x, z, 0.85 + 0.3 * hash(seed, cz, 0, 6), [0, cz], 0.12));
    }
    // грядка рядом с дорогой: три-пять продуктов вперемешку, где есть место (не в воде, не в домах и деревьях)
    if (hash(seed, cz, 0, 7) < 0.45 * dens) {
      const side = sideOf(8);
      const cx = side * (1 + Math.floor(hash(seed, cz, 0, 9) * 2));
      const n = 3 + Math.floor(hash(seed, cz, 0, 10) * 3);
      const placed = [];
      for (let i = 0; i < n * 4 && placed.length < n; i++) {
        const x = cx * T + (hash(seed, cz, i, 11) - 0.5) * 1.7;
        const z = cz * T + (hash(seed, cz, i, 12) - 0.5) * 1.7;
        if (Math.abs(x) < 0.9 || !free(x, z, 0.12) || placed.some((p) => Math.hypot(p[0] - x, p[1] - z) < 0.55)) continue;
        placed.push([x, z]);
      }
      placed.sort((a, b) => b[1] - a[1]);
      placed.forEach(([x, z], i) => out.push(card(pick(20 + i), x, z, 0.8 + 0.35 * hash(seed, cz, i, 13), [cx, cz], 0.18 + i * 0.07)));
    }
    return out;
  }

  function dropRow(cz) {
    for (const c of rows.get(cz)) scene.remove(c.root);
    rows.delete(cz);
  }

  window.__ptTick = (dt) => {
    const hz = Math.round(pt.traveller.position.z / T);
    for (const cz of rows.keys()) if (cz > hz + BEHIND || cz < hz - AHEAD - 2) dropRow(cz);
    for (let cz = hz + BEHIND; cz >= hz - AHEAD; cz--) if (!rows.has(cz)) rows.set(cz, makeRow(cz));
    for (const list of rows.values()) {
      for (const c of list) {
        const want = world.cellTarget(c.cell[0], c.cell[1]);
        if (want !== c.fold.target) { c.fold.setTarget(want, want ? c.delay : 0); c.root.visible = true; }
        if (!c.fold.settled) c.fold.update(dt);
        else if (c.fold.target === 0) c.root.visible = false;
      }
    }
  };
  /** Панель прогулки: другая густота — ряды раскладываются заново. */
  window.KcalFood = { relayout() { for (const cz of [...rows.keys()]) dropRow(cz); } };
}

const wait = setInterval(() => { if (window.__pt && window.__pt.materials) { clearInterval(wait); start(); } }, 200);
