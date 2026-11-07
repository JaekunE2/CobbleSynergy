package dev.cobblesynergy

import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.api.battles.model.actor.AIBattleActor
import com.cobblemon.mod.common.api.npc.NPCClasses
import com.cobblemon.mod.common.battles.BattleFormat
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.battles.BattleSide
import com.cobblemon.mod.common.battles.SuccessfulBattleStart
import com.cobblemon.mod.common.battles.ai.RandomBattleAI
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import com.cobblemon.mod.common.entity.npc.NPCBattleActor
import com.cobblemon.mod.common.entity.npc.NPCEntity
import com.cobblemon.mod.common.net.messages.client.battle.BattleEndPacket
import com.cobblemon.mod.common.net.messages.client.battle.BattleInitializePacket
import com.cobblemon.mod.common.net.messages.client.battle.BattleMessagePacket
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ChunkPos
import java.util.UUID

/**
 * 테스트 빌드 전용: 게임 안에서 도는 자동 테스트.
 *  - 월드가 열리고 10초 뒤, 플레이어 근처에 경기장을 격자로 깔고 경기장마다 Cobblemon NPC 트레이너 둘을 세워
 *    타입 × 단계마다 실제 전투를 **한 번에 모두** 시작한다. (포켓몬이 실제 엔티티로 나와야 Cobblemon 이 정상 진행된다)
 *  - 각 전투는 최소 6턴 진행하며 시작 효과, 지속 턴, 단계 판정, 실제 피해 증감을 검사한다.
 *  - 플레이어는 15초마다 다음 경기장 옆으로 텔레포트되고 관전 화면이 열린다. 끝나면 원래 자리로 돌아간다.
 */
object TestSuite {
    private data class Case(val type: String, val count: Int, val control: Boolean = false)
    private class Running(
        val case: Case,
        val battle: PokemonBattle,
        val startTick: Int,
        val traits: List<TraitState>,
        val targetTurn: Int,
        val npcs: List<NPCEntity>,
        val watchPos: Vec3,
        val level: ServerLevel,
    ) {
        var lastTurn = -1
        var lastTurnTick = 0
        var kicks = 0
        var forced = 0
        val label get() = labelOf(case)
    }

    private const val MIN_TURNS = 6
    private const val STALL_TICKS = 200
    private const val TIMEOUT_TICKS = 20 * 60 * 5
    private const val SPECTATE_PERIOD = 20 * 15
    private const val SPACING = 14
    private const val COLUMNS = 7

    private val queue = ArrayDeque<Case>()
    private val running = ArrayList<Running>()
    private var pass = 0
    private var fail = 0
    private var info = 0
    private var total = 0
    private var finished = 0
    private var launched = 0
    private var spectateIndex = 0
    private var lastSpectateTick = -SPECTATE_PERIOD
    private var base: Vec3? = null
    private var baseLevel: ServerLevel? = null
    private val home = HashMap<UUID, Pair<ServerLevel, Vec3>>()
    private val watching = HashMap<UUID, PokemonBattle>()
    private val forced = HashSet<Long>()
    private val pendingPeek = ArrayList<Triple<Int, String, PokemonBattle>>()
    var active = false
        private set
    /** 스위트가 끝난 뒤 이어서 할 일 (던전 테스트 시작) */
    var onFinished: (() -> Unit)? = null

    private fun labelOf(c: Case) =
        if (c.control) "비교용(주입 안 함)#${c.count}" else "${SynergyEngine.TYPE_KO[c.type]}×${c.count}"

    private fun ok(name: String, good: Boolean, detail: String = "") {
        if (good) pass++ else fail++
        TestLog.result(name, good, detail)
    }

    fun begin(server: MinecraftServer) {
        if (!TestMode.enabled || active) return
        val player = server.playerList.players.firstOrNull()
        if (player == null) {
            TestLog.log("suite", "접속한 플레이어가 없어 자동 테스트를 시작하지 않습니다")
            return
        }
        queue.clear(); running.clear(); pass = 0; fail = 0; info = 0; finished = 0; launched = 0; spectateIndex = 0
        queue += Case("normal", 1, control = true)
        queue += Case("normal", 2, control = true)
        for (def in SynergyConfig.current.defs) {
            for (tier in def.tiers) queue += Case(def.type, tier.count)
            if (def.tiers.any { it.isExact }) queue += Case(def.type, def.tiers.first { it.isExact }.count + 1)
        }
        total = queue.size
        base = player.position()
        baseLevel = player.serverLevel()
        home.clear()
        server.playerList.players.forEach { home[it.uuid] = it.serverLevel() to it.position() }
        SynergyFeed.onCheck = { _, good, _ -> if (good) pass++ else fail++ }
        SynergyFeed.labelOf = { b -> running.firstOrNull { it.battle === b }?.label }
        active = true
        TestLog.log("suite", "==== 게임 내 자동 테스트 시작: ${total}개 전투 동시 진행 (NPC 트레이너 대결, 전투마다 최소 ${MIN_TURNS}턴) 기준 위치=$base ====")
        player.sendSystemMessage(Component.literal("[시너지 테스트] 근처에 경기장 ${total}개를 만들고 동시에 전투를 시작합니다. 15초마다 다음 경기장으로 이동합니다.")
            .withStyle(ChatFormatting.AQUA))
    }

    fun tick(now: Int, server: MinecraftServer) {
        if (!active) return
        pendingPeek.filter { it.first <= now }.also { pendingPeek.removeAll(it.toSet()) }.forEach { (_, label, b) ->
            TestLog.log("stall", "$label 직접 선택 후 반응: " + lines(b).takeLast(8).joinToString(" ⏎ ") { it.take(160) })
        }
        repeat(2) { queue.removeFirstOrNull()?.let { launch(it, now) } }

        for (r in running) watchStall(r, now)
        val done = running.filter { r ->
            r.battle.ended || r.battle.turn > r.targetTurn || now - r.startTick > TIMEOUT_TICKS ||
                r.kicks >= 12
        }
        done.forEach { finish(it, now) }
        running.removeAll(done.toSet())

        spectate(now, server, force = done.any { d -> watching.values.any { it === d.battle } })

        if (running.isEmpty() && queue.isEmpty()) {
            active = false
            stopSpectating(server)
            baseLevel?.let { lv -> forced.forEach { runCatching { lv.setChunkForced(ChunkPos.getX(it), ChunkPos.getZ(it), false) } } }
            forced.clear()
            SynergyFeed.onCheck = null
            TestLog.log("suite", "==== 자동 테스트 끝: PASS $pass / FAIL $fail / 판정 불가 $info ====")
            server.playerList.players.forEach {
                it.sendSystemMessage(Component.literal("[시너지 테스트] 끝: PASS $pass / FAIL $fail / 판정 불가 $info — 5초 뒤 던전 테스트를 이어서 시작합니다.")
                    .withStyle(if (fail == 0) ChatFormatting.GREEN else ChatFormatting.RED))
            }
            onFinished?.invoke()
        }
    }

    // ------------------------------------------------------------------ 경기장과 NPC

    private fun arena(index: Int): Vec3 {
        val b = base!!
        val col = index % COLUMNS - COLUMNS / 2
        val row = index / COLUMNS
        return Vec3(b.x + col * SPACING, b.y, b.z + 12 + row * SPACING)
    }

    /**
     * 플레이어가 다른 경기장으로 텔레포트하면 먼 경기장의 청크가 멈춰 포켓몬 내보내기 연출이 끝나지 않고 전투가 멈춘다.
     * 테스트 동안만 경기장 청크를 강제로 계속 처리하고, 끝나면 풀어 준다.
     */
    private fun forceArea(level: ServerLevel, center: Vec3) {
        for (dx in listOf(-10, 10)) for (dz in listOf(-10, 10)) {
            val cp = ChunkPos(BlockPos.containing(center.x + dx, center.y, center.z + dz))
            if (forced.add(cp.toLong())) runCatching { level.setChunkForced(cp.x, cp.z, true) }
        }
    }

    private fun groundY(level: ServerLevel, x: Double, z: Double): Double =
        level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x.toInt(), z.toInt()).toDouble()

    private fun spawnNpc(level: ServerLevel, x: Double, z: Double, yaw: Float, name: String): NPCEntity {
        val npc = NPCEntity(level)
        NPCClasses.getByName("standard")?.let { npc.npc = it }
        npc.moveTo(x, groundY(level, x, z), z, yaw, 0f)
        npc.customName = Component.literal(name)
        npc.isCustomNameVisible = true
        level.addFreshEntity(npc)
        return npc
    }

    private fun launch(case: Case, now: Int) {
        val level = baseLevel ?: return
        val label = labelOf(case)
        val party = if (case.control) TestBattle.buildParty("random", 6) else TestBattle.buildParty(case.type, case.count)
        val foes = TestBattle.buildParty("random", 6)
        val traits = if (case.control) emptyList() else SynergyEngine.traits(SynergyEngine.countTypes(party))
        val center = arena(launched++)
        val npcs = ArrayList<NPCEntity>()
        forceArea(level, center)
        val result = runCatching {
            val n1 = spawnNpc(level, center.x - 4, center.z, -90f, "테스트 $label").also { npcs += it }
            val n2 = spawnNpc(level, center.x + 4, center.z, 90f, "상대 ($label)").also { npcs += it }
            val a1 = NPCBattleActor(n1, party.map { BattlePokemon.safeCopyOf(it) }, 3, RandomBattleAI())
            val a2 = NPCBattleActor(n2, foes.map { BattlePokemon.safeCopyOf(it) }, 3, RandomBattleAI())
            TestDriver.actors += a1.uuid; TestDriver.actors += a2.uuid
            if (case.control) { TestBattle.noInject += a1.uuid; TestBattle.noInject += a2.uuid } else TestBattle.actorParties[a1.uuid] = party
            BattleRegistry.startBattle(BattleFormat.GEN_9_SINGLES, BattleSide(a1), BattleSide(a2))
        }
        val battle = (result.getOrNull() as? SuccessfulBattleStart)?.battle
        if (battle == null) {
            ok("$label 전투 시작", false, result.exceptionOrNull()?.let { "$it ${it.stackTrace.take(3).joinToString(" < ")}" } ?: result.getOrNull().toString())
            npcs.forEach { runCatching { it.discard() } }
            finished++
            return
        }
        val needed = traits.flatMap { it.tier?.effectList.orEmpty() }.maxOfOrNull { it.turns } ?: 0
        val watch = Vec3(center.x, groundY(level, center.x, center.z + 7) + 1, center.z + 7)
        running += Running(case, battle, now, traits, maxOf(MIN_TURNS, needed + 1), npcs, watch, level).also { it.lastTurnTick = now }
        TestLog.log("suite", "$label 시작 battle=${battle.battleId} 위치=(${center.x.toInt()}, ${center.z.toInt()}) 파티=" +
            party.joinToString { "${it.species.name}(${it.types.joinToString("/") { t -> t.name }})" })
    }

    // ------------------------------------------------------------------ 멈춤 감지

    private fun watchStall(r: Running, now: Int) {
        val b = r.battle
        if (b.turn != r.lastTurn) {
            r.lastTurn = b.turn
            r.lastTurnTick = now
            return
        }
        if (b.ended || now - r.lastTurnTick < STALL_TICKS) return
        r.lastTurnTick = now
        r.kicks++
        val state = b.actors.joinToString(" | ") { a ->
            "${a.showdownId}: request=${a.request != null} mustChoose=${a.mustChoose} responses=${a.responses.size} " +
                "active=${a.activePokemon.joinToString { ap -> "${ap.battlePokemon?.originalPokemon?.species?.name}:${ap.battlePokemon?.health}" }}"
        }
        TestLog.log("stall", "${r.label} 턴 ${b.turn}에서 멈춤 (${r.kicks}회째) dispatches=${b.dispatches.size} $state")
        if (r.kicks == 1) TestLog.dump("${r.label} battle=${b.battleId} 턴 ${b.turn}", lines(b))
        // 응답이 비어 있는 요청이 있으면 다시 고르게 한다 (직접 명령을 써 넣으면 Cobblemon 응답과 겹쳐 오류가 난다)
        for (a in b.actors) if (a is AIBattleActor && a.request != null && a.responses.isEmpty()) runCatching { TestDriver.retry(a) }
            .onFailure { TestLog.log("stall", "${r.label} ${a.showdownId} 재선택 실패: $it") }
        runCatching { b.checkForInputDispatch() }
    }

    // ------------------------------------------------------------------ 관전 (텔레포트 + 관전 화면)

    private fun spectate(now: Int, server: MinecraftServer, force: Boolean) {
        if (running.isEmpty()) return
        if (!force && now - lastSpectateTick < SPECTATE_PERIOD) return
        lastSpectateTick = now
        for (player in server.playerList.players) {
            if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) continue
            val target = running[spectateIndex % running.size]
            val previous = watching[player.uuid]
            if (previous === target.battle) continue
            runCatching {
                previous?.spectators?.remove(player.uuid)
                if (previous != null) BattleEndPacket().sendToPlayer(player)
                val p = target.watchPos
                player.teleportTo(target.level, p.x, p.y, p.z, 180f, 15f)
                target.battle.spectators.add(player.uuid)
                BattleInitializePacket(target.battle, target.battle.side1).sendToPlayer(player)
                BattleMessagePacket(target.battle.chatLog).sendToPlayer(player)
                watching[player.uuid] = target.battle
                val own = target.traits.filter { it.active }.joinToString { "${it.def.name}(${SynergyEngine.tierLabel(it.tier!!)})" }.ifEmpty { "시너지 없음" }
                player.sendSystemMessage(Component.literal(
                    "[시너지 테스트] 관전: ${target.label} — $own · 턴 ${target.battle.turn}/${target.targetTurn} · 완료 $finished/$total · 15초 후 다음 경기장")
                    .withStyle(ChatFormatting.AQUA))
            }.onFailure { TestLog.log("spectate", "관전 이동 실패: $it") }
        }
        spectateIndex++
    }

    private fun stopSpectating(server: MinecraftServer) {
        for ((uuid, battle) in watching) {
            battle.spectators.remove(uuid)
            server.playerList.getPlayer(uuid)?.let { runCatching { BattleEndPacket().sendToPlayer(it) } }
        }
        watching.clear()
        for ((uuid, place) in home) {
            val (level, pos) = place
            server.playerList.getPlayer(uuid)?.let { runCatching { it.teleportTo(level, pos.x, pos.y, pos.z, it.yRot, it.xRot) } }
        }
        home.clear()
    }

    // ------------------------------------------------------------------ 검사

    private fun lines(battle: PokemonBattle): List<String> =
        runCatching { battle.showdownMessages.flatMap { it.toString().split('\n') } }.getOrDefault(emptyList())

    private fun turnAt(lines: List<String>, index: Int): Int {
        var turn = 0
        for (i in 0..index) Regex("""\|turn\|(\d+)""").find(lines[i])?.let { turn = it.groupValues[1].toInt() }
        return turn
    }

    private fun finish(r: Running, now: Int) {
        finished++
        val label = r.label
        SynergyFeed.tick()
        val all = lines(r.battle)
        val reached = r.battle.turn
        val kinds = SynergyFeed.kinds(r.battle)
        TestLog.log("suite", "$label 종료: 턴 $reached (목표 ${r.targetTurn}), 멈춤 ${r.kicks}회, " +
            "${(now - r.startTick) / 20}초, 시너지 이벤트=$kinds · 완료 $finished/$total")
        val winner = all.any { it.startsWith("|win|") || it == "|tie" || it.startsWith("|tie|") }
        ok("$label ${MIN_TURNS}턴 진행", reached >= MIN_TURNS || winner, "턴 $reached${if (winner) " (한쪽이 모두 쓰러져 승부 결정)" else if (r.battle.ended) " (오류로 중단)" else ""}")
        ok("$label Cobblemon 전투 오류 없음", !r.battle.ended || winner || reached >= MIN_TURNS, if (r.battle.ended && !winner) "승부 없이 전투가 끝남" else "")
        ok("$label 스크립트 오류 없음", all.none { it.contains("<<< error") }, all.firstOrNull { it.contains("<<< error") }?.take(200) ?: "")
        ok("$label eval 로그 숨김", all.none { it.contains(">>> ") }, all.firstOrNull { it.contains(">>> ") }?.take(200) ?: "")
        if (r.case.control) {
            ok("$label 시너지 이벤트 없음", kinds.isEmpty(), "$kinds")
        } else {
            val def = SynergyConfig.current.defs.first { it.type == r.case.type }
            val own = r.traits.firstOrNull { it.def.type == r.case.type }
            val expectedTier = def.tiers.indexOfLast { t -> if (t.isExact) r.case.count == t.count else r.case.count >= t.count }
            ok("$label 단계 판정", (own?.activeTier ?: -1) == expectedTier, "켜진 단계=${own?.activeTier ?: -1} 기대=$expectedTier")
        }

        for (trait in r.traits.filter { it.active }) {
            val tier = trait.tier ?: continue
            for (e in tier.effectList) {
                val name = SHOWDOWN[e.id] ?: e.id.orEmpty()
                val what = "$label ${trait.def.name}: ${SynergyEngine.describe(trait.def, e)}"
                val eventKind = mapOf("power" to "power", "taken" to "dmg", "endure" to "endure", "grudge" to "grudge",
                    "ppgrudge" to "ppgrudge", "onhit" to "onhit", "charm" to "charm", "entry" to "entry")[e.kind]
                if (eventKind != null) {
                    val n = kinds[eventKind] ?: 0
                    if (n > 0) ok("$what — 발동", true, "${n}회")
                    else { info++; TestLog.log("check", "INFO $what — 이번 전투에서 조건이 안 맞아 미발생") }
                    continue
                }
                val (startNeedles, endNeedles) = when (e.kind) {
                    "sidecond" -> listOf("-sidestart", if (e.onFoe) "p2" else "p1", name) to listOf("-sideend", if (e.onFoe) "p2" else "p1", name)
                    "room", "terrain" -> listOf("-fieldstart", name) to listOf("-fieldend", name)
                    "weather" -> listOf("-weather|$name") to listOf("-weather|none")
                    "foevolatile" -> listOf("-start|p2", name) to listOf("-end|p2", name)
                    else -> continue
                }
                val si = all.indexOfFirst { l -> startNeedles.all { l.contains(it) } && !l.contains("[upkeep]") }
                ok("$what — 발동", si >= 0, if (si >= 0) "turn ${turnAt(all, si)}" else "메시지 없음")
                if (si < 0 || e.turns <= 0) continue
                val ei = (si + 1 until all.size).firstOrNull { i -> endNeedles.all { all[i].contains(it) } } ?: -1
                if (ei < 0) {
                    info++
                    TestLog.log("check", "INFO $what — ${e.turns}턴 유지 판정 불가 (턴 $reached 에서 종료, 교체·덮어쓰기 또는 전투 종료)")
                } else {
                    // 효과가 걸린 턴부터 끝난 턴까지 (턴 1 시작 전에 걸리면 턴 1부터 적용된 것으로 센다)
                    val lasted = turnAt(all, ei) - maxOf(1, turnAt(all, si)) + 1
                    ok("$what — ${e.turns}턴 유지", lasted == e.turns, "실제 ${lasted}턴")
                }
            }
        }
        if (!r.battle.ended) runCatching { r.battle.stop() }
        r.npcs.forEach { runCatching { it.discard() } }
        val ids = r.battle.actors.map { it.uuid }.toSet()
        ids.forEach { TestDriver.forget(it) }
        TestBattle.actorParties.keys.removeAll(ids)
        TestBattle.noInject.removeAll(ids)
    }

    private val SHOWDOWN = mapOf(
        "reflect" to "Reflect", "lightscreen" to "Light Screen", "auroraveil" to "Aurora Veil", "safeguard" to "Safeguard",
        "tailwind" to "Tailwind", "mist" to "Mist", "stealthrock" to "Stealth Rock", "spikes" to "Spikes",
        "toxicspikes" to "Toxic Spikes", "stickyweb" to "Sticky Web", "trickroom" to "Trick Room",
        "electricterrain" to "Electric Terrain", "grassyterrain" to "Grassy Terrain", "mistyterrain" to "Misty Terrain",
        "psychicterrain" to "Psychic Terrain", "sunnyday" to "SunnyDay", "raindance" to "RainDance", "sandstorm" to "Sandstorm",
        "snow" to "Snow", "taunt" to "Taunt", "leechseed" to "Leech Seed", "aquaring" to "Aqua Ring",
    )
}
