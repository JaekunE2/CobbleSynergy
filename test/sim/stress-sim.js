/*
 * 스트레스 시뮬레이션: 무작위 팀(랜덤배틀 세트) + 무작위 행동(기술·교체)으로 많은 전투를 돌려
 * 시너지 스크립트가 들어간 상태에서 전투가 멈추거나(요청이 안 옴) 오류가 나는지 본다.
 * 사용: node stress-sim.js <showdown 폴더> <build 폴더> [전투 수]
 */
'use strict';
const path = require('path');
const fs = require('fs');
const [showdownDir, buildDir, countArg] = process.argv.slice(2);
const sim = require(path.join(showdownDir, 'sim'));
const { BattleStream, Teams, Dex } = sim;
const sides = JSON.parse(fs.readFileSync(path.join(buildDir, 'synergy-sides.json'), 'utf8'));
const script = fs.readFileSync(path.join(buildDir, 'battle-oneline.js'), 'utf8');
const CAP = JSON.stringify({ taken: 0.85, heal: 0.125, drain: 0.35 });
const N = +(countArg || 200);

let uid = 0;
function withCobblemon(team) {
  return team.map(m => ({ ...m, movesInfo: m.moves.map(id => { const pp = Dex.moves.get(id).pp || 5; return { pp, maxPp: pp }; }), uuid: 'u' + (++uid) }));
}
function merged() {
  // 여러 시너지 설정을 한 진영에 섞어 넣어 모든 처리기를 동시에 돌린다
  const keys = Object.keys(sides);
  const out = {};
  for (let i = 0; i < 6; i++) {
    const t = keys[Math.floor(Math.random() * keys.length)];
    const counts = Object.keys(sides[t]);
    const cfg = sides[t][counts[Math.floor(Math.random() * counts.length)]];
    for (const k in cfg) out[k] = (out[k] || []).concat(JSON.parse(JSON.stringify(cfg[k])));
  }
  (out.onhit || []).forEach(e => { e.c = 0.5; });
  return out;
}
let hangs = 0, errors = 0, done = 0;
const problems = [];
for (let n = 0; n < N; n++) {
  const stream = new BattleStream({ noCatch: false });
  const seed = [n + 1, 7, 13, 21];
  let t1, t2;
  try {
    t1 = withCobblemon(Teams.generate('gen9randombattle'));
    t2 = withCobblemon(Teams.generate('gen9randombattle'));
  } catch (e) { console.log('팀 생성 실패', e.message); break; }
  stream.write(`>start {"formatid":"gen9customgame@@@!teampreview","seed":${JSON.stringify(seed)}}`);
  stream.write(`>player p1 ${JSON.stringify({ name: 'A', team: t1 })}`);
  stream.write(`>player p2 ${JSON.stringify({ name: 'B', team: t2 })}`);
  const b = stream.battle;
  const c1 = merged(), c2 = merged();
  stream.write('>eval ' + script.replace('__CAP__', CAP).replace('__SIDES__', JSON.stringify({ p1: c1, p2: c2 })));
  let lastTurn = -1, same = 0, hung = false;
  for (let g = 0; g < 400 && !b.ended; g++) {
    let wrote = false;
    for (const side of b.sides) {
      if (!side.requestState || side.isChoiceDone()) continue;
      const req = side.activeRequest;
      if (side.requestState === 'switch' || (req && req.forceSwitch)) {
        const i = side.pokemon.findIndex((p, idx) => idx >= side.active.length && !p.fainted);
        stream.write(i >= 0 ? `>${side.id} switch ${i + 1}` : `>${side.id} pass`);
      } else if (side.requestState === 'move') {
        const bench = side.pokemon.map((p, i) => ({ p, i })).filter(x => x.i >= side.active.length && !x.p.fainted);
        const trapped = req && req.active && req.active[0] && req.active[0].trapped;
        if (bench.length && !trapped && Math.random() < 0.25) stream.write(`>${side.id} switch ${bench[Math.floor(Math.random() * bench.length)].i + 1}`);
        else {
          const moves = (req && req.active && req.active[0] ? req.active[0].moves : []).map((m, i) => ({ m, i })).filter(x => !x.m.disabled);
          stream.write(moves.length ? `>${side.id} move ${moves[Math.floor(Math.random() * moves.length)].i + 1}` : `>${side.id} default`);
        }
      } else stream.write(`>${side.id} default`);
      wrote = true;
    }
    if (b.turn === lastTurn) same++; else { same = 0; lastTurn = b.turn; }
    if (!wrote && !b.ended) { hung = true; break; }
    if (same > 30) { hung = true; break; }
  }
  const text = b.log.join('\n');
  const err = (text.match(/<<< error[^\n]*|\|error\|(?!\[Invalid choice\])[^\n]*/) || [null])[0];
  if (hung) { hangs++; problems.push({ n, turn: b.turn, why: '멈춤', tail: b.log.slice(-15) }); }
  if (err) { errors++; problems.push({ n, turn: b.turn, why: err }); }
  done++;
}
console.log(`전투 ${done}판 · 멈춤 ${hangs} · 오류 ${errors}`);
for (const p of problems.slice(0, 5)) console.log(JSON.stringify(p, null, 1).slice(0, 2500));
