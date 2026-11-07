/* 디버그용: node peek.js <showdown> <build> <type> <count> [turns] [ko] — 한 전투의 로그를 출력 */
'use strict';
const path = require('path');
const fs = require('fs');
const [sd, build, type, count, turns = '7', ko] = process.argv.slice(2);
const { BattleStream, Dex } = require(path.join(sd, 'sim'));
const sides = JSON.parse(fs.readFileSync(path.join(build, 'synergy-sides.json'), 'utf8'));
const script = fs.readFileSync(path.join(build, 'battle-oneline.js'), 'utf8');
let uid = 0;
const mon = (n, level, moves, ability) => {
  const s = Dex.species.get(n);
  return { name: s.name, species: s.name, level, moves, ability: ability || s.abilities[0], evs: {}, ivs: {}, item: '',
    movesInfo: moves.map(m => ({ pp: Dex.moves.get(m).pp, maxPp: Dex.moves.get(m).pp })), uuid: 'u' + (++uid) };
};
const T = type[0].toUpperCase() + type.slice(1);
const mono = t => Dex.species.all().filter(s => s.exists && !s.isNonstandard && s.types.length === 1 && s.types[0] === t && !s.evos.length && !s.battleOnly);
const p1 = mono(T).slice(0, +count).concat(mono('Normal')).slice(0, 6).map((s, i) => mon(s.name, ko && i === 0 ? 1 : 50, ['tackle']));
const p2 = mono('Water').slice(0, 6).map(s => mon(s.name, ko ? 100 : 50, [ko ? 'tackle' : 'splash']));
const st = new BattleStream();
st.write('>start {"formatid":"gen9customgame@@@!teampreview","seed":[1,2,3,4]}');
st.write('>player p1 ' + JSON.stringify({ name: 'A', team: p1 }));
st.write('>player p2 ' + JSON.stringify({ name: 'B', team: p2 }));
st.write('>eval ' + script.replace('__CAP__', '{"taken":0.85,"heal":0.125,"drain":0.35}').replace('__SIDES__', JSON.stringify({ p1: sides[type][count] })));
for (let i = 0; i < +turns && !st.battle.ended; i++) { st.write('>p1 default'); st.write('>p2 default'); }
console.log('p1 =', p1.map(x => x.species).join(', '));
console.log(st.battle.log.filter(l => !/^\|(t:|j|c|split|request)\|/.test(l) && l !== '|').join('\n'));
