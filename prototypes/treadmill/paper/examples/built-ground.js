// papercraft / examples / built-ground.js
// WORKED EXAMPLES of the built-ground recipes in references/mechanisms.md section 14 (three.js), not a parts library:
// how one world built its cottage (on the core boxFold), a bridge, a terrace, stairs and a ramp from cards, each with
// userData.walk.heightAt(x, z) so the walk field (WalkField, ../lib/paper-motion.js) reads the same construction the
// camera sees. Read them for the construction, the fold-flat order and the walk surface; build your own subject's
// structures (a boardwalk, a pier, a temple stair, a space-station gantry) the same way, with your own prints.
import * as THREE from 'three';
import { makeCard, platform, boxFold } from '../lib/paperkit-3d.js';
import { shape as P2shape, shade, rng } from '../lib/paperkit-2d.js';
import { paintWall, paintHouse, paintRoof } from './countryside.js';   // the cottage's prints only

// The constructions below print plain card by default in this example world's material colours (a two-tone sheet with
// planks or courses as layers): pass your subject's colours and prints in `colors` / `paints` (the template passes
// the countryside ones). paints.side of a bridge may be a
// factory (deckH, arch) => printer, so a custom side print can follow the deck line.
const neutral = (c) => (P) => { P.gradient(shade(c, 0.1), shade(c, -0.12), 0.5); P.felt(1); P.innerShade(shade(c, -0.6), { alpha: 0.25, blurPx: 10 }); };
const banded = (c, n, vertical = false) => (P) => {          // planks / courses / treads: every band its own layer
  const { box } = P; neutral(c)(P);
  P.layer((g) => { for (let i = 0; i < n; i++) { g.fillStyle = i % 2 ? shade(c, -0.07) : shade(c, 0.05); const f0 = i / n, f1 = (i + 1) / n;
    if (vertical) g.fillRect(box.x0 + box.w * f0 + box.w * 0.008, box.y0 - 1, box.w * (f1 - f0) - box.w * 0.016, box.h + 2);
    else g.fillRect(box.x0 - 1, box.y0 + box.h * f0 + box.h * 0.008, box.w + 2, box.h * (f1 - f0) - box.h * 0.016); } }, c, { lip: 0.7, depth: 0.75 });
};
const sideNeutral = (c, deckH) => (P) => {                   // a bridge side: the face, then the parapet above the deck line
  const { box, U } = P; neutral(c)(P);
  P.layer((g) => { g.beginPath(); for (let i = 0; i <= 48; i++) { const x = box.x0 + box.w * i / 48; (i ? g.lineTo : g.moveTo).call(g, x, deckH(x) + U * 0.07); }
    g.lineTo(box.x1 + 1, box.y1 + 1); g.lineTo(box.x0 - 1, box.y1 + 1); g.closePath(); g.fill(); }, shade(c, 0.08), { rim: 0.9, lip: 0.8 });
};

/**
 * The cottage of the countryside example: the core box fold (boxFold) with this world's prints (a stone-flecked
 * facade with framed lit windows and an arched door, plain gables with a window, shingle-row roofs, a brick chimney).
 * colors: { wall, roof, window, ink, door?, trim?, chimney? }.
 */
export function boxHouse({ colors, windows = 2, wallH = 3, paints = {}, chimney = true, ...opts } = {}) {
  const g = boxFold({ ...opts, wallH, paints: {
    front: paints.front || paintHouse(colors, { wallH, windows }),
    gable: paints.gable || paintWall(colors, { windows: 1, wallH }),
    roof: paints.roof || paintRoof(colors),
  } });
  if (chimney) {   // a brick chimney card on the ridge frame, off-centre: it rides the roof and folds flat with it
    const w = opts.w ?? 4, roofH = opts.roofH ?? 1.8, cw = w * 0.13, chH = roofH * 0.62, print = opts.print || {};
    const ch = makeCard(P2shape.rect(cw, chH), { paper: opts.paper, thickness: opts.thickness ?? 0.08, edgeWidth: opts.edgeWidth ?? 0.06, back: 'kraft',
      seed: (opts.seed ?? 1) + 11, folds: ['bottom'], pxPerUnit: print.pxPerUnit ?? 64, effects: print.effects ?? 1, paint: paints.chimney || ((P) => {
        P.gradient(colors.chimney || '#b0603e', '#8a4a30', 0.5); P.marks('brick', { color: '#4a2214', alpha: 0.5, size: 0.12 });
        P.draw((gg) => { gg.fillStyle = '#5a3020'; gg.fillRect(P.box.x0 - 1, P.box.y1 - P.box.h * 0.12, P.box.w + 2, P.box.h * 0.12 + 1); });
      }) });
    ch.position.set(-w * 0.24, -roofH * 0.18, 0); g.userData.ridge.add(ch);
    g.userData.chimneyTop = { object: ch, offset: [0, chH * 1.02, 0] };   // for smoke: paper-life puffs({ at: [...] })
  }
  return g;
}

const smooth01 = (a, b, x) => { const t = Math.min(1, Math.max(0, (x - a) / (b - a))); return t * t * (3 - 2 * t); };
const EDGE = 0.12; // walk surfaces reach this far past their edges, so abutting stairs, ramps and decks connect

/**
 * Walkable bridge, built like the shipped one. The DECK: two ramps hinged on the banks rise ~26 deg, each with a crown
 * panel bent back to ~8 deg, so the hero walks up, over and down a real surface (planks printed across it). The SIDES:
 * one card along each deck edge, foot on the page, top following the deck plus a parapet, an arch cut under the deck,
 * leaning ~18 deg OUTWARD (a trough: from along the bridge both printed faces still show, never a knife-thin sliver),
 * square newel-post returns at the ends, and (double) a second, lower, darker card outside for parapet thickness.
 * Folded, the deck lies flat on the water and the sides lie flat outward on the banks.
 * Frame: centre of the span on the page, span along local z (bank to bank), width along local x. The group animates
 * itself: put it on hinge(group, { flat: 0, rest: 0 }) (or call userData.follow(p)). userData.walk.heightAt(x, z).
 * paints: { deck, side } printers (defaults: planks; stone sides, or wood with style: 'wood').
 */
export function bridge({ span = 10, width = 3, rampAngle = 26, crownAngle = 8, rampShare = 0.55, railH = 1.1, lean = 18, arch = 0.6,
  posts = true, double = true, sides = 'full', style = 'stone', colors = {}, paper, paints = {}, seed = 1, thickness = 0.08, edgeWidth = 0.06, rimLight , print = {}} = {}) {
  // sides: 'full' = foot on the page with an arch cut (bank to bank over water); 'parapet' = a band from just under the
  // deck line up (between two raised decks over a gorge: parent the bridge to a deck frame or set its y to the deck
  // height; both ends at one height, stairs at one end otherwise)
  const g = new THREE.Group(), hs = span / 2, hw = width / 2, D2R = Math.PI / 180;
  const A1 = rampAngle * D2R, A2 = crownAngle * D2R;
  const L1 = rampShare * hs / Math.cos(A1), z1 = hs - L1 * Math.cos(A1), h1 = L1 * Math.sin(A1), L2 = z1 / Math.cos(A2) + thickness;
  const deckH = (u) => { u = Math.abs(u); return u >= hs ? 0 : u >= z1 ? (hs - u) * Math.tan(A1) : h1 + (z1 - u) * Math.tan(A2); };
  const base = { paper, thickness, edgeWidth, ...(rimLight ? { rimLight } : {}), pxPerUnit: print.pxPerUnit ?? 64, effects: print.effects ?? 1 };
  const wood = colors.wood || '#a8683a';
  const deckPaint = (n) => paints.deck || banded(wood, n);
  // the deck: a ramp on each bank, a crown panel nested on its top edge
  const ramps = [];
  for (const s of [1, -1]) {
    const F = new THREE.Group(); F.position.set(0, 0.012, s * hs); F.rotation.y = s > 0 ? 0 : Math.PI; // F's -z points at the centre
    const rp = new THREE.Group(); F.add(rp);
    rp.add(makeCard(P2shape.rect(width, L1), { ...base, seed: seed + (s > 0 ? 1 : 2), paint: deckPaint(Math.max(3, Math.round(L1 / 0.6))), folds: ['bottom', 'top'], back: 'kraft' }));
    const cp = new THREE.Group(); cp.position.set(0, L1, thickness * 0.6); rp.add(cp);
    cp.add(makeCard(P2shape.rect(width, L2), { ...base, seed: seed + (s > 0 ? 3 : 4), paint: deckPaint(Math.max(2, Math.round(L2 / 0.6))), folds: ['bottom'], back: 'kraft' }));
    g.add(F); ramps.push({ rp, cp });
  }
  // the sides: outline along the span (x = along local z), foot on the page, an arch under the deck, parapet on top
  const sideOutline = (top) => {
    const ext = hs * 1.06;
    if (sides === 'parapet') {
      const pts = [];
      for (let i = 0; i <= 24; i++) { const x = -ext + 2 * ext * i / 24; pts.push([x, Math.max(0, deckH(x) - 0.35)]); }
      for (let i = 0; i <= 24; i++) { const x = ext - 2 * ext * i / 24; pts.push([x, deckH(x) + top * (Math.abs(x) > hs ? 0.85 : 1)]); }
      return { pts, aw: 0.01, ah: 0.01 };
    }
    const pts = [[-ext, 0], [-hs * arch, 0]];
    const aw = hs * arch, ah = Math.max(0.1, deckH(0) * 0.72);
    for (let i = 1; i < 16; i++) { const a = Math.PI - Math.PI * i / 16; pts.push([Math.cos(a) * aw, Math.sin(a) * ah]); }
    pts.push([aw, 0], [ext, 0]);
    for (let i = 0; i <= 24; i++) { const x = ext - 2 * ext * i / 24; pts.push([x, deckH(x) + top * (Math.abs(x) > hs ? 0.85 : 1)]); }
    return { pts, aw, ah };
  };
  const sideCards = [];
  for (const sg of [1, -1]) {
    for (const outer of double ? [false, true] : [false]) {
      const F = new THREE.Group(); F.position.set(sg * (hw + 0.1 + (outer ? 0.28 : 0)), 0, 0); F.rotation.y = sg > 0 ? -Math.PI / 2 : Math.PI / 2; // +z inward
      const pv = new THREE.Group(); F.add(pv);
      const { pts, aw, ah } = sideOutline(outer ? railH * 0.62 : railH);
      const sideOf = (dh, archSpec) => (typeof paints.side === 'function' && paints.side.length >= 2 ? paints.side(dh, archSpec) : paints.side) || sideNeutral(style === 'wood' ? wood : colors.stone || '#c2ab8a', dh);
      const paint = sideOf(deckH, { w: aw * 2, h: ah });
      const card = makeCard(pts, { ...base, seed: seed + 10 + (sg > 0 ? 0 : 5) + (outer ? 2 : 0), paint, back: 'mirror', folds: ['bottom'] });
      if (outer) for (const m of card.material) m.color.multiplyScalar(0.8);
      pv.add(card);
      if (posts && !outer) for (const ex of [-hs * 1.06, hs * 1.06]) {   // newel-post returns at both ends, facing along the span
        const ph = railH * 1.25, post = makeCard(P2shape.rect(0.5, ph), { ...base, seed: seed + 30, paint: sideOf(() => -1, null), folds: ['bottom'] });
        const pg = new THREE.Group(); pg.position.set(ex, 0, -0.25); pg.rotation.y = ex > 0 ? Math.PI / 2 : -Math.PI / 2; pg.add(post); pv.add(pg);
      }
      g.add(F); sideCards.push({ pv, outer });
    }
  }
  g.userData.follow = (p) => {
    const kr = smooth01(0, 0.6, p), ks = smooth01(0.35, 1, p);
    for (const { rp, cp } of ramps) { rp.rotation.x = -Math.PI / 2 + A1 * kr; cp.rotation.x = -(A1 - A2) * kr; }
    for (const { pv } of sideCards) pv.rotation.x = -Math.PI / 2 + 0.01 + (Math.PI / 2 - 0.01 - lean * D2R) * ks; // flat outward -> leaning out
  };
  g.userData.follow(1);
  g.userData.walk = { heightAt: (x, z) => (Math.abs(x) <= hw - 0.12 && Math.abs(z) <= hs + EDGE ? deckH(z) + thickness : null), halfSpan: hs, halfWidth: hw, deckH };
  return g;
}

/**
 * Raised BUILT masonry you can walk onto (a quay wall, a stage, a plinth, a rooftop): a box fold. Never natural
 * terrain: hills, plateaus, mesas, ledges, crater rims and islands stay flat ground (height from nested hill cards
 * and rock outcrops, references/mechanisms.md section 8); where the hero climbs, build it open (a deck on posts, stairs
 * on stringers). A printed face on the page hinge, a printed top deck that stays LEVEL (walkable at height h), two side cheeks
 * hinged on the face's side edges that swing out as it rises (from any angle it reads as a solid block, never a
 * standing flat) and a back support. deckPts: a custom top outline (lobed edges; keep the front fold straight on
 * y = 0 and the back on y = d). Frame: the face's base line on the page (x across, z toward the camera); the deck
 * spans z 0..-d; userData.deck = a frame on its top surface for pieces standing on it (each on its own hinge).
 * Put it on a regular hinge(). Get up with stairs() or ramp() against a face, or let the path rise onto a low edge.
 * paints: { face, deck, side, back } (defaults: neutral card in colors.stone / colors.grass); facePts: a custom face
 * outline (a stage apron, a quay with a stair cut; base on y = 0, top fold on y = h).
 */
export function terrace({ w = 8, d = 6, h = 2, colors = {}, paper, paints = {}, seed = 1, thickness = 0.08, edgeWidth = 0.06, deckPts = null, facePts = null, rimLight, cheeks = true, relief = 2, gap = null , print = {}} = {}) {
  // relief: torn-top flats stepped in front of the face (each lower, nearer and darker), the references' banks and
  // hills: the raised ground reads as layered paper, never a solid block. gap: [x0, x1] left open for stairs or a ramp.
  const facePaint = paints.face || neutral(colors.stone || '#b7a386');   // masonry, not earth
  const g = platform({ w, d, h, paper, seed, thickness, edgeWidth, deckPts, facePts, rimLight, print,
    paints: { face: facePaint, deck: paints.deck || neutral(colors.grass || '#a9a86a'), support: paints.back || facePaint } });
  const base = { paper, thickness, edgeWidth, back: 'kraft', ...(rimLight ? { rimLight } : {}), pxPerUnit: print.pxPerUnit ?? 64, effects: print.effects ?? 1 };
  const cks = [];
  if (cheeks) for (const side of [1, -1]) {
    const pts = P2shape.rect(d, h).map(([x, y]) => [(x + d / 2) * side, y]), ordered = side > 0 ? pts : pts.slice().reverse();
    const m = makeCard(ordered, { ...base, seed: seed + (side > 0 ? 7 : 8), paint: paints.side || facePaint,
      folds: (x0, y0, x1, y1) => (Math.abs(x0) < 1e-6 && Math.abs(x1) < 1e-6) || (Math.abs(y0) < 1e-6 && Math.abs(y1) < 1e-6) });
    const pv = new THREE.Group(); pv.position.set(side * w / 2, 0, -thickness); pv.add(m); g.add(pv); cks.push([pv, side]);
  }
  const reliefD = 0.24 * relief + 0.12, rr = rng(seed + 97), reliefH = (k) => h * (0.8 - 0.24 * (k - 1));
  for (let k = 1; k <= relief; k++) {
    const rh = reliefH(k), segs = gap ? [[-w / 2, gap[0]], [gap[1], w / 2]] : [[-w / 2, w / 2]];
    for (const [a, b] of segs) {
      if (b - a < 0.6) continue;
      const pts = [[a, 0], [b, 0]];
      for (let i = 0; i <= 18; i++) { const x = b - (b - a) * i / 18, edge = i === 0 || i === 18; pts.push([x, rh * (edge ? 0.86 : 0.9 + rr() * 0.1)]); }
      const shadeK = 0.1 * k, facePrint = paints.relief || facePaint;
      const m = makeCard(pts, { ...base, seed: seed + 40 + k, paint: (P) => { facePrint(P); P.draw((gg) => { gg.fillStyle = `rgba(40,20,8,${shadeK})`; gg.fillRect(P.box.x0 - 1, P.box.y0 - 1, P.box.w + 2, P.box.h + 2); }); }, folds: ['bottom'] });
      m.position.z = 0.24 * k; g.add(m);
    }
  }
  const pf = g.userData.follow;
  g.userData.follow = (p) => {
    pf(p);
    const k = smooth01(0.1, 0.7, p ?? 1);
    for (const [pv, side] of cks) pv.rotation.y = side * (Math.PI - (Math.PI / 2) * k);
  };
  g.userData.follow(1);
  // the deck, measured on its LEVEL frame (the face may lean on its hinge; the deck never does), reaching EDGE past its
  // edges; the relief strip in front of the face is a wall (its height), except in the gap left for stairs
  g.userData.walk = { frame: g.userData.deck, heightAt: (x, z) => {
    if (Math.abs(x) <= w / 2 + EDGE && z <= EDGE && z >= -d - EDGE) return 0;
    if (relief && Math.abs(x) <= w / 2 && z > EDGE && z <= reliefD && !(gap && x > gap[0] && x < gap[1])) return 50;   // a wall: never a step up or down
    return null;
  }, w, d, h };
  return g;
}

/**
 * Stairs: thin plank treads on two stepped stringers (the side boards), open or with thin risers, never stacked
 * blocks (blocks read as terrain, round 8 critic). Climbs away from the front edge (local -z): tread k is (k + 1) * rise
 * high and `run` deep; keep rise <= ~1/8 of the hero's height (0.45 for a 3.6 hero) and the stair at least 2x his feet
 * wide. It rises as one piece on its hinge (popup without `self`). userData.walk.heightAt(x, z) = the tread height.
 * paints: { tread, stringer, riser } (default: neutral wood); risers: false leaves them open.
 */
export function stairs({ steps = 4, rise = 0.45, run = 1.1, w = 2.6, colors = {}, paper, paints = {}, seed = 1, thickness = 0.06, edgeWidth = 0.05, rimLight, risers = true, print = {} } = {}) {
  const g = new THREE.Group(), wood = colors.wood || '#a8683a';
  const base = { paper, thickness, edgeWidth, ...(rimLight ? { rimLight } : {}), pxPerUnit: print.pxPerUnit ?? 64, effects: print.effects ?? 1 };
  const L = steps * run, H = steps * rise;
  // the stringers: stepped side boards standing on the page along the run, in the YZ plane at x = +-w/2
  const stringerPts = [[0, 0], [0, rise]];
  for (let k = 0; k < steps; k++) { stringerPts.push([(k + 1) * run, (k + 1) * rise]); if (k < steps - 1) stringerPts.push([(k + 1) * run, (k + 2) * rise]); }
  stringerPts.push([L, 0]);
  for (const side of [-1, 1]) {
    const m = makeCard(side > 0 ? stringerPts : stringerPts.map(([x, y]) => [-x, y]).reverse(), { ...base, seed: seed + (side > 0 ? 1 : 2), paint: paints.stringer || banded(shade(wood, -0.08), 2, true), folds: ['bottom'] });
    m.rotation.y = side * Math.PI / 2; m.position.x = side * (w / 2 + thickness / 2); g.add(m);
  }
  // the treads: plank cards lying level on the stringers, a little nosing over the riser below; thin risers under them
  for (let k = 0; k < steps; k++) {
    const t = makeCard(P2shape.rect(w + 0.08, run + 0.12), { ...base, seed: seed + 10 + k, paint: paints.tread || banded(wood, Math.max(3, Math.round(w / 0.4)), true), folds: [] });
    t.rotation.x = -Math.PI / 2; t.position.set(0, (k + 1) * rise, -k * run + 0.1); g.add(t);
    if (risers) {
      const rr = makeCard(P2shape.rect(w - 0.04, rise), { ...base, seed: seed + 30 + k, paint: paints.riser || neutral(shade(wood, -0.18)), folds: ['bottom'] });
      rr.position.set(0, k * rise, -k * run - 0.02); g.add(rr);
    }
  }
  g.userData.walk = { heightAt: (x, z) => { if (Math.abs(x) > w / 2 - 0.1 || z > EDGE || z < -L - EDGE) return null; const k = Math.max(0, Math.min(steps - 1, Math.floor(-z / run))); return (k + 1) * rise + thickness / 2; }, steps, rise, run, w };
  return g;
}

/**
 * Ramp up to raised ground (a lean-out deck with wedge cheeks so it reads solid): rises from the page at z = 0 to
 * height h at z = -run. Animates itself: hinge(group, { flat: 0, rest: 0 }). userData.walk.heightAt(x, z).
 */
export function ramp({ h = 2, run = 4, w = 3, colors = {}, paper, paints = {}, seed = 1, thickness = 0.08, edgeWidth = 0.06, rimLight , print = {}} = {}) {
  const g = new THREE.Group(), a = Math.atan2(h, run), L = Math.hypot(h, run) + thickness * 2;
  const base = { paper, thickness, edgeWidth, ...(rimLight ? { rimLight } : {}), pxPerUnit: print.pxPerUnit ?? 64, effects: print.effects ?? 1 };
  const dp = new THREE.Group(); dp.position.y = 0.012; g.add(dp);
  dp.add(makeCard(P2shape.rect(w, L), { ...base, seed, paint: paints.deck || banded(colors.wood || '#a8683a', Math.max(3, Math.round(L / 0.6))), folds: ['bottom'], back: 'kraft' }));
  const cks = [];
  for (const side of [1, -1]) {
    const tri = [[0, 0], [run, 0], [run, h]].map(([x, y]) => [x * side, y]), ordered = side > 0 ? tri : tri.slice().reverse();
    const F = new THREE.Group(); F.position.set(side * (w / 2 - thickness), 0, 0); F.rotation.y = Math.PI / 2; // card x -> world -z
    const pv = new THREE.Group(); F.add(pv);
    pv.add(makeCard(ordered.map(([x, y]) => [x * side, y]), { ...base, seed: seed + (side > 0 ? 3 : 4), paint: paints.side || neutral(colors.earth || '#b07a4a'), folds: ['bottom'], back: 'mirror' }));
    g.add(F); cks.push([pv, side]);
  }
  g.userData.follow = (p) => {
    const k = smooth01(0, 0.7, p), kc = smooth01(0.3, 1, p);
    dp.rotation.x = -Math.PI / 2 + a * k;
    for (const [pv, side] of cks) pv.rotation.x = side * (Math.PI / 2 - 0.01) * (1 - kc);
  };
  g.userData.follow(1);
  g.userData.walk = { heightAt: (x, z) => (Math.abs(x) <= w / 2 - 0.1 && z <= 0 && z >= -run - EDGE ? h * Math.min(1, -z / run) + thickness : null), h, run, w };
  return g;
}
