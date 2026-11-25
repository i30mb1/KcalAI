// KcalAI: печать сцены «Молочный край» (вид 2, зона снега) — дом Масла: вместо елей — мороженое пяти вкусов, вместо
// кустов — творог с клубникой и сливки с черникой, ледяная глыба — брусок масла, поленница — стопка сырных голов,
// иглу — дом-головка сыра, избушка — сырный дом под кремовой крышей, снеговик — из шариков пломбира, забор — из
// сырных палочек, камни — сыры: чеддер, маасдам, дорблю и бри.
//
// Как и у Поля Зёрнышка: рисунок — внутри контура старого места и заполняет его (карточка вырезана по контуру).
// Предпросмотр — prints-preview.html?skin=2.
import { P, ell, poly, rrect, union, leaf, strip, rng } from './print-kit.js';

const WAFFLE = '#d39a52', WAFFLE_DARK = '#b07a3e', CREAM = '#f6eedb', CHEESE = '#f1c95a', RIND = '#cf8a34';

/** Мороженое на месте ели: рожок вместо ствола, ярусы крема вместо лап, сверху — завиток и посыпка своего вкуса. */
function iceCream(o) {
  return (b, H, outline) => {
    const R = rng(o.seed);
    const base = H * 0.84;
    b.base(outline, o.dark);
    // рожок — на месте ствола, вафельная клетка
    const cone = poly(26, base - 2, 76, base - 2, 51, H + 3);
    b.layer(cone, WAFFLE, { clip: outline, shade: 0.8 });
    const grid = P(); for (let i = -6; i < 8; i++) { grid.addPath(strip(20 + i * 8, base - 4, 40 + i * 8, H + 4, 1.3)); grid.addPath(strip(40 + i * 8, base - 4, 20 + i * 8, H + 4, 1.3)); }
    b.layer(grid, WAFFLE_DARK, { clip: cone, shade: 0, edge: 0 });
    // ярусы крема снизу вверх, всё уже; верхний — завиток
    const n = o.tiers, top = 6, step = (base - top) / (n + 0.6);
    for (let i = 0; i < n; i++) {
      const y = base - step * (i + 0.5), w = 49 * (1 - i / (n + 0.9)) + 3;
      const col = o.alt && i % 2 ? o.alt : o.cream;
      const tier = union(ell(50, y, w, step * 0.68), ell(50 - w * 0.55, y + step * 0.18, w * 0.5, step * 0.5), ell(50 + w * 0.55, y + step * 0.18, w * 0.5, step * 0.5));
      b.layer(tier, col, { clip: outline, shade: 1 });
      b.layer(ell(50 + w * 0.45, y + step * 0.15, w * 0.55, step * 0.5), o.dark, { clip: tier, shade: 0, edge: 0 });
      b.layer(ell(50 - w * 0.4, y - step * 0.25, w * 0.35, step * 0.2, -0.1), 'rgba(255,252,240,0.55)', { clip: tier, shade: 0, edge: 0 });
    }
    const curl = P(); curl.moveTo(50, top - 2); curl.bezierCurveTo(64, top + step * 0.5, 62, top + step * 1.2, 50, top + step * 1.2); curl.bezierCurveTo(38, top + step * 1.2, 40, top + step * 0.4, 50, top - 2);
    b.layer(curl, o.alt || o.cream, { clip: outline, shade: 0.8 });
    // посыпка своего вкуса
    if (o.top === 'sprinkles') {
      const cols = ['#d8665a', '#6f9cc4', '#e8c047', '#8cb860', '#c47ab0'];
      for (let i = 0; i < 26; i++) {
        const y = top + 8 + R() * (base - top - 14), w = 46 * (1 - (base - y) / (base - top)) + 2, x = 50 + (R() - 0.5) * w * 1.5;
        const a = R() * Math.PI; b.layer(strip(x - Math.cos(a) * 2.2, y - Math.sin(a) * 2.2, x + Math.cos(a) * 2.2, y + Math.sin(a) * 2.2, 1.6), cols[i % cols.length], { clip: outline, shade: 0.4, edge: 0 });
      }
    } else if (o.top === 'drizzle' || o.top === 'choc') {
      const col = o.top === 'choc' ? '#6b3f24' : '#fbf5ea';
      for (let i = 0; i < n; i++) {
        const y = base - step * (i + 0.5) - step * 0.45, w = 49 * (1 - i / (n + 0.9));
        const d = P(); d.moveTo(50 - w * 0.8, y); for (let x = -0.8; x <= 0.8; x += 0.2) d.lineTo(50 + x * w, y + (Math.round(x * 10) % 4 === 0 ? step * 0.5 : step * 0.12));
        d.lineTo(50 + w * 0.8, y - 1.6); d.lineTo(50 - w * 0.8, y - 1.6); d.closePath();
        b.layer(d, col, { clip: outline, shade: 0.5, edge: 0.3 });
      }
    } else if (o.top === 'nuts') {
      const pts = []; for (let i = 0; i < 22; i++) { const y = top + 6 + R() * (base - top - 10), w = 46 * (1 - (base - y) / (base - top)) + 2; pts.push(50 + (R() - 0.5) * w * 1.5, y); }
      b.dots(pts, 1.8, '#8a9a4e', outline, 0.5);
    }
    if (o.cherry) { b.layer(ell(52, top + 2, 6, 5.5), '#c23b3b', { shade: 0.8 }); b.layer(strip(53, top - 2, 60, top - 10, 1.4), '#5f7a33', { shade: 0, edge: 0 }); }
  };
}

/** Горка творога или сливок на месте куста: комочки, ягоды и листики мяты. */
function mound(o) {
  return (b, H, outline) => {
    const R = rng(o.seed);
    b.base(outline, o.cream);
    const lumps = P(), shade = P();
    for (let i = 0; i < 30; i++) { const x = 6 + R() * 88, y = H * (0.25 + R() * 0.7), r = 4 + R() * 5; (i % 3 ? lumps : shade).addPath(ell(x, y, r, r * 0.8)); }
    b.layer(lumps, o.light, { clip: outline, shade: 0.5, edge: 0.4 });
    b.layer(shade, o.dark, { clip: outline, shade: 0.3, edge: 0 });
    for (const [x, y] of o.berries) {
      if (o.kind === 'strawberry') {
        const s = P(); s.moveTo(x, y - 6); s.bezierCurveTo(x + 8, y - 7, x + 7, y + 4, x, y + 8); s.bezierCurveTo(x - 7, y + 4, x - 8, y - 7, x, y - 6);
        b.layer(s, '#d24a45', { clip: outline, shade: 1 });
        b.dots([x - 2.5, y - 2, x + 2.5, y - 1, x, y + 2, x - 2, y + 4, x + 2, y + 4.5], 0.7, '#f2d27a', s, 0);
        b.layer(union(leaf(x, y - 6, 6, 2, -2.6), leaf(x, y - 6, 6, 2, -0.5)), '#5f8f45', { clip: outline, shade: 0.4, edge: 0 });
      } else {
        b.layer(ell(x, y, 4.6, 4.4), '#4a5a8c', { clip: outline, shade: 1 });
        b.layer(ell(x - 1.4, y - 1.6, 1.4, 1.1), 'rgba(200,210,240,0.6)', { shade: 0, edge: 0 });
      }
    }
    b.layer(union(leaf(o.mint[0], o.mint[1], 11, 4, -2.2), leaf(o.mint[0], o.mint[1], 11, 4, -0.9)), '#6fa65a', { clip: outline, shade: 0.7 });
  };
}

/** Брусок масла в обёртке на месте ледяной глыбы — дом Масла рядом. */
function butterBlock(b, H, outline) {
  b.base(outline, '#e9c862');
  b.layer(poly(-4, H * 0.42, 40, H * 0.05, 104, H * 0.18, 104, H * 0.42, 60, H * 0.62), '#f6e08e', { clip: outline, shade: 0.4 });
  b.layer(poly(60, H * 0.62, 104, H * 0.42, 104, H + 2, 60, H + 2), '#d8b350', { clip: outline, shade: 0, edge: 0 });
  // обёртка: светлая бумага снизу с голубой полосой
  const paper = poly(-4, H * 0.72, 60, H * 0.86, 104, H * 0.7, 104, H + 2, -4, H + 2);
  b.layer(paper, '#eef1f2', { clip: outline, shade: 0.9 });
  b.layer(poly(-4, H * 0.8, 60, H * 0.94, 104, H * 0.78, 104, H * 0.86, 60, H + 1, -4, H * 0.88), '#7aa6c9', { clip: paper, shade: 0, edge: 0 });
  // завиток масла сверху
  const curl = union(ell(40, H * 0.3, 10, 4.5, -0.2), ell(46, H * 0.26, 7, 3.2, -0.2));
  b.layer(curl, '#fbeaa8', { clip: outline, shade: 0.8 });
  b.layer(strip(34, H * 0.31, 48, H * 0.27, 1.2), '#e2c26a', { clip: curl, shade: 0, edge: 0 });
}

/** Сырная голова срезом: корка кольцом, мякоть, пара дырок; [cut] — вырезан клин. */
function wheelFace(b, x, y, r, clip, cut) {
  b.layer(ell(x, y, r, r), RIND, { clip, shade: 0.9 });
  const face = ell(x, y, r * 0.8, r * 0.8);
  b.layer(face, CHEESE, { clip, shade: 0, edge: 0.4 });
  b.layer(ell(x + r * 0.25, y + r * 0.3, r * 0.22, r * 0.18), '#d9a83e', { clip: face, shade: 0.4, edge: 0 });
  b.layer(ell(x - r * 0.3, y - r * 0.15, r * 0.14, r * 0.12), '#d9a83e', { clip: face, shade: 0.4, edge: 0 });
  if (cut) b.layer(poly(x, y, x + r, y - r * 0.5, x + r, y + r * 0.2), '#f7dc86', { clip: face, shade: 0.6 });
}

/** Стопка сырных голов на месте поленницы. */
function cheesePile(b, H, outline) {
  b.base(outline, '#b9752c');
  const r = 9.6;
  const rows = [[5, H - r], [4, H - r * 2.7], [3, H - r * 4.4], [2, H - r * 6.1]];
  rows.forEach(([n, y], k) => { for (let i = 0; i < n; i++) wheelFace(b, 50 + (i - (n - 1) / 2) * r * 2.02, y, r, outline, (i + k) % 3 === 1); });
}

/** Дом — головка сыра на месте иглу: купол с коркой, дырки, дверь аркой и круглое окошко. */
function cheeseDome(b, H, outline) {
  b.base(outline, RIND);
  const dome = ell(50, H + 2, 46, H - 2);
  b.layer(dome, CHEESE, { clip: outline, shade: 0.7 });
  b.layer(ell(66, H * 0.8, 26, H * 0.55), '#e2b54c', { clip: dome, shade: 0, edge: 0 });
  for (const [x, y, rr] of [[22, H * 0.55, 4], [34, H * 0.3, 3], [70, H * 0.35, 4.6], [80, H * 0.62, 3.2], [58, H * 0.2, 2.6]]) {
    const hole = ell(x, y, rr, rr * 0.85);
    b.layer(hole, '#c99630', { clip: dome, shade: 0, edge: 0 });
    b.layer(ell(x + rr * 0.25, y + rr * 0.25, rr * 0.75, rr * 0.6), '#dba843', { clip: hole, shade: 0.8, edge: 0 });
  }
  const arch = P(); arch.moveTo(38, H + 1); arch.lineTo(38, H * 0.62); arch.arc(50, H * 0.62, 12, Math.PI, 0); arch.lineTo(62, H + 1); arch.closePath();
  b.layer(arch, '#f6e4b0', { clip: outline, shade: 0.8 });
  const door = P(); door.moveTo(42, H + 1); door.lineTo(42, H * 0.66); door.arc(50, H * 0.66, 8, Math.PI, 0); door.lineTo(58, H + 1); door.closePath();
  b.layer(door, '#7d5230', { clip: outline, shade: 0.6 });
  b.layer(ell(50, H * 0.25, 5, 5), '#ffd36b', { clip: outline, shade: 0.5 });
}

/** Окно сырного дома: рама, тёплый свет, переплёт. */
function windowAt(b, x, y, w, h, clip) {
  b.layer(rrect(x - 2.5, y - 2.5, w + 5, h + 5, 1.8), '#6e4526', { clip, shade: 0.9 });
  const pane = rrect(x, y, w, h, 1);
  b.layer(pane, '#f39a3a', { clip, shade: 0, edge: 0 });
  b.layer(ell(x + w * 0.4, y + h * 0.45, w * 0.45, h * 0.4), '#ffd27a', { clip: pane, shade: 0, edge: 0 });
  b.layer(union(rrect(x + w / 2 - 1.1, y, 2.2, h, 0.5), rrect(x, y + h / 2 - 1.1, w, 2.2, 0.5)), '#6e4526', { clip: pane, shade: 0.4, edge: 0 });
}
/** Сырная стена: мякоть, корка по краям, дырки. */
function cheeseWall(b, H, outline, seed) {
  const R = rng(seed);
  b.base(outline, CHEESE);
  b.layer(union(rrect(-2, -2, 8, H + 4, 0), rrect(94, -2, 8, H + 4, 0)), RIND, { clip: outline, shade: 0.5 });
  for (let i = 0; i < 9; i++) {
    const x = 10 + R() * 80, y = H * (0.1 + R() * 0.8), rr = 2 + R() * 3;
    const hole = ell(x, y, rr, rr * 0.85);
    b.layer(hole, '#c99630', { clip: outline, shade: 0, edge: 0 });
    b.layer(ell(x + rr * 0.25, y + rr * 0.25, rr * 0.75, rr * 0.6), '#dba843', { clip: hole, shade: 0.8, edge: 0 });
  }
}
function cheeseFront(b, H, outline) {
  cheeseWall(b, H, outline, 41);
  windowAt(b, 13, H * 0.48, 14, H * 0.22, outline);
  windowAt(b, 72, H * 0.48, 14, H * 0.22, outline);
  b.layer(rrect(37, H * 0.44, 26, H * 0.58, 6), '#7d5230', { clip: outline, shade: 0.8 });
  b.layer(rrect(40, H * 0.47, 20, H * 0.55, 5), '#4f8a7e', { clip: outline, shade: 0, edge: 0.3 });
  b.layer(ell(56, H * 0.75, 1.4, 1.4), '#e8c47a', { shade: 0, edge: 0 });
  b.layer(ell(50, H * 0.22, 6, 6), '#6e4526', { clip: outline, shade: 0.6 });
  b.layer(ell(50, H * 0.22, 4.4, 4.4), '#f39a3a', { clip: outline, shade: 0, edge: 0 });
}
function cheeseSide(b, H, outline) {
  cheeseWall(b, H, outline, 53);
  windowAt(b, 18, H * 0.27, 22, H * 0.34, outline);
  windowAt(b, 60, H * 0.27, 22, H * 0.34, outline);
}

/** Крыша под кремом: доски корки снизу, сверху — крем с каплями. */
function creamRoof(o) {
  return (b, H, outline) => {
    b.base(outline, '#a85f2c');
    b.layer(rrect(-2, H * 0.62, 104, H * 0.4, 0), '#c27a3a', { clip: outline, shade: 0, edge: 0.3 });
    const cream = P(); cream.moveTo(-2, -2); cream.lineTo(102, -2); cream.lineTo(102, H * 0.5);
    for (let x = 102; x >= -2; x -= 8.5) { cream.quadraticCurveTo(x - 2, H * 0.78, x - 4.25, H * 0.56); cream.quadraticCurveTo(x - 6.5, H * 0.46, x - 8.5, H * 0.5); }
    cream.closePath();
    b.layer(cream, o.cream, { clip: outline, shade: 1 });
    b.layer(rrect(-2, -2, 104, H * 0.2, 0), o.light, { clip: cream, shade: 0, edge: 0 });
    if (o.berries) b.dots([14, H * 0.24, 38, H * 0.2, 62, H * 0.25, 86, H * 0.21], 3.2, '#c23b3b', outline, 0.7);
  };
}

/** Снеговик из шариков пломбира: вафельные руки и рожок-шляпа, клубничный шарф. */
function scoopMan(b, H, outline) {
  b.base(outline, WAFFLE);
  const low = ell(49, H - 30, 36, 30), up = ell(50, H * 0.42, 23, 21);
  b.layer(low, CREAM, { clip: outline, shade: 1 });
  b.layer(ell(62, H - 24, 22, 22), '#e6dcc4', { clip: low, shade: 0, edge: 0 });
  b.layer(up, '#f2b7b2', { clip: outline, shade: 1 });
  b.layer(ell(58, H * 0.46, 13, 14), '#df9f9a', { clip: up, shade: 0, edge: 0 });
  b.layer(rrect(30, H * 0.56, 42, 8, 4), '#d8665a', { clip: outline, shade: 0.8 });
  b.layer(rrect(56, H * 0.56, 9, 22, 3), '#c95a4f', { clip: outline, shade: 0.6 });
  b.dots([43, H * 0.38, 57, H * 0.38], 2.2, '#5a3420', outline, 0.3);
  b.dots([44, H * 0.5, 48, H * 0.52, 52, H * 0.52, 56, H * 0.5, 49, H - 44, 49, H - 32, 49, H - 20], 1.8, '#5a3420', outline, 0.3);
  b.layer(ell(50, H * 0.45, 3, 2.4), '#d24a45', { shade: 0.4, edge: 0 });
  const hat = poly(37, H * 0.24, 63, H * 0.24, 50, -4);
  b.layer(hat, WAFFLE, { clip: outline, shade: 0.8 });
  const grid = P(); for (let i = -3; i < 5; i++) { grid.addPath(strip(34 + i * 7, H * 0.26, 46 + i * 7, -4, 1.1)); grid.addPath(strip(46 + i * 7, H * 0.26, 34 + i * 7, -4, 1.1)); }
  b.layer(grid, WAFFLE_DARK, { clip: hat, shade: 0, edge: 0 });
}

/** Забор из сырных палочек с кремовыми шапочками. */
function cheeseFence(b, H, outline) {
  b.base(outline, '#ecd38a');
  b.layer(union(rrect(-2, H * 0.32, 104, H * 0.13, 2), rrect(-2, H * 0.62, 104, H * 0.13, 2)), '#e2bf62', { clip: outline, shade: 0.7 });
  const strings = P(); for (let x = 2; x < 100; x += 3.4) strings.addPath(strip(x, -2, x + 0.6, H + 2, 0.8));
  b.layer(strings, 'rgba(255,248,226,0.5)', { clip: outline, shade: 0, edge: 0 });
  for (const x of [8, 50, 92]) b.layer(ell(x, H * 0.08, 9, 6), '#fbf5e6', { clip: outline, shade: 0.8 });
}

/** Сыр на месте камня: чеддер, маасдам, дорблю, бри — по контуру камня. */
function cheese(o) {
  return (b, H, outline) => {
    const R = rng(o.seed);
    b.base(outline, o.base);
    b.layer(poly(-4, -4, 104, -4, 104, H * 0.35, -4, H * 0.55), o.light, { clip: outline, shade: 0.4 });
    b.layer(poly(70, -4, 104, -4, 104, H + 4, 80, H + 4), o.rind, { clip: outline, shade: 0, edge: 0.3 });
    if (o.holes) for (let i = 0; i < 6; i++) {
      const x = 12 + R() * 62, y = H * (0.3 + R() * 0.55), rr = 3 + R() * 5;
      const hole = ell(x, y, rr, rr * 0.85);
      b.layer(hole, '#c99630', { clip: outline, shade: 0, edge: 0 });
      b.layer(ell(x + rr * 0.25, y + rr * 0.25, rr * 0.75, rr * 0.6), '#dcaa42', { clip: hole, shade: 0.8, edge: 0 });
    }
    if (o.veins) {
      const v = P();
      for (let i = 0; i < 7; i++) { let x = 10 + R() * 60, y = H * (0.3 + R() * 0.6); for (let k = 0; k < 3; k++) { const nx = x + 4 + R() * 6, ny = y + (R() - 0.5) * 8; v.addPath(strip(x, y, nx, ny, 2, 1.4)); x = nx; y = ny; } }
      b.layer(v, '#6f8fa0', { clip: outline, shade: 0.3, edge: 0 });
    }
    if (o.cut) b.layer(poly(20, H + 4, 46, H * 0.35, 72, H + 4), o.cut, { clip: outline, shade: 0.8 });
  };
}

/** Место атласа -> рисунок. */
export const DAIRY = {
  snowpine_0: iceCream({ seed: 3, tiers: 4, cream: '#f5ecd2', dark: '#e2d3b0', top: 'sprinkles' }),
  snowpine_1: iceCream({ seed: 5, tiers: 6, cream: '#f2b4b0', dark: '#dc9690', top: 'drizzle' }),
  snowpine_2: iceCream({ seed: 7, tiers: 3, cream: '#cddc9e', dark: '#afc07e', top: 'nuts' }),
  snowpine_3: iceCream({ seed: 9, tiers: 6, cream: '#9c6b48', dark: '#7f5338', top: 'drizzle' }),
  snowpine_4: iceCream({ seed: 11, tiers: 4, cream: '#f5ecd2', alt: '#f2b4b0', dark: '#e2c8b8', top: 'choc', cherry: true }),
  snowbush_0: mound({ seed: 13, kind: 'strawberry', cream: '#f3ecdc', light: '#fbf7ee', dark: '#e3d8c0', berries: [[28, 30], [52, 22], [72, 34]], mint: [62, 18] }),
  snowbush_1: mound({ seed: 17, kind: 'blueberry', cream: '#f6f0e2', light: '#fffcf4', dark: '#e6dcc8', berries: [[22, 26], [30, 22], [48, 18], [56, 22], [74, 26], [80, 30]], mint: [40, 16] }),
  ice_rock_0: butterBlock,
  woodpile_0: cheesePile,
  igloo_0: cheeseDome,
  cabin_front_0: cheeseFront,
  cabin_side_0: cheeseSide,
  cabin_roof_0: creamRoof({ cream: '#fbf3dc', light: '#fffaee' }),
  cabin_roof_1: creamRoof({ cream: '#f4c0bb', light: '#f9d6d2', berries: true }),
  snowman_0: scoopMan,
  snow_fence_0: cheeseFence,
  mountains_rocks_2: cheese({ seed: 21, base: '#e3a03e', light: '#f0b856', rind: '#c47e2c' }),
  mountains_rocks_3: cheese({ seed: 23, base: '#f0cd5c', light: '#f7dc7c', rind: '#d9a843', holes: true }),
  mountains_rocks_4: cheese({ seed: 25, base: '#ece6d6', light: '#f6f2e6', rind: '#d8d0bc', veins: true }),
  mountains_rocks_5: cheese({ seed: 27, base: '#f4efe2', light: '#fbf8ef', rind: '#e2dac6', cut: '#f2dc9e' }),
};
