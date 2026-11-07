package dev.cobblesynergy.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.entity.pokemon.effects.IllusionEffect;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.cobblesynergy.AnimationRecovery;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** CobbleLeague 0.7.2 의 BattleSendOutMixin 과 같은 지점 (0.7.1 에는 없음). 테스트 빌드에서만 동작한다. */
@Mixin(value = Pokemon.class, remap = false)
public abstract class SendOutWatchMixin {
    @Inject(method = "sendOutWithAnimation", at = @At("RETURN"), remap = false)
    private void cobblesynergy$watchSendOut(LivingEntity source, ServerLevel world, Vec3 pos, UUID battleId,
            boolean cry, IllusionEffect illusion, Function1<? super PokemonEntity, Unit> mutation,
            CallbackInfoReturnable<CompletableFuture<PokemonEntity>> cir) {
        AnimationRecovery.watchSendOut((Pokemon) (Object) this, world, pos, battleId, illusion, mutation, cir.getReturnValue());
    }
}
