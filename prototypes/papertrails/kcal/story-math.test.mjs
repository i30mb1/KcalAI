import test from 'node:test';
import assert from 'node:assert/strict';
import { stage, nearestHouse } from './story-math.js';

// Что делают друзья, решает дорожка: идут — шагают, постояли — привал, стоят долго — дремлют.
test('привал и дрёма — по тому, сколько стоят', () => {
  assert.equal(stage(false, 10), 'walk');
  assert.equal(stage(false, 45), 'camp');
  assert.equal(stage(false, 299), 'camp');
  assert.equal(stage(false, 300), 'doze');
});

test('пошли — сразу снова в путь', () => {
  assert.equal(stage(true, 0), 'walk');
});

test('в гости — к ближайшему домику рядом, а нет рядом — никуда', () => {
  const houses = new Set(['2,-5', '1,-6']);
  const isHouse = (cx, cz) => houses.has(`${cx},${cz}`);
  assert.deepEqual(nearestHouse({ x: 0, z: -11 }, isHouse), { x: 2, z: -12 });
  assert.equal(nearestHouse({ x: 0, z: -40 }, isHouse), null);
});
