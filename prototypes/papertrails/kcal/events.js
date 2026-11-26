// KcalAI: случайные события прогулки — чтобы каждый раз было что-то новое. Когда и что — events-math.js; здесь,
// как это выглядит: посылка на тропе, ягоды, стая птиц, воздушный шар, танец друзей. Каждое — с короткой
// табличкой слева внизу. Ничего не копится и в дневник не пишется: это только мир.
import * as THREE from 'three';
import { milestone, offscreen, pause, pickEvent } from './events-math.js';
import { laneX } from './lane.js';
import { goalArt, milestoneArt } from './signs.js';

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
  const eye = new THREE.Vector3();
  /** Сколько вбок от героя до края кадра (side: −1 влево, 1 вправо) на высоте [y] и глубине [z]. */
  const edge = (side, y, z) => {
    pt.traveller.model.getWorldPosition(at);
    return offscreen((d) => eye.set(at.x + side * d, at.y + y, at.z + z).project(pt.follow.camera).x);
  };

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
      const from = -edge(-1, 1.9, -1.2) - 1;
      const to = edge(1, 1.9, -1.2) + 1;
      flock.position.set(at.x + from, at.y + 1.9, at.z - 1.2);
      banner('Стая птиц над дорогой');
      run(flock, (dt, t) => {
        pt.traveller.model.getWorldPosition(at);
        const x = from + 3 * t;
        flock.position.set(at.x + x, at.y + 1.9, at.z - 1.2);
        birds.forEach((wings, i) => {
          const flap = Math.sin(t * 11 + i) * 0.8;
          wings[0].rotation.x = flap;
          wings[1].rotation.x = -flap;
        });
        return x < to;
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
      const from = edge(1, 1.8, -2.4) + 0.7;
      const to = -edge(-1, 1.8, -2.4) - 0.7;
      ball.position.set(at.x + from, at.y + 0.9, at.z - 2.4);
      banner('Кто-то летит на воздушном шаре!');
      run(ball, (dt, t) => {
        pt.traveller.model.getWorldPosition(at);
        const x = from - 0.9 * t;
        ball.position.set(at.x + x, at.y + 0.9 + Math.sin(t * 1.3) * 0.18, at.z - 2.4);
        ball.rotation.y += dt * 0.25;
        return x > to;
      });
    },
    rainbow() {
      pt.traveller.model.getWorldPosition(at);
      const bow = new THREE.Group();
      ['#d95b4a', '#f0933b', '#f4c95a', '#5fb874', '#3b9bd0', '#8a6fd0'].forEach((color, i) => {
        bow.add(mesh(new THREE.TorusGeometry(2.4 - i * 0.12, 0.062, 5, 40, Math.PI), color));
      });
      bow.position.set(at.x, at.y, at.z - 3.4);
      banner('Радуга над дорогой!');
      run(bow, (dt, t) => {
        // Встаёт из земли, стоит и прячется обратно.
        const up = Math.min(1, t / 1.2) * Math.min(1, Math.max(0, (11 - t) / 1.2));
        bow.scale.set(1, up, 1);
        return t < 11;
      });
    },
    butterflies() {
      const swarm = new THREE.Group();
      const flies = Array.from({ length: 9 }, () => {
        const fly = new THREE.Group();
        const color = pick(PAPER);
        const wings = [-1, 1].map((side) => {
          const wing = new THREE.Group();
          wing.add(mesh(new THREE.CircleGeometry(0.07, 5).rotateX(-Math.PI / 2), color, side * 0.065, 0, 0));
          fly.add(wing);
          return wing;
        });
        swarm.add(fly);
        return { fly, wings, a: Math.random() * 6.3, r: 0.4 + Math.random() * 0.9, h: 0.5 + Math.random() * 0.8 };
      });
      const away = edge(1, 1, 0);
      banner('Бабочки налетели!');
      run(swarm, (dt, t) => {
        pt.traveller.model.getWorldPosition(at);
        for (const [i, b] of flies.entries()) {
          const a = b.a + t * (0.9 + (i % 3) * 0.35);
          // Слетаются из-за края кадра, под конец разлетаются обратно.
          const out = Math.max(0, 1 - t / 3, (t - 11) / 3);
          const far = b.r + out * out * away;
          b.fly.position.set(at.x + Math.cos(a) * far, at.y + b.h + Math.sin(t * 4 + i) * 0.14, at.z + Math.sin(a) * far * 0.7);
          b.fly.rotation.y = -a;
          const flap = Math.sin(t * 16 + i) * 1.1;
          b.wings[0].rotation.z = -flap;
          b.wings[1].rotation.z = flap;
        }
        return t < 14;
      });
    },
    ducks() {
      pt.traveller.model.getWorldPosition(at);
      const z = at.z - 2.6;
      const family = new THREE.Group();
      const line = [1, 0.62, 0.62, 0.62, 0.62].map((size, i) => {
        const duck = new THREE.Group();
        const body = i ? '#f4d35a' : '#fdf8ea';
        duck.add(mesh(new THREE.IcosahedronGeometry(0.13, 0), body, 0, 0.14, 0));
        duck.add(mesh(new THREE.IcosahedronGeometry(0.075, 0), body, 0.1, 0.28, 0));
        duck.add(mesh(new THREE.ConeGeometry(0.035, 0.09, 4).rotateZ(-Math.PI / 2), '#e8832f', 0.19, 0.27, 0));
        duck.add(mesh(new THREE.SphereGeometry(0.012, 6, 4), '#2c2618', 0.13, 0.3, 0.06));
        duck.scale.setScalar(size);
        duck.position.x = -i * 0.34 - (i ? 0.1 : 0);
        family.add(duck);
        return duck;
      });
      const to = at.x + edge(1, 0, -2.6) + 2;
      family.position.set(at.x - edge(-1, 0, -2.6) - 0.5, at.y, z);
      banner('Утиное семейство переходит дорогу');
      run(family, (dt, t) => {
        // Далеко от дороги торопятся, у самой дороги идут степенно.
        const near = Math.abs(family.position.x - laneX(z, pt.wfc.seed)) < 3;
        family.position.x += (near ? 0.55 : 2.2) * dt;
        line.forEach((duck, i) => {
          duck.rotation.z = Math.sin(t * 9 - i) * 0.14;
          duck.position.y = Math.abs(Math.sin(t * 9 - i)) * 0.025;
        });
        return family.position.x < to && t < 40;
      });
    },
    kite() {
      const kite = new THREE.Group();
      const color = pick(['#d95b4a', '#3b7bb8', '#e8759c', '#4c8f5a']);
      const sail = mesh(new THREE.CircleGeometry(0.3, 4), color);
      sail.scale.set(0.75, 1, 1);
      kite.add(sail, mesh(new THREE.BoxGeometry(0.012, 0.6, 0.012), '#fdf8ea', 0, 0, 0.01), mesh(new THREE.BoxGeometry(0.44, 0.012, 0.012), '#fdf8ea', 0, 0, 0.01));
      const tail = Array.from({ length: 5 }, (_, i) => {
        const bow = mesh(new THREE.BoxGeometry(0.09, 0.04, 0.008), PAPER[i % PAPER.length]);
        kite.add(bow);
        return bow;
      });
      const string = mesh(new THREE.BoxGeometry(0.006, 1, 0.006), '#fdf8ea');
      const group = new THREE.Group();
      group.add(kite, string);
      const to = new THREE.Vector3();
      const UP = new THREE.Vector3(0, 1, 0);
      banner('Запустили воздушного змея!');
      run(group, (dt, t) => {
        pt.traveller.model.getWorldPosition(at);
        const rise = Math.min(1, t / 2);
        kite.position.set(at.x + 1.1 + Math.sin(t * 1.4) * 0.35, at.y + 0.5 + rise * 1.5 + Math.sin(t * 2.3) * 0.12, at.z - 0.6);
        kite.rotation.z = Math.sin(t * 1.9) * 0.28;
        tail.forEach((bow, i) => bow.position.set(Math.sin(t * 4 - i * 0.9) * 0.08, -0.38 - i * 0.13, 0));
        to.set(at.x + 0.2, at.y + 0.35, at.z).sub(kite.position);
        string.scale.y = to.length();
        string.position.copy(kite.position).addScaledVector(to, 0.5);
        string.quaternion.setFromUnitVectors(UP, to.normalize());
        return t < 12;
      });
    },
    rain() {
      const cloud = new THREE.Group();
      for (const [x, y, r] of [[-0.45, 0, 0.3], [0, 0.12, 0.42], [0.45, 0, 0.32], [0.1, -0.05, 0.34]]) cloud.add(mesh(new THREE.IcosahedronGeometry(r, 1), '#b9c6d6', x, y, 0));
      const drops = Array.from({ length: 36 }, () => {
        const drop = mesh(new THREE.BoxGeometry(0.014, 0.1, 0.014), '#5b9bd0', (Math.random() - 0.5) * 1.4, -Math.random() * 1.6, (Math.random() - 0.5) * 0.8);
        drop.castShadow = false;
        cloud.add(drop);
        return drop;
      });
      const from = edge(1, 2, 0.2) + 1;
      const to = edge(-1, 2, 0.2) + 1;
      cloud.position.set(at.x + from, at.y + 2, at.z + 0.2);
      banner('Тучка увязалась следом');
      run(cloud, (dt, t) => {
        pt.traveller.model.getWorldPosition(at);
        // Приплывает из-за края, поливает и уплывает за другой.
        const away = from * Math.max(0, 1 - t / 3) ** 2 - to * Math.max(0, (t - 10) / 3) ** 2;
        cloud.position.set(at.x + away, at.y + 2, at.z + 0.2);
        for (const drop of drops) {
          drop.position.y -= 2.6 * dt;
          if (drop.position.y < -1.9) drop.position.y = -0.2;
        }
        return t < 13;
      });
    },
    dance() {
      window.KcalFriends && window.KcalFriends.dance(3.5);
      banner(pick(['Масло пустилось в пляс!', 'Яйцо пританцовывает!', 'Друзья устроили танцы!']));
    },
  };

  /** Картонная фигурка с рисунком [art] стоит на земле: тот же бумажный материал, что и у всего мира. */
  const standee = (art, width, x, z) => {
    const face = new THREE.CanvasTexture(art);
    face.colorSpace = THREE.SRGBColorSpace;
    face.anisotropy = 4;
    const height = (width * art.height) / art.width;
    const geo = new THREE.PlaneGeometry(width, height).toNonIndexed();
    geo.translate(0, height / 2, 0);
    const card = new THREE.Mesh(geo, materials.paper({ map: face, alphaTest: 0.5, side: THREE.DoubleSide, variant: 'kcal-sign' }));
    card.castShadow = true;
    // Чуть откинута назад — камера смотрит сверху.
    card.rotation.x = -0.3;
    const stand = new THREE.Group();
    stand.add(card);
    stand.position.set(x, at.y, z);
    run(stand, (dt, t) => {
      // Раскладывается, как в книжке, с отскоком, и стоит, пока не пройдут.
      const pop = Math.min(1, t * 2.2);
      stand.scale.set(1, pop + Math.sin(pop * Math.PI) * 0.18, 1);
      return t < 40;
    });
    confetti(x, at.y + height, z);
  };

  /** Указатель с числом — сбоку тропы. */
  const post = (label) => {
    pt.traveller.model.getWorldPosition(at);
    const z = at.z - 1.2;
    standee(milestoneArt(label), 0.95, laneX(z, pt.wfc.seed) - 0.95, z);
  };

  /** Финиш — арка через всю тропу. */
  const arch = () => {
    pt.traveller.model.getWorldPosition(at);
    const z = at.z - 1.25;
    standee(goalArt(), 2.3, laneX(z, pt.wfc.seed), z);
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
      arch();
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
