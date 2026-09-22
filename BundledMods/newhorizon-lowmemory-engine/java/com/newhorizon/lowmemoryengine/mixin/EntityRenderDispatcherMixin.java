package com.newhorizon.lowmemoryengine.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.newhorizon.lowmemoryengine.NewHorizonLowMemoryEngine;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = EntityRenderDispatcher.class, remap = false)
public abstract class EntityRenderDispatcherMixin {
    @Inject(method = "m_114397_", at = @At("HEAD"), cancellable = true, remap = false)
    private <E extends Entity> void newhorizon$capShouldRender(
            E entity,
            Frustum frustum,
            double cameraX,
            double cameraY,
            double cameraZ,
            CallbackInfoReturnable<Boolean> cir) {
        if (NewHorizonLowMemoryEngine.shouldSkipEntityRender(entity)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "m_114384_", at = @At("HEAD"), cancellable = true, remap = false)
    private <E extends Entity> void newhorizon$capRender(
            E entity,
            double x,
            double y,
            double z,
            float yaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            CallbackInfo ci) {
        if (NewHorizonLowMemoryEngine.shouldSkipEntityRender(entity)) {
            ci.cancel();
        }
    }
}
