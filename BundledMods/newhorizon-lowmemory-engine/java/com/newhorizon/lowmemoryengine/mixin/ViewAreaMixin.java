package com.newhorizon.lowmemoryengine.mixin;

import com.newhorizon.lowmemoryengine.NewHorizonLowMemoryEngine;
import net.minecraft.client.renderer.ViewArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = ViewArea.class, remap = false)
public abstract class ViewAreaMixin {
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int nhlowmem$clampInitialRenderDistance(int requested) {
        return NewHorizonLowMemoryEngine.clampClientRenderDistance(requested);
    }

    @ModifyVariable(method = "m_110853_", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int nhlowmem$clampRenderDistanceUpdate(int requested) {
        return NewHorizonLowMemoryEngine.clampClientRenderDistance(requested);
    }
}
