package com.newhorizon.lowmemoryengine.mixin;

import com.newhorizon.lowmemoryengine.NewHorizonLowMemoryEngine;
import net.minecraft.client.multiplayer.ClientChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(value = ClientChunkCache.class, remap = false)
public abstract class ClientChunkCacheMixin {
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int nhlowmem$clampInitialChunkCacheRadius(int requested) {
        return NewHorizonLowMemoryEngine.clampClientChunkCacheRadius(requested);
    }

    @ModifyVariable(method = "m_104416_", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int nhlowmem$clampChunkCacheRadiusUpdate(int requested) {
        return NewHorizonLowMemoryEngine.clampClientChunkCacheRadius(requested);
    }

    @Inject(method = "m_201698_", at = @At("TAIL"))
    private void nhlowmem$trimStorageEveryTick(BooleanSupplier shouldKeepTicking, boolean tickChunks, CallbackInfo ci) {
        NewHorizonLowMemoryEngine.trimClientChunkCacheObject(this);
    }
}
