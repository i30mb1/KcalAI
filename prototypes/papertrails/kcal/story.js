// KcalAI: сюжеты прогулки, как в мультике. Дорожка решает, чем заняты друзья (story-math.js): идут — шагают следом;
// постояли — привал: костёр, чай, хлеб с сыром; стоят долго — дремлют; пошли — вскочили и догоняют. Рядом домик —
// кто-то прыжками забегает в гости и возвращается с гостинцем. И по месту: у пруда рыбачат, в снегу играют в
// снежки, в поле устраивают пикник, в лесу пугаются. Только мир: в дневник ничего не пишется.
import * as THREE from 'three';
import { nearestHouse, pickStory, placeStories, stage, walkStories } from './story-math.js';
import { biomeAt } from '../src/core/biomes.js';
import { banner } from './events.js';
import { laneX } from './lane.js';

const TREATS = ['с пирожком', 'с баранками', 'с банкой мёда', 'с горстью орехов', 'с куском пирога', 'с яблоком'];
const NAMES = ['Масло', 'Яйцо'];
/** Сколько ждать до следующего сюжета по месту и как долго сидят в гостях, секунды. */
const STORY_PAUSE = 20;
const VISIT_INSIDE = 6;
const HOP_SPEED = 1.7;
/** Чем занят Зёрнышко, пока стоит, и сколько секунд. */
const ACTS = { wave: 3.4, look: 3.2, hop: 1.4, stretch: 2.6, spin: 1.2 };

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
  let nextStory = 6;
  let lastStory = null;
  /** Идущий сюжет по месту: чем убрать за ним реквизит. */
  let story = null;
  let forced = null;
  let clock = 0;

  const hasCell = (cx, cz, test) => {
    const cell = pt.world.tileAt(cx, cz);
    return Boolean(cell && test(cell));
  };
  const isHouse = (cx, cz) => hasCell(cx, cz, (cell) => cell.tags && cell.tags.includes('house'));
  const isPond = (cx, cz) => hasCell(cx, cz, (cell) => cell.tileId === 'pond');
  const near = (test) => {
    pt.traveller.model.getWorldPosition(at);
    return nearestHouse(at, test);
  };

  const stop = () => {
    if (story) story();
    story = null;
    visit = null;
    friends.forEach((f) => {
      f.root.visible = true;
      f.script = null;
      stand(f);
    });
  };

  /** Сидя ножки подбирают под себя: торчащие вперёд выглядели некрасиво. Фигурка садится на землю. */
  const SEAT = -0.092;
  const tuck = (f) => {
    for (const leg of f.legs) leg.visible = false;
  };
  const stand = (f) => {
    for (const leg of f.legs) {
      leg.visible = true;
      leg.rotation.x = 0;
    }
  };
  /** Точка рядом с героем, на тропе позади: туда ставят сценки на двоих. */
  const behind = (dx, dz) => {
    pt.traveller.model.getWorldPosition(at);
    return new THREE.Vector3(laneX(at.z + dz, pt.wfc.seed) + dx, at.y, at.z + dz);
  };
  const ease = (f, target, dt, turn) => {
    f.root.position.lerp(target, 1 - Math.exp(-4 * dt));
    f.root.rotation.y += (turn - f.root.rotation.y) * (1 - Math.exp(-5 * dt));
    stand(f);
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
    f.body.position.y = SEAT + Math.sin(clock * (asleep ? 1.2 : 2.4) + index * 2) * 0.006;
    f.body.rotation.z = asleep ? side * -0.28 : Math.sin(clock * 1.1 + index * 3) * 0.06;
    tuck(f);
  };

  const enter = (next) => {
    if (next === now) return;
    const was = now;
    now = next;
    stop();
    if (next === 'walk') {
      camp.visible = false;
      sleeps.forEach((z) => { z.visible = false; });
      banner('Пошли дальше!');
      return;
    }
    if (was === 'walk') {
      pt.traveller.model.getWorldPosition(at);
      camp.position.set(laneX(at.z + 0.62, pt.wfc.seed), at.y, at.z + 0.62);
      camp.visible = true;
    }
    friends.forEach((f, i) => { f.script = sit(i, next === 'doze'); });
    banner(next === 'camp' ? 'Привал: чай у костра' : 'Задремали у костра…');
  };

  /** Один из друзей прыжками — туда, побыть там и обратно с добычей. */
  const errand = (spot, { gone, going, back: returned, there }) => {
    const index = Math.floor(Math.random() * friends.length);
    const f = friends[index];
    const from = f.root.position.clone();
    const goal = new THREE.Vector3(spot.x, from.y, spot.z);
    const duration = Math.max(0.8, from.distanceTo(goal) / HOP_SPEED);
    let t = 0;
    let arrived = false;
    let props = null;
    visit = index;
    banner(going(NAMES[index]));
    const drop = () => {
      if (props) scene.remove(props);
      props = null;
    };
    story = drop;
    f.script = (dt) => {
      t += dt;
      const returning = t > duration + VISIT_INSIDE;
      if (!returning && t >= duration) {
        if (!arrived && there) props = there(goal);
        arrived = true;
        if (gone) f.root.visible = false;
        else {
          f.body.position.y = SEAT;
          tuck(f);
        }
        return;
      }
      if (returning && arrived) {
        arrived = false;
        drop();
        f.root.visible = true;
        banner(returned(NAMES[index]));
      }
      const done = Math.min(1, returning ? (t - duration - VISIT_INSIDE) / duration : t / duration);
      // Обратно — к герою, где он сейчас.
      const a = returning ? goal : from;
      const b = returning ? behind(0, 0.55) : goal;
      const was = f.root.position.clone();
      f.root.position.lerpVectors(a, b, done);
      f.root.rotation.y = Math.atan2(f.root.position.x - was.x, f.root.position.z - was.z) || f.root.rotation.y;
      f.body.position.y = Math.abs(Math.sin(done * Math.PI * Math.max(2, Math.round(duration * 2)))) * 0.17;
      stand(f);
      f.legs[0].rotation.x = -0.2;
      f.legs[1].rotation.x = 0.2;
      if (returning && done >= 1) stop();
    };
  };

  /** Сценка на двоих на [seconds]: [play] ставит друзей и реквизит каждый кадр. */
  const scenelet = (seconds, props, play, ending) => {
    let t = 0;
    if (props) scene.add(props);
    story = () => {
      if (props) scene.remove(props);
    };
    friends.forEach((f, i) => {
      f.script = (dt) => {
        if (i === 0) t += dt;
        play(f, i, dt, t);
        if (i === friends.length - 1 && t > seconds) {
          stop();
          banner(ending);
        }
      };
    });
  };

  const STORIES = {
    race() {
      banner('Догонялки!');
      scenelet(7, null, (f, i, dt, t) => {
        // Забегают вперёд героя по разным краям тропы и возвращаются.
        const side = i ? 1 : -1;
        const run = Math.sin(Math.min(1, t / 7) * Math.PI);
        ease(f, behind(side * 0.4, 0.7 - run * (i ? 2.6 : 3.2)), dt, t < 3.5 ? Math.PI : 0);
        f.body.position.y = Math.abs(Math.sin(t * 11 + i)) * 0.045;
        for (const [n, leg] of f.legs.entries()) leg.rotation.x = Math.sin(t * 11 + n * Math.PI) * 0.4;
      }, `${pick(NAMES)} победило!`);
      return true;
    },
    leapfrog() {
      banner('Чехарда!');
      scenelet(6, null, (f, i, dt, t) => {
        // По очереди перепрыгивают друг через друга.
        const turn = Math.floor(t / 1) % 2;
        const k = t % 1;
        const jumps = turn === i;
        const from = jumps ? 1.15 : 0.55;
        const to = jumps ? 0.55 : 1.15;
        f.root.position.copy(behind(0, from + (to - from) * k));
        f.root.rotation.y = Math.PI;
        // Перелёт ровно над макушкой друга, не выше.
        f.body.position.y = jumps ? Math.sin(k * Math.PI) * 0.4 : -0.05 * Math.sin(k * Math.PI);
        for (const leg of f.legs) leg.rotation.x = jumps ? -0.25 : 0;
      }, 'Напрыгались.');
      return true;
    },
    visit() {
      const house = near(isHouse);
      if (!house) return false;
      errand({ x: house.x, z: house.z + 0.55 }, {
        gone: true,
        going: (name) => `${name} заглянуло в гости`,
        back: (name) => `${name} вернулось ${pick(TREATS)}!`,
      });
      return true;
    },
    fishing() {
      const pond = near(isPond);
      if (!pond) return false;
      errand({ x: pond.x, z: pond.z + 0.65 }, {
        going: (name) => `${name} пошло на рыбалку`,
        back: (name) => `${name} вернулось ${pick(['с карасём', 'с пескарём', 'со старым сапогом', 'с золотой рыбкой'])}!`,
        there: (spot) => {
          const rod = new THREE.Group();
          const stick = mesh(new THREE.CylinderGeometry(0.01, 0.012, 0.6, 5), '#8a5a33', 0, 0.35, -0.2);
          stick.rotation.x = -0.9;
          rod.add(stick);
          rod.add(mesh(new THREE.BoxGeometry(0.004, 0.42, 0.004), '#fdf8ea', 0, 0.28, -0.44));
          rod.add(mesh(new THREE.IcosahedronGeometry(0.03, 0), '#d95b4a', 0, 0.07, -0.44));
          rod.position.copy(spot);
          scene.add(rod);
          return rod;
        },
      });
      return true;
    },
    snowball() {
      const ball = mesh(new THREE.IcosahedronGeometry(0.06, 0), '#ffffff');
      banner('Снежки!');
      scenelet(7, ball, (f, i, dt, t) => {
        const side = i ? 1 : -1;
        ease(f, behind(side * 0.55, 0.7), dt, -side * 1.4);
        // Кидают по очереди: снежок летит дугой, получивший подпрыгивает.
        const k = (t / 1.1) % 1;
        const from = Math.floor(t / 1.1) % 2 ? 1 : -1;
        f.body.position.y = side !== from && k > 0.85 ? Math.sin(((k - 0.85) / 0.15) * Math.PI) * 0.08 : 0;
        f.body.rotation.z = side === from && k < 0.2 ? from * 0.5 : 0;
        if (i === 0) {
          const spot = behind(0, 0.7);
          ball.position.set(spot.x + from * 0.5 * (1 - 2 * k), spot.y + 0.3 + Math.sin(k * Math.PI) * 0.35, spot.z);
        }
      }, 'Ничья! Все в снегу.');
      return true;
    },
    picnic() {
      const spread = new THREE.Group();
      for (let i = 0; i < 4; i++) {
        for (let j = 0; j < 3; j++) {
          spread.add(mesh(new THREE.BoxGeometry(0.17, 0.008, 0.13), (i + j) % 2 ? '#fdf8ea' : '#d95b4a', (i - 1.5) * 0.17, 0.006, (j - 1) * 0.13));
        }
      }
      spread.add(mesh(new THREE.IcosahedronGeometry(0.06, 0), '#c8394f', -0.2, 0.065, 0));
      spread.add(mesh(new THREE.SphereGeometry(0.1, 8, 5, 0, Math.PI * 2, 0, Math.PI / 2), '#d99a4e', 0.04, 0.01, -0.03));
      spread.add(mesh(new THREE.CylinderGeometry(0.045, 0.06, 0.14, 8), '#86bfe0', 0.22, 0.08, 0.02));
      spread.position.copy(behind(0, 0.75));
      banner('Пикник!');
      scenelet(12, spread, (f, i, dt, t) => {
        const side = i ? 1 : -1;
        ease(f, new THREE.Vector3(spread.position.x + side * 0.45, spread.position.y, spread.position.z), dt, -side * 0.55);
        f.body.position.y = SEAT + Math.abs(Math.sin(t * 5 + i * 2)) * 0.012;
        f.body.rotation.z = Math.sin(t * 1.3 + i * 3) * 0.07;
        tuck(f);
      }, 'Вкусно посидели.');
      return true;
    },
    scare() {
      banner('Ой! Что там в лесу?');
      scenelet(6, null, (f, i, dt, t) => {
        // Яйцо прячется за героя и дрожит, потом выглядывает; Масло озирается.
        const hides = i === 1;
        const aside = hides ? (t > 3.5 ? 0.3 : 0) : -0.4;
        ease(f, behind(aside, hides ? 0.28 : 0.65), dt, hides ? Math.PI : Math.sin(t * 3) * 1.2);
        f.body.position.y = 0;
        f.body.rotation.z = hides && t < 3.5 ? Math.sin(t * 40) * 0.08 : 0;
      }, pick(['Это всего лишь шишка.', 'Фух, это ёжик.', 'Это эхо!']));
      return true;
    },
  };

  /** Что здесь и сейчас можно: на ходу — только то, что не требует отставать. */
  const here = (walking) => {
    pt.traveller.model.getWorldPosition(at);
    const biome = biomeAt(pt.wfc.seed, Math.round(at.x / 2), Math.round(at.z / 2));
    if (walking) return walkStories({ biome });
    const house = Boolean(nearestHouse(at, isHouse));
    const all = placeStories({ biome, house, pond: Boolean(nearestHouse(at, isPond)) });
    // Стоя годятся и догонялки: в пустом месте иначе нечего делать.
    return [...all.filter((name) => !(now === 'camp' && name === 'picnic')), 'race', 'leapfrog'];
  };
  const play = (name) => {
    if (!name || !STORIES[name]()) return false;
    lastStory = name;
    return true;
  };

  // ---- Зёрнышко: стоя не столбом ----
  // Походкой фигурки занимается мир (traveller.js, через свои шарниры); здесь крутится только она целиком и сами ручки.
  const part = (name) => hero.getObjectByName(name);
  const armL = part('arm_l');
  const armR = part('arm_r');
  const legs = [part('leg_l'), part('leg_r')];
  let act = null;
  let actAt = 0;
  let nextAct = 3;
  let lastAct = null;
  const heroPose = (dt, walking) => {
    // Куда стремится поза; плавно — чтобы не дёргался, когда занятие сменяется.
    let turn = 0;
    let lift = 0;
    let tilt = 0;
    let left = 0;
    let right = 0;
    const sitting = !walking && now !== 'walk';
    if (walking) {
      act = null;
      nextAct = 4;
    } else if (sitting) {
      // На привале садится лицом к огню — а значит, и к тебе.
      act = null;
      turn = Math.PI;
      lift = -0.13;
      tilt = now === 'doze' ? 0.22 : Math.sin(clock * 1.1) * 0.05;
      if (now === 'camp') left = Math.max(0, Math.sin(clock * 0.9)) * 1.1; // подносит кружку
    } else if (act) {
      actAt += dt;
      const k = actAt / ACTS[act];
      if (act === 'wave') {
        turn = Math.PI;
        if (k > 0.2 && k < 0.85) left = 2.4 + Math.sin(actAt * 13) * 0.45;
      } else if (act === 'look') {
        turn = Math.sin(k * Math.PI * 2) * 1.2;
      } else if (act === 'hop') {
        lift = Math.abs(Math.sin(k * Math.PI * 2)) * 0.12;
      } else if (act === 'stretch') {
        left = Math.sin(Math.min(1, k) * Math.PI) * 2.7;
        right = -left;
        tilt = Math.sin(k * Math.PI * 2) * 0.1;
      } else {
        turn = k * Math.PI * 2;
        lift = Math.sin(k * Math.PI) * 0.08;
      }
      if (k >= 1) {
        act = null;
        nextAct = 3 + Math.random() * 5;
      }
    } else {
      nextAct -= dt;
      if (nextAct <= 0) {
        act = pickStory(Object.keys(ACTS), lastAct);
        lastAct = act;
        actAt = 0;
      }
    }
    const k = 1 - Math.exp(-9 * dt);
    // Кружение крутится ровно, остальное догоняет по кратчайшей дуге.
    if (act === 'spin') hero.rotation.y = turn;
    else hero.rotation.y += Math.atan2(Math.sin(turn - hero.rotation.y), Math.cos(turn - hero.rotation.y)) * k;
    hero.position.y += (lift - hero.position.y) * k;
    hero.rotation.z += (tilt - hero.rotation.z) * k;
    if (armL) armL.rotation.z += (left - armL.rotation.z) * k;
    if (armR) armR.rotation.z += (right - armR.rotation.z) * k;
    for (const leg of legs) if (leg) leg.visible = !sitting;
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
    heroPose(dt, walking);

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

    if (story || visit !== null || now === 'doze') return;
    nextStory -= dt;
    if (nextStory <= 0) nextStory = play(pickStory(here(walking), lastStory)) ? STORY_PAUSE : 10;
  };
  requestAnimationFrame(frame);

  /** Для проверки: начать привал или дрёму сразу, разыграть сюжет по имени. */
  window.KcalStory = {
    set: (name) => { forced = name; },
    act: (name) => {
      act = name;
      actAt = 0;
    },
    play: (name) => {
      stop();
      return play(name);
    },
    here: () => here(false),
  };
}

const wait = setInterval(() => {
  if (!window.__pt || !window.__ptPaper || !window.KcalFriends) return;
  clearInterval(wait);
  start(window.__pt, window.__ptPaper, window.KcalFriends.list);
}, 300);
