import test from 'node:test';
import assert from 'node:assert/strict';
import { EVENTS, pickEvent, pause, milestone } from './events-math.js';

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

test('тысяча взята на ходу — веха; открыли страницу посреди дня — без салюта задним числом', () => {
  assert.equal(milestone(4990, 5004, 8000), 5000);
  assert.equal(milestone(5004, 5100, 8000), null);
  assert.equal(milestone(-1, 5100, 8000), null);
  assert.equal(milestone(0, 5100, 8000), null);
});

test('цель — своя веха, даже не круглая', () => {
  assert.equal(milestone(7490, 7502, 7500), 'goal');
  assert.equal(milestone(7990, 8001, 8000), 'goal');
});
