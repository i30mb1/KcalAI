// papercraft / paper-sound.js
// Procedural paper foley for the Web Audio API: card flaps (fold up / fold down), paper footsteps, a small
// "book on a table" room and a soft limiter. No samples, no oscillators: every sound is synthesised per event
// from seeded noise, so the same event always sounds the same (live and in OfflineAudioContext for video).
//
// Taste (approved by ear on a shipped game, after four rounds of "quieter, lower, duller, fewer"):
//   soft, dry, low, quiet, sparse. One sound per gesture, >= 0.55 s between flaps, nearest/largest wins.
//
// Usage:
//   const sfx = new PaperSound();
//   addEventListener('pointerdown', () => sfx.unlock(), { once: true }); // also on keydown
//   sfx.flap({ size: 0.6, up: true, pan: -0.2, distance: 0 });          // a card rising
//   sfx.flap({ size: 0.6, up: false });                                 // the same card closing (-9 dB, duller)
//   sfx.step({ surface: 'grass', foot: 0 });                            // footsteps sit ~16 dB under flaps

const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
const lerp = (a, b, t) => a + (b - a) * t;
const smooth = (t) => { t = clamp(t, 0, 1); return t * t * (3 - 2 * t); };

function mulberry32(seed) {
  let a = seed >>> 0;
  return () => { a = (a + 0x6d2b79f5) | 0; let t = Math.imul(a ^ (a >>> 15), 1 | a); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
}
export function hashSeed(...parts) {
  let h = 0x811c9dc5;
  for (const p of parts) { const s = String(p); for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 0x01000193); } }
  return h >>> 0;
}

// RBJ biquad (bp = constant 0 dB peak gain)
export class Biquad {
  constructor(type, f, q, sr) {
    const w0 = (2 * Math.PI * clamp(f, 20, sr * 0.45)) / sr, cs = Math.cos(w0), al = Math.sin(w0) / (2 * q), a0 = 1 + al;
    let b0, b1, b2;
    if (type === 'lp') { b0 = (1 - cs) / 2; b1 = 1 - cs; b2 = b0; } else if (type === 'hp') { b0 = (1 + cs) / 2; b1 = -(1 + cs); b2 = b0; } else { b0 = al; b1 = 0; b2 = -al; }
    this.b0 = b0 / a0; this.b1 = b1 / a0; this.b2 = b2 / a0; this.a1 = (-2 * cs) / a0; this.a2 = (1 - al) / a0;
    this.x1 = this.x2 = this.y1 = this.y2 = 0;
  }
  run(x) {
    const y = this.b0 * x + this.b1 * this.x1 + this.b2 * this.x2 - this.a1 * this.y1 - this.a2 * this.y2;
    this.x2 = this.x1; this.x1 = x; this.y2 = this.y1; this.y1 = y;
    return y;
  }
}

export function noiseGen(seed) { let s = (seed | 0) || 0x2545f491; return () => { s ^= s << 13; s ^= s >>> 17; s ^= s << 5; return (s | 0) / 2147483648; }; }

/** One crackle grain: enveloped noise burst (3-sample fade-in, exponential decay tau seconds). */
export function grain(buf, sr, noise, t, amp, tau) {
  const i0 = Math.floor(t * sr), n = buf.length;
  if (i0 < 0 || i0 + 4 >= n) return;
  const d = Math.exp(-1 / Math.max(1, tau * sr)), end = Math.min(n, i0 + Math.ceil(tau * sr * 5.5) + 4);
  let e = amp;
  for (let i = i0, j = 0; i < end; i++, j++) { buf[i] += noise() * e * (j === 0 ? 0.15 : j === 1 ? 0.5 : j === 2 ? 0.85 : 1); e *= d; }
}

/** Fades, "loudest sample in the onset" rule, and loudness-aware normalisation (peak ~1). */
export function finish(out, sr, { attack, tailFrom, peakWithin = 0.0055 }) {
  const n = out.length, na = Math.max(1, Math.round(attack * sr));
  for (let i = 0; i < na && i < n; i++) { const u = i / na; out[i] *= u * u * (3 - 2 * u); }
  const f0 = Math.floor(tailFrom * n), nf = Math.max(1, n - f0);
  for (let i = f0; i < n; i++) { const u = 1 - (i - f0) / nf; out[i] *= u * u * (3 - 2 * u); }
  const np = Math.min(n, Math.round(peakWithin * sr));
  let early = 1e-9; for (let i = 0; i < np; i++) early = Math.max(early, Math.abs(out[i]));
  const knee = early * 0.42, room = early * Math.min(0.17, 0.97 - 0.42);
  let peak = 0, ss = 0;
  for (let i = 0; i < n; i++) {
    let v = out[i]; const a = Math.abs(v);
    if (i >= np && a > knee) { v = Math.sign(v) * (knee + room * Math.tanh((a - knee) / room)); out[i] = v; }
    peak = Math.max(peak, Math.abs(v)); ss += v * v;
  }
  const crest = peak / Math.sqrt(ss / n + 1e-12);
  const g = (1 / early) * clamp(Math.pow(crest / 8, 0.85), 0.8, 1.25);
  for (let i = 0; i < n; i++) out[i] *= g;
  return out;
}

/** Final, ear-approved flap voicing. All frequencies scale with fs = pitch * brightness(size). */
export const FLAP = {
  gain: 0.13, downGainDb: -9, downBright: 0.9, sizeGainMin: 0.85,
  durMin: 0.096, durMax: 0.23, durCurve: 1.4, durPitch: 0.3, durJitter: 0.035,
  pitchMin: 0.5, pitchMax: 0.82, pitchRandSt: 4.3, pitchSizeSt: 0.4, pitchMinStepSt: 0.7,
  brightSmall: 0.95, brightLarge: 0.72, brightFrom: 0.25, brightTo: 0.75,
  bedHz: 2500, bedQ: 0.4, bedGain: 0.45, bedGainDown: 0.4,
  resHz: [1350, 2750, 4900], resQ: [1.5, 1.8, 2.0], resGain: [0.3, 0.15, 0.05], grainDirect: 0.7,
  tiltSmall: 4200, tiltLarge: 1700, tiltSmallDown: 3400, tiltLargeDown: 1700, airTilt: 0.45, airLp: 0.35,
  lpHz: 2600, hpHz: 520, downHp: 0.78,
  decayNepers: 2.2, decayFast: 8, decayFastMix: 0.42, lumpMin: 0.2, lumpEvery: 0.022, gateEvery: 0.00035, bedSmooth: 0.35,
  grainsMin: 16, grainsMax: 38, microMin: 50, microMax: 130, grainAmp: 0.1, microAmp: 0.025, grainThin: 3.2, stopShare: 0.3,
  tick: 0.04, onsetGrains: 5, stopTick: 0.15, downTick: 1.15, downGrains: 0.5, patGain: 2.4,
  thump: 0.5, thumpHz: 430, thumpQ: 1.6, thumpDown: 0.4,
  airHz: 1100, airQ: 0.75, airLpHz: 2400, airGain: 0.55, airGainDown: 0.3, airSmall: 0.7, airRise: 0.016, airRiseDown: 0.03,
  lowHz: 190, lowQ: 1.1, lowGain: 2.2, lowFrom: 0.4, lowTau: 0.014, lowDown: 0.1,
  downSwell: 0.022, downSwellFrom: 0.3, downStop: 0.5, attackUp: 0.0022, attackDown: 0.0048,
};

/** Pitch / duration / level for one flap. Pitch is recentred in the allowed range (keeps variety when the range moves). */
export function flapParams({ size = 0.5, up = true, seed = 1, lastSt = null }, F = FLAP) {
  const r = mulberry32(seed ^ 0x51ed);
  const lo = 12 * Math.log2(F.pitchMin), hi = 12 * Math.log2(F.pitchMax), mid = (lo + hi) / 2, half = (hi - lo) / 2;
  let st = clamp(mid + F.pitchSizeSt * (1 - 2 * size) + (r() * 2 - 1) * Math.min(F.pitchRandSt, half), lo, hi);
  if (lastSt !== null && Math.abs(st - lastSt) < F.pitchMinStepSt) {
    const dir = st >= lastSt ? 1 : -1;
    let cand = lastSt + dir * F.pitchMinStepSt * 1.5;
    if (cand > hi || cand < lo) cand = lastSt - dir * F.pitchMinStepSt * 1.5;
    st = clamp(cand, lo, hi);
  }
  const pitch = Math.pow(2, st / 12);
  const dur = lerp(F.durMin, F.durMax, Math.pow(size, F.durCurve)) * Math.pow(pitch, -F.durPitch) * (1 + (r() * 2 - 1) * F.durJitter);
  const level = F.gain * lerp(F.sizeGainMin, 1, size) * (up ? 1 : Math.pow(10, F.downGainDb / 20));
  return { st, pitch, dur, level, synthSeed: (r() * 4294967296) >>> 0 };
}

/**
 * Synthesise one flap into a Float32Array (mono, peak ~1; multiply by params.level when playing).
 * size 0..1 (0 = a tab, 1 = a page-sized flap), up = rising (else closing), air 0..1 = distance softening.
 */
export function synthFlap({ size = 0.5, up = true, pitch = 0.7, dur = 0.15, seed = 1, air = 0, sr = 48000 }, F = FLAP) {
  const s = clamp(size, 0, 1), T = dur, n = Math.ceil((T + 0.012) * sr);
  const rng = mulberry32(seed);
  const resF = [0, 1, 2].map(() => 1 + (rng() * 2 - 1) * 0.06), resG = [0, 1, 2].map(() => 1 + (rng() * 2 - 1) * 0.18);
  const big01 = smooth((s - F.brightFrom) / (F.brightTo - F.brightFrom));
  const bright = F.brightSmall * Math.pow(F.brightLarge / F.brightSmall, big01) * (up ? 1 : F.downBright);
  const tiltHz = (up ? F.tiltSmall * Math.pow(F.tiltLarge / F.tiltSmall, big01) : F.tiltSmallDown * Math.pow(F.tiltLargeDown / F.tiltSmallDown, big01)) * Math.pow(pitch, 0.5) * (1 - F.airTilt * air);
  const fs = pitch * bright, ts = 1 / Math.sqrt(fs);
  const noise = noiseGen((rng() * 4294967296) | 0);
  const bed = new Float32Array(n), gr = new Float32Array(n), th = new Float32Array(n), out = new Float32Array(n);

  // brush bed: noise x two-stage decay x lumps x heavy-tailed micro gate (x swell-in when closing)
  const tauE = T / F.decayNepers * (up ? 1 : 1.1), tauF = T / F.decayFast, mixF = F.decayFastMix;
  const E = (t) => (1 - mixF) * Math.exp(-t / tauE) + mixF * Math.exp(-t / tauF);
  const segN = Math.max(4, Math.round((T / F.lumpEvery) * (0.8 + rng() * 0.4))), segLen = Math.ceil(n / segN);
  const gateEvery = Math.max(2, Math.round((F.gateEvery * sr) / fs)), gSmooth = 1 - Math.exp(-2.2 / gateEvery);
  const kSw = up ? 0 : Math.exp(-1 / (F.downSwell * sr));
  let v0 = up ? 0.7 + rng() * 0.3 : 0.5 + rng() * 0.3, v1 = F.lumpMin + rng() * (1 - F.lumpMin);
  let gate = 1, gTarget = 1, gateLeft = 0, sw = up ? 0 : 1 - F.downSwellFrom;
  for (let i = 0; i < n; i++) {
    if (i > 0 && i % segLen === 0) { v0 = v1; v1 = F.lumpMin + rng() * (1 - F.lumpMin); }
    if (--gateLeft <= 0) { gateLeft = gateEvery; const r = noise(), r2 = r * r; gTarget = F.bedSmooth + (1 - F.bedSmooth) * 5 * r2 * r2; }
    gate += gSmooth * (gTarget - gate);
    const u = (i % segLen) / segLen;
    bed[i] = noise() * E(i / sr) * (v0 + (v1 - v0) * u * u * (3 - 2 * u)) * gate * (1 - sw);
    sw *= kSw;
  }
  // onset: a handful of grains over ~4 ms (a soft "fp", not a click)
  const nOn = up ? F.onsetGrains : F.onsetGrains - 1;
  for (let k = 0; k < nOn; k++) {
    const t = (up ? 0.0008 : 0.0014) + rng() * (up ? 0.0034 : 0.0036);
    grain(gr, sr, noise, t, F.tick * (k === 0 ? 1 : 0.5 + rng() * 0.45) * (up ? 1 : F.downTick), (up ? 0.0005 + rng() * 0.0008 : 0.001 + rng() * 0.001) * ts);
  }
  // crackle clusters thinning out as the card slows, 30 % around the stop; micro fizz
  const dens = up ? 1 : F.downGrains, span = 1 - Math.exp(-F.grainThin);
  const thin = () => (-Math.log(1 - rng() * span) / F.grainThin) * T * 0.84;
  const tStop = T * (up ? 0.38 + rng() * 0.24 : F.downStop + rng() * 0.16);
  let left = Math.round(lerp(F.grainsMin, F.grainsMax, s) * dens);
  while (left > 0) {
    const tc = rng() < F.stopShare ? Math.max(0.003, tStop + (rng() + rng() - 1) * T * 0.11) : 0.003 + thin();
    const inCl = Math.min(left, 1 + Math.floor(rng() * rng() * 7)), width = 0.0012 + rng() * 0.006, r = rng();
    const ac = F.grainAmp * (0.14 + r * r * r) * E(tc);
    let tg = tc;
    for (let k = 0; k < inCl; k++) { grain(gr, sr, noise, tg, ac * (0.45 + 0.55 * rng()), (0.00015 + rng() * 0.0008) * ts); tg += (-Math.log(1 - rng() * 0.95) * width) / 2.2; }
    left -= inCl;
  }
  const nMic = Math.round(lerp(F.microMin, F.microMax, s) * dens);
  for (let k = 0; k < nMic; k++) { const t = 0.001 + thin() * 1.1; grain(gr, sr, noise, t, F.microAmp * (0.2 + rng()) * E(t), (0.0001 + rng() * 0.0003) * ts); }
  // stop: up = a few crisp grains; down = a dull pat in the bed band
  if (up) { const k = 3 + Math.floor(rng() * 3); for (let i = 0; i < k; i++) grain(gr, sr, noise, tStop + rng() * 0.003, F.tick * F.stopTick * (0.6 + rng() * 0.4), (0.0003 + rng() * 0.0006) * ts); }
  else { const k = 2 + Math.floor(rng() * 2); for (let i = 0; i < k; i++) grain(bed, sr, noise, tStop + rng() * 0.004, F.grainAmp * F.patGain * (0.5 + rng() * 0.5) * E(tStop), 0.0012 + rng() * 0.0014); }
  // quiver after the stop
  const nq = 2 + Math.floor(rng() * 4);
  for (let k = 0; k < nq; k++) { const t = tStop + 0.007 + rng() * Math.max(0, 0.88 * T - tStop - 0.007); grain(gr, sr, noise, t, F.grainAmp * (0.25 + rng() * 0.5) * E(t), (0.0002 + rng() * 0.0005) * ts); }
  // thump excitation for big cards
  if (s > 0.3) { const big = (s - 0.3) / 0.7, k = 1 + Math.floor(rng() * 2); for (let i = 0; i < k; i++) grain(th, sr, noise, 0.001 + rng() * 0.004, F.thump * Math.pow(big, 1.4) * (up ? 1 : F.thumpDown), 0.0035); }

  // filters: y = HP( LP( LP( tilt( gBed*BPbed(bed) + sum gRes*BPk(gr) + direct*gr ) ) ) ) + BPthump(th)
  const bpBed = new Biquad('bp', F.bedHz * fs, F.bedQ, sr), gBed = up ? F.bedGain : F.bedGainDown;
  const modes = F.resHz.map((h, k) => new Biquad('bp', h * fs * resF[k], F.resQ[k], sr));
  const gRes = F.resGain.map((g, k) => g * resG[k]);
  const aT = 1 - Math.exp((-2 * Math.PI * Math.min(tiltHz, sr * 0.45)) / sr);
  const lp1 = new Biquad('lp', F.lpHz * (1 - F.airLp * air), 0.6, sr), lp2 = new Biquad('lp', F.lpHz * (1 - F.airLp * air), 0.6, sr);
  const hp = new Biquad('hp', F.hpHz * Math.sqrt(fs) * (up ? 1 : F.downHp), 0.7, sr);
  const bpTh = new Biquad('bp', (F.thumpHz * pitch) / (0.8 + 0.4 * s), F.thumpQ, sr);
  let tl = 0;
  for (let i = 0; i < n; i++) {
    let x = gBed * bpBed.run(bed[i]) + F.grainDirect * gr[i];
    for (let k = 0; k < 3; k++) x += gRes[k] * modes[k].run(gr[i]);
    tl += aT * (x - tl);
    out[i] = hp.run(lp2.run(lp1.run(tl))) + bpTh.run(th[i]);
  }
  // air whoosh (alpha-function swell just behind the onset) and a low body for large cards, un-tilted
  const fA = 2 * Math.sin((Math.PI * F.airHz * Math.sqrt(pitch) * (up ? 1 : F.downBright)) / sr), qA = 1 / F.airQ;
  const tr = (up ? F.airRise : F.airRiseDown) * (0.8 + 0.4 * rng()) * (0.7 + 0.6 * s), kA = Math.exp(-1 / (tr * sr));
  const gA = (up ? F.airGain : F.airGainDown) * lerp(F.airSmall, 1, big01) * Math.E * (1 - kA);
  const aL = 1 - Math.exp((-2 * Math.PI * F.airLpHz * Math.sqrt(pitch)) / sr);
  const lowAmp = F.lowGain * Math.pow(clamp((s - F.lowFrom) / (1 - F.lowFrom), 0, 1), 0.8) * (up ? 1 : F.lowDown);
  const fT = 2 * Math.sin((Math.PI * ((F.lowHz * Math.sqrt(pitch)) / (0.8 + 0.4 * s))) / sr), qT = 1 / F.lowQ, kT = Math.exp(-1 / (F.lowTau * sr));
  let e1 = 1, e2 = 0, al = 0, ab = 0, aw = 0, lo2 = 0, lb = 0, eT = lowAmp;
  for (let i = 0; i < n; i++) {
    const x = noise();
    e2 = e2 * kA + e1; e1 *= kA;
    al += fA * ab; ab += fA * (x - al - qA * ab);
    aw += aL * (ab - aw);
    let y = aw * e2 * gA;
    if (lowAmp > 0) { lo2 += fT * lb; lb += fT * (x * eT - lo2 - qT * lb); eT *= kT; y += lb; }
    out[i] += y;
  }
  return finish(out, sr, { attack: up ? F.attackUp : F.attackDown, tailFrom: 0.82 });
}

export const SURFACES = {
  grass: { bedHz: 1100, bedQ: 0.6, bedGain: 0.8, resHz: [700, 1300], resQ: [1.0, 1.1], grain: 0.15, scuffs: 3, lpHz: 1700, knock: 0, gain: 0.9 },
  path: { bedHz: 1400, bedQ: 0.6, bedGain: 0.6, resHz: [900, 1700], resQ: [1.1, 1.2], grain: 0.22, scuffs: 2, lpHz: 2100, knock: 0, gain: 1.0 },
  wood: { bedHz: 800, bedQ: 0.7, bedGain: 0.55, resHz: [420, 950], resQ: [1.8, 1.5], grain: 0.2, scuffs: 2, lpHz: 1600, knock: 0.5, gain: 1.0 },
  water: { bedHz: 900, bedQ: 0.6, bedGain: 1.0, resHz: [650, 1300], resQ: [1.0, 1.1], grain: 0.1, scuffs: 3, lpHz: 1700, knock: 0, gain: 0.6 },
};

/** A paper footstep: a dull ~70 ms tap. Returns { buf, level }. */
export function synthStep({ surface = 'grass', foot = 0, seed = 1, sr = 48000 } = {}) {
  const V = SURFACES[surface] || SURFACES.grass, r = mulberry32(seed);
  const st = (r() * 2 - 1) * 1.6 + (foot ? 0.5 : -0.5), fs = Math.pow(2, st / 12);
  const T = 0.07 * (0.92 + r() * 0.16) * Math.pow(fs, -0.3), n = Math.ceil((T + 0.01) * sr);
  const noise = noiseGen((r() * 4294967296) | 0);
  const bed = new Float32Array(n), gr = new Float32Array(n), kn = new Float32Array(n), out = new Float32Array(n);
  const kBed = Math.exp(-1 / ((T / 4.6) * sr));
  for (let i = 0, env = 1; i < n; i++) { bed[i] = noise() * env; env *= kBed; }
  grain(gr, sr, noise, 0.0006 + r() * 0.0008, 1, (0.0005 + r() * 0.0006) / fs);
  grain(gr, sr, noise, 0.0018 + r() * 0.0016, 0.5 + r() * 0.3, (0.0004 + r() * 0.0006) / fs);
  for (let k = 0; k < V.scuffs; k++) { const t = 0.003 + r() * T * 0.6; grain(gr, sr, noise, t, (0.12 + 0.3 * r()) * Math.exp((-t / T) * 3), (0.0002 + r() * 0.0006) / fs); }
  if (V.knock > 0) grain(kn, sr, noise, 0.0008, V.knock, 0.003);
  const bp = new Biquad('bp', V.bedHz * fs, V.bedQ, sr), m = V.resHz.map((h, k) => new Biquad('bp', h * fs * (0.92 + r() * 0.16), V.resQ[k], sr));
  const lp1 = new Biquad('lp', V.lpHz * fs, 0.62, sr), lp2 = new Biquad('lp', V.lpHz * fs, 0.62, sr), hp = new Biquad('hp', 300, 0.7, sr), kb = new Biquad('bp', 560 * fs, 2.6, sr);
  for (let i = 0; i < n; i++) {
    const x = V.bedGain * bp.run(bed[i]) + V.grain * (m[0].run(gr[i]) + m[1].run(gr[i])) + 0.25 * V.grain * gr[i];
    out[i] = hp.run(lp2.run(lp1.run(x))) + 5 * kb.run(kn[i]);
  }
  finish(out, sr, { attack: 0.0016, tailFrom: 0.7 });
  return { buf: out, level: 0.02 * V.gain * (0.85 + r() * 0.3) };
}

/** 50 ms stereo "book on a small wooden table" impulse response (direct + table bounce + a few early reflections). */
function roomIR(ctx, seed = 7) {
  const sr = ctx.sampleRate, n = Math.ceil(0.05 * sr), ir = ctx.createBuffer(2, n, sr), r = mulberry32(seed);
  const taps = [[0.0013, 0.26, 0], [0.0049, 0.13, -0.6], [0.0071, 0.12, 0.6], [0.0117, 0.08, -0.8], [0.0143, 0.075, 0.8], [0.0196, 0.05, -0.4], [0.0233, 0.045, 0.5]];
  for (let ch = 0; ch < 2; ch++) {
    const d = ir.getChannelData(ch); d[0] = 1;
    let lp = 0; const a = 1 - Math.exp((-2 * Math.PI * 3200) / sr);
    for (let i = Math.round(0.003 * sr); i < n; i++) { lp += a * (r() * 2 - 1 - lp); const t = i / sr - 0.003; d[i] += lp * 0.03 * Math.exp(-t / 0.009) * Math.min(1, t / 0.002); }
    for (const [t, g, pan] of taps) { const i = Math.round(t * sr), gg = g * Math.min(1, ch ? 1 + pan : 1 - pan); d[i - 1] += gg * 0.25; d[i] += gg * 0.5; d[i + 1] += gg * 0.25; }
    for (let i = n - Math.round(0.004 * sr); i < n; i++) d[i] *= (n - i) / (0.004 * sr);
  }
  return ir;
}

function buildChain(ctx, dest) {
  const bus = ctx.createGain();
  const conv = ctx.createConvolver(); conv.normalize = false; conv.buffer = roomIR(ctx);
  const pre = ctx.createGain(); pre.gain.value = 0.5;
  const shaper = ctx.createWaveShaper();
  const curve = new Float32Array(4097), k = 0.6;
  for (let i = 0; i < curve.length; i++) { const x = (i / 2048 - 1) * 2, a = Math.abs(x); curve[i] = Math.sign(x) * (a <= k ? a : k + (1 - k) * Math.tanh((a - k) / (1 - k))) * 0.5; }
  shaper.curve = curve;
  const master = ctx.createGain(); master.gain.value = 0.855 * 2;
  bus.connect(conv); conv.connect(pre); pre.connect(shaper); shaper.connect(master); master.connect(dest);
  return { bus, master };
}

/**
 * Live player with the mixing rules that made dense paper worlds bearable:
 * one flap per gesture key, >= spacing seconds between flap onsets (a late flap waits <= 80 ms, else it is dropped),
 * distance falloff, voice cap. Footsteps bypass the spacing rule.
 */
export class PaperSound {
  constructor({ spacing = 0.55, spacingJitter = 0.2, maxVoices = 8, falloff = 9, volume = 1, groupWindow = 0.5 } = {}) {
    Object.assign(this, { spacing, spacingJitter, maxVoices, falloff, volume, groupWindow });
    this.ctx = null; this.next = 0; this.lastSt = null; this.voices = []; this.count = 0; this.muted = false; this.keys = new Map();
  }
  /** Call inside a pointerdown / keydown handler (browsers only allow audio after a gesture). */
  unlock() {
    if (!this.ctx) {
      const AC = window.AudioContext || window.webkitAudioContext;
      try { this.ctx = new AC({ sampleRate: 48000, latencyHint: 'interactive' }); } catch { this.ctx = new AC(); }
      this.chain = buildChain(this.ctx, this.ctx.destination);
      this.chain.master.gain.value = 0.855 * 2 * this.volume;
    }
    if (this.ctx.state === 'suspended') this.ctx.resume();
    return this.ctx;
  }
  setMuted(m) { this.muted = m; if (this.ctx) this.chain.master.gain.setTargetAtTime(m ? 0 : 0.855 * 2 * this.volume, this.ctx.currentTime, 0.015); }
  _play(buf, level, pan, when) {
    const ctx = this.ctx, now = ctx.currentTime;
    this.voices = this.voices.filter((v) => v.end > now);
    if (this.voices.length >= this.maxVoices) return false;
    const ab = ctx.createBuffer(1, buf.length, ctx.sampleRate); ab.getChannelData(0).set(buf);
    const src = ctx.createBufferSource(); src.buffer = ab;
    const g = ctx.createGain(); g.gain.value = level;
    const p = ctx.createStereoPanner(); p.pan.value = clamp(pan, -1, 1) * 0.5;
    src.connect(g); g.connect(p); p.connect(this.chain.bus);
    src.start(when);
    this.voices.push({ end: when + ab.duration });
    return true;
  }
  /**
   * A card folding. size 0..1, up = rising, pan -1..1, distance in your world units (falloff = half-gain distance).
   * key: one sound per gesture - every part of one piece (all flaps of a tile, both planes of a tree) passes the same
   * key, and only the first within groupWindow speaks. For "nearest / largest wins", pick the best event of a frame
   * yourself and call flap() once (see references/sound.md).
   */
  flap({ size = 0.5, up = true, pan = 0, distance = 0, seed = null, key = null } = {}) {
    if (!this.ctx || this.muted || this.ctx.state !== 'running') return false;
    const now = this.ctx.currentTime + 0.004;
    if (key !== null) { const k = `${key}|${up}`, last = this.keys.get(k); if (last !== undefined && now - last < this.groupWindow) return false; this.keys.set(k, now); if (this.keys.size > 512) this.keys.clear(); }
    if (now < this.next - 0.08) return false; // too soon after the previous flap: stay silent
    const dist = 1 / (1 + (distance / this.falloff) ** 4);
    if (dist < 0.03) return false;
    this.voices = this.voices.filter((v) => v.end > this.ctx.currentTime);
    if (this.voices.length >= this.maxVoices) return false; // refused before it can use up the spacing slot
    const sd = seed ?? hashSeed('flap', this.count++);
    const P = flapParams({ size, up, seed: sd, lastSt: this.lastSt });
    this.lastSt = P.st;
    const buf = synthFlap({ size, up, pitch: P.pitch, dur: P.dur, seed: P.synthSeed, air: clamp(distance / (this.falloff * 2), 0, 1), sr: this.ctx.sampleRate });
    const when = Math.max(now, this.next);
    const r = mulberry32(P.synthSeed ^ 0xabc)();
    this.next = when + this.spacing + (r * 2 - 1) * this.spacingJitter;
    return this._play(buf, P.level * dist, pan, when);
  }
  /** A footstep (very quiet by design: repeated sounds must sit far below single events). */
  step({ surface = 'grass', foot = 0, pan = 0, seed = null } = {}) {
    if (!this.ctx || this.muted || this.ctx.state !== 'running') return false;
    const { buf, level } = synthStep({ surface, foot, seed: seed ?? hashSeed('step', this.count++), sr: this.ctx.sampleRate });
    return this._play(buf, level, pan + (foot ? 0.07 : -0.07), this.ctx.currentTime + 0.004);
  }
}

/**
 * Render a list of events to an AudioBuffer offline (for video): events = [{ t, kind: 'flap'|'step', ...opts }].
 * Applies the same spacing rule as the live player, so the video sounds like the game.
 */
export async function renderOffline(events, duration, { sampleRate = 48000, spacing = 0.55, spacingJitter = 0.2, falloff = 9 } = {}) {
  const ctx = new OfflineAudioContext(2, Math.ceil(duration * sampleRate), sampleRate);
  const { bus } = buildChain(ctx, ctx.destination);
  let next = 0, lastSt = null, count = 0;
  for (const e of [...events].sort((a, b) => a.t - b.t)) {
    let buf, level, when = e.t;
    let pan = e.pan ?? 0;
    if (e.kind === 'step') { ({ buf, level } = synthStep({ ...e, seed: e.seed ?? hashSeed('step', count++), sr: sampleRate })); pan += e.foot ? 0.07 : -0.07; }
    else {
      if (when < next - 0.08) continue;
      const dist = 1 / (1 + ((e.distance ?? 0) / falloff) ** 4);
      if (dist < 0.03) continue;
      const P = flapParams({ size: e.size ?? 0.5, up: e.up !== false, seed: e.seed ?? hashSeed('flap', count++), lastSt });
      lastSt = P.st;
      buf = synthFlap({ size: e.size ?? 0.5, up: e.up !== false, pitch: P.pitch, dur: P.dur, seed: P.synthSeed, air: Math.min(1, Math.max(0, (e.distance ?? 0) / (falloff * 2))), sr: sampleRate });
      const r = mulberry32(P.synthSeed ^ 0xabc)();
      level = P.level * dist; when = Math.max(when, next); next = when + spacing + (r * 2 - 1) * spacingJitter;
    }
    const ab = ctx.createBuffer(1, buf.length, sampleRate); ab.getChannelData(0).set(buf);
    const src = ctx.createBufferSource(); src.buffer = ab;
    const g = ctx.createGain(); g.gain.value = level;
    const p = ctx.createStereoPanner(); p.pan.value = clamp(pan, -1, 1) * 0.5;
    src.connect(g); g.connect(p); p.connect(bus); src.start(when);
  }
  return ctx.startRendering();
}
