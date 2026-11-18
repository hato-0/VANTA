package dev.cart3r.client.mixin;

import dev.cart3r.client.Cart3rClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "getHandSwingDuration", at = @At("HEAD"), cancellable = true)
    private void vanta$changeSwingDuration(CallbackInfoReturnable<Integer> cir) {
        if ((Object) this == MinecraftClient.getInstance().player
                && Cart3rClient.enabled.contains("Animation Speed")) {
            cir.setReturnValue(3);
        }
    }
}
