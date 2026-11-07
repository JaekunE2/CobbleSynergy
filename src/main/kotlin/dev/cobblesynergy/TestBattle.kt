package dev.cobblesynergy

import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.types.ElementalTypes
import com.cobblemon.mod.common.battles.BattleFormat
import com.cobblemon.mod.common.battles.BattleRegistry
import com.cobblemon.mod.common.battles.BattleSide
import com.cobblemon.mod.common.battles.SuccessfulBattleStart
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor
import com.cobblemon.mod.common.battles.actor.TrainerBattleActor
import com.cobblemon.mod.common.battles.ai.StrongBattleAI
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.Species
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * 테스트 빌드 전용: 상세 화면의 "테스트 전투" 버튼으로 시작하는 가짜 트레이너 전투.
 *  - 플레이어 파티는 건드리지 않는다. 임시로 만든 포켓몬의 복사본(safeCopyOf)으로 싸운다.
 *  - 상대는 Cobblemon 의 TrainerBattleActor + StrongBattleAI (기존 NPC 전투 AI).
 *  - 시너지 주입 후 Showdown 이 실제로 보낸 전투 메시지를 확인해 PASS/FAIL 을 로그에 남긴다.
 */
object TestBattle {
    /** battleId → 플레이어 쪽 시너지 계산에 쓸 테스트 파티 */
    val parties = HashMap<UUID, List<Pokemon>>()
    /** 자동 테스트: AI 액터 uuid → 그 쪽 시너지 계산에 쓸 파티 */
    val actorParties = HashMap<UUID, List<Pokemon>>()
    /** 자동 테스트 비교용: 시너지를 주입하지 않는 AI 액터 */
    val noInject = HashSet<UUID>()

    private fun typesOf(species: Species) = species.types.map { it.name.lowercase() }

    private fun monoOf(type: String, exclude: Set<String> = emptySet()): List<Species> =
        PokemonSpecies.implemented.filter { s -> typesOf(s) == listOf(type) && s.name !in exclude }

    fun buildParty(type: String, count: Int): List<Pokemon> {
        val all = PokemonSpecies.implemented
        if (type == "random") return all.shuffled().take(6).map { it.create(50) }
        val n = count.coerceIn(1, 6)
        val mono = monoOf(type)
        // 단일 타입 포켓몬이 모자라면(예: 순수 비행은 3종뿐) 그 타입을 가진 복합 타입으로 채운다
        val picks = (mono.shuffled() + all.filter { type in typesOf(it) && it !in mono }.shuffled()).take(n)
        // 나머지 칸은 서로 다른 단일 타입으로 채워 다른 시너지가 켜지지 않게 한다.
        val fillers = ElementalTypes.all().map { it.name.lowercase() }.filter { it != type }.shuffled()
            .mapNotNull { t -> monoOf(t).randomOrNull() }
            .take(6 - picks.size)
        return (picks + fillers).map { it.create(50) }
    }

    fun start(player: ServerPlayer, type: String, count: Int) {
        if (!TestMode.enabled) return
        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
            player.sendSystemMessage(Component.literal("이미 전투 중입니다.").withStyle(ChatFormatting.RED))
            return
        }
        val party = buildParty(type, count)
        val foes = buildParty("random", 6)
        TestLog.log("test", "테스트 전투 요청 player=${player.scoreboardName} type=$type count=$count")
        TestLog.log("test", "  우리 파티: " + party.joinToString { "${it.species.name}(${it.types.joinToString("/") { t -> t.name }})" })
        TestLog.log("test", "  상대 파티: " + foes.joinToString { it.species.name })
        val traits = SynergyEngine.traits(SynergyEngine.countTypes(party))
        TestLog.log("test", "  예상 시너지: " + SynergyEngine.summary(traits).ifEmpty { listOf("없음") }.joinToString(" | "))

        val result = runCatching {
            BattleRegistry.startBattle(
                BattleFormat.GEN_9_SINGLES,
                BattleSide(PlayerBattleActor(player.uuid, party.map { BattlePokemon.safeCopyOf(it) })),
                BattleSide(TrainerBattleActor("시너지 테스트 트레이너", UUID.randomUUID(), foes.map { BattlePokemon.safeCopyOf(it) }, StrongBattleAI(3))),
            )
        }
        val battle = (result.getOrNull() as? SuccessfulBattleStart)?.battle
        if (battle == null) {
            TestLog.result("테스트 전투 시작", false, result.exceptionOrNull()?.toString() ?: result.getOrNull().toString())
            player.sendSystemMessage(Component.literal("테스트 전투를 시작하지 못했습니다. 로그를 확인하세요.").withStyle(ChatFormatting.RED))
            return
        }
        parties[battle.battleId] = party
        TestLog.result("테스트 전투 시작", true, "battle=${battle.battleId}")
        player.sendSystemMessage(Component.literal("[테스트] ${SynergyEngine.TYPE_KO[type] ?: "무작위"} ${if (type == "random") "" else "${count}마리 "}파티로 테스트 전투를 시작합니다.").withStyle(ChatFormatting.AQUA))
    }

    // ------------------------------------------------------------------ 검증

    private val SHOWDOWN_NAME = mapOf(
        "reflect" to "Reflect", "lightscreen" to "Light Screen", "auroraveil" to "Aurora Veil", "safeguard" to "Safeguard",
        "tailwind" to "Tailwind", "mist" to "Mist", "stealthrock" to "Stealth Rock", "spikes" to "Spikes",
        "toxicspikes" to "Toxic Spikes", "stickyweb" to "Sticky Web", "trickroom" to "Trick Room", "magicroom" to "Magic Room",
        "wonderroom" to "Wonder Room", "gravity" to "Gravity", "electricterrain" to "Electric Terrain",
        "grassyterrain" to "Grassy Terrain", "mistyterrain" to "Misty Terrain", "psychicterrain" to "Psychic Terrain",
        "sunnyday" to "SunnyDay", "raindance" to "RainDance", "sandstorm" to "Sandstorm", "snow" to "Snow",
        "taunt" to "Taunt", "leechseed" to "Leech Seed", "aquaring" to "Aqua Ring", "ingrain" to "Ingrain",
        "focusenergy" to "Focus Energy", "encore" to "Encore",
    )

    /** 전투 시작 직후 Showdown 메시지에 나와야 하는 줄 (검사 이름 → 찾을 문자열들) */
    fun expectations(traits: List<TraitState>): List<Pair<String, List<String>>> {
        val out = ArrayList<Pair<String, List<String>>>()
        for (trait in traits) {
            val tier = trait.tier ?: continue
            for (e in tier.effectList) {
                val name = SHOWDOWN_NAME[e.id] ?: e.id.orEmpty()
                val label = "${trait.def.name}: ${SynergyEngine.describe(trait.def, e)}"
                when (e.kind) {
                    "sidecond" -> out += label to listOf("-sidestart", name)
                    "room", "terrain" -> out += label to listOf("-fieldstart", name)
                    "weather" -> out += label to listOf("-weather|$name")
                    "foevolatile" -> out += label to listOf("-start", name)
                    // 처음 나오는 포켓몬이 그 타입일 때만 바로 확인할 수 있다.
                    "entry" -> out += "$label (선두가 해당 타입일 때)" to (e.volatile?.let { listOf("-start", SHOWDOWN_NAME[it] ?: it) } ?: listOf("-boost"))
                }
            }
        }
        return out
    }

    /** 주입 몇 틱 뒤 호출: 시작 효과가 실제로 전투에 나타났는지 */
    fun verifyStart(battle: PokemonBattle, sideTraits: Map<String, List<TraitState>>) {
        val lines = runCatching { battle.showdownMessages.map { it.toString() } }.getOrDefault(emptyList())
        TestLog.log("verify", "battle=${battle.battleId} Showdown 메시지 ${lines.size}줄 수집")
        lines.filter { it.contains("<<< error") || (it.contains("|error|") && !it.contains("[Invalid choice]")) }.forEach {
            TestLog.result("스크립트 오류 없음", false, it.take(300))
        }
        for ((side, traits) in sideTraits) {
            for ((label, needles) in expectations(traits)) {
                val hit = lines.firstOrNull { line -> needles.all { line.contains(it) } }
                TestLog.result("[$side] $label", hit != null, hit?.take(160) ?: "메시지 없음: ${needles.joinToString(" + ")}")
            }
        }
    }

    /** 전투 종료 시: 전투 중 발동한 시너지 관련 메시지 요약 */
    fun summarizeEnd(battle: PokemonBattle) {
        val lines = runCatching { battle.showdownMessages.map { it.toString() } }.getOrDefault(emptyList())
        val keys = listOf("move: Endure", "move: Grudge", "Aqua Ring", "Leech Seed", "-sidestart", "-fieldstart", "-weather|",
            "-status", "-boost", "-unboost", "move: Taunt", "cant|", "|-heal|")
        TestLog.log("end", "battle=${battle.battleId} 종료, 총 ${lines.size}줄")
        for (key in keys) {
            val count = lines.count { it.contains(key) }
            if (count > 0) TestLog.log("end", "  $key × $count")
        }
        lines.filter { it.contains("move: Endure") || it.contains("move: Grudge") }.take(10).forEach { TestLog.log("end", "  > ${it.take(160)}") }
        parties.remove(battle.battleId)
    }
}
