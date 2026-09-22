package com.newhorizon.lowmemoryengine.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.newhorizon.lowmemoryengine.NewHorizonLowMemoryEngine;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = BlockEntityRenderDispatcher.class, remap = false)
public abstract class BlockEntityRenderDispatcherMixin {
    @Inject(method = "m_112272_", at = @At("HEAD"), cancellable = true, remap = false)
    private <E extends BlockEntity> void newhorizon$capTryRender(
            E blockEntity,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            int packedOverlay,
            CallbackInfoReturnable<Boolean> cir) {
        if (NewHorizonLowMemoryEngine.shouldSkipBlockEntityRender(blockEntity)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "m_112267_", at = @At("HEAD"), cancellable = true, remap = false)
    private <E extends BlockEntity> void newhorizon$capRender(
            E blockEntity,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            CallbackInfo ci) {
        if (NewHorizonLowMemoryEngine.shouldSkipBlockEntityRender(blockEntity)) {
            ci.cancel();
        }
    }
}
