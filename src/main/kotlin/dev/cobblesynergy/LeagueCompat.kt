package dev.cobblesynergy

import net.fabricmc.loader.api.FabricLoader

/** 버전별 내부 연동 차이. 실제 설치 가능 범위는 fabric.mod.json을 따릅니다. */
object LeagueCompat {
    val version: String by lazy {
        FabricLoader.getInstance().getModContainer("cobbleleague").map { it.metadata.version.friendlyString }.orElse("")
    }
    val legacy: Boolean get() = version.substringBefore('+').substringBefore('-') == "0.7.1"
    /** 가상 플레이어가 hello 로 보내는 클라이언트 프로토콜 */
    val protocol: Int get() = protocolFor(version)
    /** CobbleLeague 에 연출 멈춤 복구가 없으면 CobbleSynergy 가 대신한다 */
    fun protocolFor(value:String):Int {
        val minor=value.split('.').getOrNull(1)?.toIntOrNull()?:0
        if(minor>=8)return 11
        val patch=value.substringBefore('+').substringBefore('-').split('.').getOrNull(2)?.toIntOrNull()?:return 11
        return when{patch>=4->11;patch==3->10;else->9}
    }
    val needsAnimationRecovery: Boolean get() = legacy
}
