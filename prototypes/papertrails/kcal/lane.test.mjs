import test from 'node:test';
import assert from 'node:assert/strict';
import { createWFC } from '../src/world/wfc.js';
import { laneShift, laneX } from './lane.js';

// Дорога прогулки петляет, но не рвётся: от старта на север по ней всегда можно пройти клетка за клеткой.
const STEP = { n: [0, -1, 's'], s: [0, 1, 'n'], e: [1, 0, 'w'], w: [-1, 0, 'e'] };
const ROWS = 900;

function walk(wfc) {
  const seen = new Set(['0,3']);
  const queue = [[0, 3]];
  let north = 3;
  while (queue.length) {
    const [x, z] = queue.pop();
    const cell = wfc.get(x, z);
    for (const [dir, [dx, dz, back]] of Object.entries(STEP)) {
      if (cell.sockets[dir] !== 'path') continue;
      const nx = x + dx;
      const nz = z + dz;
      if (Math.abs(nx) > 1 || nz > 3 || nz < -ROWS || seen.has(`${nx},${nz}`)) continue;
      assert.equal(wfc.get(nx, nz).sockets[back], 'path', `разрыв дороги у ${nx},${nz}`);
      seen.add(`${nx},${nz}`);
      queue.push([nx, nz]);
      north = Math.min(north, nz);
    }
  }
  return north;
}

for (const seed of [1, 2, 7, 42, 1234]) {
  test(`мир ${seed}: дорога цела, петляет и собирается без аварийных участков`, () => {
    const wfc = createWFC({ seed });
    assert.equal(walk(wfc), -ROWS);
    const shifts = new Set();
    for (let z = 3; z >= -ROWS; z--) {
      const shift = laneShift(z, seed);
      shifts.add(shift);
      assert.ok(wfc.get(shift, z).sockets.n === 'path' || wfc.get(shift, z).sockets.s === 'path', `герой идёт мимо дороги у ${shift},${z}`);
    }
    assert.deepEqual([...shifts].sort(), [-1, 0, 1]);
    // Аварийный участок (голый луг с дорогой) бывает, но редко.
    assert.ok(wfc.stats.fallbackChunks <= 2, `аварийных участков: ${wfc.stats.fallbackChunks}`);
  });
}

test('герой поворачивает плавно и начинает по прямой', () => {
  assert.equal(laneX(0, 1), 0);
  let before = laneX(0, 1);
  for (let z = 0; z > -400; z -= 0.2) {
    const now = laneX(z, 1);
    assert.ok(Math.abs(now - before) < 0.45, `рывок у z=${z.toFixed(1)}`);
    before = now;
  }
});
