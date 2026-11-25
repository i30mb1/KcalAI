// KcalAI: набор печати сцен прогулки — бумажный коллаж кодом на листе шириной 100 единиц (высота — по пропорции
// места атласа; свет слева сверху): слои с короткой тёплой тенью и светлой кромкой со стороны света. Им печатаются
// места атласа в видах сцен (field-prints.js, dairy-prints.js); собирает их в общий лист skins.js.
export const P = () => new Path2D();
export const ell = (x, y, rx, ry, rot = 0) => { const p = P(); p.ellipse(x, y, rx, ry, rot, 0, Math.PI * 2); return p; };
export const poly = (...pts) => { const p = P(); p.moveTo(pts[0], pts[1]); for (let i = 2; i < pts.length; i += 2) p.lineTo(pts[i], pts[i + 1]); p.closePath(); return p; };
export const rrect = (x, y, w, h, r) => { const p = P(); p.roundRect(x, y, w, h, r); return p; };
export const union = (...ps) => { const p = P(); for (const q of ps) p.addPath(q); return p; };
/** Лист: от (x, y) длиной len, полушириной w, под углом rot. */
export const leaf = (x, y, len, w, rot) => {
  const p = P(); const c = Math.cos(rot), s = Math.sin(rot);
  const at = (u, v) => [x + u * c - v * s, y + u * s + v * c];
  p.moveTo(...at(0, 0)); p.quadraticCurveTo(...at(len * 0.5, -w), ...at(len, 0)); p.quadraticCurveTo(...at(len * 0.5, w), ...at(0, 0)); p.closePath();
  return p;
};
/** Сердцевидный лист (гречиха): основание в (x, y), острие на len под углом rot. */
export const heart = (x, y, len, rot) => {
  const p = P(); const c = Math.cos(rot), s = Math.sin(rot); const w = len * 0.55;
  const at = (u, v) => [x + u * c - v * s, y + u * s + v * c];
  p.moveTo(...at(0, 0)); p.bezierCurveTo(...at(-len * 0.25, -w), ...at(len * 0.6, -w * 0.9), ...at(len, 0));
  p.bezierCurveTo(...at(len * 0.6, w * 0.9), ...at(-len * 0.25, w), ...at(0, 0)); p.closePath();
  return p;
};
/** Полоса толщиной w от (x0, y0) к (x1, y1) — стебель, ость, перекладина: тонких линий в печати нет. */
export const strip = (x0, y0, x1, y1, w0, w1 = w0) => {
  const dx = x1 - x0, dy = y1 - y0, l = Math.hypot(dx, dy) || 1; const nx = -dy / l, ny = dx / l;
  return poly(x0 + nx * w0 / 2, y0 + ny * w0 / 2, x1 + nx * w1 / 2, y1 + ny * w1 / 2, x1 - nx * w1 / 2, y1 - ny * w1 / 2, x0 - nx * w0 / 2, y0 - ny * w0 / 2);
};
export const ring = (x, y, r, hole) => { const p = P(); p.arc(x, y, r, 0, Math.PI * 2); p.moveTo(x + hole, y); p.arc(x, y, hole, 0, Math.PI * 2, true); return p; };
export function rng(seed) {
  let s = seed >>> 0;
  return () => { s = (s + 0x6d2b79f5) >>> 0; let t = s; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
}

/** Кисть на холсте [c] с масштабом [k] px на единицу листа. Тени — в пикселях: трансформация на них не действует. */
export function brush(c, k) {
  // Кромка — полумесяц между слоем и его копией, сдвинутой от света: только по внешнему краю, без внутренних дуг
  // у слоя из нескольких кружков. Рисуется на черновике и ложится на лист.
  const scratch = document.createElement('canvas'); scratch.width = c.canvas.width; scratch.height = c.canvas.height;
  const s = scratch.getContext('2d');
  const lip = (path, a = 0.5, rule = 'nonzero') => {
    s.setTransform(1, 0, 0, 1, 0, 0); s.clearRect(0, 0, scratch.width, scratch.height);
    s.setTransform(c.getTransform());
    s.globalCompositeOperation = 'source-over'; s.fillStyle = `rgba(255,246,222,${a})`; s.fill(path, rule);
    s.globalCompositeOperation = 'destination-out'; s.fillStyle = '#000'; s.translate(1.6 / k, 1.9 / k); s.fill(path, rule);
    c.save(); c.setTransform(1, 0, 0, 1, 0, 0); c.drawImage(scratch, 0, 0); c.restore();
  };
  /** Слой: короткая мягкая тёплая тень на нижний лист и светлая кромка со стороны света. */
  const layer = (path, fill, { clip = null, shade = 1, edge = 0.5, rule = 'nonzero' } = {}) => {
    c.save();
    if (clip) c.clip(clip);
    if (shade) { c.shadowColor = `rgba(84,46,18,${0.34 * shade})`; c.shadowBlur = 5; c.shadowOffsetX = 2; c.shadowOffsetY = 3; }
    c.fillStyle = fill; c.fill(path, rule);
    c.restore();
    if (edge) { c.save(); if (clip) c.clip(clip); lip(path, edge, rule); c.restore(); }
  };
  const base = (path, fill, rule) => layer(path, fill, { shade: 0, edge: 0.45, rule });
  const dots = (pts, r, fill, clip, shade = 0.5) => { const p = P(); for (let i = 0; i < pts.length; i += 2) { p.moveTo(pts[i] + r, pts[i + 1]); p.arc(pts[i], pts[i + 1], r, 0, Math.PI * 2); } layer(p, fill, { clip, shade, edge: 0.35 }); };
  return { layer, base, dots };
}


/** Контур места (доли, y вверх) — в путь листа шириной 100 и высотой [H]. */
export function outlinePath(outline, H) {
  const p = P();
  outline.forEach(([u, v], i) => (i ? p.lineTo(u * 100, (1 - v) * H) : p.moveTo(u * 100, (1 - v) * H)));
  p.closePath();
  return p;
}

/** Напечатать место рисунком [draw] на холст [w] x [h] px внутри его контура [outline] (доли, y вверх). */
export function paintSprite(draw, w, h, outline) {
  const cv = document.createElement('canvas'); cv.width = w; cv.height = h;
  const c = cv.getContext('2d');
  const k = w / 100, H = h / k;
  c.scale(k, k);
  const shape = outlinePath(outline, H);
  c.save(); c.clip(shape);
  draw(brush(c, k), H, shape);
  c.restore();
  return cv;
}
