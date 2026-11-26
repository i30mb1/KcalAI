// KcalAI: сюжеты прогулки, как в мультике. Дорожка решает, чем заняты друзья (story-math.js): идут — шагают следом;
// постояли — привал: костёр, чай, хлеб с сыром; стоят долго — дремлют; пошли — вскочили и догоняют. Рядом домик —
// кто-то прыжками забегает в гости и возвращается с гостинцем. Только мир: в дневник ничего не пишется.
import * as THREE from 'three';
import { nearestHouse, stage } from './story-math.js';
import { banner } from './events.js';

const TREATS = ['с пирожком', 'с баранками', 'с банкой мёда', 'с горстью орехов', 'с куском пирога', 'с яблоком'];
const NAMES = ['Масло', 'Яйцо'];
/** Сколько домик не навещают снова и как долго сидят в гостях, секунды. */
const VISIT_PAUSE = 90;
const VISIT_INSIDE = 6;
const HOP_SPEED = 1.7;

const pick = (list) => list[Math.floor(Math.random() * list.length)];

function start(pt, materials, friends) {
  const hero = pt.traveller.model;
  let scene = hero;
  while (scene.parent) scene = scene.parent;
  const mesh = (geo, color, x = 0, y = 0, z = 0, extra = {}) => {
    const g = geo.index ? geo.toNonIndexed() : geo;
    g.computeVertexNormals();
    const m = new THREE.Mesh(g, materials.paper({ color, side: THREE.DoubleSide, variant: 'traveller', ...extra }));
    m.position.set(x, y, z);
    m.castShadow = true;
    return m;
  };
  const glow = (color, strength) => ({ emissive: color, emissiveIntensity: strength, fibre: 'none', vary: false });

  // ---- стоянка: поленья крестом, камни, три языка пламени, чашки, хлеб и сыр ----
  const camp = new THREE.Group();
  for (const turn of [0.6, -0.6]) {
    const log = mesh(new THREE.CylinderGeometry(0.035, 0.035, 0.34, 6), '#8a5a33', 0, 0.04, 0);
    log.rotation.set(0, turn, Math.PI / 2);
    camp.add(log);
  }
  for (let i = 0; i < 7; i++) {
    const a = (i / 7) * Math.PI * 2;
    camp.add(mesh(new THREE.IcosahedronGeometry(0.04, 0), '#b9b3a4', Math.cos(a) * 0.19, 0.025, Math.sin(a) * 0.19));
  }
  const flames = [['#ff8d1a', 0.09, 0.24, 0], ['#ffc34d', 0.055, 0.17, 0.03], ['#e8512f', 0.05, 0.13, -0.06]].map(([color, r, h, x]) => {
    const flame = mesh(new THREE.ConeGeometry(r, h, 5), color, x, 0.06 + h / 2, 0, glow(color, 1.5));
    camp.add(flame);
    return { flame, h };
  });
  const steam = [];
  for (const sx of [-1, 1]) {
    camp.add(mesh(new THREE.CylinderGeometry(0.045, 0.035, 0.07, 8), '#fdf8ea', sx * 0.28, 0.035, 0.12));
    camp.add(mesh(new THREE.TorusGeometry(0.022, 0.007, 5, 8), '#fdf8ea', sx * 0.335, 0.04, 0.12));
    const puff = mesh(new THREE.IcosahedronGeometry(0.022, 0), '#ffffff', sx * 0.28, 0.12, 0.12);
    camp.add(puff);
    steam.push(puff);
  }
  camp.add(mesh(new THREE.SphereGeometry(0.08, 8, 5, 0, Math.PI * 2, 0, Math.PI / 2), '#d99a4e', -0.05, 0.005, 0.27));
  camp.add(mesh(new THREE.CylinderGeometry(0.075, 0.075, 0.055, 3), '#f4c95a', 0.11, 0.03, 0.28));
  camp.visible = false;
  scene.add(camp);

  // ---- «z-z-z» над дремлющими ----
  const letterZ = () => {
    const z = new THREE.Group();
    // Тёмно-голубые и крупные: на снегу кремовых не видно.
    z.scale.setScalar(1.5);
    z.add(mesh(new THREE.BoxGeometry(0.09, 0.018, 0.012), '#3f6f99', 0, 0.036, 0));
    z.add(mesh(new THREE.BoxGeometry(0.09, 0.018, 0.012), '#3f6f99', 0, -0.036, 0));
    const slant = mesh(new THREE.BoxGeometry(0.105, 0.018, 0.012), '#3f6f99');
    slant.rotation.z = 0.68;
    z.add(slant);
    z.visible = false;
    scene.add(z);
    return z;
  };
  const sleeps = friends.map(letterZ);

  const at = new THREE.Vector3();
  let now = 'walk';
  let stopped = 0;
  let visit = null;
  let nextVisit = 25;
  let forced = null;
  let clock = 0;

  const isHouse = (cx, cz) => {
    const cell = pt.world.tileAt(cx, cz);
    return Boolean(cell && cell.tags && cell.tags.includes('house'));
  };

  /** Сидит у костра (или спит): ножки вперёд, чуть покачивается. */
  const sit = (index, asleep) => (dt) => {
    const f = friends[index];
    const side = index ? 1 : -1;
    const p = f.root.position;
    const k = 1 - Math.exp(-3 * dt);
    p.x += (camp.position.x + side * 0.38 - p.x) * k;
    p.z += (camp.position.z + 0.02 - p.z) * k;
    p.y = camp.position.y;
    f.root.rotation.y += (-side * 0.55 - f.root.rotation.y) * k;
    f.body.position.y = -0.055 + Math.sin(clock * (asleep ? 1.2 : 2.4) + index * 2) * 0.008;
    f.body.rotation.z = asleep ? side * -0.28 : Math.sin(clock * 1.1 + index * 3) * 0.06;
    for (const leg of f.legs) leg.rotation.x = -1.35;
  };

  const enter = (next) => {
    if (next === now) return;
    const was = now;
    now = next;
    visit = null;
    friends.forEach((f) => {
      f.root.visible = true;
      f.script = null;
    });
    if (next === 'walk') {
      camp.visible = false;
      sleeps.forEach((z) => { z.visible = false; });
      banner('Пошли дальше!');
      return;
    }
    if (was === 'walk') {
      pt.traveller.model.getWorldPosition(at);
      camp.position.set(0, at.y, at.z + 0.62);
      camp.visible = true;
    }
    friends.forEach((f, i) => { f.script = sit(i, next === 'doze'); });
    banner(next === 'camp' ? 'Привал: чай у костра' : 'Задремали у костра…');
  };

  /** Один из друзей прыжками — к двери, внутрь и обратно с гостинцем. */
  const goVisiting = (house) => {
    const index = Math.floor(Math.random() * friends.length);
    const f = friends[index];
    const from = f.root.position.clone();
    const door = new THREE.Vector3(house.x, from.y, house.z + 0.55);
    const duration = Math.max(0.8, from.distanceTo(door) / HOP_SPEED);
    let t = 0;
    visit = index;
    banner(`${NAMES[index]} заглянуло в гости`);
    f.script = (dt) => {
      t += dt;
      const back = t > duration + VISIT_INSIDE;
      const leg = back ? (t - duration - VISIT_INSIDE) / duration : t / duration;
      if (!back && t >= duration) {
        f.root.visible = false;
        return;
      }
      if (back && !f.root.visible) {
        f.root.visible = true;
        banner(`${NAMES[index]} вернулось ${pick(TREATS)}!`);
      }
      const done = Math.min(1, leg);
      const a = back ? door : from;
      // Обратно — к герою, где он сейчас.
      pt.traveller.model.getWorldPosition(at);
      const b = back ? new THREE.Vector3(0, at.y, at.z + 0.55) : door;
      const was = f.root.position.clone();
      f.root.position.lerpVectors(a, b, done);
      f.root.rotation.y = Math.atan2(f.root.position.x - was.x, f.root.position.z - was.z) || f.root.rotation.y;
      f.body.position.y = Math.abs(Math.sin(done * Math.PI * Math.max(2, Math.round(duration * 2)))) * 0.45;
      f.legs[0].rotation.x = -0.5;
      f.legs[1].rotation.x = 0.5;
      if (back && done >= 1) {
        f.script = null;
        visit = null;
      }
    };
  };

  let last = performance.now();
  const frame = (time) => {
    requestAnimationFrame(frame);
    const dt = Math.min(0.05, (time - last) / 1000);
    last = time;
    if (document.hidden || !document.body.classList.contains('walking')) return;
    clock += dt;
    const speed = (window.KcalTune && window.KcalTune.speed) ?? (window.KcalWalk && window.KcalWalk.vis) ?? 0;
    const walking = speed > 0.3;
    stopped = walking ? 0 : stopped + dt;
    if (walking) forced = null;
    enter(forced || stage(walking, stopped));

    flames.forEach(({ flame, h }, i) => {
      const s = 1 + Math.sin(clock * 13 + i * 2.1) * 0.18 + Math.sin(clock * 7 + i) * 0.1;
      flame.scale.set(1, s, 1);
      flame.position.y = 0.06 + (h * s) / 2;
      flame.rotation.y += dt * 2;
    });
    steam.forEach((puff, i) => {
      const t = (clock * 0.6 + i * 0.5) % 1;
      puff.position.y = 0.1 + t * 0.22;
      puff.scale.setScalar(1 - t * 0.8);
    });
    sleeps.forEach((z, i) => {
      z.visible = now === 'doze' && visit !== i;
      const t = (clock * 0.35 + i * 0.45) % 1;
      const p = friends[i].root.position;
      z.position.set(p.x + 0.1 + t * 0.18, p.y + 0.55 + t * 0.4, p.z);
      z.scale.setScalar(0.9 + t);
    });

    nextVisit -= dt;
    if (nextVisit <= 0 && visit === null && now !== 'doze') {
      pt.traveller.model.getWorldPosition(at);
      // На ходу в гости не бегают: герой уйдёт далеко, пока друг сидит.
      const house = walking ? null : nearestHouse(at, isHouse);
      if (house) {
        goVisiting(house);
        nextVisit = VISIT_PAUSE;
      } else {
        nextVisit = 10;
      }
    }
  };
  requestAnimationFrame(frame);

  /** Для проверки: начать привал или дрёму сразу, отправить в гости. */
  window.KcalStory = {
    set: (name) => { forced = name; },
    visit: () => {
      pt.traveller.model.getWorldPosition(at);
      const house = nearestHouse(at, isHouse);
      if (house) goVisiting(house);
      return Boolean(house);
    },
  };
}

const wait = setInterval(() => {
  if (!window.__pt || !window.__ptPaper || !window.KcalFriends) return;
  clearInterval(wait);
  start(window.__pt, window.__ptPaper, window.KcalFriends.list);
}, 300);
