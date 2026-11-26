import test from 'node:test';
import assert from 'node:assert/strict';
import { EVENTS, pickEvent, pause } from './events-math.js';

test('одно и то же событие дважды подряд не случается', () => {
  for (const last of EVENTS) for (const r of [0, 0.3, 0.7, 0.999]) assert.notEqual(pickEvent(last, () => r), last);
});

test('выпадают все события', () => {
  const seen = new Set();
  for (let i = 0; i < 100; i++) seen.add(pickEvent(null, () => i / 100));
  assert.deepEqual([...seen].sort(), [...EVENTS].sort());
});

test('на ходу события чаще, чем стоя', () => {
  assert.equal(pause(true, () => 0), 10);
  assert.equal(pause(true, () => 1), 22);
  assert.equal(pause(false, () => 0), 20);
  assert.equal(pause(false, () => 1), 40);
});
