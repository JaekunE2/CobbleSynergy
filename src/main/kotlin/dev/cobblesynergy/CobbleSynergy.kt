package dev.cobblesynergy

import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.google.gson.JsonObject
import com.mojang.brigadier.Command
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.math.roundToInt

class CobbleSynergy : ModInitializer {
    companion object {
        val LOGGER: Logger = LoggerFactory.getLogger("CobbleSynergy")
        private const val SYNC_INTERVAL = 10       // ticks
        private const val INJECT_DELAY = 5         // ticks (Showdown 쪽 전투 객체가 만들어진 뒤 주입)
        private const val VERIFY_DELAY = 40        // ticks (주입 후 전투 메시지 확인까지)
    }

    /** 플레이어별 마지막으로 보낸 상태 (변화가 있을 때만 다시 보낸다) */
    private val lastSent = HashMap<UUID, String>()
    private val pending = ArrayList<Pair<Int, () -> Unit>>()
    private var tick = 0
    private var server: MinecraftServer? = null
    private val script: String by lazy { loadScript() }
    /** 던전 도전(run) id → 페어리 확률 강화를 마지막으로 적용한 라운드 */
    private val oddsRound = HashMap<UUID, Int>()
    /** run id → (라운드, 이번 라운드에 지급한 추가 구매 수) */
    private val extraGranted = HashMap<UUID, Pair<Int, Int>>()
    /** 시너지를 주입한 전투 id (테스트 로그용) */
    private val injected = HashSet<UUID>()

    override fun onInitialize() {
        SynergyConfig.load(FabricLoader.getInstance().configDir.resolve("cobblesynergy"))
        SynergySwitch.load(FabricLoader.getInstance().configDir.resolve("cobblesynergy"))
        PayloadTypeRegistry.playS2C().register(SynergyPayload.TYPE, SynergyPayload.CODEC)
        PayloadTypeRegistry.playC2S().register(TestBattlePayload.TYPE, TestBattlePayload.CODEC)
        PayloadTypeRegistry.playC2S().register(SynergyTogglePayload.TYPE, SynergyTogglePayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(SynergyTogglePayload.TYPE) { payload, context ->
            val player = context.player()
            player.server.execute {
                val refused = SynergySwitch.request(player, payload.enabled)
                player.sendSystemMessage(Component.literal(refused ?: "[시너지] 던전 시너지를 ${if (SynergySwitch.enabled) "켰습니다" else "껐습니다"}.")
                    .withStyle(if (refused != null) ChatFormatting.RED else ChatFormatting.LIGHT_PURPLE))
                lastSent.clear()
                player.server.playerList.players.forEach { sync(it) }
            }
        }
        ServerPlayNetworking.registerGlobalReceiver(TestBattlePayload.TYPE) { payload, context ->
            if (!TestMode.enabled) return@registerGlobalReceiver
            val player = context.player()
            player.server.execute { runCatching { TestBattle.start(player, payload.type, payload.count) }
                .onFailure { TestLog.result("테스트 전투", false, it.toString()); LOGGER.error("CobbleSynergy test battle", it) } }
        }
        ServerLifecycleEvents.SERVER_STARTED.register { started ->
            selfCheck()
            // 테스트 빌드: 월드가 열리고 10초 뒤 게임 내 자동 테스트 시작
            if (TestMode.enabled) {
                runCatching { LeagueMapBuilder.ensure(started) }.onFailure { e -> TestLog.log("map", "맵 생성 실패: $e") }
                // 월드가 열리고 10초 뒤 가상 플레이어 3명이 접속해 던전 로비에서 대기한다 (병렬 전투 테스트는 /synergy suite 로만)
                pending += (tick + 200) to { server?.let { DungeonBot.startDungeonTest(it) } }
            }
        }

        ServerTickEvents.END_SERVER_TICK.register(::onTick)
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> lastSent.remove(handler.player.uuid) }
        ServerLifecycleEvents.SERVER_STOPPING.register { s -> if (DungeonBot.active) runCatching { DungeonBot.remove(s) } }
        ServerLifecycleEvents.SERVER_STOPPED.register { DungeonBot.stopped(); lastSent.clear(); pending.clear(); oddsRound.clear(); extraGranted.clear(); server = null }

        CobblemonEvents.BATTLE_STARTED_POST.subscribe(Priority.NORMAL) { event ->
            val battle = event.battle
            pending += (tick+1) to { server?.let { s -> runCatching { Spectate.onBattleStart(s,battle) } };Unit }
            // 자동 테스트의 비교용 전투(noInject)는 주입하지 않는다. 나머지는 실제 게임과 같은 지연.
            if (battle.actors.none { it.uuid in TestBattle.noInject }) pending += (tick + INJECT_DELAY) to { inject(battle) }
        }
        CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.NORMAL) { event ->
            val battle = event.battle
            if (TestMode.enabled && battle.battleId in injected) TestBattle.summarizeEnd(battle)
            injected.remove(battle.battleId)
            Spectate.onBattleEnd(battle)
        }

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            val root = Commands.literal("synergy").executes { ctx ->
                val player = ctx.source.playerOrException
                val party = LeagueBridge.draftParty(player.uuid)
                if (party == null) {
                    player.sendSystemMessage(Component.literal("던전 도전 중일 때만 시너지가 적용됩니다.").withStyle(ChatFormatting.GRAY))
                } else {
                    sendSummary(player, SynergyEngine.traits(SynergyEngine.countTypes(party)), "현재 시너지")
                }
                Command.SINGLE_SUCCESS
            }.then(Commands.literal("watch").executes { ctx ->
                ctx.source.sendSystemMessage(Component.literal("[시너지] " + Spectate.watch(ctx.source.playerOrException)))
                Command.SINGLE_SUCCESS
            }.then(Commands.literal("stop").executes { ctx ->
                ctx.source.sendSystemMessage(Component.literal("[시너지] " + Spectate.stop(ctx.source.playerOrException)))
                Command.SINGLE_SUCCESS
            })).then(Commands.literal("on").executes { ctx -> toggleCommand(ctx.source.playerOrException, true) })
                .then(Commands.literal("off").executes { ctx -> toggleCommand(ctx.source.playerOrException, false) })
            // 테스트 빌드 전용 명령: 배포판에는 등록하지 않는다
            if (TestMode.enabled) root.then(Commands.literal("bot").executes { ctx ->
                ctx.source.sendSystemMessage(Component.literal(DungeonBot.spawn(ctx.source.server, ctx.source.player)))
                Command.SINGLE_SUCCESS
            }.then(Commands.argument("count", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, DungeonBot.MAX_BOTS)).executes { ctx ->
                val n = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "count")
                ctx.source.sendSystemMessage(Component.literal(DungeonBot.spawn(ctx.source.server, ctx.source.player, n)))
                Command.SINGLE_SUCCESS
            }).then(Commands.literal("stop").executes { ctx ->
                ctx.source.sendSystemMessage(Component.literal(DungeonBot.remove(ctx.source.server)))
                Command.SINGLE_SUCCESS
            })).then(Commands.literal("auto").executes { ctx ->
                val p = ctx.source.playerOrException
                DungeonBot.setAuto(p, p.uuid !in DungeonBot.autoPlayers)
                ctx.source.sendSystemMessage(Component.literal("자동 조작: " + if (p.uuid in DungeonBot.autoPlayers) "켬" else "끔"))
                Command.SINGLE_SUCCESS
            }.then(Commands.literal("off").executes { ctx ->
                DungeonBot.setAuto(ctx.source.playerOrException, false)
                ctx.source.sendSystemMessage(Component.literal("자동 조작: 끔"))
                Command.SINGLE_SUCCESS
            })).then(Commands.literal("dungeon").executes { ctx ->
                DungeonBot.startDungeonTest(ctx.source.server)
                Command.SINGLE_SUCCESS
            }).then(Commands.literal("suite").executes { ctx ->
                TestSuite.begin(ctx.source.server)
                Command.SINGLE_SUCCESS
            })
            dispatcher.register(root)
        }
    }

    private fun toggleCommand(player: ServerPlayer, value: Boolean): Int {
        val refused = SynergySwitch.request(player, value)
        player.sendSystemMessage(Component.literal(refused ?: "[시너지] 던전 시너지를 ${if (SynergySwitch.enabled) "켰습니다" else "껐습니다"}.")
            .withStyle(if (refused != null) ChatFormatting.RED else ChatFormatting.LIGHT_PURPLE))
        lastSent.clear()
        player.server.playerList.players.forEach { sync(it) }
        return Command.SINGLE_SUCCESS
    }

    // ------------------------------------------------------------------ 상태 동기화

    private fun onTick(server: MinecraftServer) {
        this.server = server
        tick++
        if (pending.isNotEmpty()) {
            val due = pending.filter { it.first <= tick }
            pending.removeAll(due.toSet())
            due.forEach { (_, task) -> runCatching(task).onFailure { LOGGER.error("CobbleSynergy: 전투 주입 실패", it) } }
        }
        if (tick % 4 == 0) SynergyFeed.tick()
        if (TestMode.enabled) TestSuite.tick(tick, server)
        AnimationRecovery.tick(tick)
        if (TestMode.enabled) DungeonBot.tick(server, tick)
        if (tick % SYNC_INTERVAL != 0) return
        applyOdds(server)
        for (player in server.playerList.players) sync(player)
    }

    // ------------------------------------------------------------------ 페어리: 라운드마다 무료 확률 강화

    private fun applyOdds(server: MinecraftServer) {
        if (!SynergySwitch.enabled) return
        val runs = LeagueBridge.runs()
        oddsRound.keys.retainAll(runs.map { it.id }.toSet())
        extraGranted.keys.retainAll(runs.map { it.id }.toSet())
        for (run in runs) {
            val party = LeagueBridge.draftParty(run.player) ?: continue
            val effects = SynergyEngine.traits(SynergyEngine.countTypes(party)).flatMap { it.tier?.effectList.orEmpty() }

            // 확률 강화 추가 구매: 이번 라운드에 이미 샀으면 구매 기록을 되돌린다 (value 번까지)
            val extra = effects.filter { it.kind == "oddsbuy" }.sumOf { it.v.roundToInt() }
            if (extra > 0) {
                val granted = extraGranted[run.id]?.takeIf { it.first == run.round }?.second ?: 0
                if (granted < extra && run.grantExtraPurchase()) {
                    extraGranted[run.id] = run.round to granted + 1
                    TestLog.log("odds", "추가 구매권 지급 player=${run.player} round=${run.round} (${granted + 1}/$extra)")
                    server.playerList.getPlayer(run.player)?.sendSystemMessage(
                        Component.literal("[시너지] 이번 라운드 확률 강화를 한 번 더 구매할 수 있습니다.").withStyle(ChatFormatting.LIGHT_PURPLE),
                    )
                }
            }

            if (oddsRound[run.id] == run.round) continue
            oddsRound[run.id] = run.round
            // 페어리 4: 매 라운드 리롤 +n
            val rerolls = effects.filter { it.kind == "reroll" }.sumOf { it.v.roundToInt() }
            if (rerolls > 0 && run.addRerolls(rerolls)) {
                TestLog.log("odds", "리롤 +$rerolls player=${run.player} round=${run.round}")
                server.playerList.getPlayer(run.player)?.sendSystemMessage(
                    Component.literal("[시너지] 요정의 축복: 이번 라운드 리롤 +${rerolls}회").withStyle(ChatFormatting.LIGHT_PURPLE),
                )
            }
            val categories = effects
                .filter { it.kind == "odds" }
                .flatMap { it.id.orEmpty().split(',') }
                .flatMap { id -> if (id.trim() == "all") LeagueBridge.allCategories() else listOfNotNull(LeagueBridge.category(id.trim())) }
                .distinct()
            if (categories.isEmpty()) continue
            val applied = categories.filter { run.boostOdds(it) }
            TestLog.log("odds", "무료 강화 player=${run.player} round=${run.round} 요청=${categories.map { it.name }} 적용=${applied.map { it.name }}")
            if (applied.isEmpty()) continue
            server.playerList.getPlayer(run.player)?.sendSystemMessage(
                Component.literal("[시너지] 이번 라운드 무료 확률 강화: " + applied.joinToString(", ") { it.label })
                    .withStyle(ChatFormatting.LIGHT_PURPLE),
            )
        }
    }

    private fun sync(player: ServerPlayer) {
        if (!ServerPlayNetworking.canSend(player, SynergyPayload.TYPE)) return
        val party = LeagueBridge.draftParty(player.uuid)
        val json = if (party == null) {
            JsonObject().apply { addProperty("active", false); addProperty("enabled", SynergySwitch.enabled) }.toString()
        } else {
            SynergyEngine.uiJson(SynergyEngine.traits(SynergyEngine.countTypes(party))).apply { addProperty("enabled", SynergySwitch.enabled) }.toString()
        }
        if (lastSent[player.uuid] == json) return
        lastSent[player.uuid] = json
        if (party != null) {
            val counts = SynergyEngine.countTypes(party)
            TestLog.log("sync", "${player.scoreboardName} 타입=$counts 켜짐=" +
                SynergyEngine.traits(counts).filter { it.active }.joinToString { "${it.def.name}(${SynergyEngine.tierLabel(it.tier!!)})" })
        }
        ServerPlayNetworking.send(player, SynergyPayload(json))
    }

    // ------------------------------------------------------------------ 전투 주입

    private fun inject(battle: PokemonBattle) {
        if (battle.ended) return
        // 꺼져 있으면 던전 전투에는 주입하지 않는다 (자동 테스트 전투는 그대로)
        if (!SynergySwitch.enabled && battle.actors.none { it.uuid in TestBattle.actorParties || TestBattle.parties.containsKey(battle.battleId) }) return
        val cfg = SynergyConfig.current
        val sides = JsonObject()
        val sideTraits = LinkedHashMap<String, List<TraitState>>()
        val messages = ArrayList<Pair<ServerPlayer, List<TraitState>>>()
        val testParty = TestBattle.parties[battle.battleId]

        for (actor in battle.actors) {
            val suiteParty = TestBattle.actorParties[actor.uuid]
            val uuid = actor.getPlayerUUIDs().firstOrNull()
            if (uuid == null && suiteParty == null) continue
            val party: Iterable<com.cobblemon.mod.common.pokemon.Pokemon?> = if (suiteParty != null) {
                suiteParty
            } else if (testParty != null) {
                testParty
            } else {
                uuid ?: continue
                val draft = LeagueBridge.draftParty(uuid) ?: continue
                // 대여전 등 던전 파티가 아닌 전투는 제외
                val first = actor.pokemonList.firstOrNull()?.originalPokemon ?: continue
                if (!LeagueBridge.isDraftPokemon(first)) continue
                draft
            }
            val traits = SynergyEngine.traits(SynergyEngine.countTypes(party))
            SynergyEngine.battleConfig(traits)?.let { sides.add(actor.showdownId, it) }
            sideTraits[actor.showdownId] = traits
            uuid?.let { server?.playerList?.getPlayer(it) }?.let { messages += it to traits }
        }
        if (sides.size() == 0) {
            if (testParty != null) TestLog.log("inject", "battle=${battle.battleId} 켜진 시너지 없음 (주입 생략)")
            return
        }

        if (cfg.applyInBattle != false) {
            val cap = JsonObject().apply {
                addProperty("taken", cfg.minDamageTaken ?: 0.85)
                addProperty("heal", cfg.maxHealPerTurn ?: 0.125)
                addProperty("drain", cfg.maxDrain ?: 0.35)
            }
            val code = script.replace("__CAP__", cap.toString()).replace("__SIDES__", sides.toString())
            battle.writeShowdownAction(">eval $code")
            injected += battle.battleId
            SynergyFeed.watch(battle)
            TestLog.log("inject", "battle=${battle.battleId} test=${testParty != null} turn=${battle.turn} sides=$sides")
            if (TestMode.enabled && TestBattle.actorParties.isEmpty()) pending += (tick + VERIFY_DELAY) to { TestBattle.verifyStart(battle, sideTraits) }
        }
        messages.forEach { (player, traits) -> sendSummary(player, traits, "이번 전투 시너지") }
    }

    /** 테스트 빌드: 서버 시작 시 연동/설정 점검 결과를 로그에 남긴다 (전투는 버튼으로만 시작). */
    private fun selfCheck() {
        if (!TestMode.enabled) return
        TestLog.log("start", "==== CobbleSynergy ${TestMode.version} 테스트 빌드 시작 ====")
        LeagueBridge.selfCheck().forEach { (name, ok) -> TestLog.result(name, ok) }
        TestLog.result("battle.js 로드", runCatching { script.isNotEmpty() }.getOrDefault(false), "${runCatching { script.length }.getOrDefault(0)}자")
        val defs = SynergyConfig.current.defs
        TestLog.result("시너지 정의 ${defs.size}개", defs.size == 18)
        for (def in defs) {
            val lines = def.tiers.map { t -> "${SynergyEngine.tierLabel(t)}: " + t.effectList.joinToString(" / ") { SynergyEngine.describe(def, it) } }
            TestLog.log("start", "  ${SynergyEngine.TYPE_KO[def.type]} ${def.name} | " + lines.joinToString(" || "))
        }
    }

    private fun sendSummary(player: ServerPlayer, traits: List<TraitState>, title: String) {
        val lines = SynergyEngine.summary(traits)
        player.sendSystemMessage(Component.literal("[$title]").withStyle(ChatFormatting.GOLD))
        if (lines.isEmpty()) {
            player.sendSystemMessage(Component.literal(" 켜진 시너지가 없습니다.").withStyle(ChatFormatting.GRAY))
        } else {
            lines.forEach { player.sendSystemMessage(Component.literal(" $it").withStyle(ChatFormatting.YELLOW)) }
        }
    }

    /** battle.js 를 한 줄로 만든다 (Showdown 입력은 줄 단위). 파일에 // 주석을 쓰면 안 된다. */
    private fun loadScript(): String {
        val stream = CobbleSynergy::class.java.getResourceAsStream("/cobblesynergy/battle.js")
            ?: error("battle.js 리소스가 없습니다")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            .lines().joinToString(" ") { it.trim() }
    }
}
