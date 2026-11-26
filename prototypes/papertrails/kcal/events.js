// KcalAI: случайные события прогулки — чтобы каждый раз было что-то новое. Когда и что — events-math.js; здесь,
// как это выглядит: посылка на тропе, ягоды, стая птиц, воздушный шар, танец друзей. Каждое — с короткой
// табличкой слева внизу. Ничего не копится и в дневник не пишется: это только мир.
import * as THREE from 'three';
import { milestone, pause, pickEvent } from './events-math.js';
import { laneX } from './lane.js';

const FINDS = ['баранка', 'горсть семечек', 'кусочек сыра', 'яблоко', 'пирожок', 'орешек', 'ложка мёда', 'сушка', 'морковка', 'ягодный пряник'];
const PAPER = ['#d95b4a', '#f4c95a', '#4c8f5a', '#86bfe0', '#e8759c', '#fdf8ea'];

const pick = (list) => list[Math.floor(Math.random() * list.length)];

/** Табличка о событии — поверх мира, мимо кликов, сама гаснет. Одна: новая сменяет прошлую, а не ложится сверху. */
let shown = null;
export function banner(text) {
  if (shown) shown.remove();
  const note = document.createElement('div');
  note.textContent = text;
  note.setAttribute('role', 'status');
  note.style.cssText = 'position:fixed;left:16px;bottom:64px;z-index:2;pointer-events:none;max-width:260px;padding:9px 14px;border-radius:12px;'
    + 'background:#fdf8ea;color:#2c2618;border:2px solid #d9c9a3;box-shadow:0 5px 12px #3d300c55;font:700 14px/1.35 system-ui,sans-serif;'
    + 'transition:opacity .4s,transform .4s;opacity:0;transform:translateY(10px)';
  document.body.appendChild(note);
  shown = note;
  requestAnimationFrame(() => { note.style.opacity = '1'; note.style.transform = 'none'; });
  setTimeout(() => { note.style.opacity = '0'; }, 4200);
  setTimeout(() => note.remove(), 4800);
}

function start(pt, materials) {
  const hero = pt.traveller.model;
  let scene = hero;
  while (scene.parent) scene = scene.parent;
  const mat = (color) => materials.paper({ color, side: THREE.DoubleSide, variant: 'traveller' });
  const mesh = (geo, color, x = 0, y = 0, z = 0) => {
    const g = geo.index ? geo.toNonIndexed() : geo;
    g.computeVertexNormals();
    const m = new THREE.Mesh(g, mat(color));
    m.position.set(x, y, z);
    m.castShadow = true;
    return m;
  };

  /** Идущие сейчас: step(dt, t) возвращает false, когда пора убрать. */
  const live = [];
  const run = (object, step) => {
    scene.add(object);
    live.push({ object, step, t: 0 });
  };
  const at = new THREE.Vector3();

  /** Разлетающиеся бумажки. */
  const confetti = (x, y, z) => {
    const group = new THREE.Group();
    const bits = Array.from({ length: 26 }, () => {
      const bit = mesh(new THREE.PlaneGeometry(0.06, 0.09), pick(PAPER), x, y, z);
      group.add(bit);
      return { bit, v: new THREE.Vector3((Math.random() - 0.5) * 2.2, 1.6 + Math.random() * 1.8, (Math.random() - 0.5) * 2.2), spin: 4 + Math.random() * 8 };
    });
    run(group, (dt, t) => {
      for (const b of bits) {
        b.v.y -= 4.5 * dt;
        b.bit.position.addScaledVector(b.v, dt);
        b.bit.position.y = Math.max(y - 0.3, b.bit.position.y);
        b.bit.rotation.x += b.spin * dt;
        b.bit.rotation.z += b.spin * 0.6 * dt;
      }
      return t < 2.2;
    });
  };

  /** Что-то на тропе впереди: подошли — [found]; не дошли за минуту — тихо исчезает. */
  const waiting = (object, found, bob) => {
    run(object, (dt, t) => {
      pt.traveller.model.getWorldPosition(at);
      const near = Math.hypot(object.position.x - at.x, object.position.z - at.z) < 0.75;
      object.rotation.y += dt * 0.8;
      object.position.y = at.y + bob + Math.sin(t * 3) * 0.05;
      if (near) found();
      return !near && t < 60;
    });
  };

  const happenings = {
    gift(walking) {
      const box = new THREE.Group();
      box.add(mesh(new THREE.BoxGeometry(0.34, 0.28, 0.34), '#d95b4a', 0, 0.14, 0));
      box.add(mesh(new THREE.BoxGeometry(0.37, 0.07, 0.37), '#f4c95a', 0, 0.31, 0));
      box.add(mesh(new THREE.BoxGeometry(0.06, 0.29, 0.355), '#fdf8ea', 0, 0.145, 0));
      box.add(mesh(new THREE.TorusGeometry(0.07, 0.025, 6, 10), '#fdf8ea', 0, 0.4, 0));
      pt.traveller.model.getWorldPosition(at);
      // Стоящий до посылки не дойдёт — тогда она ложится у ног и открывается.
      const z = at.z - (walking ? 3.2 : 0.6);
      box.position.set(laneX(z, pt.wfc.seed), at.y, z);
      banner(walking ? 'Впереди посылка!' : 'Смотри, посылка!');
      waiting(box, () => {
        confetti(box.position.x, box.position.y + 0.3, box.position.z);
        banner(`В посылке — ${pick(FINDS)}!`);
        window.KcalFriends && window.KcalFriends.dance(1.6);
      }, 0);
    },
    berries(walking) {
      pt.traveller.model.getWorldPosition(at);
      const z = at.z;
      let got = 0;
      const total = walking ? 5 : 3;
      banner('На тропе ягоды!');
      for (let i = 0; i < total; i++) {
        const berry = new THREE.Group();
        berry.add(mesh(new THREE.IcosahedronGeometry(0.1, 0), '#c8394f'));
        berry.add(mesh(new THREE.ConeGeometry(0.06, 0.07, 4), '#4c8f5a', 0, 0.11, 0));
        const bz = z - (walking ? 2 + i * 0.9 : 0.35 + i * 0.12);
        berry.position.set(laneX(bz, pt.wfc.seed) + Math.sin(i * 1.7) * 0.2, 0, bz);
        waiting(berry, () => {
          confetti(berry.position.x, berry.position.y, berry.position.z);
          got += 1;
          if (got === total) banner('Все ягоды собраны!');
        }, 0.25);
      }
    },
    birds() {
      pt.traveller.model.getWorldPosition(at);
      const flock = new THREE.Group();
      const color = pick(['#fdf8ea', '#86bfe0', '#e8759c', '#f4c95a']);
      const birds = Array.from({ length: 7 }, (_, i) => {
        const bird = new THREE.Group();
        bird.add(mesh(new THREE.ConeGeometry(0.05, 0.2, 4).rotateZ(-Math.PI / 2), color));
        const wings = [-1, 1].map((side) => {
          const wing = new THREE.Group();
          wing.add(mesh(new THREE.PlaneGeometry(0.11, 0.2).rotateX(-Math.PI / 2), color, 0, 0, side * 0.1));
          bird.add(wing);
          return wing;
        });
        bird.position.set(-Math.abs(i - 3) * 0.35, Math.random() * 0.2, (i - 3) * 0.35);
        flock.add(bird);
        return wings;
      });
      flock.position.set(at.x - 7, at.y + 1.9, at.z - 1.2);
      banner('Стая птиц над дорогой');
      run(flock, (dt, t) => {
        flock.position.x += 2.1 * dt;
        birds.forEach((wings, i) => {
          const flap = Math.sin(t * 11 + i) * 0.8;
          wings[0].rotation.x = flap;
          wings[1].rotation.x = -flap;
        });
        return t < 7;
      });
    },
    balloon() {
      pt.traveller.model.getWorldPosition(at);
      const ball = new THREE.Group();
      const color = pick(['#d95b4a', '#f4c95a', '#4c8f5a', '#e8759c']);
      ball.add(mesh(new THREE.IcosahedronGeometry(0.55, 1), color, 0, 0.9, 0));
      ball.add(mesh(new THREE.TorusGeometry(0.55, 0.045, 6, 18).rotateX(Math.PI / 2), '#fdf8ea', 0, 0.9, 0));
      ball.add(mesh(new THREE.BoxGeometry(0.24, 0.2, 0.24), '#8a5a33', 0, 0.02, 0));
      for (const sx of [-1, 1]) ball.add(mesh(new THREE.BoxGeometry(0.012, 0.36, 0.012), '#6b4a2a', sx * 0.11, 0.28, 0));
      ball.position.set(at.x + 4.5, at.y + 0.9, at.z - 3);
      banner('Кто-то летит на воздушном шаре!');
      run(ball, (dt, t) => {
        ball.position.x -= 0.6 * dt;
        ball.position.y = at.y + 0.9 + Math.sin(t * 1.3) * 0.18;
        ball.rotation.y += dt * 0.25;
        return t < 15;
      });
    },
    dance() {
      window.KcalFriends && window.KcalFriends.dance(3.5);
      banner(pick(['Масло пустилось в пляс!', 'Яйцо пританцовывает!', 'Друзья устроили танцы!']));
    },
  };

  /** Верстовой столб с числом у тропы. */
  const post = (text) => {
    pt.traveller.model.getWorldPosition(at);
    const z = at.z - 2.4;
    const board = document.createElement('canvas');
    board.width = 256;
    board.height = 128;
    const g = board.getContext('2d');
    g.fillStyle = '#fdf8ea';
    g.fillRect(0, 0, 256, 128);
    g.strokeStyle = '#b78d4d';
    g.lineWidth = 12;
    g.strokeRect(6, 6, 244, 116);
    g.fillStyle = '#2c2618';
    g.font = '800 62px system-ui, sans-serif';
    g.textAlign = 'center';
    g.textBaseline = 'middle';
    g.fillText(text, 128, 66);
    const face = new THREE.CanvasTexture(board);
    face.colorSpace = THREE.SRGBColorSpace;
    const sign = new THREE.Group();
    sign.add(mesh(new THREE.CylinderGeometry(0.045, 0.055, 0.75, 6), '#8a5a33', 0, 0.37, 0));
    const plate = new THREE.Mesh(new THREE.PlaneGeometry(0.74, 0.37), new THREE.MeshBasicMaterial({ map: face, side: THREE.DoubleSide }));
    plate.position.set(0, 0.86, 0.01);
    sign.add(plate);
    sign.position.set(laneX(z, pt.wfc.seed) - 0.85, at.y, z);
    run(sign, (dt, t) => {
      // Выскакивает из земли с отскоком и стоит, пока не пройдут.
      const pop = Math.min(1, t * 2.5);
      sign.scale.setScalar(pop + Math.sin(pop * Math.PI) * 0.25);
      return t < 40;
    });
    confetti(sign.position.x, at.y + 1, z);
  };

  /** Салют за цель дня: залп за залпом вокруг героя. */
  const salute = () => {
    for (let i = 0; i < 9; i++) {
      setTimeout(() => {
        pt.traveller.model.getWorldPosition(at);
        confetti(at.x + (Math.random() - 0.5) * 4, at.y + 1.2 + Math.random(), at.z - Math.random() * 2.5);
      }, i * 420);
    }
    window.KcalFriends && window.KcalFriends.dance(4);
  };

  const cheer = (what) => {
    if (what === 'goal') {
      banner('Цель шагов взята! Салют!');
      post('ЦЕЛЬ ⚑');
      salute();
      return;
    }
    const label = String(what).replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
    banner(`Веха: ${label} шагов!`);
    post(label);
  };

  /** Быстрый шаг — из-под ног летит пыль (или снег). */
  let puffIn = 0;
  const dust = (dt, speed) => {
    puffIn -= dt;
    if (speed < 3.5 || puffIn > 0) return;
    puffIn = Math.max(0.09, 0.5 - speed * 0.06);
    pt.traveller.model.getWorldPosition(at);
    const puff = mesh(new THREE.IcosahedronGeometry(0.05, 0), '#efe9dc', at.x + (Math.random() - 0.5) * 0.3, at.y + 0.04, at.z + 0.2);
    run(puff, (d, t) => {
      puff.position.y += d * 0.18;
      puff.position.z += d * 0.3;
      puff.scale.setScalar(1 + t * 1.6 - t * t * 2.2);
      return t < 0.8;
    });
  };

  let steps = -1;
  let previous = null;
  let wait = 6; // первое — вскоре после открытия, чтобы не ждать
  let last = performance.now();
  const frame = (now) => {
    requestAnimationFrame(frame);
    const dt = Math.min(0.05, (now - last) / 1000);
    last = now;
    for (let i = live.length - 1; i >= 0; i--) {
      const item = live[i];
      item.t += dt;
      if (!item.step(dt, item.t)) {
        scene.remove(item.object);
        live.splice(i, 1);
      }
    }
    // Мир не виден — события не копятся.
    if (document.hidden || !document.body.classList.contains('walking')) return;
    const walk = window.KcalWalk;
    if (walk && walk.steps >= 0) {
      const what = milestone(steps, walk.steps, walk.goal);
      steps = walk.steps;
      if (what) cheer(what);
    }
    dust(dt, (window.KcalTune && window.KcalTune.speed) ?? (walk && walk.vis) ?? 0);
    wait -= dt;
    if (wait > 0) return;
    const walking = (window.KcalWalk && window.KcalWalk.vis > 0.3) || false;
    previous = pickEvent(previous);
    happenings[previous](walking);
    wait = pause(walking);
  };
  requestAnimationFrame(frame);
  /** Для проверки: вызвать событие сразу. */
  window.KcalEvents = { cheer, happen: (name) => happenings[name](window.KcalWalk && window.KcalWalk.vis > 0.3) };
}

const wait = setInterval(() => {
  if (!window.__pt || !window.__ptPaper) return;
  clearInterval(wait);
  start(window.__pt, window.__ptPaper);
}, 300);
