(function () {
  var B = battle;
  if (!B) return;
  var L = B.log;
  if (L.length && String(L[L.length - 1]).indexOf('||>>> ') === 0) L.pop();
  if (!B.__cs) {
    B.__cs = {};
    B.__csCap = __CAP__;
    var originalAdd = B.add;
    B.add = function () {
      if (arguments.length === 2 && arguments[0] === '' && typeof arguments[1] === 'string' &&
          (arguments[1].indexOf('>>> ') === 0 || arguments[1] === '<<< undefined')) return;
      return originalAdd.apply(this, arguments);
    };
    B.__csEv = function (o) {
      try { originalAdd.call(B, 't:', 'cobblesynergy:' + JSON.stringify(o)); } catch (err) {}
    };
    var ev = B.__csEv;
    var who = function (p) { return p ? { side: p.side.id, uuid: p.uuid || '', species: p.species.name } : null; };
    var S = function (p) { return p && p.side ? B.__cs[p.side.id] : null; };
    var each = function (list, p, fn) {
      if (!list || !p) return;
      for (var i = 0; i < list.length; i++) {
        var e = list[i];
        if (!e.t || p.hasType(e.t)) fn(e);
      }
    };
    var has = function (list, p) {
      var found = null;
      each(list, p, function (e) { if (!found) found = e; });
      return found;
    };

    B.__csEntry = function (p) {
      var c = S(p);
      if (!c || !c.entry || !p || p.fainted || p.__csEntry) return;
      var b = {};
      var vols = [];
      var names = [];
      var timed = [];
      each(c.entry, p, function (e) {
        if (e.fo) {
          var fk = '__csFo_' + e.nm;
          if (p.side[fk]) return;
          p.side[fk] = true;
        }
        if (e.v) vols.push(e.v);
        var own = {};
        for (var k in e.b) {
          var key = k;
          if (k === 'best') key = p.storedStats.atk >= p.storedStats.spa ? 'atk' : 'spa';
          b[key] = (b[key] || 0) + e.b[k];
          own[key] = (own[key] || 0) + e.b[k];
        }
        if (e.d && e.b) timed.push({ nm: e.nm, b: own, d: e.d });
        if (names.indexOf(e.nm) < 0) names.push(e.nm);
      });
      p.__csEntry = true;
      if (!names.length) return;
      ev({ k: 'entry', nm: names.join(','), mon: who(p), b: b, v: vols });
      vols.forEach(function (v) { p.addVolatile(v, p); });
      if (Object.keys(b).length) {
        B.boost(b, p, p);
        timed.forEach(function (t) { B.__csTimed(p, t.nm, t.b, t.d); });
      }
    };

    /* 지속 턴이 있는 랭크업: d 턴이 지나면 올린 만큼(남아 있는 만큼만) 되돌린다. 교체되면 엔진이 랭크를 지우므로 기록도 지운다. */
    B.__csTimed = function (p, nm, b, d) {
      if (!p.__csTimedList) p.__csTimedList = [];
      /* 턴 끝 처리(Residual) 뒤에 들어온 포켓몬은 다음 턴부터 센다. 그 턴 끝에 d 번째 턴이 끝나면 되돌린다. */
      var start = B.__csResidualTurn === B.turn ? B.turn + 1 : Math.max(B.turn, 1);
      p.__csTimedList.push({ nm: nm, b: b, until: start + d - 1 });
    };
    B.__csExpire = function () {
      B.sides.forEach(function (sd) {
        sd.active.forEach(function (p) {
          if (!p || !p.__csTimedList || !p.__csTimedList.length) return;
          var keep = [];
          p.__csTimedList.forEach(function (t) {
            if (B.turn < t.until) { keep.push(t); return; }
            if (p.fainted || !p.hp) return;
            var back = {};
            for (var k in t.b) {
              var cur = p.boosts[k] || 0;
              var undo = t.b[k] > 0 ? Math.min(t.b[k], Math.max(cur, 0)) : Math.max(t.b[k], Math.min(cur, 0));
              if (undo) back[k] = -undo;
            }
            ev({ k: 'expire', nm: t.nm, mon: who(p), b: back });
            if (Object.keys(back).length) B.boost(back, p, p);
          });
          p.__csTimedList = keep;
        });
      });
    };
    B.onEvent('SwitchOut', B.format, function (p) { if (p) p.__csTimedList = []; });

    B.__csStart = function (sd, list) {
      var me = sd.active[0];
      if (!me || !list) return;
      var foe = sd.foe;
      list.forEach(function (s) {
        try {
          if (s.k === 'sidecond') {
            var target = s.foe ? foe : sd;
            if (!target) return;
            ev({ k: 'start', nm: s.nm, side: sd.id, id: s.id, d: s.d, n: s.n, foe: !!s.foe, r: 'ok' });
            for (var i = 0; i < (s.n || 1); i++) target.addSideCondition(s.id, me);
            var st = target.sideConditions[s.id];
            if (st && s.d) st.duration = s.d;
          } else if (s.k === 'foevolatile') {
            var fa = foe && foe.active[0];
            if (!fa || fa.fainted) return;
            var ok = fa.addVolatile(s.id, me);
            if (ok && s.d && fa.volatiles[s.id]) fa.volatiles[s.id].duration = s.d;
            ev({ k: 'start', nm: s.nm, side: sd.id, id: s.id, d: s.d, foe: true, r: ok ? 'ok' : 'fail', mon: who(fa) });
          }
        } catch (err) {}
      });
    };

    B.__csAlive = function (sd) {
      return sd.active.find(function (p) { return p && !p.fainted; }) || sd.pokemon.find(function (p) { return !p.fainted; });
    };
    B.__csFieldState = function (k, id) {
      if (k === 'weather') return B.field.weather ? { id: B.field.weather, st: B.field.weatherState } : null;
      if (k === 'terrain') return B.field.terrain ? { id: B.field.terrain, st: B.field.terrainState } : null;
      return B.field.pseudoWeather[id] ? { id: id, st: B.field.pseudoWeather[id] } : null;
    };
    B.__csTryField = function (sd, s, late) {
      var me = B.__csAlive(sd);
      if (!me) return true;
      var cur = B.__csFieldState(s.k, s.id);
      if (cur) {
        if (cur.id !== s.id) return false;
        if (s.d && cur.st && cur.st.duration && cur.st.duration < s.d) cur.st.duration = s.d;
        ev({ k: 'start', nm: s.nm, side: sd.id, id: s.id, d: s.d, r: 'merged' });
        return true;
      }
      var ok = false;
      try {
        if (s.k === 'weather') { ok = B.field.setWeather(s.id, me); if (ok && s.d) B.field.weatherState.duration = s.d; }
        else if (s.k === 'terrain') { ok = B.field.setTerrain(s.id, me); if (ok && s.d) B.field.terrainState.duration = s.d; }
        else { B.field.addPseudoWeather(s.id, me); var ps = B.field.pseudoWeather[s.id]; ok = !!ps; if (ps && s.d) ps.duration = s.d; }
      } catch (err) { ok = true; }
      if (ok) ev({ k: 'start', nm: s.nm, side: sd.id, id: s.id, d: s.d, r: late ? 'late' : 'ok' });
      return !!ok;
    };
    B.__csQueue = [];
    B.onEvent('Residual', B.format, { order: 99 }, function () {
      B.__csExpire();
      B.__csResidualTurn = B.turn;
      var rest = [];
      for (var i = 0; i < B.__csQueue.length; i++) {
        var q = B.__csQueue[i];
        if (B.turn > q.until) { ev({ k: 'start', nm: q.s.nm, side: q.sd.id, id: q.s.id, r: 'expired' }); continue; }
        var blocked = rest.some(function (r) { return r.s.k === q.s.k && (q.s.k !== 'room' || r.s.id === q.s.id); });
        if (blocked || !B.__csTryField(q.sd, q.s, true)) rest.push(q);
      }
      B.__csQueue = rest;
    });

    B.__csLast = function (sd) {
      var c = sd && B.__cs[sd.id];
      if (!c || !c.laststand) return;
      c.laststand.forEach(function (e) {
        var key = '__csLast_' + e.nm;
        if (sd[key]) return;
        var alive = sd.pokemon.filter(function (m) { return m.hp > 0 && !m.fainted && (!e.t || m.hasType(e.t)); });
        if (alive.length !== 1) return;
        var p = alive[0];
        if (!p.isActive) return;
        sd[key] = true;
        var b = {};
        for (var k in e.b) {
          var key2 = k === 'best' ? (p.storedStats.atk >= p.storedStats.spa ? 'atk' : 'spa') : k;
          b[key2] = (b[key2] || 0) + e.b[k];
        }
        ev({ k: 'laststand', nm: e.nm, mon: who(p), b: b, d: e.d || 0 });
        B.boost(b, p, p);
        if (e.d) B.__csTimed(p, e.nm, b, e.d);
      });
    };

    B.onEvent('SwitchIn', B.format, function (p) { B.__csEntry(p); B.__csLast(p.side); });

    B.onEvent('BasePower', B.format, function (bp, src, tgt, move) {
      var c = S(src);
      if (!c || !move) return;
      var m = 1;
      var names = [];
      each(c.power, src, function (e) {
        if (e.mt && move.type !== e.mt) return;
        if (e.ns && src.hasType(move.type)) return;
        m *= e.m; names.push(e.nm);
      });
      each(c.first, src, function (e) { if (src.activeMoveActions <= 1) { m *= e.m; names.push(e.nm); } });
      if (m === 1) return;
      ev({ k: 'power', nm: names.join(','), mon: who(src), move: move.name, bp: bp, m: m });
      return this.chainModify(m);
    });

    ['Atk', 'Def', 'SpA', 'SpD', 'Spe'].forEach(function (k) {
      var s = k.toLowerCase();
      B.onEvent('Modify' + k, B.format, function (v, p) {
        var c = S(p);
        if (!c) return;
        var m = 1;
        each(c.stat, p, function (e) { if (e.s === s) m *= e.m; });
        if (m !== 1) return this.chainModify(m);
      });
    });

    B.onEvent('ModifyCritRatio', B.format, function (r, src) {
      var c = S(src);
      if (!c) return;
      var n = 0;
      each(c.crit, src, function (e) { n += e.n; });
      if (n) return r + n;
    });

    B.onEvent('ModifyPriority', B.format, function (pr, p, t, move) {
      var c = S(p);
      if (!c || !c.priority || !move || move.category === 'Status' || p.activeMoveActions > 0) return;
      var n = 0;
      each(c.priority, p, function (e) { n = Math.max(n, e.n); });
      if (n) return pr + n;
    });

    B.onEvent('ModifyDamage', B.format, function (d, src, tgt, move) {
      var m = 1;
      var names = [];
      var c = S(src);
      if (c && tgt) {
        each(c.execute, src, function (e) { if (tgt.hp <= tgt.maxhp * e.hp) { m *= e.m; names.push(e.nm); } });
        if (tgt.status) each(c.vsstatus, src, function (e) { m *= e.m; names.push(e.nm); });
      }
      var dc = S(tgt);
      if (dc && tgt) {
        var t = 1;
        each(dc.taken, tgt, function (e) { t *= e.m; names.push(e.nm); });
        m *= Math.max(t, B.__csCap.taken);
      }
      if (m === 1) return;
      ev({ k: 'dmg', nm: names.join(','), atk: who(src), mon: who(tgt), move: move ? move.name : '', before: d, m: m, hp: tgt ? tgt.hp : 0 });
      return this.chainModify(m);
    });

    B.onEvent('Damage', B.format, function (dmg, tgt, src, eff) {
      if (!eff || eff.effectType !== 'Move' || !tgt || tgt.__csEndure) return;
      if (tgt.hp !== tgt.maxhp || dmg < tgt.hp) return;
      var c = S(tgt);
      var e = c && has(c.endure, tgt);
      if (!e) return;
      tgt.__csEndure = true;
      ev({ k: 'endure', nm: e.nm, mon: who(tgt), dmg: dmg });
      B.add('-activate', tgt, 'move: Endure');
      return tgt.hp - 1;
    });

    B.onEvent('AfterMoveSecondarySelf', B.format, function (src, tgt, move) {
      if (!move || move.category === 'Status' || !move.totalDamage || !src) return;
      var c = S(src);
      if (c) {
        var f = 0;
        each(c.drain, src, function (e) { f += e.f; });
        f = Math.min(f, B.__csCap.drain);
        if (f > 0 && src.hp && src.hp < src.maxhp) B.heal(Math.max(1, Math.floor(move.totalDamage * f)), src, tgt, 'drain');
        if (tgt && tgt.hp && tgt.side !== src.side) {
          each(c.onhit, src, function (e) {
            if (e.mt && move.type !== e.mt) return;
            if (!B.randomChance(Math.round(e.c * 100), 100)) return;
            var done = false;
            if (e.st) done = !!tgt.trySetStatus(e.st, src) || done;
            if (e.v) done = !!tgt.addVolatile(e.v, src) || done;
            if (e.b) done = !!B.boost(e.b, tgt, src) || done;
            if (done) ev({ k: 'onhit', nm: e.nm, atk: who(src), mon: who(tgt), st: e.st || '', v: e.v || '', b: e.b || null });
          });
        }
      }
      var dc = S(tgt);
      if (dc && tgt && tgt.hp && src.hp && tgt.side !== src.side) {
        var ce = null;
        each(dc.charm, tgt, function (e) { if (!ce || e.c > ce.c) ce = e; });
        if (ce && B.randomChance(Math.round(ce.c * 100), 100)) {
          var b = {};
          b[move.category === 'Physical' ? 'atk' : 'spa'] = -1;
          if (B.boost(b, src, tgt)) ev({ k: 'charm', nm: ce.nm, atk: who(src), mon: who(tgt), b: b });
        }
      }
    });

    B.onEvent('Residual', B.format, function () {
      B.sides.forEach(function (sd) {
        var c = B.__cs[sd.id];
        if (!c || !c.heal) return;
        sd.active.forEach(function (p) {
          if (!p || p.fainted || !p.hp || p.hp >= p.maxhp) return;
          var f = 0;
          each(c.heal, p, function (e) { f += e.f; });
          f = Math.min(f, B.__csCap.heal);
          if (f > 0) B.heal(Math.max(1, Math.floor(p.maxhp * f)), p, p, B.dex.conditions.get('aquaring'));
        });
      });
    });

    B.onEvent('Faint', B.format, function (tgt, src) {
      B.__csLast(tgt.side);
      var c = S(tgt);
      if (!c || !src || !src.hp || src.side === tgt.side) return;
      var ge = null;
      each(c.grudge, tgt, function (e) { if (!ge || e.f > ge.f) ge = e; });
      if (ge) {
        var dmg = Math.max(1, Math.floor(src.maxhp * ge.f));
        ev({ k: 'grudge', nm: ge.nm, mon: who(tgt), atk: who(src), dmg: dmg });
        B.damage(dmg, src, tgt, B.dex.conditions.get('grudge'));
      }
      var pe = has(c.ppgrudge, tgt);
      if (pe && src.lastMove) {
        var md = src.getMoveData(src.lastMove.id);
        if (md && md.pp) {
          md.pp = 0;
          ev({ k: 'ppgrudge', nm: pe.nm, mon: who(tgt), atk: who(src), move: src.lastMove.name });
          B.add('-activate', src, 'move: Grudge', src.lastMove.name);
        }
      }
    });
  }

  var all = __SIDES__;
  var ids = [];
  for (var k in all) { B.__cs[k] = all[k]; ids.push(k); }
  if (ids.length > 1 && B.random(2) === 1) ids.reverse();
  var sideOf = function (id) { return B.sides.find(function (x) { return x.id === id; }); };
  var FIELD = { weather: 1, terrain: 1, room: 1 };
  var reqs = [];
  ids.forEach(function (id) {
    var sd = sideOf(id);
    if (!sd) return;
    var list = all[id].start || [];
    B.__csStart(sd, list.filter(function (s) { return !FIELD[s.k]; }));
    list.forEach(function (s) { if (FIELD[s.k]) reqs.push({ sd: sd, s: s, r: B.random(1000) }); });
  });
  reqs.sort(function (a, b) { return ((b.s.p || 0) - (a.s.p || 0)) || (a.r - b.r); });
  reqs.forEach(function (q) {
    if (!B.__csTryField(q.sd, q.s)) {
      B.__csQueue.push({ sd: q.sd, s: q.s, until: B.turn + 6 });
      B.__csEv({ k: 'start', nm: q.s.nm, side: q.sd.id, id: q.s.id, d: q.s.d, r: 'queued' });
    }
  });
  ids.forEach(function (id) {
    var sd = sideOf(id);
    if (sd) sd.active.forEach(function (p) { if (p) B.__csEntry(p); });
  });
})()
