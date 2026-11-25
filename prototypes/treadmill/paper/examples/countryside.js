// papercraft / examples / countryside.js
// WORKED EXAMPLES, not a parts library. The pieces of one shipped world (an autumn countryside) built with the method
// the references teach: the silhouettes (tree, pine, house, fence, figure), the layered collage prints (folk tree,
// tiered pine, banded bush, faceted rock, cottage facade and roof, planks, mushroom), the critters, the prints of built
// ground (cliff face, meadow deck, bridge side). Read them to see how a recipe in references/procedural-art.md turns
// into code, copy a technique, adapt one to your subject; but write your own subject's pieces: a Mars base, a reef or a
// monastery assembled from these countryside parts in a new palette is exactly the generic result the skill bans.
// Built on the core kit (../lib/paperkit-2d.js): printCard, P.layer / P.parts, drawnPiece, fillLeaf, fillLimb, motif.
import { shape as coreShape, accentsFor, draw2, drawnPiece, fillLeaf, fillLimb, fromDrawing, limbPoint, mix, motif, rgba, scallopTiles, shade, spanAt, tierTips, toRGB } from '../lib/paperkit-2d.js';

/** The core outline primitives plus this world's silhouettes (shape.tree, .pine, .house, .fence, .figure). */
export const shape = {
  ...coreShape,
  /** A storybook tree: lobed canopy on a tapered trunk, base at y = 0. canopy = share of the height (0.7 and w ~0.8 h
   *  give the wide folk-print canopy of the shipped style; pair with paintTree({ trunkTop: 1 - canopy })). */
  tree(r, { w = 3, h = 4.5, trunk = 0.22, lobes = 7, depth = 0.16, canopy = 0.62 } = {}) {
    const rx = w / 2, ry = (h * canopy) / 2, cy = h - ry;
    return shape.radial(r, { cx: 0, cy, rx, ry, lobes, depth, wobble: 0.05, stalk: { w: w * trunk, base: 0 } });
  },

  /** Pine / fir: stacked zig-zag tiers on a trunk stub. */
  pine(r, { w = 3.2, h = 5, tiers = 4, trunkW = null, trunkH = null, notch = 0.7 } = {}) {
    // stacked tiers on a trunk stub, notches shallow (each tier's top hides under the next one's scalloped edge), the
    // lowest tier's underside scalloped (the cut follows it)
    trunkW = trunkW ?? w * 0.15; trunkH = trunkH ?? h * 0.1;
    const tierH = (h - trunkH) / tiers, tip = [];
    for (let i = 0; i < tiers; i++) tip.push([(w / 2) * (1 - (i / tiers) * 0.66) * (0.94 + r() * 0.12), trunkH + i * tierH * 0.9]);
    const right = [], ns = Math.max(2, Math.round((tip[0][0] - trunkW / 2) / (w * 0.16))), dep = tierH * 0.1;
    for (let k = 1; k <= ns; k++) { // the scalloped underside of the lowest tier, from the trunk out to the tip
      const x = trunkW / 2 + (tip[0][0] - trunkW / 2) * k / ns, xm = x - (tip[0][0] - trunkW / 2) / ns / 2;
      right.push([xm, trunkH - dep], [x, trunkH]);
    }
    for (let i = 0; i < tiers; i++) {
      if (i > 0) right.push(tip[i]);
      if (i < tiers - 1) right.push([tip[i + 1][0] * notch, tip[i + 1][1] + tierH * 0.05]);
    }
    const R = [[trunkW / 2, 0], [trunkW / 2, trunkH], ...right, [0, h]];
    const L = R.slice(0, -1).reverse().map(([x, y]) => [-x, y]);
    return [...R, ...L];
  },


  /** Gable house facade (optionally with chimney), base on y = 0. */
  house({ w = 3, wallH = 2.2, roofH = 1.4, overhang = 0.18, chimney = true } = {}) {
    const p = [[-w / 2, 0], [w / 2, 0], [w / 2, wallH], [w / 2 + overhang, wallH], [0, wallH + roofH]];
    if (chimney) {
      const cx = -w * 0.22, cw = w * 0.13, s = roofH / (w / 2 + overhang);
      const yAt = (x) => wallH + roofH - Math.abs(x) * s;
      p.push([cx + cw / 2, yAt(cx + cw / 2)], [cx + cw / 2, wallH + roofH * 0.95], [cx - cw / 2, wallH + roofH * 0.95], [cx - cw / 2, yAt(cx - cw / 2)]);
    }
    p.push([-w / 2 - overhang, wallH], [-w / 2, wallH]);
    return p;
  },

  /** Picket fence cut from one strip: round-topped pickets joined by a bottom rail, base on y = 0. */
  fence({ w = 8, h = 1.4, pickets = 9, railH = 0.35 } = {}) {
    const pw = w / (pickets * 1.6 - 0.6), gap = pw * 0.6, p = [[-w / 2, 0]], rr = pw / 2;
    for (let i = 0; i < pickets; i++) {
      const x = -w / 2 + i * (pw + gap);
      for (let k = 0; k <= 8; k++) { const a = Math.PI - (k / 8) * Math.PI; p.push([x + rr + Math.cos(a) * rr, h - rr + Math.sin(a) * rr]); }
      if (i < pickets - 1) p.push([x + pw, railH], [x + pw + gap, railH]);
    }
    p.push([w / 2, 0]);
    return p;
  },

  /** A little one-card paper figure for background extras (hat, head, cloak, legs), feet on y = 0, height h. A hero
   *  is never one card: build a small 3D paper model that yaws (in 2D, a puppet of cards; references/mechanisms.md
   *  section 12). */
  figure({ h = 2 } = {}) {
    const s = h / 2;
    const p = [
      [0.17, 0], [0.17, 0.55], [0.32, 0.6], [0.38, 1.18], [0.22, 1.3], [0.24, 1.55], [0.52, 1.6], [0.52, 1.7],
      [0.24, 1.74], [0.14, 2.0], [-0.14, 2.0], [-0.24, 1.74], [-0.52, 1.7], [-0.52, 1.6], [-0.24, 1.55], [-0.22, 1.3],
      [-0.38, 1.18], [-0.32, 0.6], [-0.17, 0.55], [-0.17, 0], [-0.04, 0], [-0.04, 0.22], [0.04, 0.22], [0.04, 0],
    ];
    return p.map(([x, y]) => [x * s, y * s]);
  },
};
const D2 = draw2;

/** Almond leaf with a pale vein, centred at (x, y), length L, angle a (radians). */
function almond(g, x, y, L, a, vein, veinW) {
  const ca = Math.cos(a), sa = Math.sin(a), w = L * 0.4;
  g.beginPath();
  g.moveTo(x - ca * L / 2, y - sa * L / 2);
  g.quadraticCurveTo(x - sa * w, y + ca * w, x + ca * L / 2, y + sa * L / 2);
  g.quadraticCurveTo(x + sa * w, y - ca * w, x - ca * L / 2, y - sa * L / 2);
  g.fill();
  if (vein) {
    g.save(); g.strokeStyle = vein; g.lineWidth = veinW; g.lineCap = 'round';
    g.beginPath(); g.moveTo(x - ca * L * 0.36, y - sa * L * 0.36); g.lineTo(x + ca * L * 0.36, y + sa * L * 0.36); g.stroke();
    g.restore();
  }
}

/**
 * Printed leaves: `count` almond leaves on a jittered grid inside `region`, angled in alternating directions, each with a
 * pale vein. size = leaf length as a fraction of the card's smaller side. (Flat print; the painters use layers.)
 */
export function leafField(P, { color, vein = null, count = 20, size = 0.16, region = P.box, angle = 0.55, alpha = 0.9 } = {}) {
  const { g, r, U } = P, L = size * U;
  const w = region.x1 - region.x0, h = region.y1 - region.y0;
  const cols = Math.max(1, Math.round(Math.sqrt(count * w / Math.max(h, 1e-6)))), rows = Math.max(1, Math.ceil(count / cols));
  g.save(); g.globalAlpha = alpha; g.fillStyle = color;
  let k = 0;
  for (let j = 0; j < rows; j++) for (let i = 0; i < cols && k < count; i++, k++) {
    const x = region.x0 + (i + 0.5 + (r() - 0.5) * 0.7 + (j % 2) * 0.5) * (w / cols);
    const y = region.y0 + (j + 0.5 + (r() - 0.5) * 0.6) * (h / rows);
    const a = (k % 2 ? angle : Math.PI - angle) + (r() - 0.5) * 0.5;
    almond(g, x, y, L * (0.8 + r() * 0.4), a, vein, L * 0.07);
  }
  g.restore();
}

/** Storybook broadleaf tree (shape.tree), the countryside example's folk tree as a paper collage: the crown is one
 *  mottled sheet; the trunk and branches + 1 tapered limbs (6 by default, two twigs each) are ONE cut layer running up
 *  into the crown; 15-25 FAT almond leaves
 *  in three CONTRASTING tones (red and dark olive on orange; red and olive on sage) sit at the limb tips and spread
 *  evenly over the crown, each tone one layer with its own lip and shadow; a few berries. colors: { leaf, trunk,
 *  accents?, berry? }. trunkTop = where the crown starts, as a fraction of the card height. */
export function paintTree(colors, { trunkTop = 0.3, leaves = 20, branches = 5, berries = null } = {}) {
  return (P) => {
    const { box, r, pts } = P, leaf = colors.leaf, [acc1, acc2] = colors.accents || accentsFor(leaf);
    const bark = colors.trunk || shade(leaf, -0.5), [lr, lg] = toRGB(leaf);
    const cy0 = box.y0 + box.h * trunkTop, cx = (box.x0 + box.x1) / 2, rx = box.w / 2, ry = (box.y1 - cy0) / 2, cy = cy0 + ry;
    const st = spanAt(pts, box.y0 + box.h * 0.03), T = Math.min(st ? (st[1] - st[0]) * 1.06 : box.w * 0.15, box.w * 0.16);
    P.gradient(shade(leaf, 0.12), shade(leaf, -0.1), 0.6);        // the crown: one felt sheet, lighter toward the key
    P.felt(1);
    const tips = [], sx = cx + (r() - 0.5) * T * 0.3, sy = cy0 + ry * 0.32;   // the trunk splits low in the crown
    P.layer((g) => {                                               // trunk + limbs + twigs: ONE cut layer
      fillLimb(g, cx, box.y0 - box.h * 0.03, cx, (box.y0 + sy) / 2, sx, sy, T, T * 0.5);
      const n = branches + 1;
      for (let i = 0; i < n; i++) {
        const t = n === 1 ? 0 : i / (n - 1) - 0.5, a = t * 2.2 + (r() - 0.5) * 0.25;    // fan, off vertical
        const len = 0.74 + r() * 0.12, ex = sx + Math.sin(a) * rx * len, ey = sy + Math.cos(a) * (cy + ry * 0.66 - sy) * len;
        const mx = sx + (ex - sx) * 0.3, my = sy + (ey - sy) * 0.72;
        fillLimb(g, sx, sy, mx, my, ex, ey, T * 0.36, T * 0.1);
        const ea = Math.atan2(ey - my, ex - mx); tips.push([ex, ey, ea]);
        for (const f of [0.45 + r() * 0.1, 0.7 + r() * 0.08]) {     // two twigs per limb, alternating sides
          const [bx, by] = limbPoint(sx, sy, mx, my, ex, ey, f), side = (f < 0.6 ? 1 : -1) * (a >= 0 ? 1 : -1), ta = ea + side * (0.75 + r() * 0.2);
          const tl = Math.min(rx, ry) * (0.26 + r() * 0.08), kx = bx + Math.cos(ta) * tl, ky = by + Math.sin(ta) * tl;
          fillLimb(g, bx, by, (bx + kx) / 2 + Math.cos(ea) * tl * 0.15, (by + ky) / 2 + Math.sin(ea) * tl * 0.15, kx, ky, T * 0.15, T * 0.06);
          tips.push([kx, ky, ta]);
        }
      }
    }, bark, { lip: 0.8 });
    const Lb = box.w * 0.145, placed = [];   // ~1/7 of the crown: the shipped leaves are big fat ovals
    for (const [x, y, a] of tips) placed.push({ x, y, a: a + (r() - 0.5) * 0.4, L: Lb * (0.9 + r() * 0.25) });   // one at every tip
    for (let guard = 0; placed.length < leaves && guard < 900; guard++) {   // the rest spread evenly over the crown
      const ang = r() * Math.PI * 2, rr = Math.sqrt(r()) * 0.86, x = cx + Math.cos(ang) * rx * rr, y = cy + Math.sin(ang) * ry * rr;
      if (Math.abs(x - cx) < T * 0.7 && y < sy + ry * 0.15) continue;         // keep the trunk readable
      if (placed.some((q) => Math.hypot(q.x + Math.cos(q.a) * q.L * 0.5 - x, q.y + Math.sin(q.a) * q.L * 0.5 - y) < Lb * 0.92)) continue;
      const a = Math.atan2(y - sy, x - sx) + (r() - 0.5) * 1.3;
      placed.push({ x: x - Math.cos(a) * Lb * 0.5, y: y - Math.sin(a) * Lb * 0.5, a, L: Lb * (0.85 + r() * 0.3) });
    }
    const tones = [acc1, acc2, shade(leaf, -0.28)], toneOf = (k) => (k % 10 < 6 ? 0 : k % 10 < 9 ? 1 : 2);
    P.layer((g) => { placed.forEach((q, i) => { g.fillStyle = tones[toneOf(i)]; fillLeaf(g, q.x, q.y, q.L, q.a); }); }, acc1, { lip: 0.7, depth: 0.83 });
    P.draw((g) => {                                                // a faint midrib
      g.lineWidth = Lb * 0.035; g.lineCap = 'round';
      placed.forEach((q, i) => { const c = Math.cos(q.a), s = Math.sin(q.a); g.strokeStyle = rgba(shade(tones[toneOf(i)], -0.35), 0.5); g.beginPath(); g.moveTo(q.x + c * q.L * 0.15, q.y + s * q.L * 0.15); g.lineTo(q.x + c * q.L * 0.78, q.y + s * q.L * 0.78); g.stroke(); });
    });
    const nb = berries ?? (lg >= lr ? 5 : 0);                       // small red berries on green crowns
    if (nb > 0) P.layer((g) => {
      for (let i = 0; i < nb; i++) {
        const q = placed[Math.floor(r() * placed.length)], rad = box.w * 0.02;
        for (let k = 0; k < 3; k++) { g.beginPath(); g.arc(q.x + (r() - 0.5) * Lb * 0.6, q.y + (r() - 0.5) * Lb * 0.6, rad, 0, Math.PI * 2); g.fill(); }
      }
    }, colors.berry || '#c23a24', { lip: 0.8, depth: 0.62 });
    P.innerShade(shade(leaf, -0.65), { alpha: 0.3, blurPx: 12 });
  };
}

/** Pine / fir (shape.pine) as a paper collage: every tier is its own cut layer laid from the bottom up, so each tier's
 *  scalloped lower edge overlaps the one below with a pale cut edge and a short shadow (the shipped pine); needle
 *  strokes in a pale and a dark green inside each tier; a bark stub. */
export function paintPine(colors, { tiers = 4 } = {}) {
  return (P) => {
    const { box, r, pts } = P, c = colors.leaf, cx = (box.x0 + box.x1) / 2;
    const tones = [c, mix(c, '#c9c27a', 0.12)], pale = mix(c, '#f0eaa8', 0.66), dark = shade(c, -0.4);
    P.fill(shade(c, -0.18)); P.felt(1);
    const st = spanAt(pts, box.y0 + box.h * 0.02) || [cx - box.w * 0.07, cx + box.w * 0.07];
    let tips = tierTips(pts, box);
    if (tips.length < 2) tips = Array.from({ length: tiers }, (_, i) => [box.w * 0.5 * (1 - i / tiers * 0.7), box.y0 + box.h * (0.1 + 0.8 * i / tiers)]);
    P.layer((g) => g.fillRect(st[0] - box.w * 0.03, box.y0 - 1, st[1] - st[0] + box.w * 0.06, tips[0][1] - box.y0 + box.h * 0.06), colors.trunk || '#6b3f26', { lip: 0.6 });
    tips.forEach(([hw, yb], k) => {
      const top = k + 1 < tips.length ? Math.min(box.y1 + 1, tips[k + 1][1] + (box.y1 - tips[k + 1][1]) * 0.6) : box.y1 + 1;
      const n = Math.max(3, Math.round(hw * 2 / (box.w * 0.22))), sw = hw * 2.12 / n, dep = Math.min(sw * 0.32, box.h * 0.035);
      P.layer((g) => {
        g.beginPath(); g.moveTo(cx - hw * 1.06, yb + dep);
        for (let i = 0; i < n; i++) { const x0 = cx - hw * 1.06 + i * sw; g.quadraticCurveTo(x0 + sw / 2, yb - dep, x0 + sw, yb + dep); }
        g.lineTo(cx, top); g.closePath(); g.fill();
      }, tones[k % 2], { rim: 1, lip: 0.7, depth: 1 });
      const nh = (Math.min(top, box.y1) - yb) * 0.55;
      P.draw((g) => {                                            // needle strokes, printed
        for (const [col, m] of [[pale, 5 + Math.round(hw / box.w * 6)], [dark, 3 + Math.round(hw / box.w * 4)]]) {
          g.fillStyle = col;
          for (let i = 0; i < m; i++) {
            const u = (r() - 0.5) * 1.5, x = cx + u * hw * 0.8, y = yb + dep * 1.5 + r() * nh * 0.7;
            fillLeaf(g, x, y, nh * (0.32 + r() * 0.14), -Math.PI / 2 + u * 0.55 + (r() - 0.5) * 0.25, 0.16);
          }
        }
      });
    });
    P.innerShade(shade(c, -0.6), { alpha: 0.32, blurPx: 10 });
  };
}

/** Bush / shrub / hedge (shape.mound) as a paper collage (a coral head has its own recipe): the scalloped body; an inner band (a smaller
 *  mound in a paler, warmer tone with its own cut edge); a printed sprig with berries in the middle; five-petal flowers;
 *  a fan of fat grass blades at the foot that anchors it (TARGET: body + printed motif + grass fan = 3 layers).
 *  colors: { leaf, inner?, accents?, grass?, stem? }. */
export function paintBush(colors, { bands = 1, flowers = false, motifs = true, grass = true } = {}) {
  return (P) => {
    const { box, r, U } = P, c = colors.leaf, [acc1, acc2] = colors.accents || accentsFor(c), cx = (box.x0 + box.x1) / 2;
    const [cr, cg, cb] = toRGB(c), warm = cr > cg;
    P.gradient(shade(c, 0.12), shade(c, -0.14), 0.4); P.felt(1);
    const inner = colors.inner || mix(c, warm ? '#f2b45a' : '#efdcae', 0.66);
    for (let k = 0; k < bands; k++) {
      const s = 0.74 - k * 0.24, m = shape.mound(r, { w: box.w * s, h: box.h * (0.72 - k * 0.2), lobes: 5 - k, depth: 0.12 }).map(([x, y]) => [x + cx, y + box.y0]);
      P.layer(m, k ? mix(inner, acc1, 0.3) : inner, { rim: 0.8, lip: 0.8 });
    }
    if (motifs) {   // printed sprigs fanned from the foot, berries at their tips: on the inner band, or pale on the body
      const n = Math.max(2, Math.min(4, Math.round(box.w / U * 0.9))), stem = colors.stem || (bands ? shade(warm ? '#6f7f3c' : c, -0.32) : mix(c, '#f2e3c0', 0.7));
      const bcol = warm ? '#f2e3c0' : (acc1 === '#f2e3c0' ? '#c8452d' : acc1), tipsXY = [];
      P.layer((g) => {
        for (let i = 0; i < n; i++) {
          const t = (i + 0.5) / n - 0.5, len = box.h * (bands ? 0.5 : 0.62) * (0.85 + r() * 0.2), ang = -t * 1.0 + (r() - 0.5) * 0.15, x = cx + t * box.w * 0.18;
          motif.sprig(g, x, box.y0 + box.h * 0.04, len, ang, stem, r); tipsXY.push([x - Math.sin(ang) * len, box.y0 + box.h * 0.04 + Math.cos(ang) * len]);
        }
        g.fillStyle = bcol; for (const [x, y] of tipsXY) motif.berries(g, x, y, U * 0.04, bcol, r);
      }, stem, { lip: 0.6, shade: 0.4, depth: 0.67 });
    }
    if (flowers) {
      const petal = acc1 === '#f2e3c0' ? acc1 : '#f2e3c0', centre = '#e0a33a';
      const nf = Math.max(2, Math.round(box.w / U * 1.2)), fl = Array.from({ length: nf }, (_, i) => [cx + box.w * 0.72 * ((i + 0.5) / nf - 0.5) + (r() - 0.5) * U * 0.1, box.y0 + box.h * (0.45 + r() * 0.35), U * (0.1 + r() * 0.03)]);
      P.layer((g) => { for (const [x, y, rad] of fl) motif.flower(g, x, y, rad, petal, null); }, petal, { lip: 0.6, depth: 0.75 });
      P.draw((g) => { for (const [x, y, rad] of fl) { g.fillStyle = centre; g.beginPath(); g.arc(x, y, rad * 0.3, 0, Math.PI * 2); g.fill(); g.fillStyle = rgba('#5a2a10', 0.6); for (let k = 0; k < 5; k++) { const a = k / 5 * Math.PI * 2; g.beginPath(); g.arc(x + Math.cos(a) * rad * 0.16, y + Math.sin(a) * rad * 0.16, rad * 0.05, 0, Math.PI * 2); g.fill(); } } });
    }
    if (grass) {
      const gc = colors.grass || mix(warm ? '#7d8f4f' : c, '#7d8f4f', 0.5);
      P.layer((g) => {
        for (const [col, m, sp] of [[shade(gc, -0.1), 5, 1.0], [shade(gc, 0.16), 3, 0.7]]) {
          g.fillStyle = col;
          for (let i = 0; i < m; i++) { const u = (i + 0.5) / m - 0.5; fillLeaf(g, cx + u * box.w * 0.8 * sp, box.y0 - box.h * 0.04, box.h * (0.26 + r() * 0.1), Math.PI / 2 - u * 1.2 + (r() - 0.5) * 0.25, 0.18); }
        }
      }, gc, { lip: 0.7, depth: 0.75 });
    }
    P.innerShade(shade(c, -0.6), { alpha: 0.3, blurPx: 10 });
  };
}

/** Rock / boulder / crag as folded card: a lit facet and a darker facet meeting at a crease, speckle, an optional moss
 *  cap (a lime scalloped layer). colors: { stone, ink, moss? } */
export function paintRock(colors, { moss = false } = {}) {
  return (P) => {
    const { box, r } = P, c = colors.stone;
    P.gradient(shade(c, 0.14), shade(c, -0.04), 0.6); P.felt(1.3);
    // a folded card: a darker diagonal band (the face turned from the light) between the lit face and a mid face
    const line = (x) => [[x, box.y1 + 1], [x + box.w * 0.12, box.y0 + box.h * 0.62], [x + box.w * 0.2, box.y0 + box.h * 0.34], [x + box.w * 0.32, box.y0 - 1]];
    const a0 = box.x0 + box.w * (0.34 + r() * 0.1), b0 = a0 + box.w * (0.15 + r() * 0.05);
    P.layer([...line(b0), [box.x1 + 1, box.y0 - 1], [box.x1 + 1, box.y1 + 1]], shade(c, -0.08), { lip: 0, shadow: 0, shade: 0.3, felt: 1.2 });
    P.layer([...line(a0), ...line(b0).reverse()], shade(c, -0.32), { lip: 0, shadow: 0.5, shade: 0.3, felt: 1.2 });
    P.marks('dot', { color: shade(c, -0.5), alpha: 0.5, size: 0.03, density: 0.5 });
    P.marks('dot', { color: shade(c, 0.45), alpha: 0.45, size: 0.025, density: 0.3 });
    if (moss) P.layer(shape.mound(r, { w: box.w * 0.62, h: box.h * 0.22, lobes: 6, depth: 0.25 }).map(([x, y]) => [x + box.x0 + box.w * 0.38, box.y1 - box.h * 0.2 + y]), colors.moss || '#a9a84a', { rim: 0.7 });
    P.innerShade(shade(c, -0.65), { alpha: 0.32, blurPx: 10 });
  };
}

/** Hill / ridge / dune (shape.hill): top-lit gradient, a paler crest layer along the top edge, stripes, dots. */
export function paintHill(colors) {
  return (P) => {
    const { pts, box, U } = P, c = colors.ground;
    P.gradient(shade(c, 0.12), shade(c, -0.15), 0); P.felt(1);
    P.marks('hatch', { color: shade(c, -0.3), alpha: 0.2, size: 0.06, angle: 0.15 });
    P.layer((gg) => { // crest band: a paler strip glued along the top edge (only the upper contour, not the base line)
      gg.beginPath(); let first = true;
      for (const [x, y] of pts) { if (y < box.y0 + 1e-6) { first = true; continue; } if (first) { gg.moveTo(x, y); first = false; } else gg.lineTo(x, y); }
      gg.lineWidth = U * 0.16; gg.stroke();
    }, shade(c, 0.32), { rim: 0, lip: 0.8, shadow: 0.8, shade: 0 });
    P.layer((gg) => {
      gg.beginPath(); let first = true;
      for (const [x, y] of pts) { if (y < box.y0 + 1e-6) { first = true; continue; } const yy = y - U * 0.22; if (first) { gg.moveTo(x, yy); first = false; } else gg.lineTo(x, yy); }
      gg.lineTo(box.x1 + 1, box.y0 - 1); gg.lineTo(box.x0 - 1, box.y0 - 1); gg.closePath(); gg.fill();
    }, c, { rim: 0, lip: 0.6, shadow: 1, shade: 0.4 });
    if (colors.accent) P.marks('dot', { color: colors.accent, alpha: 0.75, size: 0.05, density: 0.25 });
    P.innerShade(shade(c, -0.55), { alpha: 0.35, blurPx: 16, dy: -0.06 });
  };
}

/** Little stones in a wall (lighter and darker, near the corners and the base): one layer per tone. */
function wallStones(P, region, base, n = 8) {
  const { r } = P, w = region.x1 - region.x0, h = region.y1 - region.y0;
  P.layer((g) => {
    for (const col of [shade(base, 0.16), shade(base, -0.12)]) {
      g.fillStyle = col;
      for (let i = 0; i < n / 2; i++) {
        const corner = r() < 0.7, x = corner ? (r() < 0.5 ? region.x0 + w * 0.07 * r() + w * 0.03 : region.x1 - w * 0.1 * r() - w * 0.03) : region.x0 + w * r();
        const y = region.y0 + h * (corner ? r() : r() * 0.25), sw = w * (0.05 + r() * 0.04), sh = sw * (0.55 + r() * 0.25);
        g.beginPath(); if (g.roundRect) g.roundRect(x - sw / 2, y - sh / 2, sw, sh, sh * 0.4); else g.rect(x - sw / 2, y - sh / 2, sw, sh); g.fill();
      }
    }
  }, base, { lip: 0.7, depth: 0.67 });
}

/** A framed, lit window as layers: shutters, a frame with a cross bar, warm panes (glowing by day and night), a flower box. */
function paintWindow(P, x, y, ww, wh, colors, { shutters = true, box = true } = {}) {
  const fr = ww * 0.12, frame = colors.frame || '#6a3a22', trim = colors.trim || '#5f7a4a';
  P.layer((g) => {
    if (shutters) { g.fillStyle = trim; g.fillRect(x - ww / 2 - fr - ww * 0.44, y - fr, ww * 0.4, wh + 2 * fr); g.fillRect(x + ww / 2 + fr + ww * 0.04, y - fr, ww * 0.4, wh + 2 * fr); }
    g.fillStyle = frame; g.fillRect(x - ww / 2 - fr, y - fr, ww + 2 * fr, wh + 2 * fr);
    if (box) { g.fillStyle = shade(colors.door || '#8a5634', -0.1); g.fillRect(x - ww / 2 - fr, y - fr - wh * 0.24, ww + 2 * fr, wh * 0.2); g.fillStyle = '#c8452d'; for (let k = 0; k < 3; k++) motif.flower(g, x - ww * 0.35 + k * ww * 0.35, y - fr - wh * 0.02, ww * 0.16, null, null); }
  }, frame, { lip: 0.8, depth: 0.75 });
  P.draw((g) => {
    const gr = g.createRadialGradient(x, y + wh / 2, 0, x, y + wh / 2, ww * 0.75);
    gr.addColorStop(0, mix(colors.window, '#fff4c8', 0.45)); gr.addColorStop(1, colors.window);
    g.fillStyle = gr; g.fillRect(x - ww / 2, y, ww, wh);
    g.fillStyle = frame; g.fillRect(x - fr / 3, y, fr * 0.66, wh); g.fillRect(x - ww / 2, y + wh / 2 - fr / 3, ww, fr * 0.66);
  });
  if (colors.glow !== false) P.glow((gc) => { gc.fillRect(x - ww / 2, y, ww / 2 - fr / 3, wh / 2 - fr / 3); gc.fillRect(x + fr / 3, y, ww / 2 - fr / 3, wh / 2 - fr / 3); gc.fillRect(x - ww / 2, y + wh / 2 + fr / 3, ww / 2 - fr / 3, wh / 2 - fr / 3); gc.fillRect(x + fr / 3, y + wh / 2 + fr / 3, ww / 2 - fr / 3, wh / 2 - fr / 3); });
}

/** Arched plank door as a layer with a pale frame, plank lines and a knob. */
function paintDoor(P, dx, dw, dh, colors) {
  const door = colors.door || '#3f7a70', U = P.U;
  P.layer((g) => { g.beginPath(); g.moveTo(dx - dw / 2, -1); g.lineTo(dx - dw / 2, dh - dw / 2); g.arc(dx, dh - dw / 2, dw / 2, Math.PI, 0, true); g.lineTo(dx + dw / 2, -1); g.closePath(); g.fill(); }, door, { rim: 0.9, lip: 0.6, depth: 0.83 });
  P.draw((g) => {
    g.strokeStyle = rgba(shade(door, -0.5), 0.65); g.lineWidth = U * 0.01;
    for (const f of [-0.2, 0, 0.2]) { g.beginPath(); g.moveTo(dx + f * dw, 0); g.lineTo(dx + f * dw, dh - dw * 0.15); g.stroke(); }
    g.fillStyle = '#e3b04b'; g.beginPath(); g.arc(dx + dw * 0.3, dh * 0.42, U * 0.014, 0, Math.PI * 2); g.fill();
  });
}

/** A climbing plant beside a door: a thin stem and leaves in two tones. */
function climber(P, x, h, col) {
  const { r, U } = P;
  P.draw((g) => { g.strokeStyle = rgba(shade(col, -0.45), 0.8); g.lineWidth = U * 0.012; g.beginPath(); g.moveTo(x, 0); g.bezierCurveTo(x + U * 0.05, h * 0.3, x - U * 0.05, h * 0.6, x + U * 0.02, h); g.stroke(); });
  P.layer((g) => { for (let k = 0; k < 7; k++) { const y = h * (0.12 + k * 0.13), sd = k % 2 ? 1 : -1; fillLeaf(g, x + sd * U * 0.01, y, U * (0.07 + r() * 0.02), Math.PI / 2 + sd * 0.9, 0.32); } }, col, { lip: 0.6, depth: 0.58 });
}

/** Building facade (shape.house) as a paper collage: oatmeal wall with stones, a darker plinth, framed lit windows with
 *  shutters and flower boxes, an arched teal plank door with a pale frame, a climbing plant, the roof part as scalloped
 *  shingle rows, a brick chimney. wallH in shape units. colors: { wall, roof, window, ink, door?, trim?, frame?,
 *  glow? (false: unlit panes) }. */
export function paintHouse(colors, { wallH = 2.2, windows = 2 } = {}) {
  return (P) => {
    const { box, g, r, U } = P, cx0 = (box.x0 + box.x1) / 2;
    P.gradient(shade(colors.wall, 0.08), shade(colors.wall, -0.08), 0.5); P.felt(0.9);
    wallStones(P, { x0: box.x0, x1: box.x1, y0: box.y0, y1: Math.min(wallH, box.y1) }, colors.wall, 10);
    P.layer((gg) => gg.fillRect(box.x0 - 1, box.y0 - 1, box.w + 2, wallH * 0.1 + 1), shade(colors.wall, -0.22), { lip: 0.8, depth: 0.83 });
    if (box.y1 - wallH > 1e-6 * U) {   // the roof part (single-card houses): shingle rows, then a brick chimney
      scallopTiles(P, { x0: box.x0, x1: box.x1, y0: wallH, y1: box.y1 }, { color: colors.roof, rows: 4 });
      const wh = box.w - 0.36, chx = cx0 - wh * 0.22, chw = wh * 0.13;
      P.layer((gg) => gg.fillRect(chx - chw / 2, wallH + (box.y1 - wallH) * 0.35, chw, box.y1), colors.chimney || '#a8583a', { rim: 0.8 });
      P.marks('brick', { color: '#5a2a18', alpha: 0.45, size: 0.035, region: { x0: chx - chw / 2, x1: chx + chw / 2, y0: wallH + (box.y1 - wallH) * 0.35, y1: box.y1 } });
    }
    const ww = box.w * 0.17, wh = wallH * 0.28, fy = wallH * 0.5;
    const xs = windows === 1 ? [cx0 - box.w * 0.25] : Array.from({ length: windows }, (_, i) => box.x0 + box.w * (0.22 + 0.56 * i / Math.max(1, windows - 1)));
    for (const x of xs) paintWindow(P, x, fy, ww, wh, colors);
    const dw = box.w * 0.2, dh = wallH * 0.48, dx = cx0 + (windows === 2 ? 0 : box.w * 0.25);
    paintDoor(P, dx, dw, dh, colors);
    climber(P, dx + dw * 0.72, dh * 1.25, r() < 0.5 ? '#b8432a' : '#6f7f3c');
    P.innerShade(shade(colors.wall, -0.6), { alpha: 0.3, blurPx: 10 });
  };
}

/** Paper figure print: cloak, hat band on top, a pale face strip. colors: { cloak, hat, face, ink }. (For a real
 *  character, draw it with drawnPiece: every part a layer.) */
export function paintFigure(colors) {
  return (P) => {
    const { box } = P;
    P.gradient(shade(colors.cloak, 0.12), shade(colors.cloak, -0.12), 0.3); P.felt(1);
    P.layer((g) => g.fillRect(box.x0 - 1, box.y0 + box.h * 0.76, box.w + 2, box.h), colors.hat, { lip: 0.8 });
    P.layer((g) => g.fillRect(box.x0 + box.w * 0.36, box.y0 + box.h * 0.62, box.w * 0.28, box.h * 0.14), colors.face, { lip: 0.6 });
    P.layer((g) => g.fillRect(box.x0 - 1, box.y0 - 1, box.w + 2, box.h * 0.26), colors.ink, { lip: 0.5 });
    P.innerShade(shade(colors.cloak, -0.6), { alpha: 0.4, blurPx: 8 });
  };
}

/** Plain coloured card (clouds, sun, water strips): two-tone gradient + felt + speckle + soft inner shade.
 *  Use sparingly: most pieces should carry printed structure and motifs. */
export function paintFlat(color, ink, { outline = false, shadeAmt = 0.4, speckle = true } = {}) {
  return (P) => {
    P.gradient(shade(color, 0.1), shade(color, -0.1), 0.4); P.felt(1);
    if (speckle) P.marks('dot', { color: shade(color, -0.25), alpha: 0.35, size: 0.03, density: 0.5 });
    P.innerShade(shade(color, -0.6), { alpha: shadeAmt, blurPx: 12 });
    if (outline) P.outline(ink);
  };
}

/** A wall without a roof (box-fold house walls, gable ends, modules): wall, stones, optional framed lit windows. */
export function paintWall(colors, { windows = 0, wallH = null } = {}) {
  return (P) => {
    const { box, U } = P, wh = wallH ?? box.h;
    P.gradient(shade(colors.wall, 0.06), shade(colors.wall, -0.1), 0.5); P.felt(0.9);
    if (colors.course) P.marks(colors.course, { color: shade(colors.wall, -0.35), alpha: 0.2, size: 0.07 });
    wallStones(P, { x0: box.x0, x1: box.x1, y0: box.y0, y1: Math.min(wh, box.y1) }, colors.wall, 6);
    for (let i = 0; i < windows; i++) {
      const x = box.x0 + box.w * (i + 0.5) / windows, w = Math.min(box.w / windows * 0.36, wh * 0.26);
      paintWindow(P, x, wh * 0.42, w, w * 1.2, colors, { shutters: false, box: false });
    }
    P.innerShade(shade(colors.wall, -0.6), { alpha: 0.3, blurPx: 10 });
  };
}

/** Roof slope (box-fold house): shingle rows laid from the eave (y = box.y0) up, a pale ridge strip. */
export function paintRoof(colors, { rows = 5 } = {}) {
  return (P) => {
    const { box } = P, c = colors.roof;
    P.gradient(shade(c, 0.08), shade(c, -0.1), 0); P.felt(0.8);
    scallopTiles(P, box, { color: c, rows });
    P.layer((g) => g.fillRect(box.x0 - 1, box.y1 - box.h * 0.08, box.w + 2, box.h * 0.08 + 1), shade(c, -0.3), { rim: 0.8 });
    P.innerShade(shade(c, -0.6), { alpha: 0.25, blurPx: 8 });
  };
}

// ---------------------------------------------------------------------------------------------------------------
// any silhouette: draw a union of simple shapes, trace its outline (so animals, props and machines are easy)
// ---------------------------------------------------------------------------------------------------------------


/** This world's small props (worked examples of fromDrawing silhouettes; human-scale cues sell the miniature): design
 *  your subject's own. Feet on y = 0; h = height. */
export const props = {
  mushroom: (h = 1) => fromDrawing((g) => { draw2.rect(g, -0.12 * h, 0, 0.24 * h, 0.55 * h, 0.08 * h); draw2.ell(g, 0, 0.55 * h, 0.42 * h, 0.32 * h); draw2.rect(g, -0.42 * h, 0.4 * h, 0.84 * h, 0.15 * h); }, { w: 0.9 * h, h: 0.9 * h }),
  signpost: (h = 1) => fromDrawing((g) => { draw2.rect(g, -0.05 * h, 0, 0.1 * h, h * 0.95); draw2.poly(g, [[-0.32 * h, 0.62 * h], [0.3 * h, 0.62 * h], [0.4 * h, 0.72 * h], [0.3 * h, 0.82 * h], [-0.32 * h, 0.82 * h]]); }, { w: 0.85 * h, h }),
  bench: (h = 0.5) => fromDrawing((g) => { draw2.rect(g, -0.9 * h, 0.45 * h, 1.8 * h, 0.16 * h, 0.03 * h); draw2.rect(g, -0.9 * h, 0.7 * h, 1.8 * h, 0.22 * h, 0.03 * h); draw2.rect(g, -0.75 * h, 0, 0.14 * h, 0.92 * h); draw2.rect(g, 0.61 * h, 0, 0.14 * h, 0.92 * h); }, { w: 1.9 * h, h: h }), // one connected piece: fromDrawing keeps the outline of a single cut
  barrel: (h = 0.6) => fromDrawing((g) => { draw2.ell(g, 0, 0.5 * h, 0.36 * h, 0.5 * h); draw2.rect(g, -0.3 * h, 0, 0.6 * h, h); }, { w: 0.8 * h, h }),
  fox: (h = 0.8) => fromDrawing((g) => { draw2.ell(g, 0, 0.42 * h, 0.3 * h, 0.36 * h); draw2.ell(g, 0.06 * h, 0.82 * h, 0.2 * h, 0.17 * h); draw2.poly(g, [[-0.08 * h, 0.9 * h], [-0.02 * h, 1.12 * h], [0.06 * h, 0.94 * h]]); draw2.poly(g, [[0.08 * h, 0.94 * h], [0.17 * h, 1.12 * h], [0.2 * h, 0.9 * h]]); draw2.ell(g, 0.27 * h, 0.76 * h, 0.12 * h, 0.07 * h); draw2.ell(g, -0.38 * h, 0.2 * h, 0.32 * h, 0.13 * h, 0.5); draw2.rect(g, -0.16 * h, 0, 0.1 * h, 0.2 * h); draw2.rect(g, 0.08 * h, 0, 0.1 * h, 0.2 * h); }, { w: 1.4 * h, h: 1.2 * h }),
  cat: (h = 0.6) => fromDrawing((g) => { draw2.ell(g, 0, 0.4 * h, 0.28 * h, 0.4 * h); draw2.ell(g, 0, 0.88 * h, 0.22 * h, 0.2 * h); draw2.poly(g, [[-0.2 * h, 0.95 * h], [-0.15 * h, 1.16 * h], [-0.04 * h, 1.02 * h]]); draw2.poly(g, [[0.04 * h, 1.02 * h], [0.15 * h, 1.16 * h], [0.2 * h, 0.95 * h]]); draw2.ell(g, 0.32 * h, 0.22 * h, 0.2 * h, 0.06 * h, -0.6); }, { w: 1.1 * h, h: 1.2 * h }),
  duck: (h = 0.5) => fromDrawing((g) => { draw2.ell(g, 0, 0.32 * h, 0.5 * h, 0.3 * h); draw2.ell(g, 0.36 * h, 0.72 * h, 0.2 * h, 0.2 * h); draw2.poly(g, [[0.5 * h, 0.74 * h], [0.78 * h, 0.66 * h], [0.5 * h, 0.62 * h]]); draw2.poly(g, [[-0.52 * h, 0.4 * h], [-0.72 * h, 0.62 * h], [-0.4 * h, 0.5 * h]]); }, { w: 1.6 * h, h: 1.0 * h }),
};

/** Printed critter on any outline: coat with felt, a pale belly patch layer, a dark eye. colors: { coat, belly?, ink }.
 *  For real animals prefer `critter.*` (every part its own layer). */
export function paintCritter(colors) {
  return (P) => {
    const { box, U } = P, coat = colors.coat, belly = colors.belly || '#f6ead2';
    P.gradient(shade(coat, 0.15), shade(coat, -0.15), 0.5); P.felt(1);
    P.layer((g) => { g.beginPath(); g.ellipse(box.x0 + box.w * 0.55, box.y0 + box.h * 0.45, box.w * 0.13, box.h * 0.2, 0, 0, Math.PI * 2); g.fill(); }, belly, { lip: 0.6, depth: 0.67 });
    P.draw((g) => { g.fillStyle = colors.ink || '#2a1608'; g.beginPath(); g.arc(box.x0 + box.w * 0.62, box.y0 + box.h * 0.7, U * 0.035, 0, Math.PI * 2); g.fill(); });
    P.marks('tick', { color: shade(coat, -0.4), alpha: 0.4, size: 0.05, density: 0.6 });
    P.innerShade(shade(coat, -0.6), { alpha: 0.35, blurPx: 6 });
  };
}

/** Wood print for props (benches, signs, barrels, crates, fences, decks, rails): every plank its own layer, grain
 *  strokes and nail dots printed on top. */
export function paintWood(colors, { planks = 3, vertical = false } = {}) {
  return (P) => {
    const { box, g, r, U } = P, c = colors.wood;
    P.gradient(shade(c, 0.12), shade(c, -0.12), 0.5); P.felt(1);
    P.layer((gg) => {
      for (let i = 0; i < planks; i++) {
        const f0 = i / planks, f1 = (i + 1) / planks; gg.fillStyle = i % 2 ? shade(c, -0.07) : shade(c, 0.06);
        if (vertical) gg.fillRect(box.x0 + box.w * f0 + box.w * 0.008, box.y0 - 1, box.w * (f1 - f0) - box.w * 0.016, box.h + 2);
        else gg.fillRect(box.x0 - 1, box.y0 + box.h * f0 + box.h * 0.008, box.w + 2, box.h * (f1 - f0) - box.h * 0.016);
      }
    }, c, { lip: 0.7, depth: 0.75 });
    P.draw(() => {
      g.strokeStyle = rgba(shade(c, -0.35), 0.5); g.lineWidth = U * 0.014; g.lineCap = 'round';
      for (let k = 0; k < 10; k++) { const y = box.y0 + r() * box.h, x = box.x0 + r() * box.w * 0.6; g.beginPath(); g.moveTo(x, y); g.bezierCurveTo(x + box.w * 0.15, y + U * 0.03, x + box.w * 0.25, y - U * 0.03, x + box.w * 0.4, y); g.stroke(); }
      g.fillStyle = rgba('#3a2010', 0.55);
      for (let i = 0; i < planks; i++) for (const e of [0.06, 0.94]) {
        const t = (i + 0.5) / planks, x = vertical ? box.x0 + box.w * t : box.x0 + box.w * e, y = vertical ? box.y0 + box.h * e : box.y0 + box.h * t;
        g.beginPath(); g.arc(x, y, U * 0.02, 0, Math.PI * 2); g.fill();
      }
    });
    P.innerShade(shade(c, -0.6), { alpha: 0.32, blurPx: 6 });
  };
}

/** Mushroom print: a red cap layer with a pale cut edge and cream spot layers, a gill band, a cream stem. */
export function paintMushroom(colors = {}) {
  return (P) => {
    const { box, g, r, U } = P, cap = colors.cap || '#c8392b';
    P.fill(colors.stem || '#f1e3c4'); P.felt(0.8);
    P.draw(() => { g.strokeStyle = rgba('#8a6a4a', 0.55); g.lineWidth = U * 0.012; for (let k = 0; k < 12; k++) { const x = box.x0 + box.w * (0.08 + 0.84 * k / 11); g.beginPath(); g.moveTo(x, box.y0 + box.h * 0.47); g.lineTo(box.x0 + box.w / 2 + (x - box.x0 - box.w / 2) * 0.3, box.y0 + box.h * 0.56); g.stroke(); } });
    P.layer((gg) => gg.fillRect(box.x0 - 1, box.y0 + box.h * 0.56, box.w + 2, box.h), cap, { rim: 0.8, lip: 0.6 });
    P.layer((gg) => { for (let i = 0; i < 6; i++) { gg.beginPath(); gg.arc(box.x0 + box.w * (0.15 + r() * 0.7), box.y0 + box.h * (0.66 + r() * 0.26), U * (0.05 + r() * 0.04), 0, Math.PI * 2); gg.fill(); } }, '#f6ead2', { lip: 0.5, depth: 0.62 });
    P.innerShade('#5a2a14', { alpha: 0.32, blurPx: 6 });
  };
}

/**
 * Small animals as layered paper dolls, facing right, feet on y = 0, h = height in units. Every body part is its own
 * cut layer (drawnPiece), eyes and stripes are printed. Each returns [pts, paint] like drawnPiece. c overrides colours.
 * This world's small story (a cat by a door, ducks at the rim, hens at grain, a fox behind a tree): draw your world's
 * own creatures the same way.
 */
export const critter = {
  fox(h = 2, c = {}) {
    const coat = c.coat || '#d9772e', pale = c.pale || '#f6ead2', dark = c.dark || '#4a2a18', s = h;
    return drawnPiece((g, ink) => {
      g.fillStyle = ink(coat); D2.ell(g, -0.38 * s, 0.3 * s, 0.34 * s, 0.14 * s, -0.5);
      g.fillStyle = ink(pale); D2.ell(g, -0.64 * s, 0.45 * s, 0.11 * s, 0.08 * s, -0.5);
      g.fillStyle = ink(dark); D2.rect(g, 0.08 * s, 0, 0.08 * s, 0.32 * s, 0.03 * s); D2.rect(g, 0.2 * s, 0, 0.08 * s, 0.3 * s, 0.03 * s);
      g.fillStyle = ink(coat); D2.ell(g, 0.02 * s, 0.38 * s, 0.3 * s, 0.33 * s);
      g.fillStyle = ink(shade(coat, -0.1)); D2.ell(g, -0.13 * s, 0.22 * s, 0.18 * s, 0.19 * s);
      g.fillStyle = ink(pale); D2.ell(g, 0.17 * s, 0.44 * s, 0.11 * s, 0.19 * s);
      g.fillStyle = ink(coat); D2.poly(g, [[0.06 * s, 0.84 * s], [0.1 * s, 1.1 * s], [0.21 * s, 0.92 * s]]); D2.poly(g, [[0.2 * s, 0.92 * s], [0.31 * s, 1.11 * s], [0.35 * s, 0.88 * s]]);
      D2.ell(g, 0.21 * s, 0.79 * s, 0.21 * s, 0.17 * s); D2.poly(g, [[0.3 * s, 0.85 * s], [0.54 * s, 0.75 * s], [0.3 * s, 0.67 * s]]);
      g.fillStyle = ink(pale); D2.ell(g, 0.3 * s, 0.72 * s, 0.13 * s, 0.07 * s);
      g.fillStyle = ink(dark, 'print'); D2.ell(g, 0.31 * s, 0.81 * s, 0.026 * s, 0.032 * s); D2.ell(g, 0.53 * s, 0.75 * s, 0.026 * s, 0.026 * s);
      D2.poly(g, [[0.085 * s, 1.0 * s], [0.1 * s, 1.1 * s], [0.135 * s, 1.03 * s]]); D2.poly(g, [[0.29 * s, 1.05 * s], [0.31 * s, 1.11 * s], [0.33 * s, 1.0 * s]]);
    }, { w: 1.6 * h, h: 1.15 * h });
  },
  cat(h = 1.8, c = {}) {
    const coat = c.coat || '#d98a3e', pale = c.pale || '#f6ead2', dark = c.dark || shade(coat, -0.45), s = h;
    return drawnPiece((g, ink) => {
      g.fillStyle = ink(coat); D2.ell(g, -0.3 * s, 0.08 * s, 0.26 * s, 0.065 * s, -0.15);
      D2.ell(g, 0, 0.36 * s, 0.27 * s, 0.36 * s);
      g.fillStyle = ink(pale); D2.ell(g, 0.12 * s, 0.4 * s, 0.1 * s, 0.18 * s); D2.ell(g, 0.06 * s, 0.035 * s, 0.075 * s, 0.04 * s); D2.ell(g, 0.2 * s, 0.035 * s, 0.075 * s, 0.04 * s);
      g.fillStyle = ink(coat); D2.poly(g, [[-0.12 * s, 0.94 * s], [-0.1 * s, 1.15 * s], [0.02 * s, 1.02 * s]]); D2.poly(g, [[0.1 * s, 1.02 * s], [0.21 * s, 1.15 * s], [0.25 * s, 0.94 * s]]);
      D2.ell(g, 0.06 * s, 0.86 * s, 0.22 * s, 0.19 * s);
      g.fillStyle = ink(pale); D2.ell(g, 0.1 * s, 0.79 * s, 0.09 * s, 0.055 * s);
      g.fillStyle = ink(dark, 'print');
      for (const [x, y, a] of [[-0.18, 0.5, 0.3], [-0.2, 0.32, 0.2], [-0.16, 0.16, 0.1]]) D2.ell(g, x * s, y * s, 0.08 * s, 0.022 * s, a);
      for (const x of [-0.04, 0.06, 0.16]) D2.ell(g, x * s, 1.0 * s, 0.012 * s, 0.035 * s);
      D2.ell(g, 0.0, 0.88 * s, 0.024 * s, 0.03 * s); D2.ell(g, 0.14 * s, 0.88 * s, 0.024 * s, 0.03 * s);
      g.fillStyle = ink('#c86a5a', 'print'); D2.poly(g, [[0.09 * s, 0.82 * s], [0.13 * s, 0.82 * s], [0.11 * s, 0.795 * s]]);
    }, { w: 1.25 * h, h: 1.2 * h });   // the tail reaches past the body
  },
  duck(h = 1.6, c = {}) {
    const body = c.body || '#b98a5a', head = c.head || '#2f6a4a', pale = c.pale || '#f6ead2', bill = c.bill || '#e0973a', s = h;
    return drawnPiece((g, ink) => {
      g.fillStyle = ink(bill); D2.rect(g, -0.04 * s, 0, 0.05 * s, 0.12 * s); D2.rect(g, 0.08 * s, 0, 0.05 * s, 0.12 * s);
      g.fillStyle = ink(body); D2.poly(g, [[-0.42 * s, 0.38 * s], [-0.72 * s, 0.58 * s], [-0.38 * s, 0.5 * s]]); D2.ell(g, 0, 0.32 * s, 0.5 * s, 0.24 * s);
      g.fillStyle = ink(shade(body, -0.2)); D2.ell(g, -0.1 * s, 0.38 * s, 0.28 * s, 0.13 * s, 0.15);
      g.fillStyle = ink(pale); D2.ell(g, 0.32 * s, 0.55 * s, 0.11 * s, 0.08 * s);
      g.fillStyle = ink(head); D2.ell(g, 0.38 * s, 0.73 * s, 0.17 * s, 0.15 * s);
      g.fillStyle = ink(bill); D2.poly(g, [[0.5 * s, 0.76 * s], [0.74 * s, 0.7 * s], [0.5 * s, 0.65 * s]]);
      g.fillStyle = ink('#1e1208', 'print'); D2.ell(g, 0.43 * s, 0.77 * s, 0.024 * s, 0.028 * s);
    }, { w: 1.5 * h, h: 0.95 * h });
  },
  hen(h = 1.3, c = {}) {
    const body = c.body || '#f1e3c4', comb = c.comb || '#c8392b', s = h;
    return drawnPiece((g, ink) => {
      g.fillStyle = ink('#d98a3e'); D2.rect(g, -0.05 * s, 0, 0.05 * s, 0.18 * s); D2.rect(g, 0.07 * s, 0, 0.05 * s, 0.18 * s);
      g.fillStyle = ink(shade(body, -0.12)); D2.poly(g, [[-0.3 * s, 0.4 * s], [-0.56 * s, 0.78 * s], [-0.42 * s, 0.4 * s]]); D2.poly(g, [[-0.28 * s, 0.38 * s], [-0.46 * s, 0.84 * s], [-0.18 * s, 0.5 * s]]);
      g.fillStyle = ink(body); D2.ell(g, 0, 0.42 * s, 0.38 * s, 0.26 * s); D2.ell(g, 0.26 * s, 0.68 * s, 0.14 * s, 0.16 * s);
      g.fillStyle = ink(shade(body, -0.18)); D2.ell(g, -0.06 * s, 0.44 * s, 0.22 * s, 0.12 * s, 0.2);
      g.fillStyle = ink(comb); D2.ell(g, 0.24 * s, 0.86 * s, 0.07 * s, 0.05 * s); D2.ell(g, 0.32 * s, 0.84 * s, 0.05 * s, 0.045 * s); D2.ell(g, 0.36 * s, 0.6 * s, 0.035 * s, 0.05 * s);
      g.fillStyle = ink('#e0973a'); D2.poly(g, [[0.38 * s, 0.71 * s], [0.5 * s, 0.67 * s], [0.38 * s, 0.63 * s]]);
      g.fillStyle = ink('#1e1208', 'print'); D2.ell(g, 0.3 * s, 0.72 * s, 0.022 * s, 0.026 * s);
    }, { w: 1.1 * h, h: 0.95 * h });
  },
  sheep(h = 1.6, c = {}) {
    const wool = c.wool || '#f3e8d2', skin = c.skin || '#5a3a26', s = h;
    return drawnPiece((g, ink) => {
      g.fillStyle = ink(skin); for (const x of [-0.3, -0.16, 0.12, 0.26]) D2.rect(g, x * s, 0, 0.06 * s, 0.3 * s, 0.02 * s);
      g.fillStyle = ink(wool); for (let k = 0; k < 9; k++) { const a = Math.PI * (0.05 + 0.9 * k / 8); D2.ell(g, Math.cos(a) * 0.36 * s - 0.02 * s, 0.42 * s + Math.sin(a) * 0.2 * s, 0.13 * s, 0.12 * s); }
      D2.ell(g, -0.02 * s, 0.44 * s, 0.4 * s, 0.2 * s);
      g.fillStyle = ink(skin); D2.ell(g, 0.42 * s, 0.6 * s, 0.13 * s, 0.11 * s, -0.3); D2.ell(g, 0.32 * s, 0.68 * s, 0.07 * s, 0.035 * s, 0.6);
      g.fillStyle = ink(wool); D2.ell(g, 0.36 * s, 0.7 * s, 0.07 * s, 0.06 * s);
      g.fillStyle = ink('#f6ead2', 'print'); D2.ell(g, 0.46 * s, 0.63 * s, 0.022 * s, 0.026 * s);
    }, { w: 1.2 * h, h: 0.85 * h });
  },
};


/** A strata face (earth layers, a few stones, roots, a grass lip with fat blades): kept as a print technique for a face
 *  a brief names (a quay's foundation, a cut bank seen in section). Natural raised ground is NOT built as a strata
 *  block in this style: the ground stays flat (references/mechanisms.md section 8). colors: { earth, grass?, stone? }. */
export function paintCliff(colors, { strata = 3, grassLip = true, stones = 5 } = {}) {
  return (P) => {
    const { box, r, U } = P, c = colors.earth || '#b07a4a', grass = colors.grass || '#8f9c4c';
    P.gradient(shade(c, 0.14), shade(c, -0.12), 0); P.felt(1.2);
    for (let k = 0; k < strata; k++) {                     // wavy strata, darker toward the foot
      const y = box.y0 + box.h * (0.2 + 0.6 * k / Math.max(1, strata)), amp = box.h * 0.05, ph = r() * 6;
      P.layer((g) => {
        g.beginPath(); g.moveTo(box.x0 - 1, box.y0 - 1);
        for (let i = 0; i <= 40; i++) { const x = box.x0 - 1 + (box.w + 2) * i / 40; g.lineTo(x, y + Math.sin(x / U * 3.1 + ph) * amp + Math.sin(x / U * 7.3 + ph * 2) * amp * 0.4); }
        g.lineTo(box.x1 + 1, box.y0 - 1); g.closePath(); g.fill();
      }, shade(c, -0.1 - 0.08 * (strata - k)), { lip: 0.7, rim: 0.4, shadow: 0.8, shade: 0.3 });
    }
    if (stones > 0) P.layer((g) => {
      for (let i = 0; i < stones; i++) { const x = box.x0 + box.w * r(), y = box.y0 + box.h * (0.1 + r() * 0.6), sw = U * (0.08 + r() * 0.07); g.beginPath(); g.ellipse(x, y, sw, sw * 0.62, (r() - 0.5) * 0.6, 0, Math.PI * 2); g.fill(); }
    }, colors.stone || mix(c, '#c9bfa8', 0.6), { lip: 0.8, depth: 0.7 });
    if (grassLip) {
      P.draw((g) => { g.strokeStyle = rgba(shade(c, -0.5), 0.55); g.lineWidth = U * 0.012; g.lineCap = 'round'; for (let i = 0; i < 6; i++) { const x = box.x0 + box.w * r(), y = box.y1 - box.h * 0.12; g.beginPath(); g.moveTo(x, y); g.quadraticCurveTo(x + U * 0.04, y - box.h * 0.08, x - U * 0.02, y - box.h * (0.14 + r() * 0.1)); g.stroke(); } });
      P.layer((g) => {                                     // the turf: a scalloped green band hanging over the top edge
        const n = Math.max(4, Math.round(box.w / (U * 0.3))), sw = (box.w + 2) / n, y = box.y1 - box.h * 0.1;
        g.beginPath(); g.moveTo(box.x0 - 1, box.y1 + 1); g.lineTo(box.x0 - 1, y);
        for (let i = 0; i < n; i++) { const x0 = box.x0 - 1 + i * sw; g.quadraticCurveTo(x0 + sw / 2, y - box.h * 0.07, x0 + sw, y); }
        g.lineTo(box.x1 + 1, box.y1 + 1); g.closePath(); g.fill();
        for (let i = 0; i < n * 1.4; i++) { const x = box.x0 + box.w * r(); fillLeaf(g, x, y - box.h * 0.02, box.h * (0.12 + r() * 0.08), Math.PI / 2 + (r() - 0.5) * 0.9, 0.2); }
      }, grass, { rim: 0.6, lip: 0.8 });
    }
    P.innerShade(shade(c, -0.6), { alpha: 0.3, blurPx: 12 });
  };
}

/** Walkable top of raised ground (terrace, plateau, island, quay) seen from above: grass with felt, tuft fans, flowers,
 *  pebbles, all as small layers. colors: { grass, flower?, pebble? }. */
export function paintMeadow(colors, { tufts = 1, flowers = 1 } = {}) {
  return (P) => {
    const { box, r, U } = P, c = colors.grass || '#a9a86a', area = box.w * box.h / (U * U);
    P.gradient(shade(c, 0.1), shade(c, -0.08), 0.3); P.felt(1.1);
    P.marks('dab', { color: shade(c, -0.12), alpha: 0.35, size: 0.18, density: 0.25 });
    const nt = Math.round(area * 2.2 * tufts), nf = Math.round(area * 1.6 * flowers);
    const fc = colors.flower || '#f2e3c0';
    P.layer((g) => {
      g.fillStyle = shade(c, -0.22);
      for (let i = 0; i < nt; i++) { const x = box.x0 + box.w * r(), y = box.y0 + box.h * r(); for (let k = 0; k < 4; k++) fillLeaf(g, x + (k - 1.5) * U * 0.035, y, U * (0.1 + r() * 0.05), Math.PI / 2 + (k - 1.5) * 0.35, 0.22); }
      g.fillStyle = colors.pebble || '#c9bfa8';
      for (let i = 0; i < nf * 0.5; i++) { g.beginPath(); g.ellipse(box.x0 + box.w * r(), box.y0 + box.h * r(), U * 0.04, U * 0.028, r(), 0, Math.PI * 2); g.fill(); }
      g.fillStyle = fc; for (let i = 0; i < nf; i++) motif.flower(g, box.x0 + box.w * r(), box.y0 + box.h * r(), U * 0.045, fc, null);
    }, c, { lip: 0.6, depth: 0.55 });
    P.innerShade(shade(c, -0.55), { alpha: 0.25, blurPx: 10 });
  };
}

/** Bridge side card (bridge() in examples/built-ground.js): the face below the deck (stone blocks with fanned arch stones, or
 *  planks) and the parapet above it as its own layer with a pale cut edge and coping. deckH(x) = deck height along the
 *  card (x = along the span), arch = { w, h } of the opening under the deck. colors: { stone | wood, rail? }. */
export function paintBridgeSide(colors, { deckH, arch = null, wood = false } = {}) {
  return (P) => {
    const { box, r, U } = P, c = wood ? (colors.wood || '#a8683a') : (colors.stone || '#c2ab8a'), rail = colors.rail || (wood ? shade(c, -0.08) : shade(c, 0.1));
    P.gradient(shade(c, 0.1), shade(c, -0.14), 0); P.felt(1.1);
    if (wood) P.marks('stripe', { color: shade(c, -0.3), alpha: 0.35, size: 0.06, angle: 0 });
    else P.marks('brick', { color: shade(c, -0.42), alpha: 0.5, size: 0.07, width: 1.4 });
    if (arch) P.layer((g) => {                              // arch stones fanned round the opening
      const n = 11;
      for (let i = 0; i < n; i++) {
        const a0 = Math.PI * i / n, a1 = Math.PI * (i + 0.92) / n, R0 = 1, R1 = 1.22;
        g.beginPath();
        g.moveTo(Math.cos(a0) * arch.w / 2 * R0, Math.sin(a0) * arch.h * R0); g.lineTo(Math.cos(a0) * arch.w / 2 * R1, Math.sin(a0) * arch.h * R1);
        g.lineTo(Math.cos(a1) * arch.w / 2 * R1, Math.sin(a1) * arch.h * R1); g.lineTo(Math.cos(a1) * arch.w / 2 * R0, Math.sin(a1) * arch.h * R0); g.closePath(); g.fill();
      }
    }, shade(c, 0.12), { lip: 0.8, rim: 0.5, depth: 0.8 });
    P.layer((g) => {                                        // the deck edge seen from the side: a dark beam
      g.beginPath(); for (let i = 0; i <= 48; i++) { const x = box.x0 + box.w * i / 48; (i ? g.lineTo : g.moveTo).call(g, x, deckH(x) - U * 0.02); }
      for (let i = 48; i >= 0; i--) { const x = box.x0 + box.w * i / 48; g.lineTo(x, deckH(x) + U * 0.07); } g.closePath(); g.fill();
    }, shade(colors.wood || '#8a5634', -0.15), { lip: 0.6, depth: 0.7 });
    P.layer((g) => {                                        // the parapet: everything above the deck line
      g.beginPath(); for (let i = 0; i <= 48; i++) { const x = box.x0 + box.w * i / 48; (i ? g.lineTo : g.moveTo).call(g, x, deckH(x) + U * 0.07); }
      g.lineTo(box.x1 + 1, box.y1 + 1); g.lineTo(box.x0 - 1, box.y1 + 1); g.closePath(); g.fill();
    }, rail, { rim: 0.9, lip: 0.8 });
    if (wood) P.marks('stripe', { color: shade(rail, -0.35), alpha: 0.4, size: 0.05, region: { ...box, y0: box.y0 + box.h * 0.4 } });
    else P.marks('brick', { color: shade(rail, -0.4), alpha: 0.45, size: 0.06, region: { ...box, y0: box.y0 + box.h * 0.4 } });
    P.innerShade(shade(c, -0.6), { alpha: 0.3, blurPx: 10 });
  };
}
