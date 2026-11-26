// KcalAI: тропа дня — маленький бумажный свиток в левом верхнем углу. Снизу старт, сверху флажок цели; земли — островами
// по порядку сегодняшней дороги (window.KcalRoute, src/main.js), по извилистой тропинке идёт Зёрнышко. Шаги и темп —
// из window.KcalWalk. Поверх страницы и мимо кликов; мира нет, места слева нет или шаги ещё не названы — не видно.
import { routeSpot } from './trail-math.js';

const NS = 'http://www.w3.org/2000/svg';
/** Колонка дневника (COLUMN_MAX в DiaryPage.kt) — свиток не должен на неё наезжать. */
const COLUMN = 760;
/** Размер рисунка: свиток шириной 208 и до 600 высотой, табличке справа — запас. */
const VIEW = { w: 400, h: 620, scroll: 220 };
const TOP = 70;
const BOTTOM = 566;
/** Рисунок показывается в половину: уголок экрана, а не панель. Меньше этого — подписи уже не прочесть. */
const SCALE = 0.5;
const MIN_SCALE = 0.35;
const CORNER = 8;

const LOOK = {
  'Поле': { c: '#f4c95a', k: 'wheat' },
  'Осень': { c: '#eea45f', k: 'leaf' },
  'Пустыня': { c: '#efd49a', k: 'cactus' },
  'Молоко': { c: '#bfe3f5', k: 'milk' },
  'Снег': { c: '#e3eef5', k: 'snow' },
  'Сад': { c: '#f7bccd', k: 'flower' },
  'Лес': { c: '#86bf86', k: 'pine' },
};

const el = (parent, tag, attrs = {}, text) => {
  const node = document.createElementNS(NS, tag);
  for (const key in attrs) node.setAttribute(key, attrs[key]);
  if (text != null) node.textContent = text;
  parent.appendChild(node);
  return node;
};
const grouped = (n) => String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ' ');

/** Бумажный значок земли: вырезка со светлой кромкой. */
function prop(parent, kind, x, y, scale) {
  const g = el(parent, 'g', { transform: `translate(${x} ${y}) scale(${scale})` });
  const edge = { stroke: '#ffffff90', 'stroke-width': 1.5, 'stroke-linejoin': 'round' };
  if (kind === 'wheat') {
    for (const dx of [-9, 0, 9]) {
      el(g, 'path', { d: `M${dx * 0.4} 18Q${dx * 0.7} 4 ${dx} -6`, stroke: '#a97a1e', 'stroke-width': 2.5, fill: 'none', 'stroke-linecap': 'round' });
      for (let j = 0; j < 3; j++) {
        const cy = -8 - j * 5;
        el(g, 'ellipse', { cx: dx - 3, cy, rx: 2.6, ry: 4.5, fill: '#c9901f', transform: `rotate(-28 ${dx - 3} ${cy})` });
        el(g, 'ellipse', { cx: dx + 3, cy, rx: 2.6, ry: 4.5, fill: '#e0a82c', transform: `rotate(28 ${dx + 3} ${cy})` });
      }
    }
  } else if (kind === 'milk') {
    el(g, 'path', { d: 'M-9 18V-4L-4 -11V-18H5V-11L10 -4V18Z', fill: '#fdfdf8', ...edge });
    el(g, 'rect', { x: -5, y: -23, width: 11, height: 6, rx: 2, fill: '#5b9bd0' });
    el(g, 'rect', { x: -9, y: 1, width: 19, height: 9, fill: '#86bfe0' });
  } else if (kind === 'cactus') {
    el(g, 'path', { d: 'M-5 18V-14Q0 -22 5 -14V-2H9V-10Q12.5 -15 16 -10V1Q16 7 9 7H5V18ZM-5 8H-10Q-16 8 -16 1V-5Q-12.5 -10 -9 -5V2H-5Z', fill: '#5f9b5a', ...edge });
  } else if (kind === 'flower') {
    el(g, 'rect', { x: -2, y: 2, width: 4, height: 16, fill: '#6a8f4a' });
    for (let i = 0; i < 5; i++) {
      const a = (i * Math.PI * 2) / 5;
      el(g, 'circle', { cx: Math.cos(a) * 8, cy: -6 + Math.sin(a) * 8, r: 6.5, fill: '#e8759c', ...edge });
    }
    el(g, 'circle', { cx: 0, cy: -6, r: 5, fill: '#ffd76a' });
  } else if (kind === 'leaf') {
    el(g, 'rect', { x: -3, y: 6, width: 6, height: 13, fill: '#8a5a33' });
    for (const [cx, cy, r, fill] of [[-8, 0, 11, '#d9662f'], [8, 0, 11, '#e58a33'], [0, -10, 13, '#f0a63b']]) el(g, 'circle', { cx, cy, r, fill, ...edge });
  } else if (kind === 'snow') {
    el(g, 'circle', { cx: 0, cy: 8, r: 11, fill: '#fff', stroke: '#a9c6da', 'stroke-width': 1.5 });
    el(g, 'circle', { cx: 0, cy: -9, r: 8, fill: '#fff', stroke: '#a9c6da', 'stroke-width': 1.5 });
    for (const cx of [-3, 3]) el(g, 'circle', { cx, cy: -11, r: 1.4, fill: '#2c2618' });
    el(g, 'path', { d: 'M0 -9l9 2l-9 2z', fill: '#e8832f' });
  } else {
    el(g, 'rect', { x: -3, y: 10, width: 6, height: 9, fill: '#8a5a33' });
    el(g, 'path', { d: 'M0 -24L12 -6H6L15 6H8L17 14H-17L-8 6H-15L-6 -6H-12Z', fill: '#3f7a4e', ...edge });
  }
}

/** Зёрнышко — тот же рисунок, что в приложении (core/ui, GrainArt), плюс ножки для ходьбы. */
function grain(parent, shadow) {
  const g = el(parent, 'g');
  el(g, 'ellipse', { cx: 0, cy: 2, rx: 22, ry: 5, fill: '#0003' });
  const body = el(g, 'g');
  const feet = [-9, 9].map((cx) => el(body, 'ellipse', { cx, cy: -1, rx: 8, ry: 4.5, fill: '#3e7a4b' }));
  const art = el(body, 'g', { transform: 'translate(-42 -66) scale(.7)', filter: shadow });
  el(art, 'path', { d: 'M60,22 L53,6 M60,22 L60,2 M60,22 L67,6', stroke: '#4C8F5A', 'stroke-width': 5, 'stroke-linecap': 'round', fill: 'none' });
  el(art, 'path', { d: 'M60,20 C80,20 90,46 90,64 C90,84 76,94 60,94 C44,94 30,84 30,64 C30,46 40,20 60,20 Z', fill: '#7FBF8C', stroke: '#ffffffaa', 'stroke-width': 3 });
  el(art, 'path', { d: 'M60,28 C57,42 54,52 56,62', stroke: '#3E7A4B', 'stroke-width': 2, fill: 'none', 'stroke-linecap': 'round' });
  for (const cx of [48, 72]) {
    el(art, 'circle', { cx, cy: 60, r: 5, fill: '#2c2618' });
    el(art, 'circle', { cx: cx + 1.7, cy: 58.2, r: 1.7, fill: '#fff' });
  }
  for (const cx of [40, 80]) el(art, 'ellipse', { cx, cy: 71, rx: 6, ry: 4, fill: '#f2a0a0', opacity: 0.7 });
  el(art, 'path', { d: 'M52,73 C56,81 64,81 68,73', stroke: '#2c2618', 'stroke-width': 3, fill: 'none', 'stroke-linecap': 'round' });
  return { g, body, feet };
}

/** Свиток под сегодняшние земли. */
function build(svg, lands) {
  svg.textContent = '';
  const defs = el(svg, 'defs');
  const filter = el(defs, 'filter', { id: 'trail-shadow', x: '-30%', y: '-30%', width: '160%', height: '170%' });
  el(filter, 'feDropShadow', { dx: 0, dy: 5, stdDeviation: 4, 'flood-color': '#4a3a12', 'flood-opacity': 0.28 });
  const shadow = 'url(#trail-shadow)';
  el(svg, 'path', { d: 'M22 22Q112 12 202 22Q212 310 204 598Q112 610 20 598Q12 310 22 22Z', fill: '#fdf8ea', stroke: '#d9c9a3', 'stroke-width': 3, filter: shadow });

  const h = (BOTTOM - TOP) / lands.length;
  lands.forEach((name, i) => {
    const look = LOOK[name] || LOOK['Лес'];
    const y = BOTTOM - h * (i + 1);
    const island = el(svg, 'g', { filter: shadow });
    el(island, 'path', {
      d: `M34 ${y + 7}q40 -12 78 -3t76 2q8 ${h / 2} -2 ${h - 9}q-38 11 -76 2t-76 -1q-8 -${h / 2} 0 -${h - 7}z`,
      fill: look.c, stroke: '#ffffffbb', 'stroke-width': 3,
    });
    const right = i % 2 === 0;
    // Мало места на землю (много земель) — значки мельче и без второго.
    const size = Math.min(1, h / 90);
    prop(svg, look.k, right ? 158 : 62, y + h / 2 + 4 * size, size);
    if (h >= 80) prop(svg, look.k, right ? 178 : 82, y + h / 2 + 16, 0.62);
    el(svg, 'text', { x: right ? 160 : 66, y: y + 22, 'text-anchor': 'middle', 'font-size': 20, 'font-weight': 800, fill: '#4a3b17' }, name);
  });

  const d = `M112 ${BOTTOM + 14}` + lands.map((_, i) => {
    const y0 = BOTTOM - h * i;
    const y1 = BOTTOM - h * (i + 1);
    const x = 112 + (i % 2 ? 52 : -52);
    return `C${x} ${y0 - h * 0.3} ${x} ${y1 + h * 0.3} 112 ${y1}`;
  }).join('');
  const line = { d, fill: 'none', 'stroke-linecap': 'round' };
  el(svg, 'path', { ...line, stroke: '#b78d4d', 'stroke-width': 17 });
  el(svg, 'path', { ...line, stroke: '#f6e3b4', 'stroke-width': 12 });
  const done = el(svg, 'path', { ...line, stroke: '#4c8f5a', 'stroke-width': 5 });

  el(svg, 'path', { d: `M112 ${TOP + 2}v-38`, stroke: '#6b4a2a', 'stroke-width': 4, 'stroke-linecap': 'round' });
  el(svg, 'path', { d: `M114 ${TOP - 36}q14 -5 28 3q-12 5 -9 14q-10 -5 -19 -1z`, fill: '#d95b4a', stroke: '#fff9', 'stroke-width': 1.5 });

  const hero = grain(svg, shadow);
  const sign = el(svg, 'g', { filter: shadow });
  const board = el(sign, 'rect', { x: 212, width: 10, height: 38, rx: 9, fill: '#fdf8ea', stroke: '#d9c9a3', 'stroke-width': 1.5 });
  const label = el(sign, 'text', { 'font-size': 23, 'font-weight': 800, fill: '#2c2618' });
  return { done, hero, board, label };
}

const svg = document.createElementNS(NS, 'svg');
svg.id = 'walk-trail';
svg.setAttribute('viewBox', `0 0 ${VIEW.w} ${VIEW.h}`);
svg.setAttribute('aria-hidden', 'true');
svg.style.cssText = 'position:fixed;z-index:1;pointer-events:none;display:none;font-family:system-ui,sans-serif';
document.body.appendChild(svg);

let drawn = '';
let parts = null;
let shown = null;
let signed = '';
let last = 0;
const calm = matchMedia('(prefers-reduced-motion: reduce)').matches;

/** Свиток — в левом верхнем углу; в поле слева от колонки не помещается и уменьшенным — не показываем. */
function place() {
  const gutter = (innerWidth - COLUMN) / 2;
  const scale = Math.min(SCALE, (innerHeight - 2 * CORNER) / VIEW.h, (gutter - 2 * CORNER) / VIEW.scroll);
  if (scale < MIN_SCALE) return false;
  svg.style.width = `${VIEW.w * scale}px`;
  svg.style.height = `${VIEW.h * scale}px`;
  svg.style.left = `${CORNER}px`;
  svg.style.top = `${CORNER}px`;
  return true;
}

function frame(time) {
  requestAnimationFrame(frame);
  const dt = Math.min(0.05, (time - last) / 1000 || 0);
  last = time;
  const route = window.KcalRoute;
  const walk = window.KcalWalk;
  const visible = document.body.classList.contains('walking') && route && route.lands.length > 0 && walk && walk.steps >= 0 && place();
  svg.style.display = visible ? 'block' : 'none';
  if (!visible) return;

  const key = route.lands.join('|');
  if (key !== drawn) {
    parts = build(svg, route.lands);
    drawn = key;
    signed = '';
  }
  // Свежий итог шагов Зёрнышко догоняет плавно, а не прыжком.
  shown = shown == null ? walk.steps : shown + (walk.steps - shown) * Math.min(1, dt * 1.5);
  const progress = Math.max(0, Math.min(1, shown / route.goal));
  const length = parts.done.getTotalLength();
  const at = parts.done.getPointAtLength(length * progress);
  parts.done.setAttribute('stroke-dasharray', `${length * progress} ${length}`);

  const going = walk.vis > 0.3 || Math.abs(walk.steps - shown) > 20;
  const swing = calm ? 0 : Math.sin((time / 1000) * (going ? 11 : 2.6));
  const amp = going ? 1 : 0.25;
  parts.hero.g.setAttribute('transform', `translate(${at.x} ${at.y + 4})`);
  parts.hero.body.setAttribute('transform', `translate(0 ${-Math.abs(swing) * 7 * amp}) rotate(${swing * 10 * amp})`);
  parts.hero.feet[0].setAttribute('transform', `translate(0 ${-Math.max(0, swing) * 7 * amp})`);
  parts.hero.feet[1].setAttribute('transform', `translate(0 ${-Math.max(0, -swing) * 7 * amp})`);

  const spot = routeSpot(route.lands.length, route.goal, walk.steps);
  const text = spot ? `${spot.next == null ? 'Финиш' : route.lands[spot.next]}: ${grouped(spot.left)}` : 'Дошли!';
  if (text !== signed) {
    parts.label.textContent = text;
    parts.board.setAttribute('width', parts.label.getComputedTextLength() + 20);
    signed = text;
  }
  parts.board.setAttribute('y', at.y - 52);
  parts.label.setAttribute('x', 222);
  parts.label.setAttribute('y', at.y - 25);
}
requestAnimationFrame(frame);
