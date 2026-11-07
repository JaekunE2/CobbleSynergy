package dev.cobblesynergy

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigUpgradeTest {
    @Test
    fun `이전 설계 파일(designVersion 없음)은 백업하고 새 기본값으로 바꾼다`() {
        val dir = Files.createTempDirectory("cs-config")
        Files.writeString(dir.resolve("synergies.json"),
            """{"synergies":[{"type":"dragon","name":"용의 혈통","tiers":[{"count":2,"effects":[{"kind":"entry","boosts":{"spe":1}}]}]}]}""")
        SynergyConfig.load(dir)
        assertEquals("최후의 드래곤", SynergyConfig.current.defs.first { it.type == "dragon" }.name)
        assertTrue(Files.exists(dir.resolve("synergies.json.v1.bak")))
        assertTrue(Files.readString(dir.resolve("synergies.json")).contains("\"designVersion\": ${Defaults.DESIGN}"))
        // 같은 버전이면 그대로 둔다 (사용자가 바꾼 숫자 유지)
        SynergyConfig.load(dir)
        assertEquals(18, SynergyConfig.current.defs.size)
    }
}
