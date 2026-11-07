package dev.cobblesynergy.client

import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class ClientTier(val count: Int, val exact: Boolean, val desc: List<String>) {
    val label get() = if (exact) "=$count" else "$count"
}

data class ClientTrait(
    val type: String,
    val name: String,
    val count: Int,
    val activeTier: Int,
    val tiers: List<ClientTier>,
) {
    val active get() = activeTier >= 0

    /** 다음 단계까지 남은 마릿수 (없으면 null) */
    val nextTier: ClientTier?
        get() = tiers.drop(activeTier + 1).firstOrNull { !it.exact && it.count > count }

    /** TFT 식 등급 색: 단독(=1)은 보라, 최고 단계는 금, 첫 단계는 동, 그 사이는 은 */
    val color: Int
        get() = when {
            !active -> 0xFF3A3A3A.toInt()
            tiers[activeTier].exact -> 0xFFB06BE0.toInt()
            activeTier == tiers.lastIndex -> 0xFFE8B730.toInt()
            activeTier == 0 -> 0xFFB0703A.toInt()
            else -> 0xFFB8C4CC.toInt()
        }
}

object SynergyClientState {
    @Volatile var active = false
        private set
    @Volatile var traits: List<ClientTrait> = emptyList()
        private set
    /** 서버의 던전 시너지 켜기/끄기 상태 */
    @Volatile var enabled = true
        private set

    fun update(json: String) {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return
        active = root.get("active")?.asBoolean == true
        enabled = root.get("enabled")?.asBoolean != false
        traits = if (!active) emptyList() else parse(root)
    }

    fun clear() { active = false; traits = emptyList() }

    private fun parse(root: JsonObject): List<ClientTrait> =
        root.getAsJsonArray("traits")?.map { el ->
            val o = el.asJsonObject
            ClientTrait(
                type = o.get("type").asString,
                name = o.get("name").asString,
                count = o.get("count").asInt,
                activeTier = o.get("activeTier").asInt,
                tiers = o.getAsJsonArray("tiers").map { t ->
                    val to = t.asJsonObject
                    ClientTier(
                        count = to.get("count").asInt,
                        exact = to.get("exact").asBoolean,
                        desc = to.getAsJsonArray("desc").map { it.asString },
                    )
                },
            )
        }
            // TFT 처럼 켜진 시너지 먼저(높은 등급 순), 그다음 마릿수 순
            ?.sortedWith(compareByDescending<ClientTrait> { it.active }
                .thenByDescending { if (it.active) it.activeTier.toFloat() / it.tiers.size else 0f }
                .thenByDescending { it.count })
            ?: emptyList()
}
