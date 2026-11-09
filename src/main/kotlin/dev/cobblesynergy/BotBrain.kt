package dev.cobblesynergy

import com.cobblemon.mod.common.api.moves.MoveTemplate
import com.cobblemon.mod.common.api.moves.Moves
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.pokemon.Pokemon

/**
 * AI 트레이너(가상 플레이어)의 판단.
 *  - 파티 점수 = 시너지(높은 단계·6 시너지일수록 크게) + 종족값 + 다음 상대(또는 다른 참가자) 파티에 대한 상성
 *  - 기술 점수 = 위력 × 자속 × 공격/특공 중 맞는 쪽 × 명중 × 상대 파티에 대한 상성, 쓸 만한 변화기는 고정 점수
 *  - 전투에서는 상대 현재 포켓몬에게 가장 아픈 기술을 쓰고, 기술이 전부 통하지 않으면 유리한 포켓몬으로 교체한다.
 */
object BotBrain {
    // ------------------------------------------------------------------ 타입 상성 (6세대 이후)

    private val SUPER = mapOf(
        "normal" to "", "fire" to "grass ice bug steel", "water" to "fire ground rock", "grass" to "water ground rock",
        "electric" to "water flying", "ice" to "grass ground flying dragon", "fighting" to "normal ice rock dark steel",
        "poison" to "grass fairy", "ground" to "fire electric poison rock steel", "flying" to "grass fighting bug",
        "psychic" to "fighting poison", "bug" to "grass psychic dark", "rock" to "fire ice flying bug",
        "ghost" to "psychic ghost", "dragon" to "dragon", "dark" to "psychic ghost", "steel" to "ice rock fairy",
        "fairy" to "fighting dragon dark",
    ).mapValues { it.value.split(' ').filter(String::isNotEmpty).toSet() }
    private val WEAK = mapOf(
        "normal" to "rock steel", "fire" to "fire water rock dragon", "water" to "water grass dragon",
        "grass" to "fire grass poison flying bug dragon steel", "electric" to "electric grass dragon",
        "ice" to "fire water ice steel", "fighting" to "poison flying psychic bug fairy", "poison" to "poison ground rock ghost",
        "ground" to "grass bug", "flying" to "electric rock steel", "psychic" to "psychic steel", "bug" to "fire fighting poison flying ghost steel fairy",
        "rock" to "fighting ground steel", "ghost" to "dark", "dragon" to "steel", "dark" to "fighting dark fairy",
        "steel" to "fire water electric steel", "fairy" to "fire poison steel",
    ).mapValues { it.value.split(' ').filter(String::isNotEmpty).toSet() }
    private val IMMUNE = mapOf(
        "normal" to "ghost", "electric" to "ground", "fighting" to "ghost", "poison" to "steel", "ground" to "flying",
        "psychic" to "dark", "ghost" to "normal", "dragon" to "fairy",
    ).mapValues { it.value.split(' ').toSet() }

    fun effectiveness(attack: String, defence: Collection<String>): Double = defence.distinct().fold(1.0) { m, d ->
        m * when (d) {
            in IMMUNE[attack].orEmpty() -> 0.0
            in SUPER[attack].orEmpty() -> 2.0
            in WEAK[attack].orEmpty() -> 0.5
            else -> 1.0
        }
    }

    fun types(mon: Pokemon): List<String> = mon.types.map { it.name.lowercase() }.distinct()

    // ------------------------------------------------------------------ 파티 평가

    private fun stat(mon: Pokemon, s: Stats): Int = mon.form.baseStats[s] ?: 0
    fun bst(mon: Pokemon): Int = mon.form.baseStats.values.sum()

    /** 종족값 점수: 600 → 60, 450 → 30 */
    private fun strength(mon: Pokemon) = (bst(mon) - 300).coerceAtLeast(0) / 5.0

    /** 시너지 점수. 높은 단계, 6마리 단계는 크게. 다음 단계까지 1마리 남은 시너지는 파티가 덜 찼을 때 조금 더한다. */
    fun synergyScore(party: List<Pokemon>): Double {
        val traits = SynergyEngine.traits(SynergyEngine.countTypes(party))
        var score = 0.0
        for (t in traits) {
            val tier = t.tier
            if (tier != null) {
                score += if (tier.isExact) 10.0 else tier.count * (t.activeTier + 1) * 5.0
                if (!tier.isExact && tier.count >= 6) score += 45.0
            }
            if (party.size < 6) {
                val next = t.def.tiers.filter { !it.isExact && it.count > t.count }.minByOrNull { it.count }
                if (next != null && next.count - t.count == 1) score += next.count * 1.5
            }
        }
        return score
    }

    /** 상대 파티에 대한 상성: 상대 포켓몬마다 우리 쪽에 자속으로 약점을 찌를 포켓몬이 있으면 +, 우리 포켓몬이 상대 자속에 약하면 - */
    fun matchupScore(party: List<Pokemon>, threats: List<Pokemon>): Double {
        if (threats.isEmpty()) return 0.0
        var score = 0.0
        for (t in threats) {
            val dt = types(t)
            val best = party.maxOfOrNull { m -> types(m).maxOf { effectiveness(it, dt) } } ?: 1.0
            score += when { best >= 4 -> 6.0; best >= 2 -> 4.0; best < 1 -> -2.0; else -> 0.0 }
        }
        for (m in party) {
            val mt = types(m)
            val danger = threats.count { t -> types(t).any { effectiveness(it, mt) >= 2 } }
            score -= danger * 1.5
        }
        return score * 6.0 / threats.size.coerceAtLeast(6) // 상대 파티 크기와 상관없이 같은 크기로
    }

    fun partyScore(party: List<Pokemon>, threats: List<Pokemon>, owner: java.util.UUID? = null): Double =
        synergyScore(party) + party.sumOf { strength(it) } + matchupScore(party, threats) + counterScore(owner, party)

    /** 후보를 넣을 때 가장 좋은 결과: (점수, 바꿀 칸 또는 -1) */
    fun bestPlacement(party: List<Pokemon>, candidate: Pokemon, threats: List<Pokemon>, owner: java.util.UUID? = null): Pair<Double, Int> {
        if (party.size < 6) return partyScore(party + candidate, threats, owner) to -1
        return party.indices.map { slot -> partyScore(party.toMutableList().also { it[slot] = candidate }, threats, owner) to slot }
            .maxBy { it.first }
    }

    // ------------------------------------------------------------------ 역할

    /** 딜러: 공격·특공이 높아 때리는 역할 / 탱커: HP·방어·특방이 높아 버티는 역할 / 서포터: 공격이 약하고 상대를 방해·아군을 돕는 역할 */
    enum class Role(val ko: String) { DEALER("딜러"), TANK("탱커"), SUPPORT("서포터") }

    /** 세트 자료(인터넷)에 이 종족의 역할이 있으면 그것을, 없으면 종족값으로 정한다 */
    fun role(mon: Pokemon): Role {
        val offense = maxOf(stat(mon, Stats.ATTACK), stat(mon, Stats.SPECIAL_ATTACK)).toDouble()
        val bulk = (stat(mon, Stats.HP) + stat(mon, Stats.DEFENCE) + stat(mon, Stats.SPECIAL_DEFENCE)) / 3.0
        val speed = stat(mon, Stats.SPEED).toDouble()
        SetData.role(mon, bulk >= 85)?.let { return it }
        return when {
            offense >= 100 && (offense >= bulk * 1.05 || speed >= 95) -> Role.DEALER
            bulk >= 85 && bulk >= offense * 1.05 -> Role.TANK
            offense < 85 -> Role.SUPPORT
            else -> Role.DEALER
        }
    }

    // ------------------------------------------------------------------ 기술

    private enum class Kind { SETUP_PHYS, SETUP_SPEC, SETUP_DEF, SPEED, RECOVER, STATUS, SLEEP, HAZARD, SCREEN, FIELD, DISRUPT, PROTECT, PIVOT }
    private val KIND = mapOf(
        "swordsdance" to Kind.SETUP_PHYS, "dragondance" to Kind.SETUP_PHYS, "bulkup" to Kind.SETUP_PHYS, "coil" to Kind.SETUP_PHYS,
        "shellsmash" to Kind.SETUP_PHYS, "victorydance" to Kind.SETUP_PHYS, "howl" to Kind.SETUP_PHYS, "honeclaws" to Kind.SETUP_PHYS,
        "nastyplot" to Kind.SETUP_SPEC, "calmmind" to Kind.SETUP_SPEC, "quiverdance" to Kind.SETUP_SPEC, "tailglow" to Kind.SETUP_SPEC, "geomancy" to Kind.SETUP_SPEC,
        "irondefense" to Kind.SETUP_DEF, "amnesia" to Kind.SETUP_DEF, "cosmicpower" to Kind.SETUP_DEF, "acidarmor" to Kind.SETUP_DEF, "barrier" to Kind.SETUP_DEF,
        "agility" to Kind.SPEED, "rockpolish" to Kind.SPEED, "autotomize" to Kind.SPEED,
        "recover" to Kind.RECOVER, "roost" to Kind.RECOVER, "softboiled" to Kind.RECOVER, "slackoff" to Kind.RECOVER, "milkdrink" to Kind.RECOVER,
        "moonlight" to Kind.RECOVER, "synthesis" to Kind.RECOVER, "morningsun" to Kind.RECOVER, "shoreup" to Kind.RECOVER, "wish" to Kind.RECOVER,
        "rest" to Kind.RECOVER, "strengthsap" to Kind.RECOVER, "leechseed" to Kind.RECOVER,
        "willowisp" to Kind.STATUS, "toxic" to Kind.STATUS, "thunderwave" to Kind.STATUS, "glare" to Kind.STATUS, "nuzzle" to Kind.STATUS,
        "spore" to Kind.SLEEP, "sleeppowder" to Kind.SLEEP, "hypnosis" to Kind.SLEEP, "yawn" to Kind.SLEEP, "lovelykiss" to Kind.SLEEP,
        "stealthrock" to Kind.HAZARD, "spikes" to Kind.HAZARD, "toxicspikes" to Kind.HAZARD, "stickyweb" to Kind.HAZARD,
        "reflect" to Kind.SCREEN, "lightscreen" to Kind.SCREEN, "auroraveil" to Kind.SCREEN,
        "tailwind" to Kind.FIELD, "trickroom" to Kind.FIELD,
        "taunt" to Kind.DISRUPT, "encore" to Kind.DISRUPT, "haze" to Kind.DISRUPT, "roar" to Kind.DISRUPT, "whirlwind" to Kind.DISRUPT,
        "defog" to Kind.DISRUPT, "rapidspin" to Kind.DISRUPT, "partingshot" to Kind.PIVOT, "teleport" to Kind.PIVOT,
        "protect" to Kind.PROTECT, "detect" to Kind.PROTECT, "kingsshield" to Kind.PROTECT, "spikyshield" to Kind.PROTECT,
        "banefulbunker" to Kind.PROTECT, "substitute" to Kind.PROTECT,
        "uturn" to Kind.PIVOT, "voltswitch" to Kind.PIVOT, "flipturn" to Kind.PIVOT,
    )
    private fun kind(m: MoveTemplate) = KIND[m.name]
    private fun kind(name: String) = KIND[name]

    /** 역할별 변화기(와 유턴류) 가치. 물리 딜러에게 나쁜음모처럼 맞지 않는 능력 올리기는 거의 0 */
    private fun supportValue(mon: Pokemon, role: Role, m: MoveTemplate): Double {
        val atk = stat(mon, Stats.ATTACK); val spa = stat(mon, Stats.SPECIAL_ATTACK)
        val physicalMon = atk >= spa
        return when (kind(m)) {
            Kind.SETUP_PHYS -> when (role) { Role.DEALER -> if (physicalMon) 85.0 else 10.0; Role.TANK -> if (physicalMon) 45.0 else 10.0; Role.SUPPORT -> 10.0 }
            Kind.SETUP_SPEC -> when (role) { Role.DEALER -> if (!physicalMon) 85.0 else 10.0; Role.TANK -> if (m.name == "calmmind") 55.0 else if (!physicalMon) 40.0 else 10.0; Role.SUPPORT -> 10.0 }
            Kind.SETUP_DEF -> when (role) { Role.TANK -> 45.0; else -> 10.0 }
            Kind.SPEED -> when (role) { Role.DEALER -> 50.0; else -> 10.0 }
            Kind.RECOVER -> when (role) { Role.TANK -> 80.0; Role.SUPPORT -> 55.0; Role.DEALER -> 25.0 }
            Kind.STATUS -> when (role) { Role.TANK -> 65.0; Role.SUPPORT -> 70.0; Role.DEALER -> 15.0 }
            Kind.SLEEP -> when (role) { Role.SUPPORT -> 80.0; Role.TANK -> 50.0; Role.DEALER -> 20.0 } * (if (m.accuracy in 1.0..99.0) m.accuracy / 100 else 1.0)
            Kind.HAZARD -> when (role) { Role.SUPPORT -> 65.0; Role.TANK -> 50.0; Role.DEALER -> 10.0 }
            Kind.SCREEN -> when (role) { Role.SUPPORT -> 65.0; Role.TANK -> 25.0; Role.DEALER -> 5.0 }
            Kind.FIELD -> when (role) { Role.SUPPORT -> 60.0; Role.TANK -> 20.0; Role.DEALER -> 15.0 }
            Kind.DISRUPT -> when (role) { Role.SUPPORT -> 55.0; Role.TANK -> 40.0; Role.DEALER -> 10.0 }
            Kind.PROTECT -> when (role) { Role.TANK -> 40.0; Role.SUPPORT -> 30.0; Role.DEALER -> 15.0 }
            Kind.PIVOT -> when (role) { Role.SUPPORT -> 35.0; Role.TANK -> 25.0; Role.DEALER -> 20.0 }
            null -> 5.0
        }
    }

    private fun isStatus(m: MoveTemplate) = m.damageCategory.name.equals("status", true)
    private fun physical(m: MoveTemplate) = m.damageCategory.name.equals("physical", true)

    /** 공격기 하나의 화력 (상대 파티 기준) */
    private fun attackValue(mon: Pokemon, m: MoveTemplate, threats: List<Pokemon>): Double {
        val power = if (m.power > 0) m.power.coerceAtMost(150.0) else 60.0
        val type = m.elementalType.name.lowercase()
        val stab = if (type in types(mon)) 1.5 else 1.0
        val atk = stat(mon, Stats.ATTACK).toDouble(); val spa = stat(mon, Stats.SPECIAL_ATTACK).toDouble()
        val ratio = (if (physical(m)) atk else spa) / maxOf(atk, spa, 1.0)
        val acc = if (m.accuracy <= 0 || m.accuracy > 100) 1.0 else m.accuracy / 100.0
        val coverage = if (threats.isEmpty()) 1.0 else threats.map { effectiveness(type, types(it)).coerceIn(0.25, 2.0) }.average()
        return power * stab * ratio * acc * coverage
    }

    /** 기술 하나의 가치 (역할 반영) */
    fun moveValue(mon: Pokemon, m: MoveTemplate, threats: List<Pokemon>): Double {
        val role = role(mon)
        val data = SetData.moveBonus(mon, m.name) // 세트 자료에서 이 종족이 이 역할로 쓰는 기술이면 가산
        if (isStatus(m)) return supportValue(mon, role, m) + data
        val pivot = if (kind(m) == Kind.PIVOT) supportValue(mon, role, m) * 0.5 else 0.0
        val weight = when (role) { Role.DEALER -> 1.0; Role.TANK -> 0.7; Role.SUPPORT -> 0.5 }
        return attackValue(mon, m, threats) * weight + pivot + data
    }

    /**
     * 기술 세트 전체의 가치. 역할마다 원하는 구성이 있다.
     *  딜러: 공격기 3개 이상(서로 다른 타입), 능력 올리기 1개까지
     *  탱커: 공격기 1~2개(자속 우선) + 회복 + 상태이상·방어 계열
     *  서포터: 공격기 1개 + 상태이상·잠듦·장판·벽·필드·방해 2개 이상
     * 같은 계열 변화기와 같은 타입 공격기는 겹칠수록 낮게 친다.
     */
    fun setValue(mon: Pokemon, moves: List<MoveTemplate>, threats: List<Pokemon>): Double {
        val role = role(mon)
        var total = 0.0
        val seenTypes = HashSet<String>()
        val seenKinds = HashMap<Kind?, Int>()
        var attacks = 0
        for (m in moves.sortedByDescending { moveValue(mon, it, threats) }) {
            val v = moveValue(mon, m, threats)
            if (isStatus(m)) {
                val n = seenKinds.merge(kind(m), 1, Int::plus)!!
                total += if (n == 1) v else v * 0.2
            } else {
                attacks++
                val typeNew = seenTypes.add(m.elementalType.name.lowercase())
                // 역할이 원하는 공격기 수를 넘으면 가치가 줄어든다
                val wanted = when (role) { Role.DEALER -> 4; Role.TANK -> 2; Role.SUPPORT -> 1 }
                total += v * (if (typeNew) 1.0 else 0.35) * (if (attacks <= wanted) 1.0 else 0.4)
            }
        }
        val statusCount = moves.count { isStatus(it) }
        total -= when (role) {
            Role.DEALER -> if (attacks < 2) 80.0 else if (attacks < 3) 20.0 else 0.0
            Role.TANK -> (if (attacks < 1) 100.0 else 0.0) + (if (moves.none { kind(it) == Kind.RECOVER }) 25.0 else 0.0)
            Role.SUPPORT -> (if (attacks < 1) 100.0 else 0.0) + (if (statusCount < 2) 20.0 else 0.0)
        }
        return total
    }

    /** 배울 수 있는 기술 중 가장 이득인 교체: (기술, 칸, 이득) */
    fun bestLesson(mon: Pokemon, learnable: Collection<MoveTemplate>, threats: List<Pokemon>): Triple<MoveTemplate, Int, Double>? {
        val current = (0..3).map { mon.moveSet[it]?.template }
        val known = current.filterNotNull()
        val base = setValue(mon, known, threats)
        var best: Triple<MoveTemplate, Int, Double>? = null
        for (m in learnable) {
            if (known.any { it.name == m.name }) continue
            for (slot in 0..3) {
                val next = current.toMutableList().also { it[slot] = m }.filterNotNull()
                val gain = setValue(mon, next, threats) - base
                if (gain > (best?.third ?: 0.0)) best = Triple(m, slot, gain)
            }
        }
        return best
    }

    // ------------------------------------------------------------------ 아이템

    private val MULTI_HIT = setOf("bulletseed", "rockblast", "iciclespear", "scaleshot", "tailslap", "pinmissile", "populationbomb",
        "bonerush", "armthrust", "watershuriken", "furyswipes", "doubleslap", "spikecannon", "triplekick", "tripleaxel")
    private val CHARGE = setOf("solarbeam", "solarblade", "meteorbeam", "skyattack", "geomancy", "electroshot", "freezeshock", "iceburn", "razorwind", "skullbash")
    // Punch flags from the installed Cobblemon Showdown move data.
    private val PUNCH_MOVES = setOf("bulletpunch","cometpunch","dizzypunch","doubleironbash","drainpunch","dynamicpunch","firepunch","focuspunch","hammerarm","headlongrush","icehammer","icepunch","jetpunch","machpunch","megapunch","meteormash","plasmafists","poweruppunch","ragefist","shadowpunch","skyuppercut","surgingstrikes","thunderpunch","wickedblow")
    fun punchingGloveValue(moves:Collection<String>)=if(moves.any{it in PUNCH_MOVES})40.0 else 0.0
    private val SOUND = setOf("hypervoice", "boomburst", "bugbuzz", "overdrive", "clangingscales", "torchsong", "alluringvoice", "psychicnoise", "snarl")

    /**
     * 아이템 하나가 이 포켓몬에게 주는 가치 (0 이면 쓸모없거나 해롭다).
     * 역할·능력치·특성·기술 구성을 보고 판단하고, 세트 자료에서 이 종족이 쓰는 아이템이면 가산한다.
     */
    fun itemValue(mon: Pokemon, item: String): Double {
        if (item.isBlank() || item == "minecraft:air") return 0.0
        when(dev.cobbleleague.DungeonShop.equipmentKind(mon,item)){
            "mega"->return 85.0
            "form"->return 70.0
            "unusable"->return 0.0
        }
        val role = role(mon)
        val moves = mon.moveSet.getMoves().map { it.template }
        val names = moves.map { it.name }.toSet()
        val attacks = moves.filter { !isStatus(it) }
        val statusMoves = moves.size - attacks.size
        val atk = stat(mon, Stats.ATTACK); val spa = stat(mon, Stats.SPECIAL_ATTACK); val spe = stat(mon, Stats.SPEED)
        val bulk = (stat(mon, Stats.HP) + stat(mon, Stats.DEFENCE) + stat(mon, Stats.SPECIAL_DEFENCE)) / 3.0
        val physicalMon = atk >= spa
        val ability = runCatching { mon.ability.name.lowercase().filter { it.isLetterOrDigit() } }.getOrDefault("")
        val myTypes = types(mon)
        val setup = names.any { kind(it) in setOf(Kind.SETUP_PHYS, Kind.SETUP_SPEC, Kind.SETUP_DEF, Kind.SPEED) }
        val dealer = role == Role.DEALER
        val base = when (item.removePrefix("cobblemon:").removePrefix("minecraft:")) {
            "choice_band" -> if (dealer && physicalMon && statusMoves == 0) 75.0 else if (dealer && physicalMon && statusMoves == 1) 25.0 else 3.0
            "choice_specs" -> if (dealer && !physicalMon && statusMoves == 0) 75.0 else if (dealer && !physicalMon && statusMoves == 1) 25.0 else 3.0
            "choice_scarf" -> if (dealer && spe in 75..115 && statusMoves == 0) 65.0 else if (dealer && statusMoves == 0) 30.0 else 3.0
            "life_orb" -> if (ability == "magicguard" || ability == "sheerforce") 80.0 else if (dealer) 55.0 else 10.0
            "expert_belt" -> if (dealer && attacks.map { it.elementalType.name }.distinct().size >= 3) 45.0 else 12.0
            "muscle_band" -> if (physicalMon && attacks.any { physical(it) }) (if (dealer) 32.0 else 15.0) else 2.0
            "wise_glasses" -> if (!physicalMon && attacks.any { !physical(it) }) (if (dealer) 32.0 else 15.0) else 2.0
            "shell_bell" -> if (dealer) 22.0 else 8.0
            "leftovers" -> if ("poison" in myTypes) 45.0 else when (role) { Role.TANK -> 65.0; Role.SUPPORT -> 55.0; Role.DEALER -> if (setup) 40.0 else 15.0 }
            "black_sludge" -> if ("poison" in myTypes) when (role) { Role.TANK -> 70.0; Role.SUPPORT -> 60.0; Role.DEALER -> 20.0 } else 0.0
            "assault_vest" -> if (statusMoves == 0 && bulk >= 75) (if (role == Role.TANK) 70.0 else 45.0) else 0.0
            "rocky_helmet" -> if (role == Role.TANK && stat(mon, Stats.DEFENCE) >= 90) 55.0 else if (role != Role.DEALER) 25.0 else 5.0
            "heavy_duty_boots" -> if (effectiveness("rock", myTypes) >= 2) 45.0 else 15.0
            "focus_sash" -> if (bulk < 70 && (dealer || role == Role.SUPPORT)) 55.0 else 8.0
            "eviolite" -> if (runCatching { mon.form.evolutions.isNotEmpty() }.getOrDefault(false)) 80.0 else 0.0
            "light_clay" -> if (names.any { it in setOf("reflect", "lightscreen", "auroraveil") }) 65.0 else 0.0
            "heat_rock" -> if (ability == "drought" || "sunnyday" in names) 50.0 else 0.0
            "damp_rock" -> if (ability == "drizzle" || "raindance" in names) 50.0 else 0.0
            "smooth_rock" -> if (ability == "sandstream" || "sandstorm" in names) 50.0 else 0.0
            "icy_rock" -> if (ability == "snowwarning" || "snowscape" in names || "hail" in names) 50.0 else 0.0
            "terrain_extender" -> if (ability.endsWith("surge") || names.any { it.endsWith("terrain") }) 50.0 else 0.0
            "flame_orb" -> when (ability) { "guts", "flareboost", "marvelscale", "quickfeet" -> 75.0; "magicguard" -> 30.0; else -> 0.0 }
            "toxic_orb" -> when (ability) { "poisonheal" -> 85.0; "toxicboost", "guts", "quickfeet", "marvelscale" -> 60.0; else -> 0.0 }
            "loaded_dice" -> if (names.any { it in MULTI_HIT }) 65.0 else 0.0
            "punching_glove" -> punchingGloveValue(names)
            "throat_spray" -> if (names.any { it in SOUND }) 40.0 else 0.0
            "power_herb" -> if (names.any { it in CHARGE }) 60.0 else 0.0
            "white_herb" -> if ("shellsmash" in names) 65.0 else 5.0
            "weakness_policy" -> if (setup && bulk >= 80) 50.0 else if (bulk >= 90) 25.0 else 5.0
            "blunder_policy" -> if (attacks.any { it.accuracy in 1.0..85.0 }) 25.0 else 0.0
            "air_balloon" -> if (effectiveness("ground", myTypes) >= 2 && ability != "levitate") 40.0 else 0.0
            "sitrus_berry" -> when (role) { Role.TANK -> 40.0; Role.SUPPORT -> 40.0; Role.DEALER -> 22.0 }
            "lum_berry" -> if (setup) 40.0 else 25.0
            "chesto_berry" -> if ("rest" in names) 60.0 else 0.0
            "covert_cloak", "clear_amulet", "safety_goggles", "protective_pads" -> 15.0
            "mental_herb", "eject_button", "red_card", "eject_pack" -> if (role == Role.SUPPORT) 20.0 else 8.0
            else -> 10.0
        }
        if (base <= 0.0) return 0.0
        return base + SetData.itemBonus(mon, item)
    }

    // ------------------------------------------------------------------ 기억: 대항하지 못한 상대

    /** AI 트레이너별로 고전한 상대 포켓몬의 타입 조합과 무게. 새 전투마다 조금씩 잊는다. */
    private val problems = HashMap<java.util.UUID, HashMap<String, Double>>()

    /** 대항할 포켓몬이 없었거나 우리 포켓몬을 쓰러뜨린 상대를 기억한다 */
    fun noteProblem(owner: java.util.UUID, foe: Pokemon, weight: Double) {
        val key = types(foe).sorted().joinToString("/")
        val m = problems.getOrPut(owner) { HashMap() }
        m[key] = ((m[key] ?: 0.0) + weight).coerceAtMost(6.0)
    }

    fun newBattle(owner: java.util.UUID) {
        problems[owner]?.let { m -> m.replaceAll { _, w -> w * 0.75 }; m.values.removeIf { it < 0.3 } }
    }

    fun forget(owner: java.util.UUID) { problems.remove(owner) }

    fun problemsText(owner: java.util.UUID): String = problems[owner].orEmpty().entries.sortedByDescending { it.value }.take(4)
        .joinToString(", ") { (k, w) -> k.split('/').joinToString("/") { SynergyEngine.TYPE_KO[it] ?: it } + " ×" + "%.1f".format(w) }

    /** 기억한 상대마다 파티 안에서 가장 잘 대항하는 포켓몬(그 상대의 자속을 반감 이하로 받고, 자속으로 약점을 찌름)의 점수 */
    fun counterScore(owner: java.util.UUID?, party: List<Pokemon>): Double {
        val m = owner?.let { problems[it] } ?: return 0.0
        var score = 0.0
        for ((key, w) in m) {
            val foe = key.split('/')
            val best = party.maxOfOrNull { mon ->
                val mt = types(mon)
                val taken = foe.maxOf { effectiveness(it, mt) }
                val dealt = mt.maxOf { effectiveness(it, foe) }
                (if (taken <= 0.5) 3.0 else if (taken < 2) 1.0 else -1.0) + (if (dealt >= 2) 3.0 else 0.0)
            } ?: 0.0
            score += best * w * 1.5
        }
        return score.coerceIn(-20.0, 40.0)
    }

    // ------------------------------------------------------------------ 상대 공격 예상

    private fun moveId(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

    /** 상대가 쓸 공격 타입 예상: 자속 타입 + 이번 전투에서 실제로 쓴 공격기의 타입 */
    fun predictAttacks(foe: Pokemon, usedMoves: Collection<String>): Set<String> {
        val seen = usedMoves.mapNotNull { Moves.getByName(moveId(it)) }.filter { !isStatus(it) }.map { it.elementalType.name.lowercase() }
        return (types(foe) + seen).toSet()
    }

    /** 예상 공격 중 가장 아픈 배율 */
    fun danger(mon: Pokemon, attacks: Set<String>): Double = attacks.maxOfOrNull { effectiveness(it, types(mon)) } ?: 1.0

    /** 우리 포켓몬이 가진 공격기로 상대에게 줄 수 있는 가장 큰 배율 (자속 1.5 포함) */
    fun offense(mon: Pokemon, foe: List<String>): Double = mon.moveSet.getMoves().filter { !isStatus(it.template) }
        .maxOfOrNull { m -> val t = m.template.elementalType.name.lowercase(); effectiveness(t, foe) * (if (t in types(mon)) 1.5 else 1.0) } ?: 0.0

    // ------------------------------------------------------------------ 전투

    /**
     * 전투 중 기술 점수 (상대 현재 포켓몬 기준, 역할 반영).
     *  공격기: 위력 × 자속 × 상성 × 명중 × 맞는 공격 능력치
     *  회복: 체력이 절반 아래일 때 크게 / 상태이상·잠듦: 상대가 이미 상태이상이거나 면역 타입이면 0
     *  능력 올리기: 체력이 많을 때 한두 번 / 장판·벽·필드·방해: 이번 전투에 아직 안 썼을 때
     *  usedCount: 이 포켓몬이 이번 전투에서 이 기술을 쓴 횟수
     */
    fun battleMoveScore(mon: Pokemon, moveId: String, foe: List<String>, hpRatio: Double, foeStatused: Boolean = false, usedCount: Int = 0): Double {
        val m = Moves.getByName(moveId) ?: return 10.0
        val role = role(mon)
        if (isStatus(m)) {
            val base = supportValue(mon, role, m)
            return when (kind(m)) {
                Kind.RECOVER -> when {
                    m.name == "leechseed" -> if ("grass" in foe || usedCount > 0) 0.0 else base
                    m.name == "wish" -> if (hpRatio < 0.7 && usedCount < 2) base else 0.0
                    hpRatio < 0.45 -> base * 1.8
                    hpRatio < 0.65 -> base * 0.8
                    else -> 0.0
                }
                Kind.STATUS, Kind.SLEEP -> if (foeStatused || immuneToStatus(m.name, foe)) 0.0 else base * 1.6
                Kind.SETUP_PHYS, Kind.SETUP_SPEC, Kind.SETUP_DEF, Kind.SPEED -> when {
                    usedCount == 0 && hpRatio > 0.6 -> base * 1.4
                    usedCount == 1 && hpRatio > 0.8 -> base * 0.6
                    else -> 0.0
                }
                Kind.HAZARD -> if (usedCount < (if (m.name == "spikes" || m.name == "toxicspikes") 2 else 1)) base * 1.4 else 0.0
                Kind.SCREEN, Kind.FIELD -> if (usedCount == 0) base * 1.4 else 0.0
                Kind.DISRUPT -> if (usedCount == 0) base else 0.0
                Kind.PROTECT -> if (usedCount == 0 && hpRatio < 0.5) base else 5.0
                Kind.PIVOT -> base
                null -> 5.0
            }
        }
        val type = m.elementalType.name.lowercase()
        val eff = if (foe.isEmpty()) 1.0 else effectiveness(type, foe)
        val power = if (m.power > 0) m.power.coerceAtMost(150.0) else 60.0
        val stab = if (type in types(mon)) 1.5 else 1.0
        val atk = stat(mon, Stats.ATTACK).toDouble(); val spa = stat(mon, Stats.SPECIAL_ATTACK).toDouble()
        val ratio = (if (physical(m)) atk else spa) / maxOf(atk, spa, 1.0)
        val acc = if (m.accuracy <= 0 || m.accuracy > 100) 1.0 else m.accuracy / 100.0
        return power * stab * ratio * acc * eff
    }

    /** 상태이상 기술이 그 타입에게 통하지 않는 경우 */
    private fun immuneToStatus(move: String, foe: List<String>): Boolean = when (move) {
        "willowisp" -> "fire" in foe
        "toxic", "toxicspikes" -> "poison" in foe || "steel" in foe
        "thunderwave", "nuzzle" -> "electric" in foe || "ground" in foe
        "glare" -> false
        "spore", "sleeppowder", "stunspore", "poisonpowder" -> "grass" in foe
        else -> false
    }

    /** 상대 현재 포켓몬을 상대로 한 우리 포켓몬의 유리함 (교체 대상 고르기) */
    fun switchScore(mon: Pokemon, foe: List<String>): Double {
        if (foe.isEmpty()) return bst(mon) / 100.0
        val attack = mon.moveSet.getMoves().filter { !isStatus(it.template) }
            .maxOfOrNull { effectiveness(it.template.elementalType.name.lowercase(), foe) * (if (it.template.elementalType.name.lowercase() in types(mon)) 1.5 else 1.0) } ?: 0.5
        val danger = foe.maxOf { effectiveness(it, types(mon)) }
        return attack * 2 - danger + bst(mon) / 300.0
    }
}
