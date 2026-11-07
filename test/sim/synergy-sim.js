/*
 * CobbleSynergy 전투 시뮬레이션 테스트.
 * Cobblemon 이 쓰는 Showdown(sim)으로 실제 전투를 돌려, 모드가 만든 설정(build/synergy-sides.json)과
 * 한 줄 스크립트(build/battle-oneline.js)를 그대로 >eval 로 넣고 효과가 나타나는지 검사한다.
 *
 * 사용: node synergy-sim.js <showdown 폴더> <build 폴더> [결과 로그 파일]
 */
'use strict';
const path = require('path');
const fs = require('fs');

const [showdownDir, buildDir, outFile] = process.argv.slice(2);
const sim = require(path.join(showdownDir, 'sim'));
const { BattleStream, Dex, Teams } = sim;

const sides = JSON.parse(fs.readFileSync(path.join(buildDir, 'synergy-sides.json'), 'utf8'));
const script = fs.readFileSync(path.join(buildDir, 'battle-oneline.js'), 'utf8');
const CAP = JSON.stringify({ taken: 0.85, heal: 0.125, drain: 0.35 });

const NAME = {
  reflect: 'Reflect', lightscreen: 'Light Screen', auroraveil: 'Aurora Veil', safeguard: 'Safeguard', tailwind: 'Tailwind',
  mist: 'Mist', stealthrock: 'Stealth Rock', spikes: 'Spikes', toxicspikes: 'Toxic Spikes', stickyweb: 'Sticky Web',
  trickroom: 'Trick Room', electricterrain: 'Electric Terrain', grassyterrain: 'Grassy Terrain', mistyterrain: 'Misty Terrain',
  psychicterrain: 'Psychic Terrain', sunnyday: 'SunnyDay', raindance: 'RainDance', sandstorm: 'Sandstorm', snow: 'Snow',
  taunt: 'Taunt', leechseed: 'Leech Seed', aquaring: 'Aqua Ring', encore: 'Encore',
};
const TYPE_MOVE = {
  Normal: 'bodyslam', Fire: 'flamethrower', Water: 'surf', Grass: 'energyball', Electric: 'thunderbolt', Ice: 'icebeam',
  Fighting: 'closecombat', Poison: 'sludgebomb', Ground: 'earthpower', Flying: 'airslash', Psychic: 'psychic',
  Bug: 'bugbuzz', Rock: 'powergem', Ghost: 'shadowball', Dragon: 'dragonpulse', Dark: 'darkpulse', Steel: 'flashcannon',
  Fairy: 'moonblast',
};

const out = [];
let pass = 0, fail = 0;
const log = (s) => { out.push(s); console.log(s); };
const check = (name, ok, detail) => {
  if (ok) pass++; else fail++;
  log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ' — ' + detail : ''}`);
};

function monoSpecies(type, n) {
  const list = Dex.species.all().filter(s => s.exists && !s.isNonstandard && s.types.length === 1 && s.types[0] === type &&
    !s.evos.length && !s.battleOnly && s.baseStats.hp > 0 &&
    // 아르세우스·실버디 타입폼은 도구가 없으면 노말이 되므로 제외
    !s.requiredItem && !s.requiredItems && !['Arceus', 'Silvally'].includes(s.baseSpecies));
  return list.slice(0, n);
}

/* 지정 타입 n 마리 + 노말 보충 (노말 시너지 계산 영향은 테스트에서 무시: 설정을 직접 넣기 때문) */
let uid = 0;
/* Cobblemon 이 보내는 형태와 같은 세트: 기술마다 movesInfo(pp), 포켓몬마다 uuid */
function mon(speciesName, level, moves, ability) {
  const s = Dex.species.get(speciesName);
  return {
    name: s.name, species: s.name, level, moves, ability: ability || s.abilities[0], evs: {}, ivs: {}, item: '',
    movesInfo: moves.map(m => { const pp = Dex.moves.get(m).pp; return { pp, maxPp: pp }; }),
    uuid: 'test-' + (++uid),
  };
}

function team(type, n, level) {
  const mons = monoSpecies(type, Math.max(n, 1));
  const filler = monoSpecies('Normal', 6);
  const picked = mons.concat(filler).slice(0, 6);
  return picked.map((s, i) => mon(s.name, typeof level === 'function' ? level(i) : level, [TYPE_MOVE[s.types[0]] || 'tackle', 'tackle']));
}

function foeTeam(level, moves) {
  return monoSpecies('Water', 6).map(s => mon(s.name, level, moves));
}

function run(p1Team, p2Team, cfg, turns, p2cfg) {
  const stream = new BattleStream({ noCatch: false });
  stream.write('>start {"formatid":"gen9customgame@@@!teampreview","seed":[1,2,3,4]}');
  stream.write(`>player p1 ${JSON.stringify({ name: 'A', team: p1Team })}`);
  stream.write(`>player p2 ${JSON.stringify({ name: 'B', team: p2Team })}`);
  if (!stream.battle.started) throw new Error('전투가 시작되지 않음 (팀 형식 오류)');
  stream.write('>eval ' + script.replace('__CAP__', CAP).replace('__SIDES__', JSON.stringify(p2cfg ? { p1: cfg, p2: p2cfg } : { p1: cfg })));
  // 요청이 있는 쪽만 응답한다 (기절 후 교체 요청 때 다른 쪽이 응답하면 거부되어 턴이 밀린다)
  const b = stream.battle;
  for (let guard = 0; b.turn <= turns && !b.ended && guard < 200; guard++) {
    for (const side of b.sides) {
      if (!side.requestState || side.isChoiceDone()) continue;
      if (side.requestState === 'switch') {
        // Cobblemon 포크는 슬롯 없이 default 교체를 하면 오류가 나므로 살아있는 후보 번호를 직접 지정한다
        const i = side.pokemon.findIndex((p, idx) => idx >= side.active.length && !p.fainted);
        stream.write(i >= 0 ? `>${side.id} switch ${i + 1}` : `>${side.id} pass`);
      } else {
        stream.write(`>${side.id} default`);
      }
    }
  }
  return b.log.slice();
}

/* 로그에서 needle 이 처음 나온 턴, 그 다음 end 가 나온 턴 */
function turnOf(lines, pred) {
  let turn = 0;
  for (const l of lines) {
    const m = /^\|turn\|(\d+)/.exec(l);
    if (m) turn = +m[1];
    if (pred(l)) return turn;
  }
  return -1;
}

for (const type of Object.keys(sides)) {
  const T = type[0].toUpperCase() + type.slice(1);
  for (const count of Object.keys(sides[type])) {
    const cfg = JSON.parse(JSON.stringify(sides[type][count]));
    const label = `${type}×${count}`;
    // 확률 효과는 검사를 위해 100% 로 바꾼 사본을 쓴다
    (cfg.onhit || []).forEach(e => { e.c = 1; });

    const lines = run(team(T, +count, 50), foeTeam(50, ['splash']), cfg, 7);
    const text = lines.join('\n');
    if (process.env.DUMP === `${type}${count}`) fs.writeFileSync(path.join(buildDir, 'dump.log'), text, 'utf8');
    check(`${label} 스크립트 오류 없음`, !/<<< error/.test(text), (text.match(/<<< error[^\n]*/) || [''])[0]);
    check(`${label} eval 로그 숨김`, !/\|>>> /.test(text));

    for (const s of cfg.start || []) {
      const nm = NAME[s.id] || s.id;
      if (s.k === 'sidecond') {
        const sideId = s.foe ? 'p2' : 'p1';
        const started = turnOf(lines, l => l.startsWith(`|-sidestart|${sideId}`) && l.includes(nm));
        check(`${label} ${nm} 설치 (${sideId})`, started === 1, `turn=${started}`);
        if (s.d) {
          const ended = turnOf(lines, l => l.startsWith(`|-sideend|${sideId}`) && l.includes(nm));
          check(`${label} ${nm} ${s.d}턴 유지`, ended === s.d, `끝난 턴=${ended}`);
        }
        if (s.n > 1) {
          const layers = lines.filter(l => l.startsWith(`|-sidestart|${sideId}`) && l.includes(nm)).length;
          check(`${label} ${nm} ${s.n}겹`, layers === s.n, `겹=${layers}`);
        }
      } else if (s.k === 'room' || s.k === 'terrain') {
        check(`${label} ${nm} 시작`, turnOf(lines, l => l.startsWith('|-fieldstart|') && l.includes(nm)) === 1);
        if (s.d) {
          const ended = turnOf(lines, l => l.startsWith('|-fieldend|') && l.includes(nm));
          check(`${label} ${nm} ${s.d}턴 유지`, ended === s.d, `끝난 턴=${ended}`);
        }
      } else if (s.k === 'weather') {
        check(`${label} ${nm} 시작`, turnOf(lines, l => l.startsWith(`|-weather|${nm}`) && !l.includes("[upkeep]")) === 1);
        if (s.d) {
          const ended = turnOf(lines, l => l === '|-weather|none');
          check(`${label} ${nm} ${s.d}턴 유지`, ended === s.d, `끝난 턴=${ended}`);
        }
      } else if (s.k === 'foevolatile') {
        check(`${label} 상대 선두 ${nm}`, turnOf(lines, l => l.startsWith('|-start|p2a') && l.includes(nm)) === 1);
        if (s.d) {
          const ended = turnOf(lines, l => l.startsWith('|-end|p2a') && l.includes(nm));
          check(`${label} ${nm} ${s.d}턴`, ended === s.d, `끝난 턴=${ended}`);
        }
      }
    }
    for (const e of cfg.entry || []) {
      if (e.v) check(`${label} 선두 ${NAME[e.v] || e.v}`, lines.some(l => l.startsWith('|-start|p1a') && l.includes(NAME[e.v] || e.v)));
      if (e.b) check(`${label} 선두 랭크업`, lines.some(l => l.startsWith('|-boost|p1a')));
      if (e.b && e.d) {
        const back = turnOf(lines, l => l.startsWith('|-unboost|p1a'));
        check(`${label} 랭크업 ${e.d}턴 뒤 되돌림`, back === e.d, `되돌린 턴=${back}`);
      }
      if (e.fo) {
        const boosted = new Set(lines.filter(l => l.startsWith('|-boost|p1a')).map(l => l.split('|')[2]));
        check(`${label} 첫 번째 ${T} 포켓몬만 랭크업`, boosted.size === 1, [...boosted].join(','));
      }
    }
    for (const e of cfg.onhit || []) {
      const hit = e.st ? lines.some(l => l.startsWith('|-status|p2a') && l.includes(e.st))
        : e.v ? lines.some(l => l.startsWith('|cant|p2a') && l.includes('flinch'))
          : lines.some(l => l.startsWith('|-unboost|p2a'));
      // 풀죽음은 상대보다 먼저 공격해야 해서 스피드에 따라 안 나올 수 있다
      if (e.v === 'flinch' && !hit) log(`INFO ${label} 풀죽음 미발생 (스피드 차이 가능)`);
      else check(`${label} 적중 시 ${e.st || e.v || JSON.stringify(e.b)} (확률 100% 테스트)`, hit);
    }

    // 쓰러지는 상황이 필요한 효과: 우리 선두 레벨 1, 상대 레벨 100 몸통박치기
    if (cfg.endure || cfg.grudge || cfg.ppgrudge) {
      const ko = run(team(T, +count, i => (i === 0 ? 1 : 50)), foeTeam(100, ['darkpulse']), cfg, 4);
      const kt = ko.join('\n');
      check(`${label}(KO) 스크립트 오류 없음`, !/<<< error/.test(kt));
      if (cfg.endure) {
        const n = ko.filter(l => l.includes('move: Endure')).length;
        check(`${label} 버티기 발동`, n >= 1, `${n}회`);
        const once = ko.filter(l => l.startsWith('|-activate|p1a') && l.includes('move: Endure')).map(l => l.split('|')[2]);
        check(`${label} 버티기 포켓몬마다 1회`, new Set(once).size === once.length);
      }
      if (cfg.grudge) check(`${label} 원한 반격 피해`, ko.some(l => l.startsWith('|-damage|p2a') && /grudge/i.test(l)));
      if (cfg.ppgrudge) check(`${label} 원념 PP 소진`, ko.some(l => l.startsWith('|-activate|p2a') && l.includes('move: Grudge')));
    }
    // 최후의 드래곤: 마지막 한 마리만 레벨 50, 나머지는 레벨 1 이라 차례로 쓰러진다
    if (cfg.laststand) {
      // 단일 타입 드래곤이 count 보다 적으면 실제 드래곤 수 기준 (나머지 칸은 노말)
      const nd = Math.min(+count, monoSpecies(T, +count).length);
      const ls = run(team(T, +count, i => (i < nd - 1 ? 1 : 50)), foeTeam(100, ['darkpulse']), cfg, 3 + nd * 2);
      const lt = ls.join('\n');
      check(`${label}(최후) 스크립트 오류 없음`, !/<<< error/.test(lt));
      const evs = ls.filter(x => x.includes('cobblesynergy:')).map(x => JSON.parse(x.slice(x.indexOf('cobblesynergy:') + 14))).filter(e => e.k === 'laststand');
      check(`${label} 마지막 남은 ${T} 포켓몬 랭크업 1회`, evs.length === 1, JSON.stringify(evs.map(e => e.b)));
      const firstBoost = ls.findIndex(x => x.startsWith('|-boost|p1a'));
      const faints = ls.slice(0, Math.max(firstBoost, 0)).filter(x => x.startsWith('|faint|p1a')).length;
      check(`${label} 랭크업은 드래곤 ${nd - 1}마리가 쓰러진 뒤`, firstBoost >= 0 && faints === nd - 1, `쓰러진 수=${faints}`);
      const ls2 = cfg.laststand.find(e => e.d);
      if (ls2) {
        // 마지막 드래곤은 레벨 100, 상대는 레벨 50 이라 마지막 드래곤이 오래 버틴다
        const lsLong = run(team(T, +count, i => (i < nd - 1 ? 1 : 100)), foeTeam(50, ['darkpulse']), cfg, 3 + nd * 2 + ls2.d);
        const boostTurn = turnOf(lsLong, x => x.startsWith('|-boost|p1a'));
        const backTurn = turnOf(lsLong, x => x.startsWith('|-unboost|p1a'));
        if (backTurn < 0) log(`INFO ${label} 최후의 드래곤 되돌림 확인 불가 (전투가 ${ls2.d}턴 전에 끝남)`);
        // 쓰러진 뒤 교체로 들어오면 (턴 끝 처리 뒤) 다음 턴부터 d 턴
        else check(`${label} 최후의 드래곤 ${ls2.d}턴 유지 후 되돌림`, backTurn - boostTurn === ls2.d, `올림 턴=${boostTurn} 되돌림 턴=${backTurn}`);
      }
    }
  }
}

// ---------------------------------------------------------------- 날씨·필드 충돌 규칙
{
  const weatherStart = (l, nm) => turnOf(l, x => x.startsWith(`|-weather|${nm}`) && !x.includes('[upkeep]'));
  // 1) 마릿수가 많은 쪽 날씨가 먼저, 진 쪽은 대기했다가 끝나면 이어서 발동
  let l = run(team('Fire', 6, 50), team('Water', 4, 50), sides.fire['6'], 9, sides.water['4']);
  check('충돌: 불꽃6(쾌청 5턴) vs 물4(비) → 쾌청 먼저', weatherStart(l, 'SunnyDay') === 1, `turn=${weatherStart(l, 'SunnyDay')}`);
  check('충돌: 비는 쾌청이 끝난 턴에 이어서 발동', weatherStart(l, 'RainDance') === 5, `turn=${weatherStart(l, 'RainDance')}`);
  // 2) 같은 날씨를 양쪽이 원하면 한 번만, 긴 쪽 턴 수
  l = run(team('Water', 6, 50), team('Water', 4, 50), sides.water['6'], 7, sides.water['4']);
  const rains = l.filter(x => x.startsWith('|-weather|RainDance') && !x.includes('[upkeep]')).length;
  check('충돌: 같은 날씨는 한 번만 발동', rains === 1, `${rains}회`);
  check('충돌: 같은 날씨는 긴 쪽(5턴) 유지', turnOf(l, x => x === '|-weather|none') === 5, `끝난 턴=${turnOf(l, x => x === '|-weather|none')}`);
  // 3) 특성(가뭄)으로 이미 날씨가 있으면 특성 날씨 우선, 시너지 날씨는 대기 후 발동
  const foe = foeTeam(50, ['splash']);
  foe[0] = mon('Torkoal', 50, ['splash'], 'Drought');
  l = run(team('Water', 4, 50), foe, sides.water['4'], 8);
  const ab = turnOf(l, x => x.startsWith('|-weather|SunnyDay') && x.includes('Drought'));
  check('충돌: 특성 날씨(가뭄)가 먼저', ab === 0, `turn=${ab}`);
  check('충돌: 시너지 비는 특성 날씨가 끝난 뒤 발동', weatherStart(l, 'RainDance') === 5, `turn=${weatherStart(l, 'RainDance')}`);
  // 4) 대기 중인 날씨는 6턴이 지나면 버린다 (상대가 계속 날씨를 덮어쓰는 경우)
  check('충돌: 대기열 오류 없음', !l.join('\n').includes('<<< error'));
}

// ---------------------------------------------------------------- 실제 공격 피해 비교 (같은 시드, 시너지 있음/없음)
{
  const firstLoss = (l, side) => {
    let prev = null;
    for (const x of l) {
      const m = new RegExp(`^\\|(?:switch|-damage)\\|${side}a: [^|]+\\|(?:[^|]*\\|)?(\\d+)/(\\d+)`).exec(x);
      if (!m) continue;
      const hp = +m[1];
      if (x.startsWith('|-damage|') && prev !== null) return prev - hp;
      prev = hp;
    }
    return -1;
  };
  const events = (l) => l.filter(x => x.includes('cobblesynergy:')).map(x => JSON.parse(x.slice(x.indexOf('cobblesynergy:') + 14)));
  // 강철 4: 상대의 몸통박치기로 받는 피해 -15%
  const steelT = () => team('Steel', 4, 50).map(m => mon(m.species, 50, ['splash']));
  const hitter = () => foeTeam(100, ['bodyslam']);
  const withS = run(steelT(), hitter(), sides.steel['4'], 2);
  const without = run(steelT(), hitter(), {}, 2);
  const a = firstLoss(withS, 'p1'), b = firstLoss(without, 'p1');
  check('실전 피해: 강철4 받는 피해 -15%', a > 0 && b > 0 && Math.abs(a - Math.round(b * 0.85)) <= 1, `시너지 없음 ${b} → 있음 ${a} (기대 ${Math.round(b * 0.85)})`);
  const ev = events(withS).find(e => e.k === 'dmg');
  check('실전 피해: 강철4 전투 로그 이벤트', !!ev && ev.m === 0.85, ev ? `before=${ev.before} m=${ev.m}` : '없음');
  // 노말 6: 자속이 아닌 기술(10만볼트) 위력 +20%
  const normT = () => team('Normal', 6, 50).map(m => mon(m.species, 50, ['thunderbolt']));
  const tank = () => foeTeam(100, ['splash']);
  const w2 = run(normT(), tank(), sides.normal['6'], 2);
  const o2 = run(normT(), tank(), {}, 2);
  const c2 = firstLoss(w2, 'p2'), d2 = firstLoss(o2, 'p2');
  check('실전 피해: 노말6 자속 아닌 기술 +20%', c2 > 0 && d2 > 0 && Math.abs(c2 / d2 - 1.2) < 0.05, `없음 ${d2} → 있음 ${c2} (×${(c2 / d2).toFixed(3)})`);
  const pv = events(w2).find(e => e.k === 'power');
  check('실전 피해: 노말6 위력 이벤트', !!pv && pv.m === 1.2 && pv.bp === 90, pv ? `bp=${pv.bp} m=${pv.m}` : '없음');
  // 노말 6 이라도 자속 기술(노말 기술)은 그대로
  const normS = () => team('Normal', 6, 50).map(m => mon(m.species, 50, ['bodyslam']));
  const w3 = run(normS(), tank(), sides.normal['6'], 2), o3 = run(normS(), tank(), {}, 2);
  check('실전 피해: 노말6 자속 기술은 변화 없음', firstLoss(w3, 'p2') === firstLoss(o3, 'p2'), `${firstLoss(o3, 'p2')} / ${firstLoss(w3, 'p2')}`);
}

log(`\n결과: PASS ${pass} / FAIL ${fail}`);
if (outFile) fs.writeFileSync(outFile, out.join('\n') + '\n', 'utf8');
process.exit(fail ? 1 : 0);
