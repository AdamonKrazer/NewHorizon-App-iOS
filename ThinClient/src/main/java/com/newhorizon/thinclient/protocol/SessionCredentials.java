package com.newhorizon.thinclient.protocol;

import java.util.UUID;

/** Login identity supplied by the launcher; secrets are never persisted here. */
public final class SessionCredentials {
    public final String username;
    public final UUID profileId;
    public final String accessToken;

    public SessionCredentials(String username, UUID profileId, String accessToken) {
        if (username == null || username.isEmpty() || username.length() > 16) {
            throw new IllegalArgumentException("username");
        }
        this.username = username;
        this.profileId = profileId;
        this.accessToken = accessToken;
    }

    public boolean canJoinOnlineServer() {
        return profileId != null && accessToken != null && !accessToken.isEmpty();
    }
}


