package dev.cobblesynergy

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path

/**
 * 시너지 정의. 서버 config/cobblesynergy/synergies.json 으로 저장되며 숫자를 바꾸면
 * 설명 문구도 자동으로 다시 만들어진다.
 *
 * - 단계(tier)는 누적되지 않는다. 조건을 만족하는 가장 높은 단계 하나만 적용된다 (TFT 방식).
 *   그래서 높은 단계에는 아래 단계 효과를 함께 적어 둔다.
 * - exact = true 인 단계는 해당 타입이 "정확히 count 마리"일 때만 켜진다.
 */
data class SynergyDef(
    val type: String,          // cobblemon 타입 이름 (fire, water ...)
    val name: String,          // 시너지 이름
    val tiers: List<Tier>,
)

// Gson 은 JSON 에 빠진 필드를 Kotlin 기본값이 아닌 null 로 채우므로, 직접 편집될 수 있는 값은
// nullable 로 받고 아래 접근자로 기본값을 적용한다.
data class Tier(
    val count: Int,
    val exact: Boolean? = false,
    val effects: List<Effect>? = emptyList(),
) {
    val isExact get() = exact == true
    val effectList get() = effects.orEmpty()
}

/**
 * kind (효과 종류):
 *  --- 전투 시작 시 한 번 ---
 *  sidecond  진영 효과. id = reflect, lightscreen, auroraveil, safeguard, tailwind, mist (duration 턴)
 *            foe = true 면 상대 진영에 설치: stealthrock, spikes, toxicspikes, stickyweb (layers 겹)
 *  room      트릭룸 등. id = trickroom, magicroom, wonderroom, gravity (duration 턴)
 *  terrain   필드. id = electricterrain, grassyterrain, mistyterrain, psychicterrain (duration 턴)
 *  weather   날씨. id = sunnyday, raindance, sandstorm, snow (duration 턴)
 *  foevolatile 상대 선두 포켓몬에게 기술 상태. id = taunt, leechseed, encore ... (duration 턴, 0 = 기본값)
 *  --- 포켓몬별 ---
 *  entry     처음 나올 때 랭크 변화 boosts (키 "best" = 공격·특공 중 높은 쪽), duration 턴 뒤 되돌림 (0 = 계속)
 *            volatile 지정 시 그 기술 상태를 건다 (aquaring, ingrain, focusenergy ...)
 *            firstOnly = true 면 처음 나온 첫 번째 해당 타입 포켓몬에게만
 *  endure    HP 가 가득할 때 쓰러질 공격을 한 번 HP 1 로 버팀 (포켓몬마다 1회)
 *  priority  교체해 나온 뒤 첫 공격 기술 우선도 +value
 *  onhit     공격 적중 시 chance 확률로 상대에게 status / boosts / volatile(flinch 등)
 *  heal      매 턴 종료 시 최대 HP 비율 회복
 *  drain     준 피해 비율만큼 회복
 *  charm     공격받으면 chance 확률로 상대 공격(물리)/특공(특수) 1랭크 하락
 *  grudge    기절하면 쓰러뜨린 상대에게 그 상대 최대 HP 비율 피해
 *  ppgrudge  기절하면 쓰러뜨린 기술의 PP 를 모두 없앰 (원념)
 *  --- 던전 진행 ---
 *  odds      매 라운드 시작 시 무료 뽑기 확률 강화. id = mythical, legendary, sublegendary, paradox, mega (쉼표로 여러 개), all = 전부
 *  oddsbuy   상점의 확률 강화(유료)를 라운드당 value 번 더 구매할 수 있음
 *  reroll    매 라운드 리롤 횟수 +value
 *  laststand 살아 있는 그 타입 포켓몬이 한 마리만 남으면, 그 포켓몬이 필드에 있을 때 한 번 boosts
 *  --- 직접 피해 증감 (노말·강철에만 사용) ---
 *  power     기술 위력 배율 (moveType 지정 시 그 타입 기술만, nonStab=true 면 자속이 아닌 기술만)
 *  stat      능력치 배율 (stat = "atk,spa" 등)
 *  taken     받는 피해 배율 (1 미만)
 *  crit / execute / vsstatus / first : 급소 랭크, 빈사 상대 추가 피해, 상태이상 상대 추가 피해, 첫 기술 위력
 *
 * target: "member" = 그 타입 포켓몬만, "team" = 팀 전체
 */
data class Effect(
    val kind: String,
    val target: String? = "member",
    val value: Double? = 0.0,
    val moveType: String? = null,
    val nonStab: Boolean? = false,
    val stat: String? = null,
    val chance: Double? = 0.0,
    val status: String? = null,
    val boosts: Map<String, Int>? = null,
    val hpBelow: Double? = 0.5,
    val id: String? = null,
    val duration: Int? = null,
    val foe: Boolean? = false,
    val layers: Int? = 1,
    val volatile: String? = null,
    /** entry: 진영에서 처음 나온 첫 번째 해당 타입 포켓몬에게만 */
    val firstOnly: Boolean? = false,
) {
    val isTeam get() = target == "team"
    val v get() = value ?: 0.0
    val c get() = chance ?: 0.0
    val hp get() = hpBelow ?: 0.5
    val onlyNonStab get() = nonStab == true
    val onFoe get() = foe == true
    val n get() = (layers ?: 1).coerceAtLeast(1)
    val turns get() = duration ?: 0
}

data class SynergyFile(
    /** 기본 설계 버전. 모드가 올린 버전과 다르면 기존 파일을 .bak 으로 옮기고 새 기본값으로 바꾼다 */
    val designVersion: Int? = Defaults.DESIGN,
    val comment: String? = "숫자를 바꾸고 서버를 재시작하면 적용됩니다. 설명 문구는 자동 생성됩니다.",
    /** 전투에 적용할지 (false 면 표시만 한다) */
    val applyInBattle: Boolean? = true,
    /** 한 포켓몬이 받는 피해 감소 합계의 하한 (0.85 = 최대 15% 감소) */
    val minDamageTaken: Double? = 0.85,
    /** 한 포켓몬의 턴당 회복 합계 상한 (최대 HP 비율) */
    val maxHealPerTurn: Double? = 0.125,
    /** 흡수 합계 상한 */
    val maxDrain: Double? = 0.35,
    val synergies: List<SynergyDef>? = Defaults.ALL,
) {
    val defs get() = synergies.orEmpty()
}

object Defaults {
    const val DESIGN = 4

    private fun power(mult: Double, target: String = "member", nonStab: Boolean = false) =
        Effect(kind = "power", value = mult, target = target, nonStab = nonStab)
    private fun onHit(moveType: String?, chance: Double, status: String? = null, boosts: Map<String, Int>? = null, volatile: String? = null) =
        Effect(kind = "onhit", moveType = moveType, chance = chance, status = status, boosts = boosts, volatile = volatile)
    private fun mine(id: String, turns: Int) = Effect(kind = "sidecond", id = id, duration = turns, target = "team")
    private fun foeSide(id: String, layers: Int = 1) = Effect(kind = "sidecond", id = id, foe = true, layers = layers, target = "team")
    private fun room(id: String, turns: Int) = Effect(kind = "room", id = id, duration = turns, target = "team")
    private fun terrain(id: String, turns: Int) = Effect(kind = "terrain", id = id, duration = turns, target = "team")
    private fun weather(id: String, turns: Int) = Effect(kind = "weather", id = id, duration = turns, target = "team")
    private fun entry(vararg boosts: Pair<String, Int>) = Effect(kind = "entry", boosts = mapOf(*boosts))
    /** 랭크업은 지속 턴이 있다: 맞추기 쉬운 단계 3턴, 6마리가 필요한 단계 6턴 */
    private fun Effect.lasting(turns: Int) = copy(duration = turns)
    private fun odds(ids: String) = Effect(kind = "odds", id = ids, target = "team")
    private fun oddsBuy(extra: Int) = Effect(kind = "oddsbuy", value = extra.toDouble(), target = "team")
    private fun reroll(extra: Int) = Effect(kind = "reroll", value = extra.toDouble(), target = "team")
    /** 살아 있는 그 타입 포켓몬이 마지막 한 마리가 되면 (전투에 나와 있을 때) 한 번 랭크 상승 */
    private fun lastStand(vararg boosts: Pair<String, Int>) = Effect(kind = "laststand", boosts = mapOf(*boosts))
    /** 그 타입 포켓몬이 처음 나올 때 기존 기술 상태를 건다 (aquaring, ingrain, focusenergy ...) */
    private fun entryVolatile(id: String) = Effect(kind = "entry", volatile = id)
    /** 전투 시작 시 상대 선두에게 기존 기술 상태를 건다 (taunt, leechseed ...) */
    private fun foeVolatile(id: String, turns: Int = 0) = Effect(kind = "foevolatile", id = id, duration = turns, target = "team")

    /**
     * 직접 피해 증감(power/taken)은 노말·강철 두 타입에만 둔다.
     * 나머지는 필드·날씨·벽·장판·랭크·상태이상·뽑기 확률 같은 기믹으로 차별화한다.
     * 약점·상성·면역을 바꾸는 효과는 없다.
     */
    val ALL: List<SynergyDef> = listOf(
        SynergyDef("normal", "만능", listOf(
            Tier(3, effects = listOf(power(1.10, target = "team", nonStab = true))),
            Tier(6, effects = listOf(power(1.20, target = "team", nonStab = true))),
        )),
        SynergyDef("fire", "열기", listOf(
            Tier(3, effects = listOf(weather("sunnyday", 3), onHit("fire", 0.10, status = "brn"))),
            Tier(6, effects = listOf(weather("sunnyday", 5), onHit("fire", 0.20, status = "brn"))),
        )),
        SynergyDef("water", "조류", listOf(
            Tier(2, effects = listOf(entryVolatile("aquaring"))),
            Tier(4, effects = listOf(weather("raindance", 3), entryVolatile("aquaring"))),
            Tier(6, effects = listOf(weather("raindance", 5), entryVolatile("aquaring"), mine("mist", 5))),
        )),
        SynergyDef("grass", "광합성", listOf(
            Tier(2, effects = listOf(foeVolatile("leechseed"))),
            Tier(4, effects = listOf(terrain("grassyterrain", 3), foeVolatile("leechseed"))),
            Tier(6, effects = listOf(terrain("grassyterrain", 5), foeVolatile("leechseed"), entry("spd" to 1).lasting(6))),
        )),
        SynergyDef("electric", "과전류", listOf(
            Tier(3, effects = listOf(terrain("electricterrain", 3))),
            Tier(6, effects = listOf(terrain("electricterrain", 3), onHit("electric", 0.20, status = "par"))),
        )),
        SynergyDef("ice", "고고한 얼음", listOf(
            Tier(1, exact = true, effects = listOf(entry("spe" to 1).copy(firstOnly = true).lasting(3))),
            Tier(4, effects = listOf(weather("snow", 5), onHit("ice", 0.25, boosts = mapOf("spe" to -1)))),
            Tier(6, effects = listOf(weather("snow", 5), mine("auroraveil", 3), onHit("ice", 0.25, boosts = mapOf("spe" to -1)))),
        )),
        SynergyDef("fighting", "투지", listOf(
            Tier(3, effects = listOf(Effect("endure"))),
        )),
        SynergyDef("poison", "맹독", listOf(
            Tier(3, effects = listOf(foeSide("toxicspikes", 1))),
        )),
        SynergyDef("ground", "대지", listOf(
            Tier(3, effects = listOf(foeSide("stealthrock"))),
            Tier(6, effects = listOf(foeSide("stealthrock"), foeSide("spikes", 1), entry("def" to 1).lasting(6))),
        )),
        SynergyDef("flying", "순풍", listOf(
            Tier(4, effects = listOf(mine("tailwind", 4))),
        )),
        SynergyDef("psychic", "염동력", listOf(
            Tier(2, effects = listOf(mine("reflect", 3))),
            Tier(3, effects = listOf(mine("lightscreen", 3))),
            Tier(4, effects = listOf(room("trickroom", 3))),
        )),
        SynergyDef("bug", "군단", listOf(
            Tier(3, effects = listOf(foeSide("stickyweb"))),
            Tier(6, effects = listOf(foeSide("stickyweb"), entry("spa" to 1, "spd" to 1, "spe" to 1).copy(firstOnly = true).lasting(6))),
        )),
        SynergyDef("rock", "단단한 바위", listOf(
            Tier(2, effects = listOf(weather("sandstorm", 3))),
            Tier(4, effects = listOf(weather("sandstorm", 5), entry("def" to 1).lasting(3))),
        )),
        SynergyDef("ghost", "원한", listOf(
            Tier(1, exact = true, effects = listOf(Effect("grudge", value = 0.20))),
            Tier(3, effects = listOf(Effect("ppgrudge"))),
        )),
        SynergyDef("dragon", "최후의 드래곤", listOf(
            Tier(2, effects = listOf(lastStand("spe" to 1).lasting(3))),
            Tier(4, effects = listOf(lastStand("best" to 1, "spe" to 1).lasting(3))),
            Tier(6, effects = listOf(lastStand("best" to 1, "spe" to 2).lasting(6))),
        )),
        SynergyDef("dark", "도발", listOf(
            Tier(2, effects = listOf(foeVolatile("taunt", 2))),
            Tier(4, effects = listOf(foeVolatile("taunt", 3), onHit("dark", 0.20, volatile = "flinch"))),
        )),
        SynergyDef("steel", "합금", listOf(
            Tier(2, effects = listOf(Effect("taken", value = 0.92))),
            Tier(4, effects = listOf(Effect("taken", value = 0.85))),
        )),
        SynergyDef("fairy", "요정의 장막", listOf(
            Tier(2, effects = listOf(Effect(kind="accuracyshield",value=0.05,duration=3,target="team"))),
            Tier(4, effects = listOf(Effect(kind="accuracyshield",value=0.08,duration=3,target="team"),terrain("mistyterrain",3))),
            Tier(6, effects = listOf(Effect(kind="accuracyshield",value=0.10,duration=4,target="team"),terrain("mistyterrain",5))),
        )),
    )
}

object SynergyConfig {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    @Volatile
    var current: SynergyFile = SynergyFile()
        private set

    fun load(dir: Path) {
        val file = dir.resolve("synergies.json")
        try {
            Files.createDirectories(dir)
            if (Files.notExists(file)) {
                Files.writeString(file, gson.toJson(SynergyFile()))
            }
            val text = Files.readString(file)
            var parsed = gson.fromJson(text, SynergyFile::class.java)
            val fileVersion = runCatching { com.google.gson.JsonParser.parseString(text).asJsonObject.get("designVersion")?.asInt }.getOrNull() ?: 1
            if (parsed != null && fileVersion != Defaults.DESIGN) {
                Files.move(file, dir.resolve("synergies.json.v$fileVersion.bak"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                val upgraded=if(fileVersion==3)parsed.copy(designVersion=Defaults.DESIGN,
                    synergies=parsed.defs.filter{it.type!="fairy"}+Defaults.ALL.first{it.type=="fairy"}) else SynergyFile()
                Files.writeString(file,gson.toJson(upgraded))
                CobbleSynergy.LOGGER.info("CobbleSynergy: 시너지 설계가 바뀌어 synergies.json 을 새 기본값으로 교체했습니다 (이전 파일은 .bak)")
                parsed = upgraded
            }
            current = parsed ?: SynergyFile()
            CobbleSynergy.LOGGER.info("CobbleSynergy: {} synergies loaded", current.defs.size)
        } catch (e: Exception) {
            CobbleSynergy.LOGGER.error("CobbleSynergy: synergies.json 을 읽지 못해 기본값을 사용합니다", e)
            current = SynergyFile()
        }
    }
}
