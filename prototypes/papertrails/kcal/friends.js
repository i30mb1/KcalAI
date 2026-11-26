// KcalAI: Масло и Яйцо — бумажные фигурки, что идут за Зёрнышком по его следам, переваливаясь на коротких ножках.
// Рисунок — как в приложении (core/ui: ButterArt, EggArt): брусок с завитком и яйцо в розовой скорлупке.
// Мир их не знает: они сами берут героя из window.__pt и встают в ту же сцену тем же бумажным материалом.
import * as THREE from 'three';

const INK = '#2c2618';
const BLUSH = '#f3b5c8';

const faceted = (g) => { const n = g.index ? g.toNonIndexed() : g; n.computeVertexNormals(); return n; };

function makeKit(materials) {
  const mat = (color) => materials.paper({ color, side: THREE.DoubleSide, variant: 'traveller' });
  const mesh = (geo, color, x = 0, y = 0, z = 0) => {
    const m = new THREE.Mesh(faceted(geo), mat(color));
    m.position.set(x, y, z);
    m.castShadow = true;
    m.receiveShadow = true;
    return m;
  };
  return { mesh };
}

/** Лицо — на передней грани (+Z): глаза, румянец, улыбка. */
function face(kit, parent, y, z) {
  for (const sx of [-1, 1]) {
    parent.add(kit.mesh(new THREE.SphereGeometry(0.018, 8, 6), INK, sx * 0.045, y, z));
    parent.add(kit.mesh(new THREE.SphereGeometry(0.016, 8, 6), BLUSH, sx * 0.085, y - 0.035, z - 0.012));
  }
  const smile = kit.mesh(new THREE.TorusGeometry(0.025, 0.006, 6, 10, Math.PI), INK, 0, y - 0.03, z);
  smile.rotation.z = Math.PI;
  parent.add(smile);
}

function legs(kit, root, color) {
  return [-1, 1].map((sx) => {
    const leg = new THREE.Group();
    leg.position.set(sx * 0.055, 0.1, 0);
    leg.add(kit.mesh(new THREE.CylinderGeometry(0.018, 0.02, 0.09, 6), color, 0, -0.045, 0));
    const foot = kit.mesh(new THREE.IcosahedronGeometry(0.03, 0), color, 0, -0.088, 0.012);
    foot.scale.set(1, 0.55, 1.4);
    leg.add(foot);
    root.add(leg);
    return leg;
  });
}

function butter(kit) {
  const root = new THREE.Group();
  const body = new THREE.Group();
  root.add(body);
  body.add(kit.mesh(new THREE.BoxGeometry(0.3, 0.2, 0.17), '#f4cd6a', 0, 0.2, 0));
  body.add(kit.mesh(new THREE.BoxGeometry(0.27, 0.05, 0.172), '#fbe6a8', 0, 0.325, 0));
  const curl = kit.mesh(new THREE.TorusGeometry(0.045, 0.018, 6, 10, Math.PI * 1.5), '#e8b25e', 0, 0.39, 0);
  body.add(curl);
  face(kit, body, 0.23, 0.09);
  return { root, body, legs: legs(kit, root, '#c8962f') };
}

function egg(kit) {
  const root = new THREE.Group();
  const body = new THREE.Group();
  root.add(body);
  const profile = [[0.001, 0], [0.09, 0.015], [0.135, 0.08], [0.14, 0.14], [0.12, 0.22], [0.08, 0.3], [0.001, 0.345]].map(([r, y]) => new THREE.Vector2(r, y));
  const shell = kit.mesh(new THREE.LatheGeometry(profile, 10), '#fff8ee', 0, 0.09, 0);
  body.add(shell);
  // Розовая скорлупка набекрень — с зубчатым краем.
  const cap = kit.mesh(new THREE.ConeGeometry(0.108, 0.12, 8), '#e58ba8', 0, 0.385, 0);
  body.add(cap);
  for (let i = 0; i < 8; i++) {
    const a = (i / 8) * Math.PI * 2;
    const tooth = kit.mesh(new THREE.ConeGeometry(0.032, 0.05, 4), '#e58ba8', Math.sin(a) * 0.088, 0.315, Math.cos(a) * 0.088);
    tooth.rotation.x = Math.PI;
    body.add(tooth);
  }
  face(kit, body, 0.23, 0.14);
  return { root, body, legs: legs(kit, root, '#d8c8ae') };
}

/** Насколько каждый отстаёт: позади героя и в стороне от тропы. */
const PLACES = [{ side: -0.62, back: 0.55 }, { side: 0.66, back: 1.05 }];

function start(pt, materials) {
  const hero = pt.traveller.model;
  let scene = hero;
  while (scene.parent) scene = scene.parent;
  const kit = makeKit(materials);
  const friends = [butter(kit), egg(kit)].map((f, i) => {
    f.root.scale.setScalar(1.15);
    scene.add(f.root);
    return Object.assign(f, PLACES[i], { phase: i * 2, dance: 0, placed: false });
  });
  // События (kcal/events.js) просят друзей сплясать.
  window.KcalFriends = { dance: (seconds) => friends.forEach((f) => { f.dance = seconds; }) };

  const at = new THREE.Vector3();
  let last = performance.now();
  const frame = (now) => {
    requestAnimationFrame(frame);
    const dt = Math.min(0.05, (now - last) / 1000);
    last = now;
    pt.traveller.model.getWorldPosition(at);
    for (const f of friends) {
      const tx = at.x + f.side;
      const tz = at.z + f.back;
      const p = f.root.position;
      let dx = tx - p.x;
      let dz = tz - p.z;
      // Героя переставили на место по шагам дня — друзья оказываются рядом, а не бегут через весь мир.
      if (!f.placed || Math.hypot(dx, dz) > 4) {
        p.set(tx, at.y, tz);
        f.root.rotation.y = Math.PI;
        f.placed = true;
        dx = 0;
        dz = 0;
      }
      const k = 1 - Math.exp(-3 * dt);
      p.x += dx * k;
      p.z += dz * k;
      p.y = at.y;
      const speed = Math.hypot(dx, dz) * 3;
      const going = Math.min(1, speed / 0.8);
      f.phase += dt * (3 + 9 * going);
      const step = Math.sin(f.phase);
      let hop = Math.abs(step) * 0.05 * going + Math.abs(Math.sin(f.phase * 0.5)) * 0.008;
      // Стоят — лицом к камере (она на юге), идут — по ходу.
      let turn = going > 0.1 ? Math.atan2(dx, dz) : -f.side * 0.4;
      if (f.dance > 0) {
        f.dance -= dt;
        hop = Math.abs(Math.sin(f.phase * 1.4)) * 0.28;
        turn = f.root.rotation.y + dt * 9;
        f.phase += dt * 6;
      }
      let diff = turn - f.root.rotation.y;
      diff = Math.atan2(Math.sin(diff), Math.cos(diff));
      f.root.rotation.y += f.dance > 0 ? turn - f.root.rotation.y : diff * k * 2;
      f.body.position.y = hop;
      f.body.rotation.z = step * 0.14 * Math.max(0.25, going);
      f.legs[0].rotation.x = step * 0.7 * going;
      f.legs[1].rotation.x = -step * 0.7 * going;
    }
  };
  requestAnimationFrame(frame);
}

const wait = setInterval(() => {
  if (!window.__pt || !window.__ptPaper) return;
  clearInterval(wait);
  start(window.__pt, window.__ptPaper);
}, 300);
