// KcalAI: печать сцены «Поле Зёрнышка» (вид 1, зона полей) — места атласа по теме приложения: вместо лиственных
// деревьев — подсолнухи и хлебные деревья, вместо елей — колосья, вместо кустов — горох, гречиха и капуста, вместо
// травы — пучки колосков, вместо камней — огромные зёрна, тележка с хлебом, мешки муки, ящики с батонами.
//
// Карточки мира вырезаны по контуру старого рисунка (public/textures/art/atlas.json, `outline`), а шейдер режет
// карточку ещё и по прозрачности печати. Поэтому новая печать рисуется внутри того же контура и заполняет его:
// тень и край карточки — по контуру. Предпросмотр — prints-preview.html.
import { P, ell, poly, rrect, union, leaf, heart, strip, ring, rng } from './print-kit.js';


/** Подсолнух ростом с дерево: голова-диск с семечками по спирали, два ряда лепестков, стебель с листьями. */
function sunflower(o) {
  return (b, H, outline) => {
    const cx = 50, cy = 43;
    // подложка: розетка тёмных листьев за головой — карточка вырезана по кроне, край её не должен проступать
    b.base(outline, '#5f7d36');
    const ros = P(); for (let i = 0; i < 9; i++) { const a = (i / 9) * Math.PI * 2 + 0.2; ros.addPath(leaf(cx, cy, 58, 17, a)); }
    b.layer(ros, '#6f8f3e', { clip: outline, shade: 0.6, edge: 0.3 });
    b.base(strip(50, H, 50, cy + 10, 10, 7), '#6f8f3e');
    b.layer(strip(52.5, H, 51.5, cy + 10, 5, 3.5), '#5d7a33', { shade: 0, edge: 0 });
    for (const [x, y, len, w, rot, col] of [[49, H - 14, 40, 13, -2.75, '#7fa04a'], [51, H - 26, 40, 13, -0.38, '#8aab52'], [49, H - 34, 30, 10, -2.4, '#86a64d']]) {
      b.layer(leaf(x, y, len, w, rot), col, { shade: 0.8 });
      b.layer(strip(x, y, x + Math.cos(rot) * len * 0.85, y + Math.sin(rot) * len * 0.85, 1.6), '#62803a', { clip: leaf(x, y, len, w, rot), shade: 0, edge: 0 });
    }
    const petals = (n, r0, len, w, col, off, shade) => {
      const p = P();
      for (let i = 0; i < n; i++) { const a = off + (i / n) * Math.PI * 2; p.addPath(leaf(cx + Math.cos(a) * r0, cy + Math.sin(a) * r0 * o.squash, len, w, Math.atan2(Math.sin(a) * o.squash, Math.cos(a)))); }
      b.layer(p, col, { shade });
    };
    petals(18, 20, 27, 8.5, o.back, Math.PI / 18, 0.6);
    petals(16, 19, 23, 8, o.petal, 0, 1);
    petals(16, 19, 13, 3.4, o.petalLight, 0, 0);
    const disc = ell(cx, cy, 22, 22 * o.squash);
    b.layer(disc, '#6b4423', { shade: 1.1 });
    b.layer(ell(cx - 1, cy - 1, 16.5, 16.5 * o.squash), '#7d522c', { clip: disc, shade: 0.6 });
    const light = [], dark = [];
    for (let i = 4; i < 110; i++) { const r = 2.05 * Math.sqrt(i), a = i * 2.39996; (i % 2 ? light : dark).push(cx + Math.cos(a) * r, cy + Math.sin(a) * r * o.squash); }
    b.dots(light, 1.15, '#b0844c', disc, 0.4);
    b.dots(dark, 1.0, '#4d2f18', disc, 0);
    b.layer(ell(cx - 7, cy - 8, 6, 3.5, -0.6), 'rgba(214,170,110,0.45)', { clip: disc, shade: 0, edge: 0 });
  };
}

/** Хлебное дерево: крона слоями, на ней бублики, круассаны и булочки. */
function breadTree(o) {
  return (b, H, outline) => {
    const R = rng(o.seed);
    b.base(outline, o.crown);
    const crownY = H * 0.42;
    const bumps = (n, r, rr, col, dy, shade) => { const p = P(); for (let i = 0; i < n; i++) { const a = Math.PI + (i / (n - 1)) * Math.PI; p.addPath(ell(50 + Math.cos(a) * r, crownY + dy + Math.sin(a) * r * 0.85, rr, rr * 0.9)); } p.addPath(ell(50, crownY + dy, r, r * 0.7)); b.layer(p, col, { clip: outline, shade }); };
    bumps(7, 30, 14, o.crownMid, 4, 0.8);
    bumps(5, 18, 12, o.crownLight, -2, 0.7);
    b.layer(ell(66, crownY + 24, 22, 12, -0.3), o.crownDark, { clip: outline, shade: 0.5, edge: 0 });
    // ствол с ветвями — поверх нижней части кроны
    const trunk = union(poly(39, H, 62, H, 55, H * 0.6, 46, H * 0.6), strip(48, H * 0.66, 30, crownY + 8, 6, 3.5), strip(53, H * 0.64, 70, crownY + 6, 6, 3.5), strip(50, H * 0.62, 50, crownY - 4, 6, 3));
    b.layer(trunk, '#8a5428', { clip: outline, shade: 0.9 });
    b.layer(poly(53, H, 62, H, 55, H * 0.6, 51, H * 0.6), '#6e4120', { clip: trunk, shade: 0, edge: 0 });
    const leaves = P(), leavesDark = P();
    for (let i = 0; i < 20; i++) {
      const a = R() * Math.PI * 2, r = 8 + R() * 34; const x = 50 + Math.cos(a) * r, y = crownY + Math.sin(a) * r * 0.8;
      (i % 3 ? leaves : leavesDark).addPath(leaf(x, y, 8 + R() * 3, 3, R() * Math.PI * 2));
    }
    b.layer(leaves, o.leaf, { clip: outline, shade: 0.5, edge: 0.3 });
    b.layer(leavesDark, o.crownDark, { clip: outline, shade: 0.4, edge: 0 });
    for (let [kind, x, y, s] of o.fruits) {
      if (kind === 'bagel') {
        s *= 1.3; const r = ring(x, y, 7.5 * s, 2.8 * s);
        b.layer(r, '#c98a44', { rule: 'evenodd', shade: 1 });
        b.layer(ell(x - 1, y - 2.2 * s, 6.3 * s, 3.8 * s), '#e2ad62', { clip: r, shade: 0, edge: 0 });
        b.dots([x - 4 * s, y - 3 * s, x + 1 * s, y - 5 * s, x + 4.5 * s, y - 2 * s, x - 5 * s, y + 1.5 * s], 0.9, '#f6e6c4', r, 0);
      } else if (kind === 'croissant') {
        s *= 1.3; const seg = P(); for (let i = -2; i <= 2; i++) { const a = -Math.PI / 2 + i * 0.42; seg.addPath(ell(x + Math.cos(a) * 6 * s, y + 4 * s + Math.sin(a) * 6 * s, (4.6 - Math.abs(i) * 0.9) * s, (3.4 - Math.abs(i) * 0.5) * s, a + Math.PI / 2)); }
        b.layer(seg, '#d38f3e', { shade: 1 });
        for (let i = -1; i <= 1; i++) { const a = -Math.PI / 2 + i * 0.42 + 0.21; b.layer(strip(x + Math.cos(a) * 3 * s, y + 4 * s + Math.sin(a) * 3 * s, x + Math.cos(a) * 9.5 * s, y + 4 * s + Math.sin(a) * 9.5 * s, 1.3 * s), '#a8682a', { clip: seg, shade: 0, edge: 0 }); }
        b.layer(ell(x - 2 * s, y - 2.5 * s, 4 * s, 1.6 * s, -0.2), 'rgba(242,198,120,0.7)', { clip: seg, shade: 0, edge: 0 });
      } else {
        s *= 1.3; const bun = ell(x, y, 6.5 * s, 5.5 * s);
        b.layer(bun, '#c27a38', { shade: 1 });
        b.layer(ell(x - 1.2 * s, y - 1.5 * s, 4.6 * s, 3.2 * s), '#d99a52', { clip: bun, shade: 0, edge: 0 });
        b.layer(union(strip(x - 3 * s, y - 2 * s, x + 3 * s, y + 1 * s, 1.4 * s), strip(x - 3 * s, y + 1 * s, x + 3 * s, y - 2 * s, 1.4 * s)), '#f0d39c', { clip: bun, shade: 0.3, edge: 0 });
      }
    }
  };
}

/** Колос ростом с ель: зёрна парами вдоль оси, ости вверх, стебель с длинными листьями. */
function wheatEar(o) {
  return (b, H, outline) => {
    const top = 10, bot = H * 0.74, rows = 9;
    b.base(outline, o.leafDark);
    const fan = P(); for (let i = 0; i < 9; i++) fan.addPath(leaf(50, H + 4, H * 0.95, 9, -Math.PI / 2 + (i - 4) * 0.2));
    b.layer(fan, o.leaf, { clip: outline, shade: 0.6, edge: 0.3 });
    for (const [x, y, len, w, rot, col] of [[50, H, 58, 8, -2.35, o.leafDark], [50, H - 2, 60, 8, -0.8, o.leaf], [50, H - 10, 44, 6, -2.0, o.leaf]]) b.layer(leaf(x, y, len, w, rot), col, { shade: 0.7 });
    b.layer(strip(50, H, 50, top + 8, 4.5, 2.5), o.stalk, { shade: 0.6 });
    const awns = P(), grains = P(), light = P();
    for (let i = 0; i < rows; i++) {
      const t = i / (rows - 1); const y = top + 10 + t * (bot - top - 10);
      const w = 5 + 6 * Math.pow(Math.sin(Math.PI * (0.25 + t * 0.6)), 0.8);
      for (const side of [-1, 1]) {
        const gx = 50 + side * w * 0.72, rot = side * 0.55;
        grains.addPath(ell(gx, y, w * 0.55, w, rot));
        light.addPath(ell(gx - w * 0.15, y - w * 0.25, w * 0.25, w * 0.55, rot));
        const tipX = gx + Math.sin(rot) * w, tipY = y - Math.cos(rot) * w;
        awns.addPath(strip(tipX, tipY, tipX + side * (6 + 10 * t), tipY - 22 - 6 * t, 1.6, 0.6));
      }
    }
    grains.addPath(ell(50, top + 4, 4, 7));
    awns.addPath(strip(50, top - 2, 50, -10, 1.6, 0.6));
    b.layer(awns, o.awn, { shade: 0.4, edge: 0 });
    b.layer(grains, o.grain, { shade: 1 });
    b.layer(light, o.grainLight, { clip: grains, shade: 0, edge: 0 });
  };
}

/** Куст гороха: купол слоями, стручки с горошинами, белые цветочки. */
function peas(b, H, outline) {
  b.base(outline, '#6f8f3f');
  const row = (n, y, r, col, shade) => { const p = P(); for (let i = 0; i < n; i++) p.addPath(ell(8 + (84 * i) / (n - 1), y + Math.sin(i * 1.7) * 2, r, r * 0.85)); b.layer(p, col, { clip: outline, shade }); };
  row(7, H * 0.42, 10, '#86a64b', 0.7);
  row(6, H * 0.7, 9, '#98b55a', 0.8);
  const pods = [[20, H * 0.48, -1.2], [36, H * 0.62, 1.35], [55, H * 0.42, -1.4], [70, H * 0.64, 1.25], [84, H * 0.5, -1.25], [46, H * 0.82, 0.2]];
  for (const [x, y, rot] of pods) {
    const pod = leaf(x - Math.cos(rot) * 8, y - Math.sin(rot) * 8, 17, 4, rot);
    b.layer(pod, '#b9cf6e', { clip: outline, shade: 1 });
    const pts = []; for (let i = 1; i <= 3; i++) pts.push(x - Math.cos(rot) * 8 + Math.cos(rot) * i * 4.2, y - Math.sin(rot) * 8 + Math.sin(rot) * i * 4.2);
    b.dots(pts, 1.7, '#d3e28e', pod, 0.5);
  }
  const fl = []; for (const [x, y] of [[12, H * 0.3], [30, H * 0.36], [62, H * 0.28], [78, H * 0.34], [90, H * 0.66], [26, H * 0.8], [64, H * 0.84]]) fl.push(x, y, x + 2.4, y + 1.2, x - 0.6, y + 2.4);
  b.dots(fl, 1.7, '#f4ecdc', outline, 0.6);
}

/** Гречиха в цвету: красные стебли, сердцевидные листья, бело-розовые кисти. */
function buckwheat(b, H, outline) {
  const R = rng(37);
  b.base(outline, '#64803a');
  const stems = P(); for (let i = 0; i < 9; i++) { const x = 10 + i * 10; stems.addPath(strip(50 + (x - 50) * 0.3, H, x, H * 0.25 + R() * 6, 2.4, 1.6)); }
  b.layer(stems, '#9c4c3e', { clip: outline, shade: 0.6, edge: 0 });
  const lv = P(), lv2 = P();
  for (let i = 0; i < 14; i++) { const x = 8 + R() * 84, y = H * (0.35 + R() * 0.55); (i % 2 ? lv : lv2).addPath(heart(x, y, 9 + R() * 3, -Math.PI / 2 + (R() - 0.5) * 1.6)); }
  b.layer(lv, '#7e9c46', { clip: outline, shade: 0.7 });
  b.layer(lv2, '#91ae55', { clip: outline, shade: 0.7 });
  for (let i = 0; i < 10; i++) {
    const x = 9 + i * 9.2 + R() * 3, y = H * (0.2 + R() * 0.3); const pts = [];
    for (let j = 0; j < 7; j++) pts.push(x + (R() - 0.5) * 7, y + (R() - 0.5) * 6);
    b.dots(pts, 2.1, '#f6e8e0', outline, 0.7);
    b.dots(pts.slice(0, 6), 0.8, '#e2a29a', outline, 0);
  }
}

/** Капуста: три кочана в обёртке из наружных листьев с прожилками. */
function cabbage(b, H, outline) {
  b.base(outline, '#5f8c62');
  const outer = [[-2.7, 30, 26], [-0.45, 70, 26], [-1.9, 18, 22], [-1.2, 82, 22]];
  for (const [rot, x, len] of outer) { const l = leaf(x, H, len, 10, rot); b.layer(l, '#6f9e6c', { clip: outline, shade: 0.7 }); b.layer(strip(x, H, x + Math.cos(rot) * len * 0.8, H + Math.sin(rot) * len * 0.8, 1.6), '#a9cc9a', { clip: l, shade: 0, edge: 0 }); }
  for (const [x, r] of [[24, 14], [76, 14], [50, 19]]) {
    const y = H - r * 0.95; const head = ell(x, y, r, r * 0.92);
    b.layer(head, '#9cc486', { clip: outline, shade: 1 });
    b.layer(ell(x + r * 0.35, y + r * 0.2, r * 0.6, r * 0.7), '#86b373', { clip: head, shade: 0, edge: 0 });
    b.layer(ell(x - r * 0.25, y - r * 0.3, r * 0.5, r * 0.4), '#c3dfa9', { clip: head, shade: 0.4, edge: 0.3 });
    for (const a of [-2.3, -1.6, -0.9]) b.layer(strip(x, y + r * 0.5, x + Math.cos(a) * r * 0.9, y + r * 0.5 + Math.sin(a) * r * 0.9, 1.4, 0.8), '#d7ebc3', { clip: head, shade: 0, edge: 0 });
    const wrap = union(leaf(x - r * 0.95, y + r * 0.8, r * 1.1, r * 0.45, -1.15), leaf(x + r * 0.95, y + r * 0.8, r * 1.1, r * 0.45, -1.99));
    b.layer(wrap, '#7aa874', { clip: outline, shade: 0.9 });
  }
}

/** Пучок колосков: веер листьев и несколько колосков на стеблях. */
function tuft(o) {
  return (b, H, outline) => {
    const R = rng(o.seed);
    b.base(outline, o.blades[0]);
    const blades = [P(), P(), P()];
    for (let i = 0; i < 24; i++) { const x = 10 + R() * 80; const rot = -Math.PI / 2 + (x - 50) / 50 * 1.1 + (R() - 0.5) * 0.4; blades[i % 3].addPath(leaf(x, H + 2, H * (0.75 + R() * 0.4), 5, rot)); }
    b.layer(blades[0], o.blades[0], { shade: 0.6 });
    b.layer(blades[1], o.blades[1], { shade: 0.6 });
    for (const [x, lean] of o.ears) {
      const tx = x + lean * 10, ty = 5;
      b.layer(strip(x, H, tx, ty + 9, 1.8, 1.3), o.stalk, { shade: 0.5, edge: 0 });
      const g = P(); for (let i = 0; i < 5; i++) { const t = i / 4; const yy = ty + 2 + t * 8, xx = tx + (x - tx) * t * 0.1; g.addPath(ell(xx - 2, yy, 1.7, 2.8, -0.5)); g.addPath(ell(xx + 2, yy, 1.7, 2.8, 0.5)); }
      b.layer(g, o.grain, { shade: 0.8, edge: 0.3 });
    }
    b.layer(blades[2], o.blades[2], { shade: 0.6 });
  };
}

/** Огромное зерно на месте камня: по контуру, с гранями, бороздкой или глазком. */
function grain(o) {
  return (b, H, outline) => {
    b.base(outline, o.base);
    if (o.facets) {
      b.layer(poly(o.top, -2, -5, H + 2, o.top * 0.7, H + 2), o.light, { clip: outline, shade: 0.4 });
      b.layer(poly(o.top, -2, 105, H + 2, o.top + 22, H + 2), o.dark, { clip: outline, shade: 0, edge: 0 });
      b.layer(strip(o.top - 6, H * 0.3, o.top * 0.55, H * 0.75, 3, 1.5), o.shine, { clip: outline, shade: 0, edge: 0 });
    } else {
      b.layer(ell(42, H * 0.35, 40, H * 0.32, -0.08), o.light, { clip: outline, shade: 0.3 });
      b.layer(ell(78, H * 0.9, 36, H * 0.45, -0.2), o.dark, { clip: outline, shade: 0, edge: 0 });
      b.layer(ell(30, H * 0.3, 14, H * 0.08, -0.15), o.shine, { clip: outline, shade: 0, edge: 0 });
    }
    if (o.crease) b.layer(strip(o.top + 2, 4, o.top + 8, H, 3.4, 2), o.crease, { clip: outline, shade: 0.6, edge: 0 });
    if (o.eye) { const e = ell(50, H * 0.42, 9, 4.5, -0.05); b.layer(e, o.eye, { clip: outline, shade: 0.5 }); b.layer(strip(43, H * 0.42, 57, H * 0.42, 1.2), o.dark, { clip: e, shade: 0, edge: 0 }); }
    if (o.speck) b.dots([22, H * 0.55, 34, H * 0.7, 61, H * 0.6, 72, H * 0.4, 84, H * 0.72, 48, H * 0.8], 1.1, o.speck, outline, 0);
  };
}

/** Тележка с хлебом: каравай, батон и багет в деревянном коробе на двух колёсах. */
function breadCart(b, H, outline) {
  // хлеб — над коробом, горкой во весь контур груза
  b.base(outline, '#7a4222');
  b.layer(rrect(-2, -2, 104, 40, 0), '#b8763a', { clip: outline, shade: 0, edge: 0.4 });
  b.dots([12, 20, 22, 12, 36, 8, 62, 8, 80, 14, 90, 24, 16, 30, 86, 30], 3.2, '#cf9150', outline, 0.6);
  const loaf = ell(52, 24, 21, 15);
  b.layer(strip(68, 38, 86, 2, 8, 7), '#d9a04f', { clip: outline, shade: 0.8 });
  b.layer(strip(78, 22, 82, 14, 4, 4), '#efc982', { clip: strip(68, 38, 86, 2, 8, 7), shade: 0, edge: 0 });
  b.layer(loaf, '#c47f3b', { clip: outline, shade: 1 });
  b.layer(ell(48, 19, 15, 8, -0.1), '#dda25c', { clip: loaf, shade: 0, edge: 0 });
  for (const x of [40, 52, 64]) b.layer(ell(x, 22, 2.4, 8, 0.5), '#f1d39a', { clip: loaf, shade: 0.4 });
  const baton = ell(24, 30, 18, 8, -0.28);
  b.layer(baton, '#d39545', { clip: outline, shade: 1 });
  for (const x of [14, 22, 30]) b.layer(ell(x, 30 - (x - 24) * 0.28, 1.6, 5, 0.7), '#f1d39a', { clip: baton, shade: 0.4 });
  // короб
  const box = rrect(5, 33, 90, 21, 2.5);
  b.layer(box, '#9a5a2e', { clip: outline, shade: 1 });
  b.layer(rrect(5, 33, 90, 5, 2), '#b8763f', { clip: box, shade: 0.4 });
  for (const y of [41, 47]) b.layer(rrect(5, y, 90, 1.6, 0.8), '#7d4523', { clip: box, shade: 0, edge: 0 });
  const ear = P(); for (let i = 0; i < 4; i++) { ear.addPath(ell(46 - 2.2, 39 + i * 3, 1.6, 2.6, -0.5)); ear.addPath(ell(46 + 2.2, 39 + i * 3, 1.6, 2.6, 0.5)); }
  b.layer(union(ear, strip(46, 52, 46, 37, 1.4)), '#e8c47a', { clip: box, shade: 0.4, edge: 0 });
  // колёса
  for (const x of [25, 75]) {
    const y = H - 13, wheel = ring(x, y, 13, 10);
    b.layer(ell(x, y, 10, 10), '#c8873e', { clip: outline, shade: 0.6 });
    const spokes = P(); for (let i = 0; i < 6; i++) { const a = (i / 6) * Math.PI; spokes.addPath(strip(x - Math.cos(a) * 10, y - Math.sin(a) * 10, x + Math.cos(a) * 10, y + Math.sin(a) * 10, 2.4)); }
    b.layer(spokes, '#8a4b24', { clip: ell(x, y, 10, 10), shade: 0.4, edge: 0 });
    b.layer(wheel, '#7a4222', { clip: outline, rule: 'evenodd', shade: 0.9 });
    b.layer(ell(x, y, 3.2, 3.2), '#5e3219', { shade: 0.5 });
  }
}

/** Мешки муки: большой сзади, два спереди, завязаны бечёвкой, на боку — колосок. */
function flourSacks(b, H, outline) {
  const sack = (x, y0, w, h, col, side) => {
    const body = P(); const l = x - w / 2, r = x + w / 2, neck = y0 + h * 0.16;
    body.moveTo(x - w * 0.16, neck); body.bezierCurveTo(l - w * 0.02, neck + h * 0.12, l - w * 0.06, H - h * 0.25, l + w * 0.1, H);
    body.lineTo(r - w * 0.1, H); body.bezierCurveTo(r + w * 0.06, H - h * 0.25, r + w * 0.02, neck + h * 0.12, x + w * 0.16, neck); body.closePath();
    const top = union(poly(x - w * 0.16, neck + 1, x - w * 0.3, y0, x - w * 0.05, y0 + h * 0.08, x, y0 - 1, x + w * 0.08, y0 + h * 0.07, x + w * 0.3, y0 + 1, x + w * 0.16, neck + 1));
    b.layer(top, col, { clip: outline, shade: 0.6 });
    b.layer(body, col, { clip: outline, shade: 1 });
    b.layer(ell(x + side * w * 0.3, y0 + h * 0.62, w * 0.28, h * 0.4), 'rgba(160,138,100,0.45)', { clip: body, shade: 0, edge: 0 });
    b.layer(ell(x - side * w * 0.2, y0 + h * 0.45, w * 0.18, h * 0.3), 'rgba(255,250,236,0.55)', { clip: body, shade: 0, edge: 0 });
    b.layer(rrect(x - w * 0.19, neck - 1.6, w * 0.38, 3.2, 1.6), '#a8763f', { clip: outline, shade: 0.7 });
    const ear = P(); const ex = x, ey = y0 + h * 0.48, s = w / 34;
    for (let i = 0; i < 4; i++) { ear.addPath(ell(ex - 2 * s, ey + i * 3.2 * s, 1.5 * s, 2.6 * s, -0.5)); ear.addPath(ell(ex + 2 * s, ey + i * 3.2 * s, 1.5 * s, 2.6 * s, 0.5)); }
    ear.addPath(strip(ex, ey + 15 * s, ex, ey - 2 * s, 1.2 * s));
    b.layer(ear, '#c99a4a', { clip: body, shade: 0.3, edge: 0 });
  };
  b.base(outline, '#c7b691');
  sack(50, 3, 46, H - 3, '#e6d9bc', 1);
  sack(24, H * 0.36, 38, H * 0.64, '#ddcfae', -1);
  sack(76, H * 0.4, 38, H * 0.6, '#e2d4b4', 1);
  b.layer(ell(72, H * 0.4 + 1, 9, 3), '#fbf7ee', { clip: outline, shade: 0.4 });
}

/** Ящики с хлебом: в верхнем — торцы батонов, на нижних — косые перекладины. */
function breadCrates(b, H, outline) {
  const crate = (x, y, w, h) => {
    const box = rrect(x, y, w, h, 1.5);
    b.layer(box, '#c98a3e', { clip: outline, shade: 1 });
    const inner = rrect(x + 4, y + 4, w - 8, h - 8, 1);
    b.layer(inner, '#a86a2c', { clip: box, shade: 0, edge: 0 });
    return inner;
  };
  for (const x of [1, 50]) {
    const inner = crate(x, H * 0.47, 49, H * 0.53);
    const y = H * 0.47 + 4, w = 41, h = H * 0.53 - 8;
    b.layer(union(strip(x + 4, y, x + 4 + w, y + h, 5), strip(x + 4 + w, y, x + 4, y + h, 5)), '#ddae5f', { clip: inner, shade: 0.8 });
  }
  const inner = crate(25, 1, 49, H * 0.47);
  const loaves = P(), crumbs = P();
  for (const [x, y] of [[36, 12], [50, 11], [64, 12], [43, 24], [57, 24], [36, 35], [50, 36], [64, 35]]) { loaves.addPath(ell(x, y, 6.6, 5.6)); crumbs.addPath(ell(x - 0.6, y - 0.4, 4.4, 3.6)); }
  b.layer(loaves, '#c47f3b', { clip: inner, shade: 1 });
  b.layer(crumbs, '#f0d7a4', { clip: inner, shade: 0, edge: 0.3 });
}

/** Место атласа -> рисунок. */
export const FIELD = {
  trees_a_1: sunflower({ squash: 1, petal: '#f2b33d', petalLight: '#f8cf6a', back: '#d98a2b' }),
  trees_a_4: sunflower({ squash: 0.9, petal: '#f0a030', petalLight: '#f6c25a', back: '#c9742a' }),
  trees_a_0: breadTree({ seed: 11, crown: '#86a046', crownMid: '#97b04f', crownLight: '#a9bf5f', crownDark: '#6f8a38', leaf: '#bccb6c', fruits: [['bagel', 30, 40, 1], ['croissant', 66, 30, 1], ['bun', 52, 56, 0.95], ['bagel', 76, 58, 0.9], ['croissant', 24, 64, 0.85], ['bun', 46, 22, 0.8]] }),
  trees_a_3: breadTree({ seed: 23, crown: '#93a84a', crownMid: '#a3b758', crownLight: '#b6c86a', crownDark: '#788f3c', leaf: '#c6d27a', fruits: [['croissant', 34, 34, 1], ['bagel', 64, 40, 1], ['bun', 28, 60, 0.9], ['bagel', 50, 66, 0.85], ['croissant', 74, 64, 0.85], ['bun', 56, 20, 0.8]] }),
  trees_b_0: wheatEar({ grain: '#e0a644', grainLight: '#f3cb73', awn: '#e6c074', stalk: '#c9a04c', leaf: '#b7a24c', leafDark: '#9c8a3c' }),
  trees_b_1: wheatEar({ grain: '#a9b85a', grainLight: '#c9d47e', awn: '#c4cb84', stalk: '#93a64e', leaf: '#86a04a', leafDark: '#6f8a3e' }),
  bushes_0: peas,
  bushes_3: buckwheat,
  bushes_6: cabbage,
  bushes_1: tuft({ seed: 5, blades: ['#6f9a45', '#86ad52', '#9cbd5e'], stalk: '#8fae4e', grain: '#b8c96a', ears: [[34, -0.6], [56, 0.3], [70, 0.7]] }),
  bushes_4: tuft({ seed: 9, blades: ['#b39a40', '#d2ad4a', '#a9b250'], stalk: '#c9a04c', grain: '#e6b553', ears: [[28, -0.7], [44, -0.2], [60, 0.4], [76, 0.8]] }),
  mountains_rocks_2: grain({ facets: true, top: 52, base: '#9a6a3a', light: '#b98552', dark: '#7a5230', shine: '#d2a06a', speck: '#6a4526' }),
  mountains_rocks_4: grain({ facets: true, top: 56, base: '#d6a24a', light: '#e9bf6a', dark: '#b8862f', shine: '#f4d690', crease: '#a97a2c' }),
  mountains_rocks_3: grain({ base: '#e8dfc8', light: '#f8f2e4', dark: '#d2c6a8', shine: '#ffffff' }),
  mountains_rocks_5: grain({ base: '#9b3a32', light: '#b5524a', dark: '#7c2c27', shine: '#d07a70', eye: '#efe2c8', speck: '#6e2621' }),
  pumpkin_cart_0: breadCart,
  barrels_0: flourSacks,
  crate_stack_0: breadCrates,
};
