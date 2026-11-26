// KcalAI: вехи дороги как остальной мир — вырезанные из крашеного картона. Здесь только рисунок (на холсте);
// в мир их ставит events.js бумажным материалом, так что свет, волокно и тени у них общие со всеми фигурками.

const HAND = "'Segoe Print', 'Comic Sans MS', 'Marker Felt', cursive";

/** Дрожь руки — одна и та же при каждом рисовании. */
function shaky(seed) {
  let s = seed;
  return (spread) => {
    s = (s * 16807) % 2147483647;
    return (s / 2147483647 - 0.5) * spread;
  };
}

function sheet(width, height) {
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  return { canvas, g: canvas.getContext('2d'), jitter: shaky(width + height * 7) };
}

/** Кусочек картона: неровный край, светлый срез и тень от соседнего листа. */
function cut(g, jitter, points, fill) {
  const path = new Path2D();
  points.forEach(([x, y], i) => {
    const [nx, ny] = points[(i + 1) % points.length];
    if (!i) path.moveTo(x + jitter(5), y + jitter(5));
    path.quadraticCurveTo((x + nx) / 2 + jitter(8), (y + ny) / 2 + jitter(8), nx + jitter(3), ny + jitter(3));
  });
  path.closePath();
  g.save();
  g.shadowColor = 'rgba(60, 40, 12, 0.45)';
  g.shadowOffsetY = 6;
  g.shadowBlur = 7;
  g.fillStyle = fill;
  g.fill(path);
  g.restore();
  g.lineWidth = 4;
  g.strokeStyle = 'rgba(255, 240, 205, 0.75)';
  g.stroke(path);
  return path;
}

/** Волокна и сучки по доске. */
function grain(g, jitter, path, x, y, w, h) {
  g.save();
  g.clip(path);
  g.lineCap = 'round';
  for (let i = 0; i < 22; i++) {
    const gx = x + w / 2 + jitter(w);
    const gy = y + h / 2 + jitter(h);
    g.strokeStyle = i % 3 ? 'rgba(90, 52, 22, 0.28)' : 'rgba(255, 222, 170, 0.3)';
    g.lineWidth = 2 + Math.abs(jitter(4));
    g.beginPath();
    g.moveTo(gx, gy);
    g.quadraticCurveTo(gx + 30, gy + jitter(8), gx + 40 + Math.abs(jitter(60)), gy + jitter(6));
    g.stroke();
  }
  g.restore();
}

function letters(g, text, x, y, size, color = '#fff3d6') {
  g.font = `bold ${size}px ${HAND}`;
  g.textAlign = 'center';
  g.textBaseline = 'middle';
  g.lineJoin = 'round';
  g.lineWidth = size * 0.16;
  g.strokeStyle = '#5a3218';
  g.strokeText(text, x, y);
  g.fillStyle = color;
  g.fillText(text, x, y);
}

/** Кочка травы с цветами у основания столба. */
function tuft(g, jitter, cx, base) {
  for (let i = 0; i < 9; i++) {
    const x = cx - 48 + i * 12 + jitter(8);
    cut(g, jitter, [[x - 9, base], [x + jitter(14), base - 34 - Math.abs(jitter(34))], [x + 9, base]], i % 2 ? '#5f8a3d' : '#7ba24c');
  }
  for (const [dx, dy, petal] of [[-34, -26, '#fff3d6'], [30, -20, '#e8759c'], [6, -14, '#f4c95a']]) {
    g.fillStyle = petal;
    for (let p = 0; p < 5; p++) {
      g.beginPath();
      g.arc(cx + dx + Math.cos((p * 6.283) / 5) * 8, base + dy + Math.sin((p * 6.283) / 5) * 8, 6.5, 0, 6.283);
      g.fill();
    }
    g.fillStyle = '#c4661f';
    g.beginPath();
    g.arc(cx + dx, base + dy, 5, 0, 6.283);
    g.fill();
  }
}

/** Деревянный указатель с числом пройденных шагов. */
export function milestoneArt(label) {
  const { canvas, g, jitter } = sheet(320, 360);
  const post = cut(g, jitter, [[142, 86], [160, 62], [178, 86], [182, 340], [138, 340]], '#96653a');
  grain(g, jitter, post, 138, 70, 44, 270);
  // Доска стрелкой вперёд по дороге.
  const board = cut(g, jitter, [[28, 100], [258, 92], [308, 146], [260, 204], [32, 198], [44, 148]], '#c98b4b');
  grain(g, jitter, board, 28, 92, 280, 112);
  for (const x of [66, 246]) {
    g.fillStyle = '#5a3218';
    g.beginPath();
    g.arc(x, 116, 6, 0, 6.283);
    g.fill();
  }
  letters(g, label, 160, 150, 62);
  const tag = cut(g, jitter, [[96, 214], [224, 220], [220, 262], [100, 258]], '#a9713c');
  grain(g, jitter, tag, 96, 214, 128, 48);
  letters(g, 'шагов', 160, 238, 32);
  tuft(g, jitter, 160, 348);
  return canvas;
}

/** Финишная арка через дорогу: два столба, флажки и полотнище «Ура!» со звездой. */
export function goalArt() {
  const { canvas, g, jitter } = sheet(640, 460);
  const flag = ['#d95b4a', '#f4c95a', '#4c8f5a', '#5b9bd0', '#e8759c'];
  for (const x of [66, 574]) {
    const pole = cut(g, jitter, [[x - 16, 70], [x + 16, 70], [x + 18, 440], [x - 18, 440]], '#96653a');
    grain(g, jitter, pole, x - 18, 70, 36, 370);
    // Ленточка винтом.
    g.save();
    g.clip(pole);
    g.strokeStyle = '#fff3d6';
    g.lineWidth = 17;
    for (let y = 96; y < 460; y += 62) {
      g.beginPath();
      g.moveTo(x - 30, y);
      g.lineTo(x + 30, y - 34);
      g.stroke();
    }
    g.restore();
    cut(g, jitter, [[x, 30], [x + 62 * (x < 320 ? -1 : 1), 46], [x, 74]], '#d95b4a');
    tuft(g, jitter, x, 448);
  }
  // Верёвка с флажками провисает между столбами.
  const sag = (k) => [66 + 508 * k, 76 + Math.sin(k * Math.PI) * 44];
  g.strokeStyle = '#6b4a2a';
  g.lineWidth = 5;
  g.beginPath();
  g.moveTo(...sag(0));
  for (let k = 0.05; k <= 1.001; k += 0.05) g.lineTo(...sag(k));
  g.stroke();
  for (let i = 0; i < 11; i++) {
    const [x, y] = sag((i + 0.5) / 11);
    cut(g, jitter, [[x - 20, y], [x + 20, y + 2], [x + jitter(6), y + 50]], flag[i % flag.length]);
  }
  const cloth = cut(g, jitter, [[160, 168], [320, 182], [480, 166], [466, 220], [482, 274], [400, 262], [320, 282], [240, 262], [158, 276], [176, 222]], '#c9483b');
  grain(g, jitter, cloth, 158, 168, 324, 112);
  letters(g, 'Ура!', 320, 226, 78);
  const star = [];
  for (let i = 0; i < 10; i++) {
    const r = i % 2 ? 22 : 50;
    const a = -Math.PI / 2 + (i * Math.PI) / 5;
    star.push([320 + Math.cos(a) * r, 130 + Math.sin(a) * r]);
  }
  cut(g, jitter, star, '#f4c95a');
  return canvas;
}
