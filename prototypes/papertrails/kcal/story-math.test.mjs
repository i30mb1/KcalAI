import test from 'node:test';
import assert from 'node:assert/strict';
import { stage, nearestHouse, placeStories, pickStory, walkStories } from './story-math.js';

// Что делают друзья, решает дорожка: идут — шагают, постояли — привал, стоят долго — дремлют.
test('привал и дрёма — по тому, сколько стоят', () => {
  assert.equal(stage(false, 10), 'walk');
  assert.equal(stage(false, 20), 'camp');
  assert.equal(stage(false, 179), 'camp');
  assert.equal(stage(false, 180), 'doze');
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

test('какой сюжет возможен — решает место', () => {
  assert.deepEqual(placeStories({ biome: 'snow' }), ['snowball']);
  assert.deepEqual(placeStories({ biome: 'snow', house: true, pond: true }), ['visit', 'fishing', 'snowball']);
  assert.deepEqual(placeStories({ biome: 'heartland' }), ['picnic']);
  assert.deepEqual(placeStories({ biome: 'pinewood' }), ['scare']);
});

test('тот же сюжет подряд — только если другого здесь нет', () => {
  assert.equal(pickStory(['visit', 'snowball'], 'visit', () => 0), 'snowball');
  assert.equal(pickStory(['snowball'], 'snowball', () => 0.9), 'snowball');
  assert.equal(pickStory([], null), null);
});

test('на ходу в любой земле есть чем заняться', () => {
  for (const biome of ['snow', 'desert', 'heartland', 'blossom', 'pinewood']) {
    assert.ok(walkStories({ biome }).includes('race') && walkStories({ biome }).includes('leapfrog'), biome);
  }
});
