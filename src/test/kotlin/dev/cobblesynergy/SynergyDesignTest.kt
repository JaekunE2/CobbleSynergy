package dev.cobblesynergy

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 게임 없이 돌릴 수 있는 설계 검사.
 * 또 타입 × 마릿수(0~6)마다 실제로 전투에 주입될 설정을 build/synergy-sides.json 으로 내보내
 * Node 전투 시뮬레이션(test/sim/synergy-sim.js)이 같은 데이터를 쓰게 한다.
 */
class SynergyDesignTest {
    private val defs = Defaults.ALL
    private val known = setOf(
        "sidecond", "room", "terrain", "weather", "foevolatile", "entry", "endure", "priority", "onhit",
        "heal", "drain", "charm", "grudge", "ppgrudge", "odds", "oddsbuy", "reroll", "laststand",
        "power", "stat", "taken", "crit", "execute", "vsstatus", "first", "accuracyshield",
    )
    private val directDamage = setOf("power", "stat", "taken", "crit", "execute", "vsstatus", "first")

    @Test
    fun `18개 타입이 모두 한 번씩 정의되어 있다`() {
        assertEquals(SynergyEngine.TYPE_KO.keys, defs.map { it.type }.toSet())
        assertEquals(18, defs.size)
    }

    @Test
    fun `모든 효과 종류가 구현되어 있고 설명이 만들어진다`() {
        for (def in defs) for (tier in def.tiers) for (e in tier.effectList) {
            assertTrue(e.kind in known, "${def.type}: 모르는 효과 ${e.kind}")
            val text = SynergyEngine.describe(def, e)
            assertTrue(text.isNotBlank() && text != e.kind, "${def.type}: ${e.kind} 설명 없음")
            assertTrue(!text.contains("null"), "${def.type}: 설명에 null — $text")
        }
    }

    @Test
    fun `직접 피해 증감은 노말과 강철에만 있다`() {
        for (def in defs) for (tier in def.tiers) for (e in tier.effectList) {
            if (e.kind in directDamage) assertTrue(def.type in setOf("normal", "steel"), "${def.type} 에 직접 피해 효과 ${e.kind}")
        }
    }

    @Test
    fun `단계 기준이 오름차순이고 정확히 N 단계는 앞에만 있다`() {
        for (def in defs) {
            val counts = def.tiers.map { it.count }
            assertEquals(counts.sorted(), counts, "${def.type} 단계 순서")
            assertTrue(counts.all { it in 1..6 }, "${def.type} 단계는 1~6")
        }
    }

    @Test
    fun `요청된 시너지 설계가 반영되어 있다`() {
        fun def(t: String) = defs.first { it.type == t }
        // 에스퍼 2 리플렉터 / 3 빛의장막 / 4 트릭룸 (각 3턴)
        val psychic = def("psychic").tiers
        assertEquals(listOf(2, 3, 4), psychic.map { it.count })
        assertEquals(listOf("reflect"), psychic[0].effectList.map { it.id })
        assertEquals(listOf("lightscreen"), psychic[1].effectList.map { it.id })
        assertEquals(listOf("trickroom"), psychic[2].effectList.map { it.id })
        // 벌레 6 랭크업은 첫 번째 벌레 포켓몬만
        assertTrue(def("bug").tiers.last().effectList.any { it.kind == "entry" && it.firstOnly == true })
        assertTrue(psychic.flatMap { it.effectList }.all { it.turns == 3 })
        // 전기 6 일렉트릭필드
        assertTrue(def("electric").tiers.last().let { it.count == 6 && it.effectList.any { e -> e.id == "electricterrain" } })
        // 얼음 =1 / 4 설경 / 6 오로라베일 3턴
        val ice = def("ice").tiers
        assertEquals(listOf(1, 4, 6), ice.map { it.count })
        assertTrue(ice[0].isExact)
        // 얼음 =1: 첫 번째 얼음 포켓몬이 처음 나올 때 스피드 +1 만
        assertEquals(listOf(mapOf("spe" to 1)), ice[0].effectList.map { it.boosts })
        assertTrue(ice[0].effectList.single().firstOnly == true)
        // 최후의 드래곤: 마지막 남은 드래곤이 랭크 상승
        val dragon = def("dragon")
        assertEquals("최후의 드래곤", dragon.name)
        assertTrue(dragon.tiers.all { t -> t.effectList.all { it.kind == "laststand" && (it.boosts?.get("spe") ?: 0) >= 1 } })
        // 페어리 4: 매 라운드 리롤 +1
        assertTrue(def("fairy").tiers.all{t->t.effectList.none{it.kind in setOf("odds","oddsbuy","reroll")}})
        assertTrue(ice[1].effectList.any { it.id == "snow" })
        assertTrue(ice[2].effectList.any { it.id == "auroraveil" && it.turns == 3 })
        // 격투 3 버티기 하나
        val fighting = def("fighting").tiers
        assertEquals(1, fighting.size); assertEquals(3, fighting[0].count)
        assertEquals(listOf("endure"), fighting[0].effectList.map { it.kind })
        // 페어리 2 추가 구매 / 4 리롤 +1 / 6 전체 자동 강화
        val fairy = def("fairy").tiers
        assertEquals(listOf(2, 4, 6), fairy.map { it.count })
        assertEquals(listOf(0.05,0.08,0.10),fairy.map{it.effectList.first{e->e.kind=="accuracyshield"}.v})
        assertEquals(listOf(3,3,4),fairy.map{it.effectList.first{e->e.kind=="accuracyshield"}.turns})
        assertTrue(fairy[1].effectList.any{it.id=="mistyterrain"&&it.turns==3})
        assertTrue(fairy[2].effectList.any{it.id=="mistyterrain"&&it.turns==5})
    }

    @Test
    fun `전투 주입 설정을 내보낸다`() {
        val root = JsonObject()
        for (def in defs) {
            val byCount = JsonObject()
            for (count in 0..6) {
                val traits = SynergyEngine.traits(mapOf(def.type to count))
                val cfg = SynergyEngine.battleConfig(traits)
                if (cfg != null) byCount.add(count.toString(), cfg)
                val active = traits.first { it.def.type == def.type }
                if (count == 0 && active.active) fail("${def.type}: 0마리인데 켜짐")
            }
            root.add(def.type, byCount)
        }
        val out = Path.of("build", "synergy-sides.json")
        Files.createDirectories(out.parent)
        Files.writeString(out, GsonBuilder().setPrettyPrinting().create().toJson(root))
        val script = javaClass.getResourceAsStream("/cobblesynergy/battle.js")!!.bufferedReader().readText()
        assertTrue(!script.contains("//"), "battle.js 에 // 주석이 있으면 한 줄로 합칠 때 깨진다")
        Files.writeString(Path.of("build", "battle-oneline.js"), script.lines().joinToString(" ") { it.trim() })
    }
}
