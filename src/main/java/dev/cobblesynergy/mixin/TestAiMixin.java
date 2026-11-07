package dev.cobblesynergy.mixin;

import com.cobblemon.mod.common.api.battles.model.actor.AIBattleActor;
import dev.cobblesynergy.TestDriver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 테스트 빌드의 자동 테스트 NPC 만 가로챈다. 다른 NPC(던전·연습장 AI)는 그대로 둔다. */
@Mixin(value = AIBattleActor.class, remap = false, priority = 950)
public abstract class TestAiMixin {
    @Inject(method = "onChoiceRequested", at = @At("HEAD"), cancellable = true, remap = false)
    private void cobblesynergy$testChoice(CallbackInfo ci) {
        AIBattleActor self = (AIBattleActor) (Object) this;
        if (TestDriver.handles(self)) {
            ci.cancel();
            TestDriver.onRequest(self);
        }
    }
}
