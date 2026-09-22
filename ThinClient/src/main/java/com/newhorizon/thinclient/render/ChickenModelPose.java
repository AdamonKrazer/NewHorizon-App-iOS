package com.newhorizon.thinclient.render;

/** ChickenModel.setupAnim: the head, beak and wattle share the neck pivot. */
final class ChickenModelPose {
    // LivingEntityRenderer translates by -1.501 after flipping Y, not -1.5.
    // The original chicken's textured foot soles otherwise coincide with the ground.
    static final float GROUND_OFFSET = .001f;
    static void apply(String path, float yawDelta, float pitch, float[] center, float[] basis) {
        if (!path.equals("/head") && !path.equals("/beak") && !path.equals("/red_thing")) return;
        // The catalog has already converted vanilla model axes by S=(-1,-1,+1).
        // Original PartPose.offset(0,15,-4), with R_y(netHeadYaw) * R_x(pitch).
        double yaw = Math.toRadians(yawDelta), tilt = Math.toRadians(pitch);
        float cy = (float) Math.cos(yaw), sy = (float) Math.sin(yaw);
        float cp = (float) Math.cos(tilt), sp = (float) Math.sin(tilt);
        float x = center[0], y = center[1] + 15, z = center[2] + 4;
        float tiltedY = cp * y + sp * z, tiltedZ = -sp * y + cp * z;
        center[0] = cy * x - sy * tiltedZ;
        center[1] = tiltedY - 15;
        center[2] = sy * x + cy * tiltedZ - 4;
        for (int col = 0; col < 3; col++) {
            x = basis[col]; y = basis[col + 3]; z = basis[col + 6];
            tiltedY = cp * y + sp * z; tiltedZ = -sp * y + cp * z;
            basis[col] = cy * x - sy * tiltedZ;
            basis[col + 3] = tiltedY;
            basis[col + 6] = sy * x + cy * tiltedZ;
        }
    }

    private ChickenModelPose() { }
}
