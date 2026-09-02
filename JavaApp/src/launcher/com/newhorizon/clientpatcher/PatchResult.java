package com.newhorizon.clientpatcher;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PatchResult {
    public final File output;
    public final String inputSha256;
    public final String outputSha256;
    public final Map<String, Integer> changesByTransformer;

    PatchResult(File output, String inputSha256, String outputSha256,
                Map<String, Integer> changesByTransformer) {
        this.output = output;
        this.inputSha256 = inputSha256;
        this.outputSha256 = outputSha256;
        this.changesByTransformer = Collections.unmodifiableMap(
                new LinkedHashMap<>(changesByTransformer));
    }

    public int totalChangedClasses() {
        int total = 0;
        for (Integer count : changesByTransformer.values()) {
            total += count;
        }
        return total;
    }
}
