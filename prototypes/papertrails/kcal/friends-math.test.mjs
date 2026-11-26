import test from 'node:test';
import assert from 'node:assert/strict';
import { followPoint, ROAD_HALF } from './friends-math.js';

// Друзья идут гуськом по следам героя и не сходят с тропы: сбоку от неё — лес и заборы.
test('следов нет — прямо позади героя, на тропе', () => {
  assert.deepEqual(followPoint([], { x: 0, z: -10 }, 1), { x: 0, z: -9 });
});

test('по следам: столько, сколько отстают, вдоль пройденного', () => {
  const trail = [{ x: 0, z: -7 }, { x: 0.2, z: -8 }, { x: 0.2, z: -9 }];
  const p = followPoint(trail, { x: 0.2, z: -10 }, 1.5);
  assert.ok(Math.abs(p.z - -8.5) < 1e-9 && Math.abs(p.x - 0.2) < 1e-9, JSON.stringify(p));
});

test('герой обходил что-то стороной — друзья всё равно на тропе', () => {
  assert.equal(followPoint([{ x: 1.4, z: -9 }], { x: 1.4, z: -10 }, 1).x, ROAD_HALF);
  assert.equal(followPoint([], { x: -1.4, z: -10 }, 1).x, -ROAD_HALF);
});
