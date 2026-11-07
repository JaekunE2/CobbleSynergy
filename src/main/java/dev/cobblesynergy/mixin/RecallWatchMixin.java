package dev.cobblesynergy.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.cobblesynergy.AnimationRecovery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.concurrent.CompletableFuture;

/** CobbleLeague 0.7.2 의 BattleRecallMixin 과 같은 지점 (0.7.1 에는 없음). 테스트 빌드에서만 동작한다. */
@Mixin(value = PokemonEntity.class, remap = false)
public abstract class RecallWatchMixin {
    @Inject(method = "recallWithAnimation", at = @At("RETURN"), remap = false)
    private void cobblesynergy$watchRecall(CallbackInfoReturnable<CompletableFuture<Pokemon>> cir) {
        AnimationRecovery.watchRecall((PokemonEntity) (Object) this, cir.getReturnValue());
    }
}
