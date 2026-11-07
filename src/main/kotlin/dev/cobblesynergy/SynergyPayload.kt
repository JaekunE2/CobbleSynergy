package dev.cobblesynergy

import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/** 클라이언트 → 서버 (테스트 빌드): 테스트 전투 요청. type = 타입 이름 또는 "random" */
data class TestBattlePayload(val type: String, val count: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TestBattlePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<TestBattlePayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("cobblesynergy", "test_battle"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, TestBattlePayload> =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, TestBattlePayload::type, ByteBufCodecs.VAR_INT, TestBattlePayload::count, ::TestBattlePayload)
    }
}

/** 서버 → 클라이언트: 현재 시너지 상태(JSON). active=false 면 HUD 를 숨긴다. */
data class SynergyPayload(val json: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SynergyPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<SynergyPayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("cobblesynergy", "state"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, SynergyPayload> =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, SynergyPayload::json, ::SynergyPayload)
    }
}

/** 클라이언트 → 서버: 던전 시너지 켜기/끄기 요청 (던전 메뉴의 버튼) */
data class SynergyTogglePayload(val enabled: Boolean) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SynergyTogglePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<SynergyTogglePayload> =
            CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath("cobblesynergy", "toggle"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, SynergyTogglePayload> =
            StreamCodec.composite(ByteBufCodecs.BOOL, SynergyTogglePayload::enabled, ::SynergyTogglePayload)
    }
}
