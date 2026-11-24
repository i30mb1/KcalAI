// Запуск: node --test prototypes/treadmill/treadmill.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  parseTreadmillData, encodeTreadmillData, track, dayTotals, dayKey, strideMeters,
} from './treadmill.js';

const hex = (s) => Uint8Array.from(s.match(/../g).map((b) => parseInt(b, 16)));
const T0 = new Date(2026, 10, 24, 18, 20, 30).getTime();

test('разбирает настоящий пакет Umay-TM: скорость, дистанция, ккал, время', () => {
  const d = parseTreadmillData(hex('8625c8000000ec0900a000000000001a12000000'));
  assert.equal(d.speed, 2);
  assert.equal(d.distance, 2540);
  assert.equal(d.kcal, 160);
  assert.equal(d.elapsed, 4634);
  assert.equal(d.heartRate, 0);
});

test('пакет демо разбирается обратно в те же цифры', () => {
  const sample = { speed: 3.4, distance: 1234, kcal: 77, elapsed: 905 };
  const d = parseTreadmillData(encodeTreadmillData(sample));
  assert.deepEqual(
    { speed: d.speed, distance: d.distance, kcal: d.kcal, elapsed: d.elapsed },
    sample,
  );
});

test('первый пакет заводит прогулку, начавшуюся elapsed секунд назад', () => {
  const s = track([], { speed: 2, distance: 2540, kcal: 160, elapsed: 4634 }, T0, 175);
  assert.equal(s.length, 1);
  assert.equal(s[0].startedAt, T0 - 4634 * 1000);
  assert.equal(s[0].distance, 2540);
  assert.ok(s[0].steps > 0);
});

test('следующие пакеты обновляют ту же прогулку', () => {
  let s = track([], { speed: 2, distance: 2540, kcal: 160, elapsed: 4634 }, T0, 175);
  s = track(s, { speed: 2, distance: 2550, kcal: 161, elapsed: 4645 }, T0 + 11_000, 175);
  assert.equal(s.length, 1);
  assert.equal(s[0].distance, 2550);
  assert.equal(s[0].kcal, 161);
  assert.equal(s[0].seenAt, T0 + 11_000);
});

test('шаги растут вместе с дистанцией, а не пересчитываются заново', () => {
  let s = track([], { speed: 2, distance: 1000, kcal: 50, elapsed: 1800 }, T0, 175);
  const before = s[0].steps;
  s = track(s, { speed: 5, distance: 1100, kcal: 55, elapsed: 1872 }, T0 + 72_000, 175);
  const added = s[0].steps - before;
  assert.ok(Math.abs(added - 100 / strideMeters(5, 175)) < 1);
});

test('счётчики сбросились — это новая прогулка', () => {
  let s = track([], { speed: 3, distance: 3000, kcal: 150, elapsed: 3600 }, T0, 175);
  s = track(s, { speed: 3, distance: 20, kcal: 1, elapsed: 25 }, T0 + 30_000, 175);
  assert.equal(s.length, 2);
  assert.equal(s[1].distance, 20);
});

test('переподключились к той же прогулке через 20 минут — не считаем её дважды', () => {
  let s = track([], { speed: 3, distance: 1000, kcal: 50, elapsed: 1200 }, T0, 175);
  s = track(s, { speed: 3, distance: 2000, kcal: 100, elapsed: 2400 }, T0 + 1_200_000, 175);
  assert.equal(s.length, 1);
  assert.equal(s[0].distance, 2000);
});

test('дорожку выключали и включали, пока нас не было — новая прогулка', () => {
  let s = track([], { speed: 3, distance: 500, kcal: 25, elapsed: 600 }, T0, 175);
  s = track(s, { speed: 3, distance: 900, kcal: 45, elapsed: 1000 }, T0 + 3_600_000, 175);
  assert.equal(s.length, 2);
});

test('дорожка включена, но не запущена — прогулки нет', () => {
  const s = track([], { speed: 0, distance: 0, kcal: 0, elapsed: 0 }, T0, 175);
  assert.equal(s.length, 0);
});

test('итог дня складывает прогулки этого дня', () => {
  let s = track([], { speed: 3, distance: 3000, kcal: 150, elapsed: 3600 }, T0, 175);
  s = track(s, { speed: 3, distance: 500, kcal: 30, elapsed: 600 }, T0 + 60_000, 175);
  const yesterday = T0 - 86_400_000;
  s = [{ ...s[0], startedAt: yesterday, seenAt: yesterday, distance: 9999 }, ...s];
  const t = dayTotals(s, dayKey(T0));
  assert.equal(t.distance, 3500);
  assert.equal(t.kcal, 180);
  assert.equal(t.elapsed, 4200);
  assert.equal(t.walks, 2);
});

test('шаг длиннее у высоких и на быстрой скорости', () => {
  assert.ok(strideMeters(5, 175) > strideMeters(2, 175));
  assert.ok(strideMeters(4, 190) > strideMeters(4, 160));
});
