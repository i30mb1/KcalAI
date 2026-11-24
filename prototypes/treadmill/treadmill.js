// Логика дорожки без браузера: разбор пакета FTMS, склейка прогулок, шаги, итоги дня.
// Чистые функции — в KcalAI переезжают в commonMain как есть.

export const FTMS_SERVICE = 0x1826;
export const TREADMILL_DATA = 0x2acd;

// Treadmill Data (0x2ACD): флаги, за ними поля — только те, чей бит взведён.
// Бит 0 наоборот: ноль значит «скорость есть».
export function parseTreadmillData(bytes) {
  const v = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const flags = v.getUint16(0, true);
  let i = 2;
  const has = (bit) => (flags & (1 << bit)) !== 0;
  const u8 = () => v.getUint8(i++);
  const u16 = () => { const x = v.getUint16(i, true); i += 2; return x; };
  const s16 = () => { const x = v.getInt16(i, true); i += 2; return x; };
  const u24 = () => { const x = v.getUint16(i, true) | (v.getUint8(i + 2) << 16); i += 3; return x; };

  const out = { speed: 0, distance: 0, kcal: 0, elapsed: 0, heartRate: 0 };
  if (!has(0)) out.speed = u16() / 100;
  if (has(1)) out.avgSpeed = u16() / 100;
  if (has(2)) out.distance = u24();
  if (has(3)) { out.incline = s16() / 10; s16(); }
  if (has(4)) { u16(); u16(); }
  if (has(5)) u8();
  if (has(6)) u8();
  if (has(7)) { out.kcal = u16(); u16(); u8(); }
  if (has(8)) out.heartRate = u8();
  if (has(9)) u8();
  if (has(10)) out.elapsed = u16();
  if (has(11)) u16();
  if (has(12)) { s16(); s16(); }
  // Umay-TM взводит бит 13, которого нет в стандарте, и шлёт три нуля — даже на ходу.
  return out;
}

// Пакет в том же виде, что шлёт дорожка: демо идёт через тот же разбор.
export function encodeTreadmillData({ speed, distance, kcal, elapsed }) {
  const b = new Uint8Array(14);
  const v = new DataView(b.buffer);
  v.setUint16(0, (1 << 2) | (1 << 7) | (1 << 10), true);
  v.setUint16(2, Math.round(speed * 100), true);
  v.setUint16(4, distance & 0xffff, true);
  v.setUint8(6, (distance >> 16) & 0xff);
  v.setUint16(7, kcal, true);
  v.setUint16(9, 0, true);
  v.setUint8(11, 0);
  v.setUint16(12, elapsed, true);
  return b;
}

// Длина шага: растёт с ростом и со скоростью. На 2 км/ч у человека 175 см — около 58 см.
export function strideMeters(speedKmh, heightCm) {
  const v = Math.min(Math.max(speedKmh, 1), 12);
  return (heightCm / 100) * (0.26 + 0.035 * v);
}

// Прогулка, к которой вернулись, узнаётся по началу: «сейчас минус время дорожки».
const SAME_START_MS = 90_000;
// Короткий обрыв связи — та же прогулка, даже если таймер дорожки стоял на паузе.
const SHORT_GAP_MS = 180_000;

// Дорожка шлёт счётчики с момента старта, поэтому прогулка хранит их последние значения,
// а не прибавки: переподключение и повтор пакета ничего не удваивают.
export function track(sessions, sample, now, heightCm) {
  const { speed, distance, kcal, elapsed } = sample;
  if (distance === 0 && elapsed === 0) return sessions;

  const startedAt = now - elapsed * 1000;
  const last = sessions[sessions.length - 1];
  const continues = last
    && distance >= last.distance
    && elapsed >= last.elapsed
    && (now - last.seenAt <= SHORT_GAP_MS || Math.abs(startedAt - last.startedAt) <= SAME_START_MS);

  if (continues) {
    const pace = speed > 0 ? speed : avgKmh(distance, elapsed);
    const updated = {
      ...last,
      distance, kcal, elapsed,
      seenAt: now,
      steps: last.steps + (distance - last.distance) / strideMeters(pace, heightCm),
      topSpeed: Math.max(last.topSpeed, speed),
    };
    return [...sessions.slice(0, -1), updated];
  }

  return [...sessions, {
    id: startedAt,
    startedAt,
    seenAt: now,
    distance, kcal, elapsed,
    steps: distance / strideMeters(avgKmh(distance, elapsed), heightCm),
    topSpeed: speed,
  }];
}

function avgKmh(distance, elapsed) {
  return elapsed > 0 ? (distance / elapsed) * 3.6 : 0;
}

export function dayKey(ts) {
  const d = new Date(ts);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

export function dayTotals(sessions, key) {
  const t = { distance: 0, kcal: 0, elapsed: 0, steps: 0, walks: 0 };
  for (const s of sessions) {
    if (dayKey(s.startedAt) !== key) continue;
    t.distance += s.distance;
    t.kcal += s.kcal;
    t.elapsed += s.elapsed;
    t.steps += s.steps;
    t.walks += 1;
  }
  return t;
}
