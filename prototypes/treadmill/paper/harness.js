// papercraft / templates / popup-3d / harness.js
// The FIXED harness of a pop-up world seen from inside: renderer, camera, paper, the printed-ground sheet, placement of
// pieces on hinges (popup), light and moods, the macro lens, the flat-stack choreography, input, walking over the walk
// field, and the capture hook. Keep it as it is; write your world in your own scene.js against the API below (the
// bundled scene.js is the countryside worked example). URL switches: ?capture (no hint), ?test (your scene's test
// cards), ?mood=golden|dusk|night, ?walk (WASD / arrows; createHarness({ play: true }) makes it the default), ?still
// (no walking: the fixed overview), ?roam (full prints everywhere), ?diag=key (the key alone), ?ss=2 (2x supersampled
// review captures), ?size=1080x1920 (render exactly that many pixels, scaled to fit the window: a video or still
// format), ?aspect=9:16 (the largest frame of that shape in the window), ?res=1.5 (print resolution, default from the
// frame), ?closer=1.3 (a frame taller than wide comes closer), ?safe=all|meta|tiktok|shorts (outline a vertical
// platform's UI-safe area and its feed crops over the frame).
// Formats, safe areas and what scales with them: references/formats.md.
// In your own project: copy assets/lib/ (and assets/examples/ only if you import one) and this file, keep it as it is,
// and map "papercraft/" in your page's import map to the folder that holds lib/ (the template's index.html maps it to ../../).
import * as THREE from 'three';
import * as P2 from 'papercraft/lib/paperkit-2d.js';
import { paperTextures, paperMaterial, makeCard, crossCard, WalkField, fitShadow, hinge, paperLights, enableSoftShadows, PaperPost, PAPER_LIGHT, setPrintScale, cardClips } from 'papercraft/lib/paperkit-3d.js';
import { PaperSound } from 'papercraft/lib/paper-sound.js';
import { windSway } from 'papercraft/lib/paper-motion.js';

/** Measured light sets of the example world. The prints never change: only light, shadow tint, haze and grade. Night
 *  keeps every piece readable in its own printed colours under an even pale-blue moon (warm windows and lamp pools
 *  carry the warmth), navy only far away and in the deep shadows. Their haze, vignette and shadow-tint colours are the
 *  example's distance: give your world its own (references/three-js.md section 1). */
export const MOODS = {
  golden: { key: '#ffc890', ki: 3.4, dir: [-0.85, 0.52, 0.5], fill: '#b9bcd8', fi: 0.8, sky: '#ffe6c8', skyG: '#8a7a70', si: 0.4, leak: '#8f5034',
    post: { exposure: 0.86, tint: [1.02, 0.99, 0.98], saturation: 1.0, haze: '#e6c08a' } },
  dusk: { key: '#ffb38a', ki: 3.2, dir: [-0.866, 0.45, 0.5], fill: '#6f63a8', fi: 1.3, sky: '#a898d0', skyG: '#5a4a78', si: 1.0, leak: '#6a4a8a', glow: 1.1, lamp: 6,
    post: { exposure: 1.0, tint: [1.02, 0.97, 1.0], saturation: 0.95, haze: '#c98fa0', hazeStart: 24, hazeEnd: 80, hazeAmount: 0.4, vignette: 0.12, vignetteColor: '#1a1430', shadowTint: '#1e2050' } },
  night: { key: '#c8d6ff', ki: 1.6, fill: '#7890d0', fi: 0.7, sky: '#3a56b0', skyG: '#22243a', si: 0.85, leak: '#26368a', wrap: 0.4, glow: 1.4, lamp: 4.5,
    post: { exposure: 0.95, tint: [0.98, 0.98, 1.03], saturation: 1.12, haze: '#0e1a44', hazeStart: 14, hazeEnd: 55, hazeAmount: 0.9, vignette: 0.12, vignetteColor: '#0a0c24', shadowTint: '#141c48', shadowLift: 0.04 } },
};

/**
 * createHarness(options) -> H. Options (all optional): bounds { x0, x1, z0, z1 } of the printed ground sheet in world
 * units (1 unit ~ 1 cm of card; the hero ~3.6), ground (your world's plain ground colour under and beyond the print:
 * set it), stock (the paper colour multiplied under every print and the ground; default cream; a cold world takes a
 * cooler one, ~'#eef1f6', look.md section 8b), view { pitch, dist,
 * target, fov, follow: [dx, dz] } (follow: where the walking camera looks, relative to the hero, x dist / 66: the
 * default [-9.3, -9] keeps him in the lower right, so lay walks out going up and left), testView { dist, target }
 * (?test), heroHeight, light (paperLights options + leak, softness, maxPenumbra), lens (PaperPost options), walk
 * { step, radius }, moods (light sets by name), play (true: WASD / arrows walk the hero without ?walk; ?still turns
 * it off), focus ([x, y, z]: where the lens focuses until the hero first moves, e.g. a landmark he starts in front of;
 * default the hero; it eases to him on his first step), mood ('golden' | 'dusk' | 'night' | a key of moods: the world's
 * own light; ?mood= overrides it), walkArea { x0, x1, z0, z1 } (where he may walk: the walking camera goes only as far
 * as framing him there needs, and inReach(x, z) says what any of those framings can show: fill with it), camBox
 * { x0, x1, z0, z1 } (hold the camera's target in your own box instead: a single diorama the camera barely leaves),
 * size [w, h] / aspect (number or 'w:h') (the output frame, as ?size / ?aspect), subject [x, z] (what a frame narrower
 * than 16:9 swings toward so it stays in view: the hero's start or the landmark; default focus), rotate (true: also
 * fill the frame turned 90 degrees, for a phone page that may be rotated). Author the view for 16:9: another shape
 * keeps the lens on its short side (view.closer brings a tall frame closer: formats.md). H.frame { w, h, aspect, fovY,
 * wide, tall, lat, mag, printScale } describes it.
 * H.onStep(fn): fn(time, dt) every fixed step (fireflies, flicker, a turning wheel): animate on the harness clock.
 * H.onRestart(fn): fn() on restart(), so seeded extras replay too (paper-life: H.onRestart(() => life.reset(H.target))).
 * The hero is a page-level popup (no parent). Anywhere he stands, on the page or on a deck, he rides whatever is under
 * him as the world rises, until he first moves. The camera follows him only once he has moved.
 * H: THREE, flags { TEST, MOOD, WALK, ROAM, DIAG }, renderer, scene, camera, paper, view, target, walk (WalkField),
 * hinges, spots (your named places, for captures), printGround(draw), popup(pts, paint, opts), printed(pts, paint) (one
 * print stamped on many cards: popup's `materials`), stack, dome, cardOpts, groundAt(sx, sy), onStep, onRestart,
 * inFrame(x, z) (the overview), inReach(x, z) (anything the walking camera can show: fill with this),
 * sharpAt(x, z), lamp(x, y, z, k), glowing(group), setHero(handle, { stride, turn }), start(). The hero is a 3D paper
 * model facing +z: walking accelerates at a constant rate, the HEADING turns smoothly toward the way he goes (the whole
 * model yaws: it reads from every side), he leans forward on a springy settle and rolls into turns. stride(walked,
 * gait) is called every frame with the distance walked and the walking amount (0 standing .. 1 striding): swing the
 * limbs there. turn: 'yaw' (default) or 'flip' (a flat card that mirrors to face left / right: critters, extras).
 */
export function createHarness({ bounds = { x0: -64, x1: 64, z0: -62, z1: 38 }, ground = '#d9d2c2', view: view0 = {}, testView = { dist: 38, target: [3, 1.5, -6] },
  heroHeight = 3.6, light = {}, lens = {}, walk: walkOpts = { step: 0.75, radius: 0.6 }, moods = MOODS, play = false, focus = null, mood = null, stock = '#f7f0e2', walkArea = null, camBox = null,
  size = null, aspect = null, subject = null, rotate = false } = {}) {
  const T0 = performance.now();
  // print costs by the scene line that asked for them: __papercraft.printStats() (and a console line when the build is
  // slow) says which pieces to print smaller (popup res), share (one print, many cards) or cut
  const printLog = new Map(); let printN = 0, printMs = 0, printPx = 0;
  const callSite = () => {
    const lim = Error.stackTraceLimit; Error.stackTraceLimit = 30; const st = new Error().stack || ''; Error.stackTraceLimit = lim;
    const own = [];
    for (const ln of st.split('\n').slice(1)) {
      if (/\/lib\/|\/examples\/|harness\.js|<anonymous>/.test(ln)) continue;          // the kit itself: not the scene's line
      const m = ln.match(/at (?:(.*?) \()?([^()\s]*?):(\d+):\d+\)?\s*$/);
      if (m) own.push({ fn: m[1] ? m[1].replace(/^Object\./, '') : '', at: m[2].split('/').pop().split('?')[0] + ':' + m[3] });
      if (own.length === 2) break;
    }
    const f = own.length > 1 && /^(popup|place|put|add)$/.test(own[0].fn) ? own[1] : own[0];   // past a thin popup wrapper
    return f ? (f.fn ? f.fn + ' ' : '') + f.at : 'other';
  };
  P2.onPrint((ms, w, h) => {
    const k = callSite(), e = printLog.get(k) || { at: k, prints: 0, ms: 0, px: 0 };
    e.prints++; e.ms += ms; e.px += w * h; printLog.set(k, e); printN++; printMs += ms; printPx += w * h;
  });
  const Q = new URLSearchParams(location.search);
  const flags = { TEST: Q.has('test'), MOOD: Q.get('mood') || mood || 'day', WALK: Q.has('walk') || (play && !Q.has('still') && !Q.has('test')), DIAG: Q.get('diag') };
  flags.ROAM = flags.WALK || Q.has('roam');            // the camera can go anywhere: every row prints at full quality
  if (Q.has('capture')) document.getElementById('hint')?.remove();

  // ---- renderer, the output frame, camera, paper ------------------------------------------------------------------
  const renderer = new THREE.WebGLRenderer({ antialias: false, powerPreference: 'high-performance' });
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.BasicShadowMap;     // the PCSS patch reads raw depth
  document.body.appendChild(renderer.domElement);
  const scene = new THREE.Scene();
  scene.background = new THREE.Color(ground);
  const view = { yaw: 0, pitch: 47, dist: 66, fov: 26, ...view0 };
  view.tyaw = view.yaw; view.tpitch = view.pitch;
  if (flags.TEST) view.dist = testView.dist;          // close enough to judge edges, fibre, shadows, blur
  if (Q.get('closer')) view.closer = Number(Q.get('closer'));   // ?closer=1.3: a tall frame comes closer (below)
  // The output frame. ?size=WxH (or size): exactly that many pixels (a video or still format, references/formats.md),
  // scaled to fit the window; ?aspect=W:H (or aspect): the largest frame of that shape in the window; neither: the
  // window. ?ss=2 supersamples (renders at 2x and lets the browser downsample): review captures at the shipped frames'
  // edge smoothness; the live view on a retina screen gets the same from its pixel ratio.
  const SS = Math.max(1, Number(Q.get('ss')) || 1);
  const pair = (s, re) => { const m = re.exec(String(s ?? '')); return m ? [Number(m[1]), Number(m[2])] : null; };
  const SIZE = pair(Q.get('size'), /^(\d+)x(\d+)$/i) || size;
  const ASPECT = (() => { const p = pair(Q.get('aspect') ?? aspect, /^([\d.]+)\s*[:x/]\s*([\d.]+)$/i); return p ? p[0] / p[1] : typeof aspect === 'number' ? aspect : null; })();
  function frameBox() {   // drawing size (CSS px; times pr = device px), its size on screen (cw x ch) and pixel ratio
    if (SIZE) { const k = Math.min(1, innerWidth / SIZE[0], innerHeight / SIZE[1]); return { w: SIZE[0], h: SIZE[1], cw: SIZE[0] * k, ch: SIZE[1] * k, pr: SS }; }
    const pr = Math.min(3, Math.min(devicePixelRatio, 2) * SS);
    if (ASPECT) { const cw = Math.min(innerWidth, innerHeight * ASPECT), ch = cw / ASPECT; return { w: Math.round(cw), h: Math.round(ch), cw, ch, pr }; }
    return { w: innerWidth, h: innerHeight, cw: innerWidth, ch: innerHeight, pr };
  }
  // The view is authored for 16:9. Another shape keeps the lens on its SHORT side: a 9:16 frame is the 16:9 camera
  // turned upright (the hero keeps his size against the frame's width; it sees more of the world above and below), a
  // square one is the 16:9 frame without its sides. view.closer brings a frame taller than wide closer, as a crop from
  // the same camera: at 9:16 by that factor (1 = the camera turned upright, it shows the most; default 1.33, so the
  // hero reads on a phone; 1.78 = the 16:9 frame's own height, a close-up), less as the frame nears square. wide /
  // tall: its extent against the 16:9 frame (tangent space); lat: how far a subject beside the target stays beside it
  // (a narrow frame swings toward the subject); mag: how much bigger things look than at 16:9.
  const REF = 16 / 9, rad = THREE.MathUtils.degToRad;
  const lensFor = (a, closer = view.closer ?? 1.33) => {
    const z = a < 1 ? Math.min(1 / a, Math.max(1, closer) ** (Math.log(1 / a) / Math.log(REF))) : 1;
    const ty0 = Math.tan(rad(view.fov / 2)), ty = a >= 1 ? ty0 : ty0 / a / z, wide = ty * a / (ty0 * REF);
    return { aspect: a, fovY: THREE.MathUtils.radToDeg(2 * Math.atan(ty)), wide, tall: ty / ty0, lat: Math.min(1, a / REF) * Math.min(1, wide), mag: z };
  };
  const box0 = frameBox();
  let LENS = lensFor(box0.w / box0.h);
  const FOLLOW = view.follow || [-9.3, -9];
  const placeOn = (cam, t, yawDeg, pitchDeg) => {
    const yaw = rad(yawDeg), pit = rad(pitchDeg);
    cam.position.set(t.x + Math.sin(yaw) * Math.cos(pit) * view.dist, t.y + Math.sin(pit) * view.dist, t.z + Math.cos(yaw) * Math.cos(pit) * view.dist);
    cam.lookAt(t); cam.updateMatrixWorld();
  };
  const subj = subject || (focus ? [focus[0], focus[2]] : null);   // [x, z] (or [x, y, z])
  const yawR = () => { const yaw = rad(view.yaw); return [Math.cos(yaw), -Math.sin(yaw)]; };   // the camera's right on the page
  const _aim = new THREE.PerspectiveCamera(), _p = new THREE.Vector3();
  // How far to move a target along the page, away from the camera, so the point s sits at screen height y (-1 bottom
  // .. 1 top) in a frame of lens L (a few secant steps on the projection).
  function liftFor(L, s, t, y) {
    _aim.fov = L.fovY; _aim.aspect = L.aspect; _aim.updateProjectionMatrix();
    const yaw = rad(view.yaw), fx = -Math.sin(yaw), fz = -Math.cos(yaw), t2 = new THREE.Vector3();
    const f = (d) => { placeOn(_aim, t2.set(t.x + fx * d, t.y, t.z + fz * d), view.yaw, view.pitch); return _p.copy(s).project(_aim).y - y; };
    let d0 = 0, d1 = 2, f0 = f(d0), f1 = f(d1);
    for (let i = 0; i < 6 && Math.abs(f1) > 1e-4 && Math.abs(f1 - f0) > 1e-9; i++) { const d2 = d1 - f1 * (d1 - d0) / (f1 - f0); d0 = d1; f0 = f1; d1 = d2; f1 = f(d1); }
    return d1;
  }
  // A frame taller than wide lifts the subject toward ~42 % from the top (view.subjectY, -1 bottom .. 1 top; default
  // 0.15 at 9:16, less as the frame nears square), clear of the caption and buttons a vertical platform lays over the
  // lower third (formats.md: safe areas).
  const liftY = (L, y0) => { const w = Math.min(1, (1 - L.aspect) / (1 - 9 / 16)); return view.subjectY ?? (y0 + (0.15 - y0) * w); };
  const lifted = (L, s, t) => {   // t moved along the page so s sits at its lifted height in a frame of lens L
    _aim.fov = L.fovY; _aim.aspect = L.aspect; _aim.updateProjectionMatrix(); placeOn(_aim, t, view.yaw, view.pitch);
    const d = liftFor(L, s, t, liftY(L, _p.copy(s).project(_aim).y)), yaw = rad(view.yaw);
    return t.set(t.x - Math.sin(yaw) * d, t.y, t.z - Math.cos(yaw) * d);
  };
  function aimed(L) {    // the authored target, swung toward the subject as far as this frame shape needs
    const t = new THREE.Vector3(...(flags.TEST ? testView.target : (view0.target || [2, 1.5, -6])));
    if (!subj || flags.TEST) return t;
    const [rx, rz] = yawR(), side = (subj[0] - t.x) * rx + (subj[subj.length - 1] - t.z) * rz, k = side * (1 - L.lat);
    t.set(t.x + rx * k, t.y, t.z + rz * k);
    return L.aspect < 1 ? lifted(L, new THREE.Vector3(subj[0], subj.length > 2 ? subj[1] : 1.8, subj[subj.length - 1]), t) : t;
  }
  // the walking camera's offset from the hero for lens L: sideways it shrinks with lat; a tall frame lifts him the way
  // it lifts the subject
  function followFor(L) {
    const kd = view.dist / 66, fo = [FOLLOW[0] * L.lat, FOLLOW[1]];
    if (L.aspect >= 1) return fo;
    const t = lifted(L, new THREE.Vector3(0, 1.8, 0), new THREE.Vector3(fo[0] * kd, 1.5, fo[1] * kd));
    return [t.x / kd, t.z / kd];
  }
  const target = aimed(LENS);
  const camera = new THREE.PerspectiveCamera(LENS.fovY, LENS.aspect, 5, 600);
  // Print resolution: the kit's prints are tuned for a 1080 px short side at the template's distance and lens (~35 px
  // per world unit at the target). A bigger output (?size: a 4K frame prints 2x; a tall frame brought closer by its
  // mag) or a closer camera prints finer and builds slower; the live window keeps 1x for its size. ?res= sets it.
  const PRINT = Number(Q.get('res')) || Math.min(2.5, Math.max(1, (SIZE ? LENS.mag * Math.min(...SIZE) : 1080) / (2 * view.dist * Math.tan(rad(view.fov / 2))) / 35.4));
  setPrintScale(PRINT);
  const frame = { w: box0.w, h: box0.h, aspect: LENS.aspect, fovY: LENS.fovY, wide: LENS.wide, tall: LENS.tall, lat: LENS.lat, mag: LENS.mag, printScale: PRINT, size: SIZE };
  // the paper stock under every print and the ground: warm cream by default; a cold world (snow, ice, a pale station)
  // takes a cooler stock (~'#eef1f6'), or the multiplied cream warms its whites to beige
  const paper = paperTextures(renderer, { color: stock, seed: 4, unitsPerTile: 5, fibres: 1.5, mottle: 0.08, normalStrength: 3 });
  function placeCamera(yawDeg, pitchDeg) { placeOn(camera, target, yawDeg, pitchDeg); }
  placeCamera(view.yaw, view.pitch);
  // rotate: a phone page may be turned, so the build also fills the frame turned 90 degrees (its own lens and aim)
  const cam2 = rotate && !SIZE && !ASPECT ? (() => { const L2 = lensFor(1 / LENS.aspect), c = new THREE.PerspectiveCamera(L2.fovY, L2.aspect, 5, 600); placeOn(c, aimed(L2), view.yaw, view.pitch); return c; })() : null;
  const _v = new THREE.Vector3();
  /** The point on the page (y = 0) under screen position (sx, sy) in -1..1 (sy = 1 the top edge), in the overview
   *  camera: e.g. groundAt(0, 0.95) is where the far edge of the frame meets the page (place backdrops just inside it). */
  const _ray = new THREE.Raycaster(), _pl = new THREE.Plane(new THREE.Vector3(0, 1, 0), 0);
  const hitOn = (cam, sx, sy) => { _ray.setFromCamera({ x: sx, y: sy }, cam); const p = new THREE.Vector3(); return _ray.ray.intersectPlane(_pl, p) ? p : null; };
  const groundAt = (sx, sy) => { camera.updateMatrixWorld(); return hitOn(camera, sx, sy); };
  /** Is (x, z) in the frame (with margin m, at height y)? Fill only where the camera can see. */
  const inCam = (cam, x, z, m, y) => { _v.set(x, y, z).project(cam); return Math.abs(_v.x) < 1 + 2 * m && _v.y > -1 - m && _v.y < 1 + m; };
  const inFrame = (x, z, m = 0.12, y = 1) => inCam(camera, x, z, m, y) || (cam2 !== null && inCam(cam2, x, z, m, y));
  /** Inside the lens's sharp-to-mid band at the starting view (the far top rows and the big near cards are blurred);
   *  measured against the 16:9 frame's height, so a tall frame prints its extra rows above and below coarser. */
  const sharpAt = (x, z, y = 1) => { _v.set(x, y, z).project(camera); const s = _v.y * LENS.tall; return s > -0.95 && s < 0.7; };
  // walkArea { x0, x1, z0, z1 }: where the hero may walk. The walking camera then goes only as far as framing him there
  // needs (its target is held inside), and inReach(x, z) says whether ANY of those framings can show (x, z): fill with
  // it, not inFrame, or the walk finds bare page past the overview. camBox { x0, x1, z0, z1 }: hold the camera's target
  // in a box of your own instead (a single diorama: a few units round the overview target, the hero walking inside the
  // frame). Without either, inReach is inFrame and nothing is held. A frame narrower than 16:9 keeps him nearer its
  // middle (the follow offset sideways shrinks with lat) and widens camBox sideways by the width it lost.
  const FO = followFor(LENS);
  const reachFor = (L, t) => { if (!camBox && !walkArea) return null; const kd = view.dist / 66, [fx, fz] = followFor(L).map((v) => v * kd);
    let R = camBox || { x0: walkArea.x0 + fx, x1: walkArea.x1 + fx, z0: walkArea.z0 + fz, z1: walkArea.z1 + fz };
    if (camBox && L.wide < 1) {
      const lost = view.dist * Math.tan(rad(view.fov / 2)) * REF * (1 - L.wide), [rx, rz] = yawR();
      R = { x0: R.x0 - Math.abs(rx) * lost, x1: R.x1 + Math.abs(rx) * lost, z0: R.z0 - Math.abs(rz) * lost, z1: R.z1 + Math.abs(rz) * lost };
    }
    return { x0: Math.min(R.x0, t.x), x1: Math.max(R.x1, t.x), z0: Math.min(R.z0, t.z), z1: Math.max(R.z1, t.z) }; };
  const reachR = reachFor(LENS, target);
  const inReach = (x, z, m = 0.12, y = 1) => {
    if (!reachR) return inFrame(x, z, m, y);
    const nx = Math.max(1, Math.ceil((reachR.x1 - reachR.x0) / 6)), nz = Math.max(1, Math.ceil((reachR.z1 - reachR.z0) / 6));
    for (let i = 0; i <= nx; i++) for (let j = 0; j <= nz; j++)   // the frame moved to each camera target the walk allows
      if (inFrame(x - (reachR.x0 + (reachR.x1 - reachR.x0) * i / nx - target.x), z - (reachR.z0 + (reachR.z1 - reachR.z0) * j / nz - target.z), m, y)) return true;
    return false;
  };
  // The printed sheet reaches past every edge of every frame shape from 9:16 to 2.39:1 the camera can take here, walking
  // included, so every format gets the same sheet (a tall frame sees further up the page); its washes and drifts keep
  // their density over the larger sheet.
  const AREA0 = (bounds.x1 - bounds.x0) * (bounds.z1 - bounds.z0);
  for (const a of [9 / 16, 16 / 9, 2.39]) {
    const L = lensFor(a, 1), t = aimed(L), c = new THREE.PerspectiveCamera(L.fovY, a, 5, 600); placeOn(c, t, view.yaw, view.pitch);
    const R = reachFor(L, t), offs = R ? [[R.x0 - t.x, R.z0 - t.z], [R.x1 - t.x, R.z1 - t.z]] : [[0, 0]];
    for (const [sx, sy] of [[-1, -1], [1, -1], [-1, 1], [1, 1], [0, 1]]) {
      const p = hitOn(c, sx * 1.1, sy * 1.1);
      if (!p || p.distanceTo(c.position) > 400) continue;   // the top edge near the horizon: the haze hides that far
      for (const [ox, oz] of offs) bounds = { x0: Math.min(bounds.x0, p.x + ox - 6), x1: Math.max(bounds.x1, p.x + ox + 6), z0: Math.min(bounds.z0, p.z + oz - 6), z1: Math.max(bounds.z1, p.z + oz + 6) };
    }
  }

  // ---- the printed ground: one continuous sheet, far bigger than the frame ----------------------------------------
  /**
   * printGround(draw, { ppu }): draw(t) paints the ground sheet with the tools t = { g (2D context, pixel units), W, H,
   * ppu, X(x), Z(z) (world -> sheet px), rnd, washes(colours) (three scales of watercolour blotches with wet edges),
   * torn(pts, fill, fringe) (a torn-paper region: never an airbrushed band), band(fx, half, z0, z1) (a ribbon along
   * x = fx(z) as a polygon for torn), ribbon(fx, w, colour, z0, z1, wobble), bandOf(pts, half) and line(pts, w, colour)
   * (the same along any polyline, e.g. P2.pathLine(ctrl).pts: across the view too), drifts(fn, { n, inside }) (marks gathered
   * in drifts: fn(px, py) at clustered sheet positions) }. Then the paper is multiplied in and the sheet laid down.
   */
  function printGround(draw, { ppu = 22, seed = 20 } = {}) {
    const G = bounds, gw = G.x1 - G.x0, gd = G.z1 - G.z0, W = Math.round(gw * ppu), H = Math.round(gd * ppu), rnd = P2.rng(seed);
    // the sheet prints finer with the frame (PRINT), inside browser canvas limits; draw works in ppu pixels regardless
    const k = Math.min(PRINT, 16000 / Math.max(W, H), Math.sqrt(1.2e8 / (W * H))), dens = gw * gd / AREA0;
    const c = document.createElement('canvas'); c.width = Math.round(W * k); c.height = Math.round(H * k);
    const g = c.getContext('2d');
    g.setTransform(k, 0, 0, k, 0, 0);
    const X = (x) => (x - G.x0) * ppu, Z = (z) => (z - G.z0) * ppu;
    g.fillStyle = ground; g.fillRect(0, 0, W, H);
    const washes = (cols, { scales = [[60, 180, 460, 0.3], [200, 50, 150, 0.2], [700, 10, 34, 0.12]] } = {}) => {
      for (const [n0, r0, r1, a] of scales) for (let i = 0, n = Math.round(n0 * dens); i < n; i++) {
        const x = rnd() * W, y = rnd() * H, r = r0 + rnd() * (r1 - r0), col = cols[Math.floor(rnd() * cols.length)];
        const gr = g.createRadialGradient(x, y, 0, x, y, r);
        gr.addColorStop(0, P2.rgba(col, a * 0.6)); gr.addColorStop(0.78, P2.rgba(col, a)); gr.addColorStop(0.9, P2.rgba(P2.shade(col, -0.15), a * 1.2)); gr.addColorStop(1, P2.rgba(col, 0));
        g.fillStyle = gr; g.fillRect(x - r, y - r, 2 * r, 2 * r);
      }
    };
    const torn = (pts, fill, fringe = 'rgba(255,248,230,0.7)') => {
      const out = [];
      for (let i = 0; i < pts.length; i++) {
        const [x0, z0] = pts[i], [x1, z1] = pts[(i + 1) % pts.length], L = Math.hypot(x1 - x0, z1 - z0), n = Math.max(1, Math.ceil(L / 0.18));
        for (let k = 0; k < n; k++) { const t = k / n, j = (rnd() - 0.5) * 0.12 + Math.sin((x0 + z0 + t * L) * 1.7) * 0.05; out.push([x0 + (x1 - x0) * t + j, z0 + (z1 - z0) * t + j]); }
      }
      g.beginPath(); out.forEach(([x, z], i) => (i ? g.lineTo(X(x), Z(z)) : g.moveTo(X(x), Z(z)))); g.closePath();
      g.fillStyle = fill; g.fill(); g.strokeStyle = fringe; g.lineWidth = 0.15 * ppu; g.stroke();   // the torn paper's white fibrous edge, ~2-4 px on screen
    };
    const band = (fx, half, z0 = G.z0, z1 = G.z1) => {
      const L = [], R = [];
      for (let z = z0; z <= z1; z += 0.4) { const w = half(z), dx = fx(z + 0.2) - fx(z - 0.2), n = Math.hypot(dx, 0.4); L.push([fx(z) - w * 0.4 / n, z + w * dx / n]); R.push([fx(z) + w * 0.4 / n, z - w * dx / n]); }
      return [...L, ...R.reverse()];
    };
    const ribbon = (fx, w, col, z0 = G.z0, z1 = G.z1, wob = 0) => {
      g.strokeStyle = col; g.lineWidth = w * ppu; g.lineCap = 'round'; g.lineJoin = 'round'; g.beginPath();
      for (let z = z0; z <= z1; z += 0.5) (z === z0 ? g.moveTo : g.lineTo).call(g, X(fx(z) + wob * (Math.sin(z * 1.1 + w) * 0.6 + Math.sin(z * 2.7) * 0.4)), Z(z));
      g.stroke();
    };
    // bandOf(pts, half): a polygon around a polyline [[x, z], ...] (e.g. P2.pathLine(ctrl).pts) for torn(): paths and
    // rivers that run in any direction, across the view too; line(pts, w, colour): a stroke along one
    const bandOf = (pts, half) => {
      const Lf = [], Rt = [];
      pts.forEach(([x, z], i) => {
        const [ax, az] = pts[Math.max(0, i - 1)], [bx, bz] = pts[Math.min(pts.length - 1, i + 1)], tx = bx - ax, tz = bz - az, n = Math.hypot(tx, tz) || 1;
        const w = typeof half === 'function' ? half(i / Math.max(1, pts.length - 1)) : half;
        Lf.push([x - tz / n * w, z + tx / n * w]); Rt.push([x + tz / n * w, z - tx / n * w]);
      });
      return [...Lf, ...Rt.reverse()];
    };
    // water(pts, opts): ANY water body printed into the page (references/structures.md, printed): a pond or a lake (a
    // closed outline), a river or a canal (bandOf(line, half)), a sea edge (a big outline past the frame), a pool or a
    // fountain basin (edge 'cut': a crisp built lip), a frozen pond (marks 'cracks'). Layers: a rim (pale torn bank or
    // the lip), the water, a deeper middle, marks (ripples along `flow`, or cracks), an optional foam line just inside
    // the shore. rimPts / deepPts override the rim and the deep part (a river: bandOf(line, half * 1.2) / half * 0.5).
    // Returns inside(x, z) for walk.block.
    // only when the brief has water; colors and rim are YOURS (the defaults are a plain grey-blue placeholder)
    const water = (pts, { colors = ['#8fa7ad', '#6d8a92'], rim = '#e8e2d4', rimPts = null, deepPts = null, edge = 'torn', inset = 0.62,
      marks = 'ripples', markColor = 'rgba(250,244,228,0.8)', flow = 0, foam = null, density = 1 } = {}) => {
      let cxw = 0, czw = 0; for (const [x, z] of pts) { cxw += x / pts.length; czw += z / pts.length; }
      const about = (k) => pts.map(([x, z]) => [cxw + (x - cxw) * k, czw + (z - czw) * k]);
      const inside = (x, z, poly = pts) => { let c = false; for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) { const [xi, zi] = poly[i], [xj, zj] = poly[j]; if ((zi > z) !== (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) c = !c; } return c; };
      const fill = (poly, col) => {
        if (edge === 'torn') { torn(poly, col); return; }
        g.fillStyle = col; g.beginPath(); poly.forEach(([x, z], i) => (i ? g.lineTo(X(x), Z(z)) : g.moveTo(X(x), Z(z)))); g.closePath(); g.fill();
        g.strokeStyle = 'rgba(40,28,18,0.35)'; g.lineWidth = 0.06 * ppu; g.stroke();   // a cut edge: a thin dark lip
      };
      if (rim) fill(rimPts || about(1.07), rim);
      fill(pts, colors[0]);
      if (colors[1]) fill(deepPts || about(inset), P2.rgba(colors[1], 0.55));
      let bx0 = Infinity, bx1 = -Infinity, bz0 = Infinity, bz1 = -Infinity;
      for (const [x, z] of pts) { bx0 = Math.min(bx0, x); bx1 = Math.max(bx1, x); bz0 = Math.min(bz0, z); bz1 = Math.max(bz1, z); }
      const area = (bx1 - bx0) * (bz1 - bz0), N = Math.round(area * 0.35 * density);
      g.lineCap = 'round';
      for (let k = 0; k < N && marks !== 'none'; k++) {
        const x = bx0 + rnd() * (bx1 - bx0), z = bz0 + rnd() * (bz1 - bz0);
        if (!inside(x, z) || (rim && !inside(x, z, about(0.9)) && !rimPts)) continue;
        g.strokeStyle = markColor; g.lineWidth = (marks === 'cracks' ? 0.05 : 0.11) * ppu; g.beginPath();
        if (marks === 'cracks') { let px = x, pz = z; g.moveTo(X(px), Z(pz)); for (let s = 0; s < 4; s++) { px += (rnd() - 0.5) * 1.6; pz += (rnd() - 0.5) * 1.6; g.lineTo(X(px), Z(pz)); } }
        else { const a = flow + (rnd() - 0.5) * 0.5, l = 0.5 + rnd() * 1.1; g.moveTo(X(x - Math.cos(a) * l / 2), Z(z - Math.sin(a) * l / 2)); g.lineTo(X(x + Math.cos(a) * l / 2), Z(z + Math.sin(a) * l / 2)); }
        g.stroke();
      }
      if (foam) {   // a broken pale line just inside the shore (a sea, a lake in wind)
        g.strokeStyle = foam; g.lineWidth = 0.14 * ppu; g.setLineDash([0.9 * ppu, 0.6 * ppu]); g.beginPath();
        about(0.96).forEach(([x, z], i) => (i ? g.lineTo(X(x), Z(z)) : g.moveTo(X(x), Z(z)))); g.closePath(); g.stroke(); g.setLineDash([]);
      }
      return (x, z) => inside(x, z);
    };
    const line = (pts, w, col) => { g.strokeStyle = col; g.lineWidth = w * ppu; g.lineCap = 'round'; g.lineJoin = 'round'; g.beginPath(); pts.forEach(([x, z], i) => (i ? g.lineTo(X(x), Z(z)) : g.moveTo(X(x), Z(z)))); g.stroke(); };
    const drifts = (fn, { n = 9000, inside = 0.5, outside = 0.005, threshold = 0.68 } = {}) => {   // sparse and clustered: an even sprinkle reads as a stamp
      const mask = P2.tileNoise(256, 9, rnd), at = (x, y) => mask[(Math.floor(y / H * 256) & 255) * 256 + (Math.floor(x / W * 256) & 255)];
      for (let k = 0, N = Math.round(n * dens); k < N; k++) { const x = rnd() * W, y = rnd() * H; if (rnd() > (at(x, y) > threshold ? inside : outside)) continue; fn(x, y); }
    };
    draw({ g, W, H, ppu, X, Z, rnd, washes, torn, band, ribbon, bandOf, line, drifts, water });
    g.globalCompositeOperation = 'multiply'; g.fillStyle = g.createPattern(paper.sheet, 'repeat'); g.fillRect(0, 0, W, H); // the paper shows through
    const tex = new THREE.CanvasTexture(c); tex.colorSpace = THREE.SRGBColorSpace; tex.anisotropy = renderer.capabilities.getMaxAnisotropy();
    const mat = paperMaterial({ map: tex, normalMap: paper.normalMap.clone(), normalScale: 0.9 });
    mat.normalMap.repeat.set((G.x1 - G.x0) / 5, (G.z1 - G.z0) / 5); mat.normalMap.needsUpdate = true;
    const sheet = new THREE.Mesh(new THREE.PlaneGeometry(G.x1 - G.x0, G.z1 - G.z0), mat);
    sheet.rotation.x = -Math.PI / 2; sheet.position.set((G.x0 + G.x1) / 2, 0, (G.z0 + G.z1) / 2); sheet.receiveShadow = true;
    const farMap = paper.map.clone(); farMap.repeat.set(160, 160); farMap.needsUpdate = true; // plain card beyond the print
    const beyond = new THREE.Mesh(new THREE.PlaneGeometry(800, 800), paperMaterial({ color: ground, map: farMap }));
    beyond.rotation.x = -Math.PI / 2; beyond.position.y = -0.02; beyond.receiveShadow = true;
    scene.add(sheet, beyond);
    return sheet;
  }

  // ---- placement: every piece is a printed card (or a construction) on a hinge -------------------------------------
  const hinges = [], walk = new WalkField(walkOpts), spots = {}, lamps = [], glowers = [];
  if (walkArea) walk.block((x, z) => x < walkArea.x0 || x > walkArea.x1 || z < walkArea.z0 || z > walkArea.z1);   // she stays where the world is built
  const Z_NEAR = bounds.z1 - 13;     // the nearest z the fill reaches: the top of the flat stack (order runs from here)
  const lean = P2.rng(7);            // its own stream: an edit in the scene never reshuffles the leans
  const turn = P2.rng(11);           // and the turns of pieces placed without a yaw
  let seedN = 1;
  // Pieces placed without a yaw turn as the source game turns them: a single card faces the camera (+-14 deg, seeded);
  // a slotted cross turns its main plane ~24 deg off facing the camera (the other plane ~66 deg off, either side), so
  // the front print reads in full, the cross shows its depth and neither plane is ever edge-on. Measured against the
  // line from the overview camera to the piece (perspective turns the view up to ~20 deg toward the frame edges).
  // A half cross turns like a full one (its half must never point straight at or away from the camera: edge-on it
  // reads as a spike); a quarter cross (L or V) turns its opening toward the camera (the apex away, as the source
  // game's dune folds do).
  const DEG = Math.PI / 180;
  const autoYaw = (x, z, kind, angle = 90) => {
    const phi = Math.atan2(camera.position.x - x, camera.position.z - z);
    if (!kind) return phi + (turn() - 0.5) * 2 * 14 * DEG;
    if (kind === 'quarter') return phi - (90 - angle / 2) * DEG + (turn() - 0.5) * 2 * 10 * DEG;
    return phi + (turn() < 0.5 ? -1 : 1) * (24 + (turn() - 0.5) * 16) * DEG;   // full, half-front
  };
  // popup's cross option: true / 'full' (+), 'half' (T: the half behind), 'half-front' (T: a buttress toward the viewer),
  // 'quarter' (L; angle > 90 opens it into a V-fold)
  const CROSS = { full: {}, half: { halves: 'back', second: { scale: 0.8 } }, 'half-front': { halves: 'front' }, quarter: { main: 'right', halves: 'front' } };
  /**
   * popup(pts, paint, opts) puts a printed card (or opts.node: a cross, a box fold, a construction) on a hinge.
   * opts: x, z, yaw (omit it: a card faces the camera +-14 deg, a cross stands with its main plane ~24 deg off the view,
   * as the source game turns them; a node or a piece on a deck keeps 0), cross (slotted planes of pts, mechanisms.md
   * section 5: true / 'full' a + (trees, palms, cacti, tents, posts), 'half' a T with the half behind (a boulder, an
   * igloo, a shrine), 'half-front' a T with a buttress toward the viewer, 'quarter' an L, and with angle > 90 a V-fold
   * (dunes, drifts, ridges, outcrops); second: { pts, paint, ymax } gives the second plane its own side view and print,
   * ymax cuts it low), angle (deg the halves open to, 90), sway (wind, 0-1), scale, node, height (of a node, for its spring),
   * edge (cut-edge width), holes (outlines cut through the card: a door, windows, gaps), self (a construction that moves
   * its own parts on a flat-0 hinge: bridge, ramp; stairs rise as one piece without it), level (no hand-placed lean: walkable boxes), effects (force full prints: 1, flat: 0),
   * solid (footprint radius for the walk field, in card units: it is multiplied by scale; ~0.3-0.45 for a prop),
   * parent (a deck frame to stand on). On a parent, x and z are in the DECK's frame (x across, z toward its front),
   * yaw is relative to the deck (subtract the deck's yaw to face the camera), pass `order` yourself (the default comes
   * from z), give a solid its world position (wx, wz) and its height (base), and the piece always prints in full.
   * order: rise order (default from depth). res: print resolution x (1 = 40 px a unit; 0.6-0.75 for big pieces seen
   * only at the follow distance or repeated by the dozen: a forest, a crowd, a far ring; the print time goes with
   * res squared). materials: a print made once with printed(pts, paint) and stamped on many cards (same pts, no new
   * print: a forest of 60 from 6 prints; vary scale and turn, and keep ~3 identical in view). sight: whether the
   * walking lens eases round it when it hides the hero (default: constructions yes; cards and crosses, the trees,
   * bushes and props, no). Returns the hinge handle.
   */
  function popup(pts, paint, { x = 0, z = 0, yaw = null, cross = false, second = null, angle = 90, sway = 0, scale = 1, node = null, height = 0, edge = 0.08, self = false, parent = null, order = null, solid = 0, level = false, wx = x, wz = z, base = 0, effects = null, holes = null, res = 1, materials = null, sight = null } = {}) {
    const box = pts ? P2.bbox(pts) : { h: height };
    // pieces the lens blurs (the far rows, the big near cards) print flat and coarse: the build is ~2x faster and nothing
    // visible changes; a roaming camera (?walk, ?roam) prints everything fully
    const sharp = effects ?? (parent || flags.TEST || flags.ROAM || sharpAt(x, z) ? 1 : 0);
    const opts = { paint, paper, seed: seedN++, thickness: 0.08, edgeWidth: edge / scale, pxPerUnit: (sharp ? 40 : 28) * res / scale, effects: sharp, materials };
    const kind = cross === true ? 'full' : cross || null;
    if (kind && !CROSS[kind]) throw new Error(`popup: cross must be true, 'full', 'half', 'half-front' or 'quarter' (got ${cross})`);
    if (yaw === null) yaw = node || parent ? 0 : autoYaw(x, z, kind, angle);
    const card = node || (kind ? crossCard(pts, { ...opts, ...CROSS[kind], angle, second: { ...(CROSS[kind].second || {}), ...(second || {}) } }) : makeCard(pts, { ...opts, holes }));
    card.scale.setScalar(scale);
    const size = Math.min(1, (box.h * scale) / 12); // 0 = a tab, 1 = a page-sized flap: bigger cards rise slower
    // cards fold back (away from the camera), so a nearer card lies ON TOP of the flat stack: its fold line sits a hair
    // higher, it rises first and folds down last. rest: nothing stands plumb.
    const h = hinge(card, { at: new THREE.Vector3(x, parent ? 0.01 : 0.02 + (z - bounds.z0) * 0.0006, z), yaw, open: 0, size,
      ...(self ? { flat: 0, rest: 0 } : { rest: level ? 0 : (lean() - 0.5) * 0.07 }) });
    h.order = order ?? (Z_NEAR - z) / 5 + lean() * 0.4; h.swayAmp = sway; h.size = size; h.x = x; h.z = z; h.jit = lean() * 0.035; h.id = hinges.length;
    h.sight = sight ?? !!node;   // constructions the lens looks past (not cards and crosses: trees, bushes, props)
    hinges.push(h); (parent || scene).add(h.group);
    if (solid) walk.solid(wx, wz, solid * scale, { base });
    return h;
  }
  /**
   * stack(x, z, layers, opts): parallel cards each on its OWN hinge (references/structures.md, layered): nested sails
   * (hills, dunes, drifts, banks: the back one tallest, each nearer one lower and lighter), the slices of a dome, a
   * relief, a crowd. layers: [{ pts, paint, d, dx, yaw, scale }] back to front: d = how far toward the camera along the
   * stack's normal (in units), dx = sideways, yaw = a small extra turn. opts: yaw (the stack's; omitted: it faces the
   * camera +-14 deg) + popup options for every layer (sway, scale, solid...). Returns the hinge handles.
   */
  function stack(x, z, layers, { yaw = null, ...o } = {}) {
    const y0 = yaw ?? autoYaw(x, z, null), nx = Math.sin(y0), nz = Math.cos(y0);   // the stack's normal (toward the camera)
    return layers.map((L) => popup(L.pts, L.paint, { ...o, x: x + nx * (L.d || 0) + nz * (L.dx || 0), z: z + nz * (L.d || 0) - nx * (L.dx || 0), yaw: y0 + (L.yaw || 0), scale: (o.scale ?? 1) * (L.scale ?? 1) }));
  }
  /** dome(x, z, { r, h, n, paint }): a dome, an igloo, a yurt roof, a round hill, a cap as n parallel slices (a stack):
   *  each slice the dome's section at its depth; paint: one print, or [paint, ...] back to front. */
  function dome(x, z, { r = 3, h = r, n = 5, paint = null, ...o } = {}) {
    const layers = [];
    for (let i = 0; i < n; i++) {
      const d = -r + (2 * i + 1) * r / n, k = Math.sqrt(Math.max(0, 1 - (d / r) ** 2)), w = r * k, hh = h * k, pts = [];
      for (let s = 0; s <= 16; s++) { const a = Math.PI * s / 16; pts.push([-Math.cos(a) * w, Math.sin(a) * hh]); }
      layers.push({ pts, paint: Array.isArray(paint) ? paint[i % paint.length] : paint, d });
    }
    return stack(x, z, layers, o);
  }
  /** printed(pts, paint, { res, cross, holes }): print once, stamp many (popup's `materials`): the print of a card cut
   *  to pts, at full quality (res as popup's). cross: true for a print that crosses will use (both faces printed). */
  const printed = (pts, paint, { res = 1, cross = false, holes = null } = {}) =>
    makeCard(pts, { paint, paper, seed: seedN++, thickness: 0.08, edgeWidth: 0.08, pxPerUnit: 40 * res, effects: 1, back: cross ? 'mirror' : 'kraft', holes }).material;
  /** Settings for a card you make yourself (layeredCard, a construction) so it prints like popup's own (res as popup's). */
  const cardOpts = (x, z, scale = 1, parent = null, res = 1) => { const sharp = parent || flags.TEST || flags.ROAM || sharpAt(x, z) ? 1 : 0; return { paper, thickness: 0.08, edgeWidth: 0.08 / scale, pxPerUnit: (sharp ? 40 : 28) * res / scale, effects: sharp, seed: seedN++ * 31 }; };
  /** A practical light (lamp, lit door, brazier): a warm pool at dusk and night, k = relative power. */
  const lamp = (x, y, z, k = 1, dist = 10) => lamps.push([x, y, z, k, dist]);
  /** A group whose printed panes glow (P.glow): dim by day, lit at dusk and night. */
  const glowing = (group) => { glowers.push(group); group.traverse((o) => o.userData.setGlow?.(0.35)); };

  const H = { THREE, P2, flags, renderer, scene, camera, paper, view, target, walk, hinges, spots, bounds, frame, heroHeight, groundAt, stack, dome,
    printGround, popup, printed, cardOpts, inFrame, inReach, walkArea, sharpAt, lamp, glowing, hero: null, heroStride: null, stepHooks: [], onStep: (fn) => H.stepHooks.push(fn), restartHooks: [], onRestart: (fn) => H.restartHooks.push(fn), heroTurn: 'yaw', setHero: (h, { stride = null, turn = 'yaw' } = {}) => { H.hero = h; H.heroStride = stride; H.heroTurn = turn; }, start };

  // ---- start: light, lens, choreography, input, walking, the loop and the capture hook -------------------------------
  function start() {
    const hero = H.hero; if (!hero) throw new Error('papercraft harness: call H.setHero(popup(...)) before H.start()');
    const L = { target: new THREE.Vector3(-1, 0, -12), keyDir: [-0.8, 0.95, 0.55], key: '#ffdcb2', keyIntensity: 3.0, fill: '#f2dcc2', fillIntensity: 0.8,
      fillDir: [0.45, 0.5, 1.0], sky: '#fff0da', ground: '#b39a78', skyIntensity: 0.42, shadowExtent: 50, distance: 100, mapSize: 4096, leak: '#a0602c', softness: 0.26, maxPenumbra: 1.4, ...light };
    // key from the side, ~55 deg off the camera axis and ~45 deg up: faces stay lit, shadows fall sideways where the
    // camera sees them (a key from behind the camera hides every shadow)
    const lights = paperLights(scene, L); H.lights = lights; H.lightTarget = L.target;   // KcalAI: свет меняется с прогрессом
    PAPER_LIGHT.leak.value.set(L.leak);      // the world's shadow colour (light.leak), more saturated than the ground, never grey (default: a warm ground's day amber; other light: assets/lights.json)
    enableSoftShadows(lights.key, { softness: L.softness, maxPenumbra: L.maxPenumbra });
    const post = new PaperPost(renderer, scene, camera, { haze: '#d8bf8e', hazeStart: 34, hazeEnd: 95, hazeAmount: 0.3, focusBand: 0.3, ramp: 0.3, maxBlur: 18,
      tint: [1.03, 0.985, 0.98], saturation: 0.96, greenDesat: 0.3, exposure: 0.82, contrast: 1.18, vignette: 0.03, ...lens });
    const M = moods[flags.MOOD];
    if (M) {
      lights.key.color.set(M.key); lights.key.intensity = M.ki; lights.fill.color.set(M.fill); lights.fill.intensity = M.fi;
      if (M.dir) lights.key.position.copy(lights.key.target.position).addScaledVector(new THREE.Vector3(...M.dir).normalize(), L.distance);
      lights.sky.color.set(M.sky); lights.sky.groundColor.set(M.skyG); lights.sky.intensity = M.si;
      PAPER_LIGHT.leak.value.set(M.leak); if (M.wrap) PAPER_LIGHT.wrap.value = M.wrap;
      if (M.glow && flags.DIAG !== 'key') for (const gr of glowers) gr.traverse((o) => o.userData.setGlow?.(M.glow));
      if (M.lamp && flags.DIAG !== 'key') for (const [x, y, z, k, d] of lamps) { const P = new THREE.PointLight('#ffb070', M.lamp * k, d, 1.0); P.position.set(x, y, z); scene.add(P); } // before the first render: the light count is fixed
      Object.assign(post.o, M.post); post.apply();
    }
    // ?diag=key: the key light alone (look.md 8b), applied AFTER the mood so it holds in every mood
    if (flags.DIAG === 'key') { lights.fill.intensity = 0; lights.sky.intensity = 0; PAPER_LIGHT.leak.value.set('#000000'); }

    // choreography: the nearest card lies on top of the flat stack, so it rises first; closing is the exact reverse
    const sound = new PaperSound();
    let up = false;
    function setOpen(open) {
      up = open;
      const maxOrder = Math.max(...hinges.map((h) => h.order));
      for (const h of hinges) {
        const k = open ? h.order : maxOrder - h.order;
        h.set(open ? 1 : 0, 0.1 + k * (open ? 0.09 : 0.045) + h.jit);   // fixed per-piece jitter: replays are identical
        h.onStart = (hh) => sound.flap({ size: hh.size, up: open, key: hh.id });  // a number per piece: the engine groups by key
      }
    }
    // camera: looking down into the world (40-55 deg), long lens, gentle drift; drag to look around
    let drag = null;
    renderer.domElement.addEventListener('pointerdown', (e) => { drag = { x: e.clientX, y: e.clientY, moved: 0 }; sound.unlock(); });
    addEventListener('keydown', () => sound.unlock());     // a player on the keys alone hears it too
    addEventListener('pointermove', (e) => {
      if (!drag) return;
      drag.moved += Math.abs(e.movementX) + Math.abs(e.movementY);
      view.tyaw = THREE.MathUtils.clamp(view.tyaw - e.movementX * 0.15, -12, 12);      // the world is filled where the camera
      view.tpitch = THREE.MathUtils.clamp(view.tpitch + e.movementY * 0.12, 40, 55);  // looks; never so low the far edge shows
    });
    addEventListener('pointerup', () => { if (drag && drag.moved < 6) setOpen(!up); drag = null; });

    // walking (?walk): WASD / arrows move the hero over the walk field; the camera follows and refits the shadow map.
    // Movement as the source game moves its player: velocity approaches the wanted one at a constant rate (a snappy
    // start, a clean stop), the heading turns smoothly toward the way he goes (rotation only, never a scale), a forward
    // lean on an under-damped spring (he rocks once when he stops), a roll into turns
    const KEYS = { KeyW: [0, -1], ArrowUp: [0, -1], KeyS: [0, 1], ArrowDown: [0, 1], KeyA: [-1, 0], ArrowLeft: [-1, 0], KeyD: [1, 0], ArrowRight: [1, 0] };
    const keys = new Set(), heroPos = new THREE.Vector3(), camOff = new THREE.Vector3(), shadowAt = new THREE.Vector3().copy(target);
    const HERO0 = new THREE.Vector3(), TARGET0 = new THREE.Vector3().copy(target);   // FO: the follow offset for this frame shape
    const SPEED0 = 1.9 * heroHeight; let SPEED = SPEED0; const ACCEL = 28 * heroHeight, TURN = 13, LEAN = 3.5 * Math.PI / 180, LEAN_W = 2 * Math.PI * 3.4, LEAN_Z = 0.75, ROLL = 4 * Math.PI / 180;
    let facing = 1, moved = false;   // moved: the hero has walked (keys or walkPath): the camera follows, the lens too
    H.setSpeed = (k) => { SPEED = SPEED0 * Math.max(0.05, k); };   // KcalAI: темп дорожки
    let walked = 0, gait = 0;        // the distance walked and how much he is walking (0 standing .. 1 striding)
    let heading = 0, heading0 = 0, yawRate = 0, lean = 0, leanV = 0; const vel = new THREE.Vector3();
    let looking = false;             // a look() review frame holds the camera until restart()
    let steering = null;             // steer(x, z): the wanted direction on the page, as if held on a stick
    const DIST0 = view.dist;
    if (flags.WALK) {
      // physical keys (e.code: any layout, caps lock or shift), no modifier chords, all released when the page loses
      // focus (a key let go in another window never sticks)
      addEventListener('keydown', (e) => { if (!KEYS[e.code] || e.metaKey || e.ctrlKey || e.altKey) return; keys.add(e.code); e.preventDefault(); });
      addEventListener('keyup', (e) => keys.delete(e.code));
      addEventListener('blur', () => keys.clear());
      document.addEventListener('visibilitychange', () => { if (document.hidden) keys.clear(); });
    }
    // camera window (as the source game frames its player): a STILL zone round the hero where the camera does not move
    // at all (short moves and stops leave the frame dead still: no glide after the keys are let go), a soft zone where
    // it picks up his speed gradually (0 at the still edge, all of it at the hard edge he can never cross), and after
    // ~0.35-1.1 s of steady walking one way the window slides ahead so he has room in front. The camera chases the
    // window on a critically damped spring (0.28 s) evaluated at the middle of each step, so the path does not depend
    // on the frame rate. Sizes: the source game's, in its units at its distance, scaled with view.dist.
    const ZN = { stillR: 1.0, stillUp: 0.4, stillDown: 0.6, hardR: 2.2, hardUp: 1.1, hardDown: 1.4, leadAfter: [0.35, 1.1], leadTau: 0.7, leadRate: 1.2, smoothTime: 0.28, lookAhead: 1.0, lookAheadSouth: 2.4 };
    const zc = new THREE.Vector3(), zPrev = new THREE.Vector3(), zDir = new THREE.Vector2(), goal = new THREE.Vector3(), prevGoal = new THREE.Vector3(), tVel = new THREE.Vector3();
    let walkT = 0, zoneLive = false;
    const sm = (a, b, x) => { const t = Math.min(1, Math.max(0, (x - a) / (b - a))); return t * t * (3 - 2 * t); };
    const frameAt = (cx, cz) => {
      const kd = view.dist / 66; goal.set(cx + FO[0] * kd, 1.5 + heroPos.y * 0.5, cz + FO[1] * kd);
      if (reachR) { goal.x = THREE.MathUtils.clamp(goal.x, reachR.x0, reachR.x1); goal.z = THREE.MathUtils.clamp(goal.z, reachR.z0, reachR.z1); }   // never past the built world
      return goal;
    };
    // centre: the window centred on him (a snap: place, walkPath); otherwise it starts from the frame as it is (his first
    // step from the authored overview moves the camera only if he leaves the still zone: no swoop to re-centre him)
    function zoneStep(dt, centre = false) {
      const p = heroPos, k = view.dist / 10.8;
      if (!zoneLive) {
        if (centre) zc.copy(p); else { const kd = view.dist / 66; zc.set(target.x - FO[0] * kd, 0, target.z - FO[1] * kd); }
        zPrev.copy(p); zDir.set(0, 0); walkT = 0; zoneLive = true;
      }
      const yaw = THREE.MathUtils.degToRad(view.yaw), ux = -Math.sin(yaw), uz = -Math.cos(yaw), rx = Math.cos(yaw), rz = -Math.sin(yaw);
      let oR = (p.x - zc.x) * rx + (p.z - zc.z) * rz, oU = (p.x - zc.x) * ux + (p.z - zc.z) * uz;
      const dR = (p.x - zPrev.x) * rx + (p.z - zPrev.z) * rz, dU = (p.x - zPrev.x) * ux + (p.z - zPrev.z) * uz;
      zPrev.copy(p);
      const kr = k * Math.min(1, LENS.wide);   // a frame narrower than 16:9 narrows the window sideways: he stays in it
      const sR = ZN.stillR * kr, hR = ZN.hardR * kr, sU = (oU >= 0 ? ZN.stillUp : ZN.stillDown) * k, hU = (oU >= 0 ? ZN.hardUp : ZN.hardDown) * k;
      const v = Math.hypot(vel.x, vel.z);
      if (v > 0.2 * SPEED && dt > 0) {                         // steady walking one way: the window travels with him
        const nx = vel.x / v, nz = vel.z / v, a = 1 - Math.exp(-dt / 0.35);
        if (zDir.lengthSq() < 0.01) zDir.set(nx, nz); else { zDir.x += (nx - zDir.x) * a; zDir.y += (nz - zDir.y) * a; }
        walkT = (nx * zDir.x + nz * zDir.y) / (zDir.length() || 1) > 0.7 ? walkT + dt : Math.max(0, walkT - 3 * dt);
      } else walkT = 0;
      const w = sm(ZN.leadAfter[0], ZN.leadAfter[1], walkT - dt * 0.5);
      const fR = dR * oR > 0 ? sm(sR, hR, Math.abs(oR - dR * 0.5)) : 0, fU = dU * oU > 0 ? sm(sU, hU, Math.abs(oU - dU * 0.5)) : 0;
      let mR = dR * Math.max(fR, w), mU = dU * Math.max(fU, w);
      oR -= mR; oU -= mU;
      if (w > 0 && dt > 0) {                                   // slide ahead of him, calmly (never faster than 1.2x his speed)
        const nx = vel.x / v, nz = vel.z / v, down = Math.max(0, -(nx * ux + nz * uz));
        const look = (ZN.lookAhead + (ZN.lookAheadSouth - ZN.lookAhead) * down) * k + v * ZN.smoothTime;
        const lR = Math.max(-(hR - 0.1 * k), Math.min(hR - 0.1 * k, -(nx * rx + nz * rz) * look));
        const lU = Math.max(-(ZN.hardDown - 0.1) * k, Math.min((ZN.hardUp - 0.1) * k, -(nx * ux + nz * uz) * look));
        const gR = oR - lR, gU = oU - lU, g = Math.hypot(gR, gU);
        if (g > 1e-5) { const d = Math.min(g * (1 - Math.exp(-dt / ZN.leadTau)), ZN.leadRate * v * dt) * w; mR += gR / g * d; mU += gU / g * d; oR -= gR / g * d; oU -= gU / g * d; }
      }
      const hU2 = (oU >= 0 ? ZN.hardUp : ZN.hardDown) * k;     // hard edges: he never leaves the window
      if (Math.abs(oR) > hR) mR += oR - Math.sign(oR) * hR;
      if (Math.abs(oU) > hU2) mU += oU - Math.sign(oU) * hU2;
      zc.x += mR * rx + mU * ux; zc.z += mR * rz + mU * uz;
      return frameAt(zc.x, zc.z);
    }
    const damp = (ax, g, omega, e, dt) => { const d = target[ax] - g, tmp = (tVel[ax] + omega * d) * dt; tVel[ax] = (tVel[ax] - omega * tmp) * e; target[ax] = g + (d + tmp) * e; };
    function camFollow(dt) {
      const fresh = !zoneLive, gl = zoneStep(dt);
      if (fresh) prevGoal.copy(gl);
      const omega = 2 / ZN.smoothTime, e = Math.exp(-omega * dt);
      damp('x', (gl.x + prevGoal.x) * 0.5, omega, e, dt); damp('y', (gl.y + prevGoal.y) * 0.5, omega, e, dt); damp('z', (gl.z + prevGoal.z) * 0.5, omega, e, dt);
      prevGoal.copy(gl);
    }
    // snap (place, walkPath, the first frame): the window centred on him, no glide
    const follow = (now) => { zoneLive = false; zoneStep(0, true); camOff.copy(goal); if (now) { target.copy(camOff); prevGoal.copy(camOff); tVel.set(0, 0, 0); } return camOff; };
    // sight assist (the source game's): when a large construction (>= 0.9 hero height: a house, a roof, a tower, a stall;
    // not cards and crosses, popup's `sight` says otherwise) hides
    // him (2 of 3 rays: thigh, chest, hat) for 0.4 s, the lens eases a little, the gentlest of up to +10 deg of pitch and
    // +-14 deg round that clears him (under a roof, down toward 40 deg), critically damped, and eases back after 0.6 s clear. Scenery never moves aside for
    // him: no cut-out, no fade, nothing folds away (motion.md section 10).
    const AS = { delay: 0.4, release: 0.6, pitchMax: 10, yawMax: 14, ease: 0.8, interval: 0.1, rethink: 1.2, far: 1.3 }, AS_H = [0.35, 0.65, 0.95];
    let aYaw = 0, aPitch = 0, aYawV = 0, aPitchV = 0, gYaw = 0, gPitch = 0, occT = 0, clearT = 0, checkT = 0, heldT = 0, blockers = null, blockersT = -9;
    const _bb = new THREE.Box3();
    const sightBlockers = () => hinges.filter((h) => h.sight && h !== hero && _bb.setFromObject(h.group).max.y - _bb.min.y >= 0.9 * heroHeight)   // as they stand now
      .flatMap((h) => { const m = []; h.group.traverse((o) => { if (o.isMesh && !o.isInstancedMesh) m.push(o); }); return m; });
    const _rc = new THREE.Raycaster(), _ro = new THREE.Vector3(), _rd = new THREE.Vector3(), _rcam = new THREE.Vector3();
    function assistBlocked(yawOff, pitchOff) {   // rays (of 3) from him to where the lens would stand with these offsets
      if (!blockers || Math.abs(time - blockersT) > 2) { blockers = sightBlockers(); blockersT = time; }   // re-read as the world rises
      const yaw = (view.yaw + yawOff) * DEG, pit = Math.min(80, view.pitch + pitchOff) * DEG;
      _rcam.set(target.x + Math.sin(yaw) * Math.cos(pit) * view.dist, target.y + Math.sin(pit) * view.dist, target.z + Math.cos(yaw) * Math.cos(pit) * view.dist);
      let n = 0;
      for (const f of AS_H) {
        _ro.set(heroPos.x, heroPos.y + heroHeight * f, heroPos.z); _rd.subVectors(_rcam, _ro); const l = _rd.length() || 1; _rd.divideScalar(l);
        _rc.set(_ro, _rd); _rc.far = l - camera.near; if (_rc.intersectObjects(blockers, false).length) n++;
      }
      return n;
    }
    function assistPick(baseN) {                  // the gentlest offset that shows more of him: a small lift, a slide, then more
      const P = AS.pitchMax, Y = AS.yawMax, f = AS.far;
      const cand = [[0, P * 0.5], [Y * 0.5, 0], [-Y * 0.5, 0], [0, P], [Y, 0], [-Y, 0], [Y * 0.5, P * 0.5], [-Y * 0.5, P * 0.5], [Y, P], [-Y, P]];
      let best = -1, bestN = baseN;
      for (let i = 0; i < cand.length; i++) {
        if (view.pitch + cand[i][1] > 62) continue;
        const n = assistBlocked(cand[i][0], cand[i][1]); if (n < bestN) { bestN = n; best = i; if (!n) break; }
      }
      if (bestN >= 1) for (const c of [[0, P * f], [Y * f, P], [-Y * f, P], [Y * f, P * f], [-Y * f, P * f]]) {   // one wider tier
        if (view.pitch + c[1] > 62) continue;
        const n = assistBlocked(c[0], c[1]); if (n < bestN) { bestN = n; best = cand.length; cand.push(c); if (!n) break; }
      }
      const L = Math.min(7, view.pitch - 40);        // under a roof (a covered bridge, an arcade) only a lower lens looks in
      if (bestN >= 2 && L > 0) for (const c of [[0, -L], [Y, -L], [-Y, -L], [Y * f, -L], [-Y * f, -L]]) {
        const n = assistBlocked(c[0], c[1]); if (n < bestN) { bestN = n; best = cand.length; cand.push(c); if (!n) break; }
      }
      if (best >= 0) { gYaw = cand[best][0]; gPitch = cand[best][1]; heldT = 0; }
    }
    function updateAssist(dt, force = false) {   // decide 10 times a second on the fixed clock, then ease; force: decide and arrive
      checkT -= dt; heldT += dt;
      if (checkT <= 0 || force) {
        const st = force ? 0 : AS.interval; checkT = AS.interval;
        const baseN = assistBlocked(0, 0), hidden = baseN >= 2;
        if (hidden) { occT += st; clearT = 0; } else { clearT += st; occT = 0; }
        if (gYaw === 0 && gPitch === 0) { if (hidden && (force || occT >= AS.delay)) assistPick(baseN); }
        else if (!hidden) { if (clearT >= AS.release) { gYaw = 0; gPitch = 0; } }
        else if (heldT > AS.rethink && assistBlocked(gYaw, gPitch) >= 2) {   // he walked on behind it: look again
          const py = gYaw, pp = gPitch; gYaw = 0; gPitch = 0; assistPick(baseN); if (gYaw === 0 && gPitch === 0) { gYaw = py; gPitch = pp; heldT = 0; }
        }
      }
      if (force) { aYaw = gYaw; aPitch = gPitch; aYawV = 0; aPitchV = 0; return; }
      if (dt <= 0) return;
      const om = 2 / AS.ease, e = Math.exp(-om * dt);
      let d = aYaw - gYaw, tmp = (aYawV + om * d) * dt; aYawV = (aYawV - om * tmp) * e; aYaw = gYaw + (d + tmp) * e;
      d = aPitch - gPitch; tmp = (aPitchV + om * d) * dt; aPitchV = (aPitchV - om * tmp) * e; aPitch = gPitch + (d + tmp) * e;
    }
    const assistOff = () => { aYaw = aPitch = aYawV = aPitchV = gYaw = gPitch = 0; occT = clearT = checkT = heldT = 0; blockers = null; };
    const sync = () => hero.group.position.set(heroPos.x, heroPos.y + 0.02, heroPos.z);
    const wrapA = (a) => Math.atan2(Math.sin(a), Math.cos(a));
    const pose = (dt) => {                                    // heading (or the flip), lean and roll on the model
      const node = hero.pivot.children[0];
      if (H.heroTurn === 'flip') { hero.pivot.scale.x += (facing - hero.pivot.scale.x) * (1 - Math.exp(-dt * 14)); return; }
      hero.group.rotation.y = heading;
      if (node) { node.rotation.x = lean; node.rotation.z = -Math.max(-ROLL, Math.min(ROLL, 0.035 * yawRate)); }
    };
    function walkStep(dt) {
      let ix = 0, iz = 0;
      for (const c of keys) { ix += KEYS[c][0]; iz += KEYS[c][1]; }
      if (steering) [ix, iz] = steering;                       // steer(x, z): a scripted stick (a video along a route)
      const il = Math.hypot(ix, iz), wx = il ? ix / il : 0, wz = il ? iz / il : 0;
      if (il && !moved) { moved = true; if (focus && !focusAt) { focusFrom.copy(post.focus); focusBlend = 0; } }   // the lens eases from the overview's subject to him
      // velocity: a constant-rate approach to the wanted one
      const dvx = wx * SPEED - vel.x, dvz = wz * SPEED - vel.z, dvl = Math.hypot(dvx, dvz), maxDv = ACCEL * dt;
      if (dvl <= maxDv) vel.set(wx * SPEED, 0, wz * SPEED); else { vel.x += dvx / dvl * maxDv; vel.z += dvz / dvl * maxDv; }
      const sp = Math.hypot(vel.x, vel.z);
      // heading: toward the input (or the remaining velocity while coasting), smoothed
      let dh = 0;
      if (il || sp > 0.05) { const want = il ? Math.atan2(wx, wz) : Math.atan2(vel.x, vel.z); dh = wrapA(want - heading) * (1 - Math.exp(-TURN * dt)); heading = wrapA(heading + dh); }
      yawRate += (dh / Math.max(dt, 1e-4) - yawRate) * (1 - Math.exp(-20 * dt));
      const x0 = heroPos.x, z0 = heroPos.z;
      if (sp > 1e-4) { walk.move(heroPos, vel.x * dt, vel.z * dt); if (vel.x) facing = Math.sign(vel.x); }
      const d = Math.hypot(heroPos.x - x0, heroPos.z - z0); walked += d;
      if (sp > 1e-4 && dt > 0 && d < sp * dt * 0.98) vel.set((heroPos.x - x0) / dt, 0, (heroPos.z - z0) / dt);   // blocked or sliding: keep only what the obstacle allowed
      gait += ((d > 1e-4 ? 1 : 0) - gait) * (1 - Math.exp(-dt * 10));
      // lean: an under-damped spring toward a forward lean that grows with speed (rocks once on stop)
      // sub-stepped at 240 Hz like paper-motion's Spring: one explicit step per frame flips the lean's sign every frame
      // (front / back flapping) once a frame is longer than ~1 / (2 x 0.75 x 21.4) = 31 ms, i.e. below ~32 fps
      const leanTo = LEAN * Math.min(1, sp / SPEED), ln = Math.max(1, Math.ceil(dt * 240)), lh = dt / ln;
      for (let i = 0; i < ln; i++) { leanV += (LEAN_W * LEAN_W * (leanTo - lean) - 2 * LEAN_Z * LEAN_W * leanV) * lh; lean += leanV * lh; }
      H.heroStride?.(walked, gait);
      sync(); pose(dt);
      if (!moved || looking) return;                          // the authored overview until he first moves; a look() frame holds
      camFollow(dt); updateAssist(dt);
      if (target.distanceTo(shadowAt) > 2) { shadowAt.copy(target); fitShadow(lights, shadowAt); } // keep the shadow map on the view
    }
    let time = 0;
    function step(dt) {
      time += dt;
      for (const h of hinges) {
        h.update(dt);
        h.sway = h.swayAmp ? windSway(time, h.x, h.z, { amp: 1.6 * h.swayAmp, heroHeight }) : 0; // about the fold line
      }
      hero.pivot.rotation.y = Math.sin(time * 0.7) * 0.05;
      // until he first moves the hero rides whatever is under him (a quay, a terrace) as the world rises: a hero who
      // starts on a deck stands on it when it has risen, and his first step is from there
      if (!moved) { const s = walk.surface(heroPos.x, heroPos.z); if (s) heroPos.y = s.y; sync(); }
      if (flags.WALK) walkStep(dt);
      if (focusBlend < 1) focusBlend = Math.min(1, focusBlend + dt / 0.6);
      for (const fn of H.stepHooks) fn(time, dt);
      view.yaw += (view.tyaw - view.yaw) * (1 - Math.exp(-dt * 6));
      view.pitch += (view.tpitch - view.pitch) * (1 - Math.exp(-dt * 6));
      placeCamera(view.yaw + aYaw + Math.sin(time * 0.21) * 0.5, view.pitch + aPitch + Math.sin(time * 0.17) * 0.3);
    }
    let focusAt = null;            // look() focuses the lens on a point; otherwise it follows the hero
    let focusBlend = 1; const focusFrom = new THREE.Vector3(), _fg = new THREE.Vector3();
    function render() {
      const kd = view.dist / 66;
      if (focusAt) post.focus.copy(focusAt);
      else if (focus && !moved) post.focus.set(...focus);     // the overview's subject (createHarness({ focus }))
      else {
        _fg.set(hero.group.position.x - 3 * kd, 1.8 + hero.group.position.y, hero.group.position.z - 4 * kd);
        if (focusBlend < 1) { const e = focusBlend * focusBlend * (3 - 2 * focusBlend); post.focus.lerpVectors(focusFrom, _fg, e); } else post.focus.copy(_fg);
      }
      post.render(time);
    }
    // ?safe=: a vertical platform's UI-safe area (buttons, caption) and the feed crops (4:5, 3:4, 1:1) over a 9:16
    // frame, as fractions [top, right, bottom, left] (references/formats.md); a preview aid, never part of the render
    const SAFE = { meta: [0.14, 0.06, 0.35, 0.06], tiktok: [0.125, 0.111, 0.344, 0.111], shorts: [0.15, 0.178, 0.35, 0.044], all: [0.15, 0.178, 0.35, 0.111] };
    function drawSafe(b) {
      const id = Q.get('safe'); if (!id) return;
      let el = document.getElementById('papercraft-safe');
      if (!el) { el = document.createElement('div'); el.id = 'papercraft-safe'; el.style.cssText = 'position:absolute;pointer-events:none;z-index:5'; document.body.appendChild(el); }
      Object.assign(el.style, { left: (innerWidth - b.cw) / 2 + 'px', top: (innerHeight - b.ch) / 2 + 'px', width: b.cw + 'px', height: b.ch + 'px' });
      const box = ([t, r, bo, l], css) => `<div style="position:absolute;top:${t * 100}%;right:${r * 100}%;bottom:${bo * 100}%;left:${l * 100}%;${css}"></div>`;
      el.innerHTML = box(SAFE[id] || SAFE.all, 'border:2px solid rgba(255,70,60,0.9)')
        + [0.148, 0.125, 0.219].map((c) => box([c, 0, c, 0], 'border-top:1px dashed rgba(255,255,255,0.85);border-bottom:1px dashed rgba(255,255,255,0.85)')).join('');
    }
    // the frame: drawing size and letterbox, the lens for its shape (the short side keeps it), the overview swung toward
    // the subject, the follow offset, and the lens's blur band
    function resize() {
      const b = frameBox(), el = renderer.domElement;
      renderer.setPixelRatio(b.pr);
      renderer.setSize(b.w, b.h, false);
      Object.assign(el.style, { position: 'absolute', width: b.cw + 'px', height: b.ch + 'px', left: (innerWidth - b.cw) / 2 + 'px', top: (innerHeight - b.ch) / 2 + 'px' });
      const L = lensFor(b.w / b.h), shape = Math.abs(L.aspect - LENS.aspect) > 1e-3;
      LENS = L; camera.aspect = L.aspect; camera.fov = L.fovY; camera.updateProjectionMatrix();
      [FO[0], FO[1]] = followFor(L);
      if (shape) { TARGET0.copy(aimed(L)); if (!moved && !looking) { target.copy(TARGET0); placeCamera(view.yaw, view.pitch); } }
      post.setSize(b.w, b.h); post.bandScale = 1 / L.tall; post.magnify = L.mag;
      Object.assign(frame, { w: b.w, h: b.h, aspect: L.aspect, fovY: L.fovY, wide: L.wide, tall: L.tall, lat: L.lat, mag: L.mag });
      drawSafe(b);
    }
    addEventListener('resize', resize);
    resize();
    setOpen(true);
    heroPos.set(hero.group.position.x, 0, hero.group.position.z); HERO0.copy(heroPos); heading = heading0 = hero.group.rotation.y;
    if (!subj && !flags.TEST && LENS.lat < 0.99) {   // a narrow frame and nothing to swing toward: say so if he is at its edge
      _v.copy(hero.group.position).setY(1.8).project(camera);
      if (Math.abs(_v.x) > 0.8 || Math.abs(_v.y) > 0.9) console.warn(`papercraft: the hero is at the edge of this ${frame.w}x${frame.h} frame: give createHarness a subject: [x, z] (his start) so a narrow frame swings toward him (references/formats.md)`);
    }
    if (walkArea && (heroPos.x < walkArea.x0 || heroPos.x > walkArea.x1 || heroPos.z < walkArea.z0 || heroPos.z > walkArea.z1)) console.warn('papercraft: the hero starts outside walkArea: he cannot move');
    let last = performance.now(), auto = true;
    renderer.setAnimationLoop((now) => { if (!auto) return; step(Math.min(0.05, Math.max(0, (now - last) / 1000))); last = now; render(); }); // dt clamped to [0, 50 ms]
    function restart() { // a full reset: hinges flat, the hero back at his start, the camera on its first view
      time = 0; for (const h of hinges) h.reset(0);
      heroPos.copy(HERO0); sync(); facing = 1; hero.pivot.scale.x = 1; moved = false; looking = false; view.dist = DIST0;
      heading = heading0; yawRate = 0; lean = 0; leanV = 0; vel.set(0, 0, 0); walked = 0; gait = 0; steering = null; H.heroStride?.(0, 0); pose(0); assistOff();
      for (const fn of H.restartHooks) fn();             // seeded extras (paper-life) replay from t = 0 too
      target.copy(TARGET0); zoneLive = false; tVel.set(0, 0, 0); keys.clear(); focusAt = null; focusBlend = 1; if (shadowAt.distanceTo(TARGET0) > 0.01) { shadowAt.copy(TARGET0); fitShadow(lights, shadowAt); }
      setOpen(true);
    }
    /** Scripted walk through the same walk field the keys use (blockers, feet and the step limit apply); the camera
     *  follows. Returns { reached, of, stuck, y, on, heights }: reached < of means the route is blocked. */
    function walkPath(points, o) {
      moved = true; focusBlend = 1;
      const r = walk.route(heroPos, points, o); sync(); follow(true); assistOff(); updateAssist(0, true);
      const tr = r.trace, ta = tr[Math.max(0, tr.length - 8)], tz = tr[tr.length - 1];
      if (points.length) facing = Math.sign(points[points.length - 1][0] - tr[0][0]) || facing;
      if (ta && tz && Math.hypot(tz[0] - ta[0], tz[2] - ta[2]) > 1e-3) heading = Math.atan2(tz[0] - ta[0], tz[2] - ta[2]);   // facing the way he last walked
      if (H.heroTurn === 'flip') hero.pivot.scale.x = facing; else pose(0); // turned already: a capture right after never catches a turn
      return { reached: r.reached, of: points.length, stuck: r.stuck || null, blockedBy: r.blockedBy || null, y: +heroPos.y.toFixed(2), on: r.on ? (r.on.name || r.on.userData?.name || 'structure') : 'page',
        heights: r.trace.filter((_, i) => i % 15 === 0).map((q) => +q[1].toFixed(2)) };
    }
    /** A close review frame on a point (x, z): the camera at `dist` looking at it (y: default 1.5 above whatever is
     *  there: the page or a deck), the lens focused there. */
    function look(x, z, dist = 30, y = null) {
      const s = walk.surface(x, z); y ??= (s && s.y < 10 ? s.y : 0) + 1.5;   // a wall's placeholder height is not a floor
      view.dist = dist; target.set(x, y, z); focusAt = new THREE.Vector3(x, y, z); looking = true; assistOff();
      placeCamera(view.yaw, view.pitch); camera.updateMatrixWorld(); shadowAt.copy(target); fitShadow(lights, shadowAt);
    }
    /** The walk field as text, far rows (z0) first: '#' blocked (water, a solid), '.' the page, 1-9 the height of a
     *  deck, '^' a wall (a surface set too high to step on), 'H' the hero. Print it to find an invisible blocker. */
    function walkMap(x0, z0, x1, z1, cell = 1) {
      const rows = [];
      for (let z = z0; z <= z1 + 1e-6; z += cell) {
        let row = '';
        for (let x = x0; x <= x1 + 1e-6; x += cell) {
          if (Math.abs(x - heroPos.x) <= cell / 2 && Math.abs(z - heroPos.z) <= cell / 2) { row += 'H'; continue; }
          const s = walk.surface(x, z); row += !s ? '#' : s.y >= 10 ? '^' : s.y < 0.15 ? '.' : String(Math.min(9, Math.max(1, Math.round(s.y))));
        }
        rows.push(row);
      }
      return rows.join('\n');
    }
    // The hero's cards never cut into each other, standing or anywhere in his stride: one card through another (or two
    // in one plane) renders as a cut line sliding as he walks and turns, or as flicker. clips(n) poses him standing and
    // through two stride cycles and returns the pairs that do (cardClips, references/mechanisms.md section 12).
    function clips(n = 24) {
      const found = new Map(), stride = H.heroStride, keep = [walked, gait];
      const scan = (at) => { for (const c of cardClips(hero.group)) { const k = `${c.a} / ${c.b}`; if (!found.has(k)) found.set(k, { ...c, at }); } };
      if (stride) stride(0, 0);
      scan('standing');
      if (stride) for (let i = 0; i < n; i++) { const w = i / n * 4.8; stride(w, 1); scan(`walking ${w.toFixed(1)}`); }
      if (stride) stride(keep[0], keep[1]);
      return [...found.values()];
    }
    const heroClips = clips();
    if (heroClips.length) console.warn(`papercraft: the hero's cards cut into each other (${heroClips.length}): ${heroClips.slice(0, 6).map((c) => `${c.a} / ${c.b} ${c.kind} (${c.at})`).join('; ')}. Layer them apart: references/mechanisms.md section 12`);
    const buildMs = Math.round(performance.now() - T0);
    if (buildMs > 6000 * PRINT * PRINT) {   // say where a slow build went, where any agent reading the console sees it (a big frame prints more on purpose)
      const top = [...printLog.values()].sort((a, b) => b.ms - a.ms).slice(0, 4).map((e) => `${e.at} ${e.prints}x ${Math.round(e.ms)} ms`).join('; ');
      console.warn(`papercraft: the build took ${(buildMs / 1000).toFixed(1)} s, ${printN} prints ${(printMs / 1000).toFixed(1)} s (${(printPx / 1e6).toFixed(1)} Mpx). Slowest: ${top}. __papercraft.printStats(); trims: references/three-js.md section 9`);
    }
    // capture hook: restart() folds everything flat and replays from t = 0, so step(0.8) is a true mid-rise frame;
    // step(sec) advances a fixed clock (stops the real-time loop) and renders
    window.__papercraft = { step(sec, fps = 60) { auto = false; for (let i = 0; i < Math.round(sec * fps); i++) step(1 / fps); render(); return time; }, restart,
      resume() { last = performance.now(); auto = true; }, toggle: () => setOpen(!up), post, scene, camera, pieces: hinges.length, walk, view, target, heroPos,
      /** steer(x, z): walk him that way (a direction on the page, any length; null stops) through the same
       *  acceleration, turning and blockers as the keys: capture.py's route= clips steer him along waypoints. */
      steer: (x, z) => { steering = x == null || (!x && !z) ? null : [x / Math.hypot(x, z), z / Math.hypot(x, z)]; },
      place: (x, z) => { const s = walk.surface(x, z); if (!s) return null; heroPos.set(x, s.y, z); sync(); moved = true; follow(true); focusBlend = 1; assistOff(); updateAssist(0, true); return s.y; },
      walkPath, walkMap, look, spots, buildMs, frame, clips, ready: true, // ready: the build is synchronous; frame: the output frame and its lens; clips(): the hero's cards that cut into each other
      /** Can the lens see him? rays (of 3: thigh, chest, hat) a large piece blocks from the plain view and from the
       *  sight assist's current one, and the assist's offsets (deg). Walk a route with place() and read it. */
      sight: () => ({ plain: assistBlocked(0, 0), now: assistBlocked(aYaw, aPitch), yaw: +aYaw.toFixed(1), pitch: +aPitch.toFixed(1), blockers: blockers ? blockers.length : 0 }),
      /** What the build spent printing, by the scene line that asked (slowest first): trim those (popup res, one print
       *  shared by many cards, fewer copies) when the build is slow (references/three-js.md section 9). */
      printStats(n = 10) {
        const top = [...printLog.values()].sort((a, b) => b.ms - a.ms).slice(0, n)
          .map((e) => ({ at: e.at, prints: e.prints, ms: Math.round(e.ms), each: +(e.ms / e.prints).toFixed(1), mpx: +(e.px / 1e6).toFixed(2) }));
        return { buildMs, prints: printN, printMs: Math.round(printMs), mpx: +(printPx / 1e6).toFixed(1), top };
      },
      /** Density check: standing cards whose origin is in the frame now (the source game's gameplay frames: ~270-320). */
      cards() { scene.updateMatrixWorld(true); let n = 0; const v = new THREE.Vector3();
        scene.traverse((o) => { if (!o.isMesh || o.isInstancedMesh || !o.visible) return; for (let q = o; q; q = q.parent) if (q.name === 'life') return;
          if (o.geometry && o.geometry.type === 'PlaneGeometry' && o.parent === scene) return;   // the ground sheets
          v.setFromMatrixPosition(o.matrixWorld).project(camera); if (Math.abs(v.x) < 1 && Math.abs(v.y) < 1 && v.z < 1) n++; });
        return n; } };
  }
  return H;
}
