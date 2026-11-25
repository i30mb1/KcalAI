// papercraft / paper-motion.js
// Engine-free motion for paper: springs, the latched paper-flap spring, wind sway as a pure function of time and
// place, a fixed-step clock, and the walk field (what the hero may walk on, read from the built constructions). No dependencies; used by paperkit-3d.js and the 2D template, portable to any engine.

/**
 * Damped spring, sub-stepped at 240 Hz (frame-rate independent). hz = natural frequency, zeta = damping ratio.
 * Overshoot fraction = exp(-zeta*PI/sqrt(1-zeta^2)): zeta 0.55 = 12 %.
 */
export class Spring {
  constructor({ value = 0, hz = 2.2, zeta = 0.55 } = {}) { this.x = value; this.v = 0; this.target = value; this.hz = hz; this.zeta = zeta; }
  step(dt) {
    const w = 2 * Math.PI * this.hz, n = Math.max(1, Math.ceil(dt * 240)), h = dt / n;
    for (let i = 0; i < n; i++) { this.v += (-w * w * (this.x - this.target) - 2 * this.zeta * w * this.v) * h; this.x += this.v * h; }
    return this.x;
  }
  set(target) { this.target = target; }
  /** Jump to a value at rest (replays, captures, scrubbing). */
  reset(x = 0) { this.x = x; this.v = 0; this.target = x; return this; }
  get p() { return this.x; }
  get settled() { return Math.abs(this.x - this.target) < 1e-3 && Math.abs(this.v) < 1e-3; }
}

/**
 * The paper flap: one scalar p (0 = flat, 1 = upright, > 1 = overshoot). Rises on a soft spring (1.25 Hz, damping
 * 0.32), LATCHES at upright into a stiff 4.5 Hz quiver whose air drag eats the big swing and whose crease friction ends
 * the tail (8-15 % overshoot, 4-6 reversals, still by ~1 s); falls softly (0.5 Hz, damping 0.7, constant pull) after a
 * small outward unlatch twitch and lands with one 1-2 % slap. size 0..1: bigger = slower and whippier.
 * Values from a shipped pop-up game; p is a fraction of the fold angle, so it works for any angle.
 */
export class FlapSpring {
  constructor({ p = 0, size = 0.4, jitter = 0, quiverJitter = jitter } = {}) {
    const t = Math.min(1, Math.max(0, (size - 0.12) / 0.58));
    const fs = (1.14 + (0.88 - 1.14) * t) * (1 + jitter * 0.03), wob = 1 + t;
    Object.assign(this, {
      fs, p, v: 0, target: p, locked: p >= 1, atRest: true,
      kUp: 62 * fs * fs, cUp: 5 * fs * (1 - 0.5 * t),
      wL: 2 * Math.PI * 4.5 * (1 + quiverJitter * 0.03), zL: 0.04 / Math.sqrt(wob), dragL: 3.5 / Math.sqrt(wob), fricL: 2.2 * fs * (1 + 0.8 * t), // every size quivers at 4.5 Hz
      kDn: 10 * fs * fs, cDn: 4.5 * fs, gDn: 5 * fs * fs, antic: 1.2 * fs, rest: 0.25, minBounce: 0.5 * fs,
    });
  }
  /** Jump to p at rest: 0 = lying flat, 1 = standing (replays, captures, scrubbing). */
  reset(p = 0) { Object.assign(this, { p, v: 0, target: p, locked: p >= 1, atRest: true }); return this; }
  set(target) {
    if (target === this.target) return;
    if (target === 0 && this.atRest && this.p >= 1) this.v = this.antic; // unlatch twitch before the fall
    this.target = target; this.atRest = false; if (target === 0) this.locked = false;
  }
  step(dt) {
    const n = Math.max(1, Math.ceil(dt * 240)), h = dt / n;
    for (let i = 0; i < n && !this.atRest; i++) {
      let a;
      if (this.target === 1) {
        if (!this.locked && this.p >= 1) this.locked = true;
        a = this.locked
          ? -this.wL * this.wL * (this.p - 1) - (2 * this.zL * this.wL + this.dragL * Math.abs(this.v) + this.fricL / (Math.abs(this.v) + 0.05)) * this.v
          : this.kUp * (1 - this.p) - this.cUp * this.v;
      } else {
        a = -this.kDn * this.p - this.cDn * this.v - this.gDn;
        if (this.v > 0) a -= this.cUp * this.v * Math.min(1, this.p);
        if (this.p > 1) a -= this.wL * this.wL * (this.p - 1) + 2 * this.zL * this.wL * this.v; // the end stop still acts past upright
      }
      this.v += a * h; this.p += this.v * h;
      if (this.p <= 0 && this.target === 0) { this.p = 0; this.v = this.v < -this.minBounce ? -this.v * this.rest : 0; if (this.v === 0) this.atRest = true; }
      if (this.target === 1 && Math.abs(this.p - 1) < 0.002 && Math.abs(this.v) < 0.024) { this.p = 1; this.v = 0; this.atRest = true; }
    }
    return this.p;
  }
}

/**
 * Wind sway angle (radians) for a standing piece at (x, z) and time t: two travelling gust fronts so neighbours move
 * one after another, a flutter whose strength follows the gust. amp in degrees (0.5-2.5 for foliage, ~0.25 for fences,
 * 0 for walls). heroHeight = your hero's height in your units (gust wavelengths are ~23 / 13 / 8 hero heights).
 * Add the result to the HINGE angle (rotation about the fold line), never as an in-plane rotation that lifts a foot.
 * Pure function: no state, identical in every capture.
 */
export function windSway(t, x, z, { amp = 1.5, hz = 0.6, phase = 0, dir = 0.52, heroHeight = 0.6 } = {}) {
  const k = 0.6 / heroHeight; x *= k; z *= k;
  const dx = Math.cos(dir), dz = Math.sin(dir);
  const a = (x * dx + z * dz) * (2 * Math.PI / 14) - t * (2 * Math.PI / 9) + 1.7 * Math.sin((z * dx - x * dz) * (2 * Math.PI / 14) * 0.6);
  const b = (x * 0.31 - z * 0.95) * (2 * Math.PI / 8) - t * (2 * Math.PI / 5.3);
  const g = 0.5 + 0.32 * Math.sin(a) + 0.18 * Math.sin(b);
  const ph = hz * 2 * Math.PI * t + phase - (x * dx + z * dz) * (2 * Math.PI / 5);
  const e = 0.35 + 0.65 * g;
  return (amp * Math.PI / 180) * (0.5 * (g - 0.3) + e * (0.55 * Math.sin(ph) + 0.1 * Math.sin(2.31 * ph + 1.7)));
}

/** Fixed-step clock: feed it real dt, it calls update(h) in fixed steps (deterministic sims, repeatable captures). */
export class FixedClock {
  constructor(h = 1 / 120, maxSteps = 30) { this.h = h; this.maxSteps = maxSteps; this.acc = 0; this.time = 0; }
  advance(dt, update) {
    this.acc += Math.min(dt, this.h * this.maxSteps);
    while (this.acc >= this.h) { update(this.h, this.time); this.time += this.h; this.acc -= this.h; }
    return this.acc / this.h; // interpolation alpha for rendering between steps
  }
}

/**
 * The walk field (engine-free; the 3D kit re-exports it): one answer to "how high is the ground here, and may the
 * hero step there?", read from the same constructions the camera sees ("the walk field is what is drawn").
 *   add(surface)  a world-space surface { heightAt(x, z) -> y | null } (2D decks, boardwalks, bridges), or a three.js
 *                 group whose userData.walk.heightAt works in the group's own frame (the 3D builders' groups)
 *   block(fn)     no-go areas of the page, fn(x, z) -> true (water, chasms, fields)
 *   solid(x, z, r, { base, top }) round footprints (houses, trees, rocks, wells, a crate ON a pier: base = the deck
 *                 height); they block any surface whose height lies in [base - step, top], so a rock under a bridge
 *                 does not block the deck above it; wall(x0, z0, x1, z1, { r, base, top }) the same along a segment
 *                 (parapets, fences, house fronts); why(x, z, y) says what stops the hero there
 *   radius        (constructor) half the hero's FEET span (~0.15 x hero height), not half the card: feet around the
 *                 centre must all stand on surfaces within one step, so the hero never pokes over a deck edge
 *   surface(x, z) -> { y, on } of the highest surface there, the page (y 0), or null when blocked
 *   move(pos, dx, dz) slides along blockers and steers round round ones, steps up or down at most `step` (~0.2 x hero
 *                 height), so parapets, cliff faces, deck edges and water stop the hero like walls while ramps, stairs
 *                 and bridges let him on
 *   route(pos, points) walks through waypoints with move() (a virtual stick): the scripted check of a route
 * pos is any { x, y, z } (a THREE.Vector3 works). A three.js group's walk may name a `frame` (a child object) whose
 * axes heightAt uses: a platform's level deck frame, so a leaning face never tilts the walk surface.
 */
export class WalkField {
  constructor({ step = 0.75, radius = 0 } = {}) { this.items = []; this.blocks = []; this.solids = []; this.step = step; this.radius = radius; this._side = 0; this._free = 0; }
  add(surface) { this.items.push(surface); return surface; }
  block(fn) { this.blocks.push(fn); }
  solid(x, z, r, { base = -Infinity, top = Infinity } = {}) { this.solids.push({ x, z, r, base, top }); }
  /** A wall along a segment (a parapet, a fence, a house front, a rail): blocks within r of it when the surface there is
   *  between base - step and top (a wall on a deck: base = the deck's height). Circles for round things, walls for long. */
  wall(x0, z0, x1, z1, { r = 0.3, base = -Infinity, top = Infinity, name = 'wall' } = {}) { this.solids.push({ seg: [x0, z0, x1, z1], r, base, top, name }); }
  _dist(q, x, z) {
    if (!q.seg) return Math.hypot(x - q.x, z - q.z);
    const [x0, z0, x1, z1] = q.seg, dx = x1 - x0, dz = z1 - z0, t = Math.max(0, Math.min(1, ((x - x0) * dx + (z - z0) * dz) / (dx * dx + dz * dz || 1)));
    return Math.hypot(x - x0 - dx * t, z - z0 - dz * t);
  }
  /** What stops the hero at (x, z) coming from height y: null when nothing does. */
  why(x, z, y = 0, feet = true) {
    const s = this._top(x, z); if (!s) return 'a block (water, a cliff)';
    for (const q of this.solids) if (this._dist(q, x, z) < q.r && s.y >= q.base - this.step && s.y <= q.top) return q.seg ? `${q.name} [${q.seg.map((v) => +v.toFixed(1))}]` : `a solid at [${q.x.toFixed(1)}, ${q.z.toFixed(1)}] r ${q.r.toFixed(2)}`;
    if (Math.abs(s.y - y) > this.step) return `a step of ${(s.y - y).toFixed(2)} (limit ${this.step})${s.y >= 10 ? ': a wall' : ''}`;
    if (feet && this.radius > 0) for (let k = 0; k < 8; k++) {       // the feet: what is under the one that fails
      const w = this.why(x + Math.cos(k * Math.PI / 4) * this.radius, z + Math.sin(k * Math.PI / 4) * this.radius, s.y, false);
      if (w) return `a foot on ${w}`;
    }
    return null;
  }
  _height(it, x, z) {
    if (!it.isObject3D) return it.heightAt(x, z);
    const w = it.userData.walk, o = w.frame || it, V = it.position.constructor;   // a three.js group: its own frame
    o.updateWorldMatrix(true, false);
    const v = o.worldToLocal(new V(x, 0, z)), hh = w.heightAt(v.x, v.z);
    return hh == null ? null : o.localToWorld(new V(v.x, hh, v.z)).y;
  }
  _top(x, z) {                          // the highest surface at a point, or the page, or null (a block)
    let best = null;
    for (const it of this.items) { const y = this._height(it, x, z); if (y != null && (!best || y > best.y)) best = { y, on: it }; }
    if (best) return best;
    return this.blocks.some((f) => f(x, z)) ? null : { y: 0, on: null };
  }
  surface(x, z) {
    const s = this._top(x, z); if (!s) return null;
    for (const q of this.solids) if (this._dist(q, x, z) < q.r && s.y >= q.base - this.step && s.y <= q.top) return null;
    return s;
  }
  _feet(x, z, y) {                      // every foot around the centre on a surface within one step of it
    const r = this.radius; if (!(r > 0)) return true;
    for (let k = 0; k < 8; k++) { const s = this.surface(x + Math.cos(k * Math.PI / 4) * r, z + Math.sin(k * Math.PI / 4) * r); if (!s || Math.abs(s.y - y) > this.step) return false; }
    return true;
  }
  /** Try to move pos by (dx, dz); returns true if it moved (pos.y becomes the new ground height). */
  move(pos, dx, dz) {
    const go = (mx, mz) => {
      if (!mx && !mz) return false;
      const x = pos.x + mx, z = pos.z + mz, s = this.surface(x, z);
      if (s && Math.abs(s.y - pos.y) <= this.step && this._feet(x, z, s.y)) { pos.x = x; pos.y = s.y; pos.z = z; return true; }
      return false;
    };
    if (go(dx, dz)) { if (++this._free > 8) this._side = 0; return true; }
    this._free = 0;
    // blocked: slide round it as the source game's player does. Steer 20-100 deg off the way he pushes, first to the side
    // that worked last time (decided once per obstacle, so he never dithers at a head-on bump), at the share of the step
    // that still goes forward (cos a: slower round a corner, never backwards), then the axis slides along a wall
    const len = Math.hypot(dx, dz), ux = dx / (len || 1), uz = dz / (len || 1);
    const sides = this._side ? [this._side, -this._side] : [1, -1];
    for (const a of [0.35, 0.7, 1.05, 1.4, 1.75]) for (const sd of sides) {   // 1.75: just past sideways, round a head-on bump
      const c = Math.cos(a), sn = Math.sin(a) * sd, k = len * Math.max(0.35, c);
      if (go((ux * c - uz * sn) * k, (uz * c + ux * sn) * k)) { this._side = sd; return true; }
    }
    return go(dx, 0) || go(0, dz);
  }
  /** Walk pos through waypoints [[x, z], ...] with move() in fixed steps of speed * dt (blockers and the step limit
   *  apply; never a teleport). Returns { reached, trace: [[x, y, z], ...], stuck? }. */
  route(pos, points, { speed = 7, dt = 1 / 60, maxTime = 60, near = 0.25 } = {}) {
    // stuck: the hero cannot move, or slides along a blocker for a second without getting closer to the waypoint
    const trace = [[pos.x, pos.y, pos.z]], end = (extra) => ({ reached, of: points.length, trace, y: pos.y, on: this.surface(pos.x, pos.z)?.on ?? null, ...extra });
    const stop = (tx, tz) => {          // what blocked: probe a step toward the waypoint
      const dx = tx - pos.x, dz = tz - pos.z, d = Math.hypot(dx, dz) || 1, k = Math.min(d, Math.max(0.3, speed * dt * 4)) / d;
      return end({ stuck: [pos.x, pos.y, pos.z], waypoint: reached, blockedBy: this.why(pos.x + dx * k, pos.z + dz * k, pos.y) || 'sliding along a blocker without getting closer' });
    };
    let reached = 0, t = 0;
    for (const [tx, tz] of points) {
      let bestD = Infinity, since = 0;
      while (t < maxTime) {
        const dx = tx - pos.x, dz = tz - pos.z, d = Math.hypot(dx, dz);
        if (d < near) { reached++; break; }
        if (d < bestD - 0.02) { bestD = d; since = 0; } else if ((since += dt) > 1) return stop(tx, tz);
        const k = Math.min(d, speed * dt) / d; t += dt;
        if (!this.move(pos, dx * k, dz * k)) return stop(tx, tz);
        if (trace.length < 4000) trace.push([pos.x, pos.y, pos.z]);
      }
      if (t >= maxTime) return end({ stuck: [pos.x, pos.y, pos.z], waypoint: reached });
    }
    return end({});
  }
}
