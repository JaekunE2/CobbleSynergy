package dev.cobblesynergy

import net.fabricmc.loader.api.FabricLoader

/**
 * 설치된 CobbleLeague 버전에 따라 달라지는 부분. jar 하나로 0.7.1 ~ 0.7.3 을 모두 지원한다.
 *  - 0.7.1: 클라이언트 프로토콜 9, 연출 멈춤 자체 복구 없음 (CobbleSynergy 가 대신 복구)
 *  - 0.7.2 이상: 프로토콜 10, CobbleLeague 가 직접 복구 (CobbleSynergy 복구는 끈다), 관장 보상은 하나를 골라야 한다
 */
object LeagueCompat {
    val version: String by lazy {
        FabricLoader.getInstance().getModContainer("cobbleleague").map { it.metadata.version.friendlyString }.orElse("")
    }
    val legacy: Boolean get() = version.startsWith("0.7.1")
    /** 가상 플레이어가 hello 로 보내는 클라이언트 프로토콜 */
    val protocol: Int get() = if (legacy) 9 else 10
    /** CobbleLeague 에 연출 멈춤 복구가 없으면 CobbleSynergy 가 대신한다 */
    val needsAnimationRecovery: Boolean get() = legacy
}
