package dev.cobblesynergy.mixin;

import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 가상 플레이어 연결에 빈 채널을 넣기 위한 접근자 (Carpet 의 가짜 플레이어와 같은 방식) */
@Mixin(Connection.class)
public interface ConnectionAccessor {
    @Accessor("channel")
    void cobblesynergy$setChannel(Channel channel);
}
