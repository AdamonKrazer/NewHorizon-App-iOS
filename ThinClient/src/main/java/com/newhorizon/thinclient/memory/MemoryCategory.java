package com.newhorizon.thinclient.memory;

/** Independent memory domains whose growth must never be implicit. */
public enum MemoryCategory {
    NETWORK,
    WORLD,
    MESH,
    TEXTURE_STAGING,
    DISPLAY_STATE,
    AUDIO,
    TRANSIENT,
    FILE_MAPPED
}

