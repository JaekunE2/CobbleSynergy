package dev.cobblesynergy

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.battles.ShowdownActionRequest
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.Pokemon
import com.google.gson.JsonObject
import com.mojang.authlib.GameProfile
import dev.cobbleleague.DraftPreparation
import dev.cobbleleague.DraftState
import dev.cobbleleague.DungeonShop
import dev.cobbleleague.LeagueMod
import dev.cobblesynergy.mixin.ConnectionAccessor
import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.ChatFormatting
import net.minecraft.network.Connection
import net.minecraft.network.PacketListener
import net.minecraft.network.PacketSendListener
import net.minecraft.network.ProtocolInfo
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.world.phys.Vec3
import java.lang.reflect.Method
import java.util.UUID

/**
 * 테스트 빌드 전용: CobbleLeague 던전을 같이 플레이하는 가상 플레이어.
 *
 *  - 실제 서버 플레이어로 접속시킨다(접속 목록에 들어가야 CobbleLeague 가 참가자로 인식한다).
 *    네트워크 연결은 없는 빈 연결이고, 보내는 패킷은 모두 버린다.
 *  - CobbleLeague 클라이언트가 보내는 것과 같은 요청(LeagueService.handle)으로 로비 입장·대기 완료·준비 완료·보상 확인을 한다.
 *  - 영입은 후보에 직접 닿아야 하므로, CobbleLeague 의 시간 초과 자동 영입과 같은 경로(pick automatic=true)를 쓴다.
 *    후보 중 현재 파티와 타입이 겹쳐 시너지를 키우는 포켓몬을 고른다.
 *  - 전투에서는 요청이 올 때마다 전투 처리가 비었을 때 한 번 행동을 고른다(TestDriver 와 같은 규칙).
 */
object DungeonBot {
    const val NAME = "SynergyBot"
    private fun uuidOf(name: String): UUID = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray())
    const val MAX_BOTS = 7 // 로비 정원 8명 - 본인

    private val bots = LinkedHashMap<UUID, ServerPlayer>()
    fun isBot(id: UUID) = id in bots

    /** 자동 조작 상태 (가상 플레이어는 항상, 실제 플레이어는 자동 조작을 켠 경우) */
    private class Pilot {
        var lastPhase = ""
        var lastAction = 0
        var offeredVersion = ""
        var offeredAt = 0
        var rerolledFor = ""
        var boostedFor = ""
        var boughtFor = ""
        var answered: ShowdownActionRequest? = null
        var answeredAt = 0
        var retries = 0
        var lastBattle: UUID? = null
        var lastTurn = -1
        var lastTurnAt = 0
        var stalls = 0
        var scheduled: ShowdownActionRequest? = null
        var hadRun = false
    }
    private val pilots = HashMap<UUID, Pilot>()
    val autoPlayers = HashSet<UUID>()

    fun setAuto(p: ServerPlayer, on: Boolean) {
        if (on) autoPlayers += p.uuid else { autoPlayers -= p.uuid; pilots.remove(p.uuid) }
        TestLog.log("bot", "${p.scoreboardName} 자동 조작 ${if (on) "켬" else "끔"}")
    }

    val active get() = bots.isNotEmpty()

    // ------------------------------------------------------------------ 접속·해제

    private class BotConnection : Connection(PacketFlow.SERVERBOUND) {
        init { (this as ConnectionAccessor).`cobblesynergy$setChannel`(EmbeddedChannel()) }
        override fun send(packet: Packet<*>) {}
        override fun send(packet: Packet<*>, listener: PacketSendListener?) {}
        override fun send(packet: Packet<*>, listener: PacketSendListener?, flush: Boolean) {}
        override fun <T : PacketListener> setupInboundProtocol(info: ProtocolInfo<T>, listener: T) {}
        override fun setupOutboundProtocol(info: ProtocolInfo<*>) {}
        override fun setReadOnly() {}
        override fun handleDisconnection() {}
    }

    /** 가상 플레이어를 count 명까지 접속시킨다 (SynergyBot1, SynergyBot2 ...) */
    fun spawn(server: MinecraftServer, near: ServerPlayer?, count: Int = 1): String {
        if (!TestMode.enabled) return "테스트 빌드에서만 쓸 수 있습니다."
        val want = count.coerceIn(1, MAX_BOTS)
        val level = near?.serverLevel() ?: server.overworld()
        var added = 0
        for (i in 1..MAX_BOTS) {
            if (bots.size >= want) break
            val name = "$NAME$i"
            val id = uuidOf(name)
            if (id in bots || server.playerList.getPlayer(id) != null) continue
            val profile = GameProfile(id, name)
            val player = ServerPlayer(server, level, profile, ClientInformation.createDefault())
            val cookie = CommonListenerCookie(profile, 0, player.clientInformation(), false)
            server.playerList.placeNewPlayer(BotConnection(), player, cookie)
            near?.let { player.teleportTo(it.serverLevel(), it.x + 1.5 * i, it.y, it.z, it.yRot, 0f) }
            runCatching { Cobblemon.playerDataManager.getGenericData(player).apply { starterSelected = true; starterPrompted = true } }
                .onFailure { TestLog.log("bot", "스타터 처리 실패: $it") }
            bots[id] = player
            pilots.remove(id)
            request(player, "hello") { addProperty("protocol", LeagueCompat.protocol) }
            TestLog.log("bot", "가상 플레이어 접속 $name uuid=$id")
            added++
        }
        return "가상 플레이어 ${bots.size}명 접속 (이번에 $added 명). 각자 로비에 들어가 대기 완료합니다."
    }

    fun remove(server: MinecraftServer): String {
        if (bots.isEmpty()) return "접속한 가상 플레이어가 없습니다."
        val n = bots.size
        bots.values.toList().forEach { removeOne(server, it) }
        return "가상 플레이어 ${n}명을 내보냈습니다."
    }

    private fun removeOne(server: MinecraftServer, p: ServerPlayer) {
        runCatching { LeagueMod.service?.disconnect(p.uuid) }.onFailure { TestLog.log("bot", "던전 정리 실패: $it") }
        runCatching { BattleRegistry.getBattleByParticipatingPlayer(p)?.stop() }
        runCatching { server.playerList.remove(p) }.onFailure { TestLog.log("bot", "접속 해제 실패: $it") }
        bots.remove(p.uuid); pilots.remove(p.uuid)
        TestLog.log("bot", "가상 플레이어 접속 해제 ${p.scoreboardName}")
    }

    fun stopped() { bots.clear(); pilots.clear(); autoPlayers.clear() }

    // ------------------------------------------------------------------ 매 틱

    private var lastTick = 0

    fun tick(server: MinecraftServer, now: Int) {
        lastTick = now
        for (p in bots.values.toList()) {
            if (p.isRemoved || server.playerList.getPlayer(p.uuid) !== p) { bots.remove(p.uuid); TestLog.log("bot", "${p.scoreboardName} 이(가) 서버에서 빠졌습니다"); continue }
            runCatching { p.doTick() }
            if (now % 10 == 0) runCatching { p.serverLevel().chunkSource.move(p) }
            drive(p, now)
        }
        for (id in autoPlayers.toList()) if (id !in bots) server.playerList.getPlayer(id)?.let { drive(it, now) }
        if (bots.isNotEmpty() && now % 10 == 0) runCatching { userDuelFirst() }
    }

    /**
     * CobbleLeague 는 리그장이 하나라 PvP 를 한 판씩 차례로 한다. 테스트 중 봇끼리 싸우는 동안 본인이 기다리지 않게
     * 아직 시작하지 않은 대전 중 실제 플레이어가 들어간 대전을 앞으로 옮긴다. (순서만 바꾸고 대진은 그대로)
     */
    private fun userDuelFirst() {
        val service = LeagueMod.service ?: return
        val game = field(service, "lobbyGame") ?: return
        val duels = field(game, "duels") as? ArrayDeque<*> ?: return
        if (duels.size < 2) return
        fun human(d: Any?): Boolean = d is dev.cobbleleague.LeagueDuel && (d.first !in bots || d.second !in bots)
        if (human(duels.first())) return
        val sorted = duels.sortedBy { if (human(it)) 0 else 1 }
        @Suppress("UNCHECKED_CAST")
        val q = duels as ArrayDeque<Any?>
        q.clear(); q.addAll(sorted)
        TestLog.log("bot", "본인 대전을 먼저 하도록 대전 순서 변경")
    }

    private fun drive(p: ServerPlayer, now: Int) {
        val pilot = pilots.getOrPut(p.uuid) { Pilot() }
        BattleRegistry.getBattleByParticipatingPlayer(p)?.let { battle(p, pilot, it, now); return }
        if (now - pilot.lastAction < 20) return
        runCatching { think(p, pilot, now) }.onFailure { TestLog.log("bot", "${p.scoreboardName} 판단 오류: $it ${it.stackTrace.take(3).joinToString(" < ")}") }
    }

    private fun think(p: ServerPlayer, pilot: Pilot, now: Int) {
        val service = LeagueMod.service ?: return
        val run = runOf(service, p.uuid)
        if (run == null) {
            if (pilot.hadRun) { finished(p, pilot); return } // 던전이 끝났다
            // 로비에 없으면 들어가서 대기 완료 (가상 플레이어와 자동 조작을 켠 플레이어)
            val queue = call(field(service, "lobbyQueue") ?: return, "members") as? List<*> ?: return
            if (p.uuid !in queue) { act(p, pilot, now, "lobby_join") ; return }
            val ready = call(field(service, "lobbyQueue")!!, "isReady", p.uuid) as? Boolean ?: false
            // 실제 플레이어가 로비에 있고 모두 대기 완료한 뒤에만 대기 완료 (봇끼리 먼저 시작하지 않게)
            val humans = queue.filterIsInstance<UUID>().filter { it !in bots && it !in autoPlayers }
            val humansReady = humans.isNotEmpty() && humans.all { call(field(service, "lobbyQueue")!!, "isReady", it) as? Boolean ?: false }
            if (!ready && humansReady) act(p, pilot, now, "lobby_ready") { addProperty("ready", true) }
            return
        }
        pilot.hadRun = true
        val game = field(service, "lobbyGame")
        val phase = game?.let { call(it, "getPhase")?.toString() } ?: "NONE"
        if (phase != pilot.lastPhase) {
            pilot.lastPhase = phase
            TestLog.log("bot", "${p.scoreboardName} 단계: $phase · 파티=${partyText(run)}")
            say(p.server, "[${who(p)}] 단계: ${PHASE_KO[phase] ?: phase} · 파티: ${partyText(run)}")
        }
        val state = call(run, "getState") as DraftState
        val prep = call(run, "getPreparation") as DraftPreparation
        when (phase) {
            "SELECTION" -> {
                val room = call(run, "getRoom") as Int
                @Suppress("UNCHECKED_CAST")
                val displays = call(run, "getDisplays") as List<Pair<PokemonEntity, Vec3>>
                @Suppress("UNCHECKED_CAST")
                val choices = call(run, "getChoices") as List<String>
                if (room < 0 || state.ready || displays.isEmpty() || choices.isEmpty()) return
                if (pilot.offeredVersion != state.version) { pilot.offeredVersion = state.version; pilot.offeredAt = now; return }
                if (now - pilot.offeredAt < 40) return // 후보가 다 나올 때까지 2초 기다린다
                // 돈 사용 1: 라운드마다 한 번, 돈이 3 이상이면 무작위 분류의 뽑기 확률 강화
                val roundKey = runCatching { call(call(game!!, "getModel")!!, "getRound").toString() }.getOrDefault("?")
                if (pilot.boostedFor != roundKey && prep.money >= 3) {
                    pilot.boostedFor = roundKey
                    val kind = LeagueBridge.allCategories().random()
                    act(p, pilot, now, "odds_boost") { addProperty("category", kind.id); addProperty("revision", prep.revision) }
                    return
                }
                choose(service, p, pilot, run, state, prep, displays, choices, now)
            }
            "PREPARATION" -> if (!prep.confirmed) {
                // 돈 사용 2: 도구가 없는 포켓몬에게 살 수 있는 가장 비싼 장비를 하나씩 산다. 살 것이 없으면 바로 준비 완료.
                if (!shop(p, pilot, run, prep, now) && humansPrepared(service)) act(p, pilot, now, "ready") { addProperty("revision", prep.revision) }
            }
            "REWARDS" -> {
                val readySet = call(game!!, "getRewardReady") as? Set<*> ?: return
                if (p.uuid in readySet) return
                // CobbleLeague 0.7.3: 관장전 승리 보상은 하나를 먼저 골라야 확인 완료할 수 있다
                val choice = field(run, "gymReward")?.let { field(it, "choice") }
                if (choice != null && call(choice, "getSelected") == null) {
                    @Suppress("UNCHECKED_CAST")
                    val options = call(choice, "getOptions") as? List<Enum<*>> ?: emptyList()
                    val prize = options.firstOrNull { it.name == "MONEY" } ?: options.firstOrNull() ?: return
                    act(p, pilot, now, "gym_reward_choose") { addProperty("token", call(choice, "getToken").toString()); addProperty("prize", prize.name) }
                    return
                }
                act(p, pilot, now, "reward_done")
            }
        }
    }

    // ------------------------------------------------------------------ 영입

    private fun choose(service: Any, p: ServerPlayer, pilot: Pilot, run: Any, state: DraftState, prep: DraftPreparation,
                       displays: List<Pair<PokemonEntity, Vec3>>, choices: List<String>, now: Int) {
        val party = (call(run, "getParty") as PlayerPartyStore).toList()
        val counts = SynergyEngine.countTypes(party)
        fun score(list: List<Pokemon>): Int = SynergyEngine.traits(SynergyEngine.countTypes(list)).sumOf { (it.activeTier + 1) * 10 } +
            list.sumOf { m -> m.types.sumOf { t -> counts[t.name.lowercase()] ?: 0 } }
        var best = -1; var bestSlot = -1; var bestScore = Int.MIN_VALUE
        for ((i, d) in displays.withIndex()) {
            if (i >= choices.size || d.first.isRemoved) continue
            val mon = d.first.pokemon
            if (party.size < 6) {
                val s = score(party + mon)
                if (s > bestScore) { bestScore = s; best = i; bestSlot = -1 }
            } else for (slot in party.indices) {
                val s = score(party.toMutableList().also { it[slot] = mon })
                if (s > bestScore) { bestScore = s; best = i; bestSlot = slot }
            }
        }
        if (best < 0) return
        val now0 = score(party)
        val mon = displays[best].first.pokemon
        pilot.lastAction = now
        // 파티가 3마리 이상인데 후보가 시너지를 늘리지 못하면, 리롤이 남아 있을 때 한 번 리롤
        if (party.size >= 3 && bestScore <= now0 && prep.rerolls > 0 && pilot.rerolledFor != state.version) {
            pilot.rerolledFor = state.version
            act(p, pilot, now, "reroll") { addProperty("version", state.version); addProperty("revision", prep.revision) }
            return
        }
        if (party.size >= 6 && bestScore <= now0) {
            act(p, pilot, now, "pick_skip") { addProperty("version", state.version) }
            TestLog.log("bot", "${p.scoreboardName} 영입 건너뜀 (후보가 시너지를 늘리지 못함)")
            return
        }
        val json = JsonObject().apply {
            addProperty("pokemon", choices[best]); addProperty("version", state.version)
            if (bestSlot >= 0) addProperty("slot", bestSlot)
        }
        val replaced = if (bestSlot >= 0) " (${party[bestSlot].species.translatedName.string} 대신)" else ""
        runCatching { pickMethod(service).invoke(service, p, json, true) }
            .onSuccess {
                val types = mon.types.joinToString("/") { SynergyEngine.TYPE_KO[it.name.lowercase()] ?: it.name }
                TestLog.log("bot", "${p.scoreboardName} 영입: ${mon.species.translatedName.string}($types)$replaced")
                say(p.server, "[${who(p)}] 영입: ${mon.species.translatedName.string}($types)$replaced")
            }
            .onFailure { e -> TestLog.log("bot", "${p.scoreboardName} 영입 실패: ${(e.cause ?: e)}") }
    }

    // ------------------------------------------------------------------ 전투

    private fun battle(p: ServerPlayer, pilot: Pilot, b: PokemonBattle, now: Int) {
        if (pilot.lastBattle != b.battleId) {
            pilot.lastBattle = b.battleId; pilot.answered = null; pilot.scheduled = null; pilot.retries = 0
            pilot.lastTurn = -1; pilot.lastTurnAt = now; pilot.stalls = 0
            TestLog.log("bot", "${p.scoreboardName} 전투 시작 battle=${b.battleId} 상대=${b.actors.filter { p.uuid !in it.getPlayerUUIDs() }.joinToString { it.getName().string }}")
        }
        if (b.ended) return
        val actor = b.actors.firstOrNull { p.uuid in it.getPlayerUUIDs() } ?: return
        if (b.turn != pilot.lastTurn) { pilot.lastTurn = b.turn; pilot.lastTurnAt = now }
        val stuck = now - pilot.lastTurnAt >= 200
        if (stuck && (now - pilot.lastTurnAt) % 200 == 0) {
            pilot.stalls++
            val state = b.actors.joinToString(" | ") { a ->
                "${a.getName().string}: request=${a.request != null} mustChoose=${a.mustChoose} responses=${a.responses.size} " +
                    "active=${a.activePokemon.joinToString { ap -> "${ap.battlePokemon?.originalPokemon?.species?.name}:${ap.battlePokemon?.health}" }}"
            }
            TestLog.log("stall", "${p.scoreboardName} 전투 턴 ${b.turn}에서 ${(now - pilot.lastTurnAt) / 20}초 멈춤 dispatches=${b.dispatches.size} " +
                "첫 처리=${b.dispatches.firstOrNull()?.javaClass?.simpleName} $state")
            if (pilot.stalls == 1) TestLog.dump("${p.scoreboardName} battle=${b.battleId} 턴 ${b.turn}",
                runCatching { b.showdownMessages.flatMap { it.toString().split('\n') }.takeLast(60) }.getOrDefault(emptyList()))
        }
        val request = actor.request ?: return
        if (actor.responses.isNotEmpty()) return
        if (request === pilot.scheduled) return
        if (request === pilot.answered) {
            // 같은 요청이 5초 넘게 남아 있고 응답이 비었으면 Showdown 이 선택을 거절한 것: 다시 고른다
            if (now - pilot.answeredAt < 100 || pilot.retries >= 8) return
            pilot.retries++
            TestLog.log("bot", "${p.scoreboardName} 선택이 받아들여지지 않아 다시 고릅니다 (${pilot.retries}회)")
        } else pilot.retries = 0
        // CobbleLeague 관장 NPC 와 같은 방식: 전투 처리(연출, 상대에게 선택 화면 보내기)가 모두 끝난 뒤 한 번 고른다.
        // 먼저 답하면 사람 쪽 선택 요청이 전달되기 전에 Cobblemon 이 봇의 선택만 보내 버려 상대가 고를 수 없게 된다.
        pilot.scheduled = request
        b.doWhenClear {
            if (pilot.scheduled === request) pilot.scheduled = null
            if (!b.ended && actor.request === request && actor.responses.isEmpty()) {
                pilot.answered = request
                pilot.answeredAt = lastTick
                TestDriver.choose(actor, request)
            }
        }
    }

    // ------------------------------------------------------------------ 도우미

    private val PHASE_KO = mapOf("BUILDING" to "선택방 준비", "SELECTION" to "영입", "PREPARATION" to "준비", "BATTLES" to "대전",
        "RESULTS" to "결과", "REWARDS" to "보상", "NONE" to "대기")

    private fun partyText(run: Any) = runCatching {
        (call(run, "getParty") as PlayerPartyStore).toList().joinToString(", ") { m ->
            "${m.species.translatedName.string}(${m.types.joinToString("/") { SynergyEngine.TYPE_KO[it.name.lowercase()] ?: it.name }})"
        }.ifEmpty { "없음" }
    }.getOrDefault("?")

    private fun say(server: MinecraftServer, text: String) =
        server.playerList.players.filter { it.uuid !in bots }.forEach { it.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.DARK_AQUA)) }

    private fun request(p: ServerPlayer, action: String, body: JsonObject.() -> Unit = {}) {
        val json = JsonObject().apply { addProperty("action", action); body() }
        runCatching { LeagueMod.service?.handle(p, json) }
            .onSuccess { TestLog.log("bot", "${p.scoreboardName} 요청 $json") }
            .onFailure { TestLog.log("bot", "${p.scoreboardName} 요청 $action 거절: ${it.message}") }
    }

    /** 던전이 끝나면 그 가상 플레이어를 내보낸다 */
    private fun finished(p: ServerPlayer, pilot: Pilot) {
        pilot.hadRun = false
        TestLog.log("bot", "${p.scoreboardName} 던전 종료")
        if (p.uuid in bots) {
            p.server.execute {
                removeOne(p.server, p)
                if (bots.isEmpty()) say(p.server, "[시너지 테스트] 던전이 끝나 가상 플레이어를 모두 내보냈습니다. 로그: logs/cobblesynergy-test.log")
            }
        } else setAuto(p, false)
    }

    /** 가상 플레이어 count 명을 접속시켜 로비에 대기시킨다. 본인은 F4 로비에서 직접 대기 완료하고 직접 플레이한다. */
    fun startDungeonTest(server: MinecraftServer, count: Int = 3) {
        val user = server.playerList.players.firstOrNull { it.uuid !in bots }
        val message = spawn(server, user, count)
        TestLog.log("bot", "==== 던전 테스트 시작: 가상 플레이어 ${bots.size}명 (영입·상점·준비·전투 자동), 본인은 직접 ====")
        user?.sendSystemMessage(Component.literal("[시너지 테스트] $message").withStyle(ChatFormatting.AQUA))
        user?.sendSystemMessage(Component.literal("[시너지 테스트] F4 로비에서 대기 완료를 누르면 던전이 시작됩니다. 본인은 직접 플레이합니다.").withStyle(ChatFormatting.AQUA))
    }

    private val CONSUMABLE = setOf("minecraft:gold_nugget", "cobblemon:ability_capsule", "cobblemon:max_mushrooms", "cobblemon:max_soup") // 육성 소모품은 사지 않는다

    private fun who(p: ServerPlayer) = if (p.uuid in bots) p.scoreboardName else "자동 조작 ${p.scoreboardName}"

    /** CobbleLeague 0.7.3 의 배틀 소모품 표시 (0.7.1 에는 없다) */
    private fun consumable(product: Any): Boolean =
        runCatching { product.javaClass.getMethod("getConsumable").invoke(product) as Boolean }.getOrDefault(false)

    /** 장비 하나를 산다. 산 것이 있으면 true */
    private fun shop(p: ServerPlayer, pilot: Pilot, run: Any, prep: DraftPreparation, now: Int): Boolean {
        val party = (call(run, "getParty") as PlayerPartyStore).toList()
        val holder = party.firstOrNull { it.heldItem().isEmpty } ?: return false
        val product = DungeonShop.products.filter { it.id !in CONSUMABLE && !consumable(it) && it.price <= prep.money }
            .maxByOrNull { it.price * 10 + (0..9).random() } ?: return false
        val key = "${prep.revision}:${holder.uuid}"
        if (pilot.boughtFor == key) return false // 같은 상태에서 이미 시도함 (거절되면 준비 완료로 넘어간다)
        pilot.boughtFor = key
        TestLog.log("bot", "${p.scoreboardName} 구매 시도: ${product.id}(${product.price}) → ${holder.species.translatedName.string} · 돈 ${prep.money}")
        act(p, pilot, now, "buy") {
            addProperty("pokemon", holder.uuid.toString()); addProperty("product", product.id)
            addProperty("operation", "buy"); addProperty("revision", prep.revision)
        }
        return true
    }

    private fun act(p: ServerPlayer, pilot: Pilot, now: Int, action: String, body: JsonObject.() -> Unit = {}) {
        pilot.lastAction = now
        request(p, action, body)
    }

    /** 준비 단계: 던전에 있는 실제 플레이어(자동 조작 제외)가 모두 준비 완료했는지 */
    private fun humansPrepared(service: Any): Boolean {
        val runs = field(service, "runs") as? Map<*, *> ?: return true
        return runs.filter { (id, _) -> id is UUID && id !in bots && id !in autoPlayers }.values.all { run ->
            runCatching { (call(run!!, "getPreparation") as DraftPreparation).confirmed }.getOrDefault(true)
        }
    }

    /** 로비의 [AI 트레이너 추가] 버튼, /synergy bot add : 가상 플레이어 1명 추가 */
    fun addOne(server: MinecraftServer, near: ServerPlayer?): String =
        if (bots.size >= MAX_BOTS) "가상 플레이어는 최대 ${MAX_BOTS}명입니다." else spawn(server, near, bots.size + 1)

    private fun runOf(service: Any, id: UUID): Any? = (field(service, "runs") as? Map<*, *>)?.get(id)

    private val fields = HashMap<String, java.lang.reflect.Field>()
    private fun field(target: Any, name: String): Any? = runCatching {
        fields.getOrPut(target.javaClass.name + "." + name) { target.javaClass.getDeclaredField(name).apply { isAccessible = true } }.get(target)
    }.getOrNull()

    private fun call(target: Any, name: String, vararg args: Any): Any? {
        val m = target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
            ?: target.javaClass.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        m.isAccessible = true
        return m.invoke(target, *args)
    }

    private var pick: Method? = null
    private fun pickMethod(service: Any): Method = pick ?: service.javaClass.getDeclaredMethod(
        "pick", ServerPlayer::class.java, JsonObject::class.java, Boolean::class.javaPrimitiveType,
    ).apply { isAccessible = true }.also { pick = it }
}
