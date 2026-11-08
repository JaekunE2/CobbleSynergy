const fs=require('fs'),path=require('path');
const [showdown,build]=process.argv.slice(2);
const {BattleStream,Dex}=require(path.join(showdown,'sim'));
const cfgs=JSON.parse(fs.readFileSync(path.join(build,'synergy-sides.json'),'utf8'));
const script=fs.readFileSync(path.join(build,'battle-oneline.js'),'utf8');
let passed=0;function check(label,ok){if(!ok)throw Error(label);passed++;}
for(const [count,ratio,turns] of [[2,.95,3],[4,.92,3],[6,.90,4]]){
 const stream=new BattleStream({noCatch:false});
 stream.write('>start '+JSON.stringify({formatid:'gen9customgame@@@!teampreview',seed:[1,2,3,4]}));
 let uid=0;const mon=name=>{const moves=['tackle','swift','fissure','protect'];return {name,species:name,ability:'Pressure',moves,level:50,uuid:'fairy-test-'+(++uid),movesInfo:moves.map(m=>({pp:Dex.moves.get(m).pp,maxPp:Dex.moves.get(m).pp})),evs:{},ivs:{},item:''};};
 stream.write('>player p1 '+JSON.stringify({name:'fairy',team:[mon('Clefable'),mon('Clefable')]}));
 stream.write('>player p2 '+JSON.stringify({name:'opponent',team:[mon('Snorlax'),mon('Snorlax')]}));
 const b=stream.battle;const target=b.p1.active[0],source=b.p2.active[0];
 stream.write('>eval '+script.replace('__CAP__',JSON.stringify({taken:.85,heal:.125,drain:.35})).replace('__SIDES__',JSON.stringify({p1:cfgs.fairy[count]})));
 const hit=(accuracy,move=b.dex.moves.get('tackle'),t=target,s=source)=>b.runEvent('Accuracy',t,s,move,accuracy);
 check('tier accuracy '+count,Math.abs(hit(100)-100*ratio)<.001);
 check('lower accuracy is multiplicative '+count,Math.abs(hit(70)-70*ratio)<.001);
 check('always hit stays true '+count,hit(true,b.dex.moves.get('swift'))===true);
 check('OHKO unchanged '+count,hit(30,b.dex.moves.get('fissure'))===30);
 check('own side unchanged '+count,hit(100,b.dex.moves.get('tackle'),target,target)===100);
 check('outgoing attacks unchanged '+count,hit(100,b.dex.moves.get('tackle'),source,target)===100);
 const before=b.turn;b.turn=turns;
 check('active on last turn '+count,Math.abs(hit(100)-100*ratio)<.001);
 b.turn=turns+1;check('expires next turn '+count,hit(100)===100);b.turn=before;
 check('economy removed '+count,!cfgs.fairy[count].odds&&!cfgs.fairy[count].reroll&&!cfgs.fairy[count].oddsbuy);
 if(count>=4)check('misty terrain '+count,b.field.terrain==='mistyterrain');
 b.__cs.p1.accuracyshield.push({m:.8,until:100});check('cannot exceed ten percent '+count,hit(100)===90);
 b.destroy();
}
console.log(JSON.stringify({passed}));
