package dev.cobblesynergy

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.server.level.ServerPlayer
import java.nio.file.Files
import java.nio.file.Path

/**
 * 던전 시너지 켜기/끄기 (서버 전체 설정, config/cobblesynergy/state.json 에 저장).
 *  - 꺼져 있으면 전투 주입·페어리 던전 효과를 하지 않고, 화면에는 "꺼짐" 으로만 보인다.
 *  - 던전이 진행 중이 아니면 누구나(서버장이 아니어도) 바꿀 수 있다. 진행 중에는 공정성을 위해 운영자만.
 */
object SynergySwitch {
    @Volatile var enabled = true
        private set
    private var file: Path? = null

    fun load(dir: Path) {
        file = dir.resolve("state.json")
        enabled = runCatching {
            JsonParser.parseString(Files.readString(file!!)).asJsonObject.get("enabled")?.asBoolean ?: true
        }.getOrDefault(true)
    }

    /** 바꿨으면 null, 거절이면 이유 */
    fun request(player: ServerPlayer, value: Boolean): String? {
        if (value == enabled) return null
        val privileged = player.hasPermissions(2) || player.server.isSingleplayerOwner(player.gameProfile)
        // 서버장이 아니어도 던전이 진행 중이 아닐 때는 누구나 바꿀 수 있다 (다음 게임부터 적용). 진행 중에는 운영자만.
        if (!privileged && LeagueBridge.runs().isNotEmpty()) return "던전 진행 중에는 바꿀 수 없습니다. 게임이 끝난 뒤 로비에서 바꿔 주세요. (운영자는 언제든 가능)"
        enabled = value
        runCatching { file?.let { Files.writeString(it, JsonObject().apply { addProperty("enabled", value) }.toString()) } }
        TestLog.log("switch", "${player.scoreboardName} 시너지 ${if (value) "켬" else "끔"}")
        return null
    }
}
