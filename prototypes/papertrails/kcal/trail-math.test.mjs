import test from 'node:test';
import assert from 'node:assert/strict';
import { routeSpot } from './trail-math.js';

// Цель шагов делится между землями поровну — так же, как мир строит дорогу (src/core/biomes.js).
test('внутри дня — следующая земля и сколько до неё', () => {
  assert.deepEqual(routeSpot(5, 8000, 0), { next: 1, left: 1600 });
  assert.deepEqual(routeSpot(5, 8000, 3450), { next: 3, left: 1350 });
});

test('на последней земле впереди финиш', () => {
  assert.deepEqual(routeSpot(5, 8000, 7900), { next: null, left: 100 });
});

test('дошли до цели или показывать нечего', () => {
  assert.equal(routeSpot(5, 8000, 8000), null);
  assert.equal(routeSpot(1, 8000, 100), null);
  assert.equal(routeSpot(5, 0, 100), null);
});
