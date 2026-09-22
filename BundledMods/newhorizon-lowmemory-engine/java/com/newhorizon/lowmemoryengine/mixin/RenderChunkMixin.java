package com.newhorizon.lowmemoryengine.mixin;

import com.newhorizon.lowmemoryengine.NewHorizonLowMemoryEngine;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ChunkRenderDispatcher.RenderChunk.class, remap = false)
public abstract class RenderChunkMixin {
    @Shadow
    protected abstract double m_112832_();

    @Shadow
    public abstract void m_112838_();

    @Inject(method = "m_200439_", at = @At("HEAD"), cancellable = true)
    private void nhlowmem$skipFarRebuild(RenderRegionCache regionCache, CallbackInfo ci) {
        if (NewHorizonLowMemoryEngine.shouldSkipChunkRebuildByDistance(m_112832_())) {
            m_112838_();
            ci.cancel();
        }
    }

    @Inject(method = "m_112809_", at = @At("HEAD"), cancellable = true)
    private void nhlowmem$skipFarTransparency(RenderType renderType, ChunkRenderDispatcher dispatcher,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (NewHorizonLowMemoryEngine.shouldSkipChunkRebuildByDistance(m_112832_())) {
            cir.setReturnValue(false);
        }
    }
}
