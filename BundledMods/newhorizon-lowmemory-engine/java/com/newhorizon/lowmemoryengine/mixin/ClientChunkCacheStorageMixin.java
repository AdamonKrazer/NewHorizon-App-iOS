package com.newhorizon.lowmemoryengine.mixin;

import com.newhorizon.lowmemoryengine.NewHorizonLowMemoryEngine;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.multiplayer.ClientChunkCache$Storage", remap = false)
public abstract class ClientChunkCacheStorageMixin {
    @Shadow
    AtomicReferenceArray<LevelChunk> f_104466_;

    @Shadow
    int f_104471_;

    @Inject(method = "m_104484_", at = @At("TAIL"))
    private void nhlowmem$trimAfterSet(int index, LevelChunk chunk, CallbackInfo ci) {
        if (chunk != null && NewHorizonLowMemoryEngine.shouldRunStorageTrimThisTick()) {
            this.f_104471_ = NewHorizonLowMemoryEngine.trimClientChunkStorage(this.f_104466_, this.f_104471_);
        }
    }
}
