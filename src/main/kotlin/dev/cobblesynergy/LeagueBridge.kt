package dev.cobblesynergy

import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore
import com.cobblemon.mod.common.pokemon.Pokemon
import dev.cobbleleague.DrawCategory
import dev.cobbleleague.DrawOdds
import dev.cobbleleague.DrawOddsBoosts
import dev.cobbleleague.DraftState
import dev.cobbleleague.DraftPreparation
import dev.cobbleleague.LeagueMod
import java.lang.reflect.Field
import java.util.UUID

/**
 * CobbleLeague 연결부.
 *  - 공개 API: LeagueMod.service, LeagueService.draftParty(id), LeagueService.isDraftPokemon
 *  - 리플렉션(페어리 시너지 전용): LeagueService.runs → Run.getDrawOdds() → DrawOddsBoosts.odds
 *    Run 클래스가 비공개라 리플렉션을 쓴다. CobbleLeague 버전이 바뀌어 실패하면 그 기능만 꺼진다.
 * 모든 호출은 예외를 삼켜서 서버가 죽지 않게 한다.
 */
object LeagueBridge {
    private val warned = HashSet<String>()

    private inline fun <T> safe(tag: String, block: () -> T?): T? = try {
        block()
    } catch (e: Throwable) {
        if (warned.add(tag)) CobbleSynergy.LOGGER.error("CobbleSynergy: CobbleLeague 연동 실패 [$tag] (버전 불일치?)", e)
        null
    }

    fun draftParty(player: UUID): PlayerPartyStore? = safe("draftParty") { LeagueMod.service?.draftParty(player) }

    fun isDraftPokemon(pokemon: Pokemon): Boolean = safe("isDraftPokemon") { LeagueMod.service?.isDraftPokemon(pokemon) } ?: false

    // ------------------------------------------------------------------ 뽑기 확률 (페어리)

    /** 진행 중인 던전 도전 하나의 요약 */
    data class RunView(val id: UUID, val player: UUID, val round: Int, private val handle: Any) {
        fun boostOdds(category: DrawCategory): Boolean = LeagueBridge.boostOdds(handle, category)
        /** 이번 라운드 확률 강화를 이미 샀다면 구매 기록을 되돌려 한 번 더 살 수 있게 한다. */
        fun grantExtraPurchase(): Boolean = LeagueBridge.grantExtraPurchase(handle, round)
        /** 리롤 횟수를 늘린다 (페어리 4) */
        fun addRerolls(n: Int): Boolean = LeagueBridge.addRerolls(handle, n)
    }

    private val rerollField: Field? by lazy {
        safe("rerolls-field") { DraftPreparation::class.java.getDeclaredField("rerolls").apply { isAccessible = true } }
    }

    private fun addRerolls(run: Any, n: Int): Boolean = safe("rerolls") {
        val prep = call(run, "getPreparation") as? DraftPreparation ?: return@safe false
        val field = rerollField ?: return@safe false
        field.setInt(prep, field.getInt(prep) + n)
        prep.changed()
        true
    } ?: false

    /** CobbleLeague 가 뽑기 확률에 쓰는 라운드 번호와 같게: 로비 던전이면 로비 라운드, 아니면 도전 라운드 */
    private fun lobbyRound(service: Any): Int? = safe("lobby-round") {
        val f = service.javaClass.getDeclaredField("lobbyGame").apply { isAccessible = true }
        val game = f.get(service) ?: return@safe null
        val model = call(game, "getModel") ?: return@safe null
        val plan = call(model, "getPlan")
        (plan?.let { call(it, "getNumber") } ?: call(model, "getRound")) as? Int
    }

    private val runsField: Field? by lazy {
        safe("runs-field") { Class.forName("dev.cobbleleague.LeagueService").getDeclaredField("runs").apply { isAccessible = true } }
    }
    private val oddsField: Field? by lazy {
        safe("odds-field") { DrawOddsBoosts::class.java.getDeclaredField("odds").apply { isAccessible = true } }
    }

    private val usedRoundField: Field? by lazy {
        safe("usedRound-field") { DrawOddsBoosts::class.java.getDeclaredField("usedRound").apply { isAccessible = true } }
    }

    private fun call(target: Any, name: String): Any? =
        target.javaClass.getMethod(name).apply { isAccessible = true }.invoke(target)

    fun runs(): List<RunView> = safe("runs") {
        val service = LeagueMod.service ?: return@safe emptyList()
        val map = runsField?.get(service) as? Map<*, *> ?: return@safe emptyList()
        map.values.mapNotNull { run ->
            if (run == null) return@mapNotNull null
            val state = call(run, "getState") as? DraftState ?: return@mapNotNull null
            val inLobby = call(run, "getLobbyGame") != null
            RunView(
                id = call(run, "getId") as UUID,
                player = call(run, "getPlayer") as UUID,
                round = (if (inLobby) lobbyRound(service) else null) ?: (state.round + 1),
                handle = run,
            )
        }
    } ?: emptyList()

    private fun boostOdds(run: Any, category: DrawCategory): Boolean = safe("boost") {
        val boosts = call(run, "getDrawOdds") as? DrawOddsBoosts ?: return@safe false
        val field = oddsField ?: return@safe false
        val current = field.get(boosts) as DrawOdds
        // 상점의 확률 강화와 같은 함수(boosted)를 쓰되, 라운드당 1회 구매 제한(usedRound)은 건드리지 않는다.
        field.set(boosts, current.boosted(category))
        true
    } ?: false

    private fun grantExtraPurchase(run: Any, round: Int): Boolean = safe("extra-purchase") {
        val boosts = call(run, "getDrawOdds") as? DrawOddsBoosts ?: return@safe false
        val field = usedRoundField ?: return@safe false
        if (boosts.available(round)) return@safe false   // 아직 이번 라운드 구매 전
        field.setInt(boosts, round - 1)
        boosts.available(round)
    } ?: false

    /** 자동 테스트용: 연동에 필요한 CobbleLeague 요소가 모두 있는지 */
    fun selfCheck(): Map<String, Boolean> = linkedMapOf(
        "LeagueMod.service 접근" to (safe("check-service") { LeagueMod.service; true } ?: false),
        "LeagueService.runs 필드" to (runsField != null),
        "DrawOddsBoosts.odds 필드" to (oddsField != null),
        "DrawOddsBoosts.usedRound 필드" to (usedRoundField != null),
        "DraftPreparation.rerolls 필드" to (rerollField != null),
        "DrawCategory 목록" to (safe("check-category") { DrawCategory.entries.isNotEmpty() } ?: false),
    )

    fun allCategories(): List<DrawCategory> = DrawCategory.entries.filter { it != DrawCategory.NORMAL }

    fun category(id: String): DrawCategory? =
        DrawCategory.entries.firstOrNull { it.name.equals(id, true) || it.id.equals(id, true) }
}
