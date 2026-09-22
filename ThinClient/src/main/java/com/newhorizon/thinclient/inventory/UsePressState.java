package com.newhorizon.thinclient.inventory;

import com.newhorizon.thinclient.world.ProjectileKind;

/** Touch holds must not alternate casting and retrieving within the same press. */
public final class UsePressState {
    private boolean fishingUsed;
    public boolean allows(String material) {
        return !fishingUsed || !"FISHING_ROD".equals(ProjectileKind.materialKey(material));
    }
    public void used(String material) {
        if("FISHING_ROD".equals(ProjectileKind.materialKey(material)))fishingUsed=true;
    }
    public void release() {fishingUsed=false;}
}
