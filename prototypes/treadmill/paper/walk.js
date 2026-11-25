// KcalAI: бумажная прогулка-эстафета. Сцена для harness.js из скилла papercraft (MIT, xikhar/papercraft).
// Пока человек идёт по дорожке, за чатом страницы раскрывается бумажный мир, и по тропе идут герои дневника.
/* PAPER SPEC
BRIEF      страница дневника калорий; прогулка на дорожке. Тропа уходит вверх по листу через земли еды, зоны часто
           сменяются. Герои — Зёрнышко, Масло и Яйцо: каждый ведёт по своей земле, у входа в неё ждёт на лавочке,
           прежний садится отдыхать. Утро → золотой час по мере шагов к цели. Нельзя: небо, горизонт, глянец, текст.
SUBJECT    эстафета героев по землям еды; смотрят на идущего героя и на то, как меняются поля вокруг
STOCK      плотный картон, тихое волокно
PALETTE    земля #c3c9a2; зоны — пшеница #d8c07a, огород #9a6e48, подсолнухи #c9c27a, сад #b9c88f, двор #ddcfa0,
           лес #a9b487; ведущая семья — зелёные листья #6f9a4a #8db35c; акценты — красный #c8452d (яблоки, грибы),
           горчица #d9a33a (пшеница, подсолнух), розовый #e7a0b4; чернила #2c2618; тень — янтарь #9a6a34
LIGHT      ключ #ffdcb2 слева-сбоку 55°/50° → к цели #ffc890, 30°; заполняющий ~1/3
LENS       fov 26, наклон 47°, резкая полоса ~40 %, размытие до 18 px
GROUND     мох; поля — рваные полосы цветной бумаги поперёк листа: борозды, грядки, солома, падалица
LAYOUT     тропа снизу вверх, петляет; зона — 34 единицы (~14 с ходьбы); лавочка хозяина у входа в его зону
MOTION     раскрытие на шарнирах при старте; ветер качает траву и колосья; шаг от пройденного пути
LIFE       падающие листья, птицы, бабочки у цветов, курицы клюют и разбегаются, тени облаков
TYPES      деревья — кресты; колосья, подсолнухи, капуста, тыквы, ящики, курятник — карточки к камере;
           герои — модели из карточек (тело, профиль, лапки, ручки)
SOUND      нет
*/
import * as THREE from 'three';
import * as P2 from 'papercraft/lib/paperkit-2d.js';
import { makeCard } from 'papercraft/lib/paperkit-3d.js';
import * as EX from 'papercraft/examples/countryside.js';
import { createLife } from 'papercraft/lib/paper-life.js';
import { createHarness } from 'papercraft/harness.js';
import { boxHouse } from 'papercraft/examples/built-ground.js';

const C = {
  ground: '#c3c9a2', wash: '#b4bd90', washB: '#cdd0a8', path: '#f1e6c8', ink: '#2c2618', moss: '#8d9a58',
  leaf: '#6f9a4a', leafB: '#8db35c', pine: '#4f7a48', trunk: '#6e4a30', stone: '#b3a98e', rowan: '#c8452d', mustard: '#d9a33a', pink: '#e7a0b4',
  wheat: '#d9b25a', wheatDark: '#a8822f', soil: '#9a6e48', sun: '#f8cc3a', sunCore: '#5a3a1e', apple: '#c8452d', cabbage: '#8db35c',
  carrot: '#e07a2e', pumpkin: '#e08a2e', straw: '#e6cf8a', wood: '#a8683a',
  grain: '#7fbf8c', grainDark: '#4c8f5a', grainVein: '#3e7a4b', butter: '#f2c766', butterTop: '#f8e2a2', butterDark: '#c9a040',
  egg: '#f4ead6', eggShade: '#d9c8a6',
};
// ---- зоны: тропа идёт вверх по листу (к −z); мир повторяется каждые P единиц ----
const ZL = 34, ZONES = ['wheat', 'garden', 'sunflower', 'orchard', 'village', 'hens', 'forest'], P = ZL * ZONES.length, TAU = Math.PI * 2;
const OWNER = { wheat: 0, sunflower: 1, hens: 2 };          // 0 Зёрнышко, 1 Масло, 2 Яйцо — хозяин ведёт по своей земле
const NAMES = ['Зёрнышко', 'Масло', 'Яйцо'];
// тропа петляет сильно: герой поворачивается, и его видно то сбоку, то со спины
const pathX = (z) => 7 * Math.sin(z * TAU / P * 3) + 3 * Math.sin(z * TAU / P * 5 + 1);
const mod = (v, m = P) => ((v % m) + m) % m;
const uOf = (z) => mod(-z);                                   // пройденный путь внутри периода
const zoneAt = (z) => ZONES[Math.floor(uOf(z) / ZL)];
const ZS = -12, ZEND = ZS - P, WIDE = innerWidth > 1100;     // старт посреди пшеницы
const H = createHarness({
  ground: C.ground, view: { pitch: 47, dist: 58, target: [pathX(ZS) + (WIDE ? 19 : 0), 1.5, ZS - (WIDE ? 0.5 : 6)], follow: WIDE ? [21, -0.5] : [0, -6] },
  bounds: { x0: -72, x1: 72, z0: ZEND - 50, z1: ZS + 28 }, play: true,
  walkArea: { x0: -14, x1: 14, z0: ZEND - 3, z1: ZS + 2 }, subject: [pathX(ZS), ZS],
  light: { leak: '#9a6a34' }, lens: { haze: '#e6d6b0' },
});

// ---- земля: мох, поля зон рваными полосами поперёк листа, тропа ----
const ZONE_WASH = { wheat: '#d8c07a', garden: '#a9805a', sunflower: '#c9c27a', orchard: '#b9c88f', hens: '#ddcfa0', forest: '#a9b487', village: '#c9c79c' };
const zoneSpans = () => {                                    // все отрезки зон в границах листа
  const out = [];
  for (let k = -2; k <= 2; k++) ZONES.forEach((name, i) => {
    const zTop = -(i + 1) * ZL + k * P, zBot = -i * ZL + k * P;
    if (zBot > H.bounds.z0 && zTop < H.bounds.z1) out.push({ name, zTop, zBot });
  });
  return out;
};
H.printGround(({ g, ppu, X, Z, rnd, washes, torn, band, ribbon, drifts }) => {
  washes([C.wash, C.wash, C.washB, C.washB, C.wash]);
  for (const s of zoneSpans()) {                              // поле зоны: рваные края с бледной бахромой
    const pts = [];
    for (let x = -74; x <= 74; x += 2) pts.push([x, s.zTop + 0.6 + Math.sin(x * 0.7 + s.zTop) * 0.5 + rnd() * 0.4]);
    for (let x = 74; x >= -74; x -= 2) pts.push([x, s.zBot - 0.6 + Math.sin(x * 0.6 + s.zBot) * 0.5 - rnd() * 0.4]);
    torn(pts, P2.rgba(ZONE_WASH[s.name], 0.7));
    if (s.name === 'wheat' || s.name === 'garden') {         // борозды и грядки поперёк
      g.strokeStyle = P2.rgba(P2.shade(ZONE_WASH[s.name], -0.3), 0.45); g.lineWidth = 0.22 * ppu;
      for (let z = s.zTop + 2; z < s.zBot - 1.5; z += s.name === 'garden' ? 2.2 : 1.1) { g.beginPath(); g.moveTo(X(-74), Z(z)); g.lineTo(X(74), Z(z + 0.3)); g.stroke(); }
    }
    for (let k = 0; k < 260; k++) {                            // мелочь зоны: падалица, солома, листья
      const x = (rnd() * 2 - 1) * 70, z = s.zTop + rnd() * ZL, r = (0.14 + rnd() * 0.14) * ppu;
      const col = { orchard: C.apple, hens: C.straw, village: C.pink, forest: [C.rowan, C.mustard][k % 2], wheat: C.wheatDark, garden: P2.shade(C.soil, -0.2), sunflower: C.mustard }[s.name];
      g.fillStyle = P2.rgba(col, 0.75); g.beginPath(); g.ellipse(X(x), Z(z), r * (s.name === 'hens' ? 2.2 : 1.2), r * 0.6, rnd() * 3, 0, 7); g.fill();
    }
  }
  torn(band(pathX, (z) => 1.35 + Math.sin(z * TAU / P * 12) * 0.12), C.path);
  ribbon(pathX, 0.7, P2.rgba('#fbf3dc', 0.5), undefined, undefined, 0.2);
  for (const sd of [-1, 1]) ribbon((z) => pathX(z) + sd * 1.55, 0.4, P2.rgba(P2.shade(C.moss, -0.15), 0.3), undefined, undefined, 0.15);
  const z0 = H.bounds.z0, z1 = H.bounds.z1;
  for (let k = 0, n = Math.round(20 * (z1 - z0)); k < n; k++) {
    const z = z0 + rnd() * (z1 - z0), x = pathX(z) + (rnd() - 0.5) * 2.3, r = (0.05 + rnd() * 0.06) * ppu;
    g.fillStyle = rnd() < 0.6 ? P2.rgba(P2.shade(C.path, -0.3), 0.55) : P2.rgba('#fff6e2', 0.7);
    g.beginPath(); g.ellipse(X(x), Z(z), r * 1.3, r, rnd() * 3, 0, 7); g.fill();
  }
  drifts((x, y) => {
    if (rnd() < 0.85) { g.strokeStyle = P2.rgba(rnd() < 0.5 ? '#6c7c4a' : '#8d9a58', 0.7); g.lineWidth = 1.5; g.beginPath(); g.moveTo(x, y); g.lineTo(x + (rnd() - 0.5) * 3, y - 5 - rnd() * 5); g.stroke(); }
    else { g.fillStyle = rnd() < 0.5 ? '#f3e6c8' : C.pink; for (let q = 0; q < 5; q++) { const t = q / 5 * 6.283; g.beginPath(); g.arc(x + Math.cos(t) * 2.2, y + Math.sin(t) * 2.2, 1.6, 0, 7); g.fill(); } }
  });
});

// ---- детали: свои карточки еды + деревья и кусты набора в нашей палитре; по несколько вариантов, один отпечаток ----
let rnd = P2.rng(101);
const D = P2.draw2, between = (a, b) => a + rnd() * (b - a);
const mound = (w, h, lobes = 7, depth = 0.18) => P2.shape.mound(rnd, { w, h, lobes, depth });
const piece = (name, w, h, draw) => P2.drawnPiece(draw, { w, h, name });
const K = {
  tree: () => { const h = between(7, 9.5); return [P2.roughen(EX.shape.tree(rnd, { w: h * between(0.72, 0.84), h, lobes: 7 + Math.floor(rnd() * 3), canopy: 0.76, trunk: 0.26 }), rnd),
    EX.paintTree({ leaf: [C.leaf, C.leafB, C.rowan, C.mustard][Math.floor(rnd() * 4)], trunk: C.trunk, ink: C.ink }, { trunkTop: 0.24 })]; },
  appletree: () => { const h = between(6, 7.5); return [P2.roughen(EX.shape.tree(rnd, { w: h * 0.86, h, lobes: 8, canopy: 0.72, trunk: 0.28 }), rnd),
    EX.paintTree({ leaf: rnd() < 0.5 ? C.leaf : C.leafB, trunk: C.trunk, ink: C.ink }, { trunkTop: 0.26, berries: C.apple })]; },
  pine: () => { const h = between(8, 11); return [P2.roughen(EX.shape.pine(rnd, { w: h * 0.58, h }), rnd), EX.paintPine({ leaf: C.pine, trunk: C.trunk, ink: C.ink })]; },
  bush: () => { const w = between(3.4, 4.6); return [P2.roughen(mound(w, w * 0.6, 9, 0.12), rnd), EX.paintBush({ leaf: rnd() < 0.5 ? '#5f8a46' : '#7c9a4e' }, { bands: 2, flowers: rnd() < 0.4 })]; },
  fern: () => [P2.shape.mound(rnd, { w: 3.0, h: 2.4, lobes: 8, depth: 0.42 }), EX.paintBush({ leaf: '#7d9a4f' }, { bands: 1, flowers: false, motifs: false })],
  flowers: () => [P2.roughen(mound(4.4, 1.8, 11, 0.25), rnd), EX.paintBush({ leaf: '#6f8f5a' }, { bands: 0, flowers: true, motifs: false })],
  rock: () => { const w = between(2.6, 4); return [P2.roughen(mound(w, w * 0.55, 4, 0.1), rnd), EX.paintRock({ stone: C.stone, ink: C.ink }, { moss: rnd() < 0.5 })]; },
  mushroom: () => [EX.props.mushroom(between(1.6, 2.2)), EX.paintMushroom()],
  // колосья: пучок стеблей на общем основании, у каждого колос с зёрнами
  // сноп: пышная золотая копна, над ней крупные колосья — не гребёнка из палочек
  wheat: () => { const hh = between(1.6, 2.1), n = 5 + Math.floor(rnd() * 2); return piece('wheat', 2.4, hh + 0.9, (g, ink) => {
    g.fillStyle = ink(C.wheatDark); D.ell(g, 0, hh * 0.42, 0.95, hh * 0.45);
    g.fillStyle = ink(C.wheat); D.ell(g, -0.15, hh * 0.5, 0.7, hh * 0.42);
    for (let i = 0; i < n; i++) { const x = -0.7 + i * 1.4 / (n - 1), top = hh * (0.85 + 0.15 * Math.sin(i * 2.3)), a = (x * 0.35) + Math.sin(i) * 0.1;
      g.fillStyle = ink(P2.shade(C.wheat, i % 2 ? 0.12 : -0.05)); D.ell(g, x + Math.sin(a) * 0.3, top + 0.3, 0.17, 0.4, a); }
    g.fillStyle = ink(C.wheatDark, 'print'); for (let i = 0; i < n; i++) { const x = -0.7 + i * 1.4 / (n - 1), top = hh * (0.85 + 0.15 * Math.sin(i * 2.3)); for (let q = 0; q < 3; q++) D.ell(g, x + Math.sin(x * 0.35) * 0.3, top + 0.12 + q * 0.16, 0.07, 0.035); }
    g.fillStyle = ink(C.rowan, 'print'); D.rect(g, -0.7, hh * 0.32, 1.4, 0.07);
  }); },
  hay: () => piece('hay', 2.0, 1.3, (g, ink) => { g.fillStyle = ink(C.straw); D.rect(g, -0.9, 0, 1.8, 1.1, 0.3);
    g.fillStyle = ink(P2.shade(C.straw, -0.25), 'print'); for (let k = 0; k < 5; k++) D.rect(g, -0.8, 0.15 + k * 0.2, 1.6, 0.04); g.fillStyle = ink(C.rowan, 'print'); D.rect(g, -0.9, 0.45, 1.8, 0.08); }),
  cornflowers: () => [P2.roughen(mound(3.2, 1.4, 9, 0.3), rnd), EX.paintBush({ leaf: '#7f9a58' }, { bands: 0, flowers: true, motifs: false })],
  // огород: капуста кочаном, морковная ботва с оранжевым плечиком, тыква с рёбрами
  cabbage: () => piece('cabbage', 1.6, 1.2, (g, ink) => { g.fillStyle = ink(P2.shade(C.cabbage, -0.15)); D.ell(g, 0, 0.45, 0.72, 0.45);
    g.fillStyle = ink(C.cabbage); D.ell(g, 0, 0.55, 0.5, 0.42); g.fillStyle = ink(P2.shade(C.cabbage, 0.2)); D.ell(g, 0, 0.62, 0.28, 0.28);
    g.fillStyle = ink(P2.shade(C.cabbage, -0.35), 'print'); D.rect(g, -0.02, 0.2, 0.04, 0.6); }),
  carrot: () => piece('carrot', 1.6, 1.7, (g, ink) => { g.fillStyle = ink('#5f8a3e'); D.rect(g, -0.08, 0.2, 0.16, 0.5); for (const a of [-0.5, -0.15, 0.2, 0.55]) D.ell(g, Math.sin(a) * 0.35, 0.7 + Math.cos(a) * 0.3, 0.12, 0.42, a);
    g.fillStyle = ink(C.carrot); D.ell(g, 0, 0.18, 0.3, 0.2); g.fillStyle = ink(P2.shade(C.carrot, -0.3), 'print'); D.rect(g, -0.2, 0.16, 0.4, 0.03); }),
  pumpkin: () => piece('pumpkin', 1.8, 1.3, (g, ink) => { g.fillStyle = ink(P2.shade(C.pumpkin, -0.12)); D.ell(g, -0.42, 0.45, 0.42, 0.42); D.ell(g, 0.42, 0.45, 0.42, 0.42);
    g.fillStyle = ink(C.pumpkin); D.ell(g, 0, 0.47, 0.5, 0.46); g.fillStyle = ink('#5f7a3a'); D.rect(g, -0.06, 0.85, 0.12, 0.25, 0.04);
    g.fillStyle = ink(P2.shade(C.pumpkin, -0.3), 'print'); D.rect(g, -0.22, 0.12, 0.04, 0.68); D.rect(g, 0.18, 0.12, 0.04, 0.68); }),
  fence: () => [EX.shape.fence({ w: 5.4, h: 2.0, pickets: 6 }), EX.paintWood({ wood: '#b07a48' }, { planks: 1 })],
  // подсолнух: стебель с листьями, жёлтые лепестки, тёмная серединка с семечками
  sunflower: () => { const hh = between(3.0, 3.8); return piece('sunflower', 2.0, hh + 1.0, (g, ink) => {
    g.fillStyle = ink('#5f8a3e'); D.rect(g, -0.07, 0, 0.14, hh, 0.05); D.ell(g, -0.32, hh * 0.45, 0.32, 0.13, 0.5); D.ell(g, 0.3, hh * 0.62, 0.3, 0.12, -0.5);
    g.fillStyle = ink(C.sun); for (let k = 0; k < 12; k++) { const a = k / 12 * TAU; D.ell(g, Math.cos(a) * 0.46, hh + 0.1 + Math.sin(a) * 0.46, 0.28, 0.14, a); }
    g.fillStyle = ink(C.sunCore); D.ell(g, 0, hh + 0.1, 0.26, 0.26);
    g.fillStyle = ink(P2.shade(C.sunCore, 0.35), 'print'); for (let k = 0; k < 9; k++) D.ell(g, Math.cos(k * 2.4) * 0.18 * Math.sqrt(k / 9), hh + 0.1 + Math.sin(k * 2.4) * 0.18 * Math.sqrt(k / 9), 0.035, 0.035);
  }); },
  // сад: ящик с яблоками
  crate: () => piece('crate', 1.8, 1.3, (g, ink) => { g.fillStyle = ink(C.apple); for (const x of [-0.45, -0.15, 0.15, 0.45]) D.ell(g, x, 0.78, 0.17, 0.17);
    g.fillStyle = ink(C.wood); D.rect(g, -0.75, 0, 1.5, 0.75, 0.06); g.fillStyle = ink(P2.shade(C.wood, -0.3), 'print'); D.rect(g, -0.75, 0.24, 1.5, 0.05); D.rect(g, -0.75, 0.5, 1.5, 0.05); }),
  // птичий двор: курятник, корзинка с яйцами, курица
  coop: () => piece('coop', 3.0, 2.8, (g, ink) => { g.fillStyle = ink('#c8513b'); D.poly(g, [[-1.3, 1.5], [0, 2.5], [1.3, 1.5]]);
    g.fillStyle = ink('#ecd4b0'); D.rect(g, -1.05, 0.2, 2.1, 1.35, 0.05); g.fillStyle = ink(C.wood); D.rect(g, -1.1, 0, 0.18, 0.4); D.rect(g, 0.92, 0, 0.18, 0.4);
    g.fillStyle = ink('#5a3a24', 'print'); D.rect(g, -0.25, 0.2, 0.5, 0.7, 0.2); g.fillStyle = ink(P2.shade('#c8513b', -0.3), 'print'); D.rect(g, -1.2, 1.5, 2.4, 0.06); }),
  eggs: () => piece('eggs', 1.5, 1.0, (g, ink) => { g.fillStyle = ink(C.egg); for (const x of [-0.3, 0, 0.3]) D.ell(g, x, 0.55, 0.15, 0.2);
    g.fillStyle = ink('#b58a4a'); D.ell(g, 0, 0.32, 0.62, 0.3); g.fillStyle = ink('#8a6230', 'print'); D.rect(g, -0.55, 0.3, 1.1, 0.04); }),
  hen: () => EX.critter.hen(1.5),
  bench: () => [EX.props.bench(1.8), EX.paintWood({ wood: C.wood }, { planks: 2 })],
};
const VAR = {};
const SINGLE = new Set(['hen', 'coop', 'fence', 'bench']);
for (const k of Object.keys(K)) VAR[k] = Array.from({ length: SINGLE.has(k) ? 1 : 4 }, () => {
  const [pts, paint] = K[k](), cross = k === 'tree' || k === 'pine' || k === 'appletree';
  return { pts, cross, materials: H.printed(pts, paint, { cross }) };
});
const put = (k, x, z, o = {}) => { const v = VAR[k][Math.floor(rnd() * VAR[k].length)]; return H.popup(v.pts, null, { materials: v.materials, cross: v.cross, x, z, ...o }); };

// ---- герои: бумажные модели из карточек (тело с лицом спереди, профиль сзади, лапки, ручки) ----
const makeDoll = (kind) => {
  const g = new H.THREE.Group(), body = new H.THREE.Group(); g.add(body);
  const PROFILE = -Math.PI / 2, T = 0.05, GAP = 0.03, SIDE = 0.01, seed = 400 + kind * 40;
  const card = ([pts, paint], s, { x = 0, y = 0, z = 0, ry = 0, parent = body, back = 'mirror', name = '', cut = null } = {}) => {
    const m = makeCard(pts, { paint, paper: H.paper, thickness: T, edgeWidth: 0.04, pxPerUnit: 64, seed: s, back, folds: [], geomPts: cut });
    m.position.set(x, y, z); m.rotation.y = ry; m.name = name; parent.add(m); return m;
  };
  const ext = ([pts]) => pts.reduce((e, [x, y]) => ({ x0: Math.min(e.x0, x), x1: Math.max(e.x1, x), y0: Math.min(e.y0, y), y1: Math.max(e.y1, y) }), { x0: Infinity, x1: -Infinity, y0: Infinity, y1: -Infinity });
  const behind = (pc, z0) => z0 - T / 2 - GAP - ext(pc).x1;
  const band = (pts, lo, hi, k = 1) => [[lo, 1], [hi, -1]].reduce((poly, [v, s]) => {   // оставить lo <= x <= hi (k 0)
    const out = [], ok = (p) => s * (p[k] - v) >= 0;
    poly.forEach((a, i) => { const b = poly[(i + 1) % poly.length]; if (ok(a)) out.push(a); if (ok(a) !== ok(b)) { const t = (v - a[k]) / (b[k] - a[k]); out.push(k ? [a[0] + (b[0] - a[0]) * t, v] : [v, a[1] + (b[1] - a[1]) * t]); } });
    return out;
  }, pts);
  const egg = (gg, cx, cy, w, h, top = 0.2) => { gg.beginPath(); for (let i = 0; i <= 40; i++) { const a = i / 40 * TAU, y = -Math.cos(a), k = 1 - top * Math.max(0, y); gg.lineTo(cx + Math.sin(a) * w / 2 * k, cy + y * h / 2); } gg.closePath(); gg.fill(); };
  const face = (gg, ink, y, sp = 0.18) => {
    gg.fillStyle = ink('#f3b5c8', 'print'); D.ell(gg, -sp * 2, y - 0.14, 0.09, 0.06); D.ell(gg, sp * 2, y - 0.14, 0.09, 0.06);
    gg.fillStyle = ink(C.ink, 'print'); D.ell(gg, -sp, y, 0.075, 0.09); D.ell(gg, sp, y, 0.075, 0.09);
    gg.fillStyle = ink('#fff8ea', 'print'); D.ell(gg, -sp - 0.02, y + 0.03, 0.022, 0.026); D.ell(gg, sp - 0.02, y + 0.03, 0.022, 0.026);
    gg.fillStyle = ink(C.ink, 'print'); D.rect(gg, -0.09, y - 0.22, 0.18, 0.035, 0.017);
  };
  // кто есть кто: тело, цвет, высота
  const SPEC = [
    { col: C.grain, dark: C.grainDark, w: 1.24, h: 1.56, front: (gg, ink, w, h) => { gg.fillStyle = ink(C.grain); egg(gg, 0, h / 2, w, h); gg.fillStyle = ink(P2.shade(C.grain, 0.18), 'print'); egg(gg, -0.12, h * 0.58, w * 0.5, h * 0.62); gg.fillStyle = ink(C.grainVein, 'print'); D.rect(gg, -0.02, 0.1, 0.04, h * 0.32, 0.02); face(gg, ink, 0.66); } },
    { col: C.butter, dark: C.butterDark, w: 1.4, h: 1.2, front: (gg, ink, w, h) => { gg.fillStyle = ink(C.butter); D.rect(gg, -w / 2, 0, w, h, 0.18); gg.fillStyle = ink(C.butterTop); D.rect(gg, -w / 2, h * 0.7, w, h * 0.3, 0.16); gg.fillStyle = ink(P2.shade(C.butter, 0.15), 'print'); D.rect(gg, -w / 2 + 0.12, 0.12, 0.16, h * 0.5, 0.06); face(gg, ink, 0.55); } },
    { col: C.egg, dark: C.eggShade, w: 1.14, h: 1.62, front: (gg, ink, w, h) => { gg.fillStyle = ink(C.egg); egg(gg, 0, h / 2, w, h, 0.28); gg.fillStyle = ink('#fffaf0', 'print'); egg(gg, -0.14, h * 0.62, w * 0.36, h * 0.46); face(gg, ink, 0.62, 0.17); } },
  ][kind];
  const { w: BW, h: BH } = SPEC, BODY_Y = 0.92;
  const front = P2.drawnPiece((gg, ink) => SPEC.front(gg, ink, BW, BH), { w: BW + 0.1, h: BH + 0.05, name: 'body' });
  const backPrint = (Pp) => { const { box } = Pp; Pp.gradient(P2.shade(SPEC.col, 0.04), P2.shade(SPEC.col, -0.14), 0.5); Pp.felt();
    Pp.layer((q) => { q.fillStyle = P2.shade(SPEC.col, 0.18); q.beginPath(); q.ellipse(box.x0 + box.w * 0.36, box.y0 + box.h * 0.42, box.w * 0.12, box.h * 0.2, 0.4, 0, 7); q.fill(); }, P2.shade(SPEC.col, 0.18), { lip: 0.6 });
    Pp.innerShade(P2.shade(SPEC.col, -0.5), { alpha: 0.3, blurPx: 8 }); };
  card(front, seed + 1, { y: BODY_Y, z: 0, back: backPrint, name: 'body' });
  const side = P2.drawnPiece((gg, ink) => { gg.fillStyle = ink(P2.shade(SPEC.col, -0.06)); if (kind === 1) D.rect(gg, -0.4, 0, 0.8, BH, 0.16); else egg(gg, 0, BH / 2, 0.8, BH); gg.fillStyle = ink(C.ink, 'print'); D.ell(gg, 0.28, kind === 1 ? 0.55 : 0.64, 0.05, 0.08); }, { w: 0.9, h: BH + 0.05, name: 'side' });
  // профиль подрезан по глубине тела: за ним, со спины, — рюкзачок своего цвета (сзади герой не голая заливка)
  const sideZ = behind(side, 0), sx = ext(side), DEPTH = 0.42;
  card(side, seed + 2, { y: BODY_Y, z: sideZ, ry: PROFILE, name: 'body side', cut: band(side[0], sx.x1 - DEPTH, 9, 0) });
  const packCol = ['#c8513b', '#4f8a8a', '#7a5ab0'][kind];
  const pack = P2.drawnPiece((gg, ink) => {
    gg.fillStyle = ink(packCol); D.rect(gg, -0.36, 0, 0.72, 0.78, 0.16);
    gg.fillStyle = ink(P2.shade(packCol, -0.2)); D.rect(gg, -0.39, 0.48, 0.78, 0.32, 0.12);
    gg.fillStyle = ink('#f3e6c8', 'print'); D.rect(gg, -0.06, 0.4, 0.12, 0.16, 0.03);
    gg.fillStyle = ink(P2.shade(packCol, 0.25), 'print'); D.rect(gg, -0.28, 0.12, 0.56, 0.06); D.ell(gg, 0.2, 0.28, 0.07, 0.07);
  }, { w: 0.86, h: 0.86, name: 'pack' });
  card(pack, seed + 5, { y: BODY_Y + BH * 0.3, z: sideZ + sx.x1 - DEPTH - GAP - T / 2, name: 'pack' });
  if (kind === 0) {                                          // хохолок Зёрнышка
    const tuft = (c) => P2.drawnPiece((gg, ink) => { gg.fillStyle = ink(c); D.rect(gg, -0.2, 0, 0.4, 0.12, 0.05);
      for (const [x, a, l] of [[-0.12, -0.5, 0.42], [0, 0, 0.5], [0.12, 0.5, 0.42]]) { const tx = x + Math.sin(a) * l, ty = Math.cos(a) * l; D.poly(gg, [[x - 0.05, 0], [x + 0.05, 0], [tx + 0.06, ty], [tx - 0.06, ty]]); D.ell(gg, tx, ty, 0.09, 0.09); } }, { w: 0.98, h: 0.76, name: 'tuft' });
    card(tuft(C.grainDark), seed + 3, { y: BODY_Y + BH - 0.06, z: 0.08, name: 'tuft' });
  }
  const legArt = (c, name) => P2.drawnPiece((gg, ink) => { gg.fillStyle = ink(c); D.rect(gg, -0.09, 0.14, 0.18, 0.82, 0.08); gg.fillStyle = ink(SPEC.dark); D.rect(gg, -0.13, 0, 0.3, 0.2, 0.09); }, { w: 0.35, h: 1.0, name });
  const limb = kind === 2 ? C.eggShade : SPEC.col;
  const legs = [-0.24, 0.24].map((x, i) => {
    const hip = new H.THREE.Group(); hip.position.set(x, 1.0, -0.2); body.add(hip);
    card(legArt(limb, `leg ${i}`), seed + 10 + i, { y: -0.98, parent: hip, name: `leg ${i}` });
    const s = legArt(P2.shade(limb, -0.08), `leg ${i} side`);
    card(s, seed + 20 + i, { y: -0.98, z: behind(s, 0), ry: PROFILE, parent: hip, name: `leg ${i} side` });
    return hip;
  });
  const armArt = (name) => P2.drawnPiece((gg, ink) => { gg.fillStyle = ink(limb); D.rect(gg, -0.09, 0.06, 0.18, 0.56, 0.09); gg.fillStyle = ink(P2.shade(limb, 0.15), 'print'); D.ell(gg, 0, 0.1, 0.08, 0.08); }, { w: 0.3, h: 0.66, name });
  const arms = [-1, 1].map((sx, i) => {
    const sh = new H.THREE.Group(); sh.position.set(sx * (BW / 2 + 0.16 + SIDE), BODY_Y + BH * 0.55, 0); body.add(sh);
    card(armArt(`arm ${i}`), seed + 30 + i, { y: -0.6, parent: sh, name: `arm ${i}` });
    return sh;
  });
  const legLen = 1.2, stepLen = 1.1, R = stepLen / (2 * legLen);
  const leg = (ph, gait) => { const u = (ph / Math.PI) % 2, A = gait * R; if (u >= 1) return A * (3 - 2 * u); const e = u * u * (3 - 2 * u); return A * (2 * e - 1); };
  g.userData.stride = (walked, gait) => {
    const ph = walked / stepLen * Math.PI, l0 = leg(ph, gait), l1 = leg(ph + Math.PI, gait);
    legs[0].rotation.x = -l0; legs[1].rotation.x = -l1; arms[0].rotation.x = l0 * 0.8; arms[1].rotation.x = l1 * 0.8;
    body.position.y = Math.abs(Math.sin(ph)) * 0.04 * gait;
  };
  g.userData.pose = (k) => { legs.forEach((l) => { l.rotation.x = -1.45 * k; }); arms[0].rotation.x = -0.3 * k; arms[1].rotation.x = -0.3 * k; body.position.y = 0; };   // 0 стоит .. 1 сидит
  return g;
};

// идущий герой: внутри все трое, виден тот, чья очередь
const walkers = [0, 1, 2].map(makeDoll);
const heroNode = new H.THREE.Group(); walkers.forEach((d, i) => { d.visible = i === 0; heroNode.add(d); });
let walker = 0;
const hero = H.popup(null, null, { node: heroNode, height: 3.1, x: pathX(ZS), z: ZS, sway: 0.15, scale: 1.2 });
H.setHero(hero, { stride: (w, gait) => walkers[walker].userData.stride(w, gait) });

// лавочки у входа в зону хозяина: хозяин ждёт, пришедший садится вместо него
const SIT_Y = 1.1 / 1.2 - 1.0, SIT_Z = 0.35, STAND_Z = 0.75;   // в долях сидящего узла (масштаб 1.2): бёдра на сиденье
const poseSitter = (d, k, z, ry) => { d.userData.pose(k); d.position.set(0, SIT_Y * k, z); d.rotation.y = ry; };
const showSitter = (b, who) => { for (const w of [b.owner, b.prev]) b.dolls[w].visible = w === who; };
const benches = [];
for (const s of zoneSpans()) if (OWNER[s.name] !== undefined) {
  const z = s.zBot - 3, side = benches.length % 2 ? -1 : 1, x = pathX(z) + side * 2.6, owner = OWNER[s.name], prev = (owner + 2) % 3;
  put('bench', x, z, { yaw: 0 });
  const sitNode = new H.THREE.Group(), dolls = {};
  for (const who of [owner, prev]) { const d = makeDoll(who); poseSitter(d, 1, SIT_Z, 0); sitNode.add(d); dolls[who] = d; }
  H.popup(null, null, { node: sitNode, height: 2.6, x, z, yaw: 0, scale: 1.2 });
  const b = { x, z, owner, prev, dolls, done: z > ZS };      // позади старта — уже сменились
  showSitter(b, b.done ? prev : owner); benches.push(b);
}

// ---- зоны: что растёт и стоит в каждой ----
const DRESS = {
  forest: { tall: ['tree', 'pine', 'pine'], low: ['bush', 'fern', 'mushroom', 'rock', 'flowers'], trees: 0.55 },
  wheat: { tall: [], low: ['wheat', 'wheat', 'wheat', 'hay', 'cornflowers'], trees: 2 },
  garden: { tall: [], low: ['cabbage', 'carrot', 'pumpkin', 'cabbage', 'carrot'], trees: 2 },
  sunflower: { tall: [], low: ['sunflower', 'sunflower', 'sunflower', 'bush', 'flowers'], trees: 2 },
  orchard: { tall: ['appletree'], low: ['crate', 'flowers', 'bush', 'fern'], trees: 0.45 },
  village: { tall: ['tree'], low: ['flowers', 'bush', 'fern', 'flowers'], trees: 0.62 },
  hens: { tall: [], low: ['hay', 'eggs', 'flowers', 'fern'], trees: 2 },
};
const cellSeed = (gx, gz, salt) => (Math.imul(Math.round(gx * 10), 73856093) ^ Math.imul(Math.round(mod(gz) * 10), 19349663) ^ Math.imul(salt, 83492791)) >>> 0;
const lush = (x, z) => 0.5 + 0.25 * Math.sin(x * 0.13 + 1.1) * Math.sin(z * TAU / P * 9 + 0.4) + 0.15 * Math.sin(x * 0.21 + z * TAU / P * 13 + 2.0);
const away = (x, z) => Math.abs(x - pathX(z));
const LIFE = { flowers: [], hens: [] };
// деревня: домики-коробочки по сторонам тропы, из труб идёт дым
const homes = [], CHIMNEYS = [];
const ROOFS = ['#c8513b', '#9c5636', '#b5683a'];
for (const s of zoneSpans()) if (s.name === 'village') [-10, 0, 10].forEach((dz, i) => {
  const z = (s.zTop + s.zBot) / 2 + dz, sd = i % 2 ? -1 : 1, x = pathX(z) + sd * (7 + i), dims = { w: 4.6, d: 3.8, wallH: 3.2, roofH: 2.4 };
  const node = boxHouse({ ...dims, colors: { wall: '#ecd4b0', roof: ROOFS[i], window: '#ffc45a', door: '#3f7a70', ink: C.ink }, paper: H.paper, seed: 70 + i, print: H.cardOpts(x, z) });
  const hh = H.popup(null, null, { node, height: dims.wallH + dims.roofH, x, z, yaw: -sd * 0.35, solid: 3.2 });
  if (node.userData.chimneyTop) CHIMNEYS.push(node.userData.chimneyTop);
  H.glowing(hh.group); homes.push([x, z]);
});
const benchNear = (x, z) => benches.some((b) => Math.hypot(x - b.x, z - b.z) < 3.2) || homes.some(([hx, hz]) => Math.hypot(x - hx, z - hz) < 5.2);
const trees = [];
for (let gz = Math.ceil(H.bounds.z0 / 3) * 3; gz <= H.bounds.z1; gz += 3) for (let gx = -68; gx <= 68; gx += 3.2) {
  rnd = P2.rng(cellSeed(gx, gz, 19));
  const x = gx + (rnd() - 0.5) * 2.4, z = gz + (rnd() - 0.5) * 2.2, d = away(x, z), zone = zoneAt(z), Zn = DRESS[zone];
  const field = zone === 'wheat' || zone === 'sunflower' || zone === 'garden';
  if (d < 2.3 || benchNear(x, z) || !H.inReach(x, z, 0.1, 4) || (!field && lush(x, z) < 0.24)) continue;
  if (Zn.tall.length && d > 4.6 && lush(x, z) > Zn.trees && trees.every(([tx, tz]) => Math.hypot(x - tx, z - tz) > 4.6)) {
    trees.push([x, z]); put(Zn.tall[Math.floor(rnd() * Zn.tall.length)], x, z, { sway: 0.7 });
    continue;
  }
  const n = field ? 3 + Math.floor(rnd() * 2) : 2 + Math.floor(rnd() * 2);
  for (let i = 0; i < n; i++) {
    const k = Zn.low[Math.floor(rnd() * Zn.low.length)], a = rnd() * 6.28, r = rnd() * 1.6, px = x + Math.cos(a) * r, pz = z + Math.sin(a) * r * 0.7;
    if (away(px, pz) < 2.0 || zoneAt(pz) !== zone) continue;
    put(k, px, pz, { yaw: (rnd() - 0.5) * 0.5, scale: 0.75 + rnd() * 0.35, sway: ['fern', 'flowers', 'wheat', 'cornflowers', 'sunflower'].includes(k) ? 0.6 : 0 });
    if (k === 'flowers' || k === 'sunflower') LIFE.flowers.push([px, pz]);
  }
}
// у каждой зоны — своя примета у тропы: изгородь огорода, курятник с курами
for (const s of zoneSpans()) {
  rnd = P2.rng(cellSeed(0, s.zBot, 77));
  const zm = (s.zTop + s.zBot) / 2, sd = rnd() < 0.5 ? -1 : 1;
  if (s.name === 'garden') for (const dz of [-8, 8]) put('fence', pathX(zm + dz) - sd * 3.2, zm + dz, { yaw: 0.1 });
  if (s.name === 'hens') {
    put('coop', pathX(zm) + sd * 5, zm, { solid: 1.5 });
    for (let i = 0; i < 3; i++) LIFE.hens.push(put('hen', pathX(zm + 4 - i * 4) - sd * (2.8 + i * 0.6), zm + 4 - i * 4, { yaw: (rnd() - 0.5) * 0.6 }));
  }
}
for (let gz = Math.ceil(H.bounds.z0 / 2) * 2; gz <= H.bounds.z1; gz += 2) for (const sd of [-1, 1]) {   // мелочь у кромки тропы
  rnd = P2.rng(cellSeed(sd * 7, gz, 69));
  const z = gz + rnd() * 1.6, x = pathX(z) + sd * (1.75 + rnd() * 0.9);
  if (rnd() < 0.4 || benchNear(x, z)) continue;
  const zone = zoneAt(z);
  put(zone === 'wheat' ? 'wheat' : zone === 'forest' ? (rnd() < 0.7 ? 'fern' : 'mushroom') : 'fern', x, z, { scale: 0.45 + rnd() * 0.25, sway: 0.6 });
}

// ---- живой мир ----
const life = createLife({ scene: H.scene, camera: H.camera, heroHeight: H.heroHeight, seed: 41, wind: [0.94, 0.34] });
life.fallers({ count: 40, cut: 'leaf', colors: [C.rowan, C.mustard, '#b8692e', C.leafB], size: [0.2, 0.3], land: 9 });
life.flock({ size: 5, height: 3.6, colors: ['#f6f1e6', '#e9e2d4'] });
life.flyers({ count: 12, colors: ['#fff6e0', C.mustard, C.pink], roam: 1.8 });
life.puffs({ at: CHIMNEYS, perEmitter: 3, life: 4.5, rise: 0.45, size: [0.2, 0.45], colors: ['#f3e8d2', '#ece0c8'] });
life.cloudShadows({ count: 4 });
for (const h of LIFE.hens) life.critter(h.group, { behaviour: 'peck', hero: H.hero.group.position, walk: H.walk, roam: 1.2 });
life.reset(H.target);
H.onStep((t, dt) => life.update(dt, H.target));
H.onRestart(() => life.reset(H.target));

H.start();

// ---- связь со страницей: темп дорожки, тропа, эстафета, свет по прогрессу, раскрытие при старте ----
const pc = window.__papercraft;
const DAY = { key: new THREE.Color('#ffdcb2'), fill: new THREE.Color('#f2dcc2'), dir: new THREE.Vector3(-0.8, 0.95, 0.55), ki: 3.0 };
const GOLD = { key: new THREE.Color('#ffc890'), fill: new THREE.Color('#c9b8c8'), dir: new THREE.Vector3(-0.85, 0.52, 0.5), ki: 3.4 };
const dir = new THREE.Vector3();
let lastP = -1, phase = 'walk', phT = 0, cur = null, heading0 = 0;
const ease = (t) => t * t * (3 - 2 * t);
const setWalker = (who) => { walker = who; walkers.forEach((d, i) => { d.visible = i === who; }); };
// сказка оживает: рядом с героем детали встают, позади и вдали лежат на листе
const RISE_IN = 17, RISE_OUT = 20;
const wake = (hp, snap = false) => {
  for (const h of H.hinges) {
    if (h === hero) continue;
    const d = Math.hypot(h.x - hp.x, h.z - hp.z), want = (h._want === 1 ? d < RISE_OUT : d < RISE_IN) ? 1 : 0;
    if (snap) { h._want = want; h.reset(want); continue; }
    if (want !== h._want) { h._want = want; h.set(want, want ? Math.max(0, (d - 9) * 0.035) + h.jit : 0.05 * Math.random()); }
  }
};
H.onStep((t, dt) => {
  const S = window.KcalWalk; if (!S) return;
  const hp = pc.heroPos;
  if (hp.z < ZEND) { pc.place(hp.x, hp.z + P); wake(hp, true); }   // за краем периода мир тот же: на период назад, без волны
  wake(hp);
  for (const b of benches) if (b.done && b.z < hp.z - 60) { b.done = false; showSitter(b, b.owner); poseSitter(b.dolls[b.owner], 1, SIT_Z, 0); }
  // эстафета: подойти к лавочке, хозяин встаёт и поворачивается по ходу, меняются, пришедший садится
  if (phase === 'walk') { const b = benches.find((q) => !q.done && q.z < hp.z && hp.z - q.z < 4.5); if (b) { cur = b; phase = 'toBench'; } }
  if (phase === 'toBench') {
    const dx = cur.x - hp.x, dz = cur.z + STAND_Z * 1.2 - hp.z;
    if (Math.hypot(dx, dz) < 0.3) { phase = 'swap'; phT = 0; heading0 = hero.group.rotation.y; pc.steer(null); }
    else { H.setSpeed(0.3); pc.steer(dx, dz); return; }
  }
  if (phase === 'swap') {
    pc.steer(null); phT += dt;
    const B = cur.owner, A = walker;
    if (phT < 0.6) { const k = 1 - ease(phT / 0.6); poseSitter(cur.dolls[B], k, SIT_Z + (STAND_Z - SIT_Z) * (1 - k), heading0 * (1 - k)); }
    else if (cur.dolls[B].visible) { setWalker(B); showSitter(cur, A); poseSitter(cur.dolls[A], 0, STAND_Z, heading0); }
    else { const k = ease(Math.min(1, (phT - 0.6) / 0.7)); poseSitter(cur.dolls[A], k, STAND_Z + (SIT_Z - STAND_Z) * k, heading0 * (1 - k)); if (phT > 1.4) { cur.done = true; phase = 'toPath'; } }
    return;
  }
  if (phase === 'toPath') {
    const tz = cur.z - 2.5, tx = pathX(tz);
    if (Math.hypot(tx - hp.x, tz - hp.z) < 0.7) phase = 'walk';
    else { H.setSpeed(Math.max(0.3, S.vis / 4 * 0.36)); pc.steer(tx - hp.x, tz - hp.z); return; }
  }
  if (S.vis > 0.05) {
    H.setSpeed(S.vis / 4 * 0.36);
    const tz = hp.z - 3.5; pc.steer(pathX(tz) - hp.x, tz - hp.z);
  } else pc.steer(null);
  const p = Math.min(1, S.steps / S.goal);
  if (Math.abs(p - lastP) > 0.002 && H.lights) {
    lastP = p;
    H.lights.key.color.copy(DAY.key).lerp(GOLD.key, p); H.lights.key.intensity = DAY.ki + (GOLD.ki - DAY.ki) * p;
    H.lights.fill.color.copy(DAY.fill).lerp(GOLD.fill, p);
    dir.copy(DAY.dir).lerp(GOLD.dir, p).normalize();
    H.lights.key.position.copy(H.lights.key.target.position).addScaledVector(dir, 100);
  }
});
addEventListener('walkstart', () => { phase = 'walk'; pc.restart(); for (const h of H.hinges) h._want = undefined; });
window.KcalWalkWho = () => NAMES[walker];
