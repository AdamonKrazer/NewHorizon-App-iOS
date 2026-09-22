package com.newhorizon.thinclient.display;

import java.util.UUID;

public abstract class DisplayMessage {
    public static final int SPAWN = 1;
    public static final int REMOVE = 2;
    public static final int CLEAR = 3;
    public static final int NAVIGATE = 4;

    public final int type;

    private DisplayMessage(int type) {
        this.type = type;
    }

    public static final class Spawn extends DisplayMessage {
        public final VirtualDisplay display;

        public Spawn(VirtualDisplay display) {
            super(SPAWN);
            this.display = display;
        }
    }

    public static final class Remove extends DisplayMessage {
        public final UUID id;

        public Remove(UUID id) {
            super(REMOVE);
            this.id = id;
        }
    }

    public static final class Clear extends DisplayMessage {
        public Clear() {
            super(CLEAR);
        }
    }

    public static final class Navigate extends DisplayMessage {
        public final UUID id;
        public final String url;

        public Navigate(UUID id, String url) {
            super(NAVIGATE);
            this.id = id;
            this.url = url;
        }
    }
}


