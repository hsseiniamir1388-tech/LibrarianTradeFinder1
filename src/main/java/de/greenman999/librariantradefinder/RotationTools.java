package de.greenman999.librariantradefinder;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Tick-based, deterministic head turning: every call moves the view a bit closer to the
 * target (fast when far, slow when close) and reports when the view is on target.
 */
public class RotationTools {

    private static final float MAX_STEP = 30.0F;   // degrees per tick
    private static final float MIN_STEP = 2.0F;    // degrees per tick
    private static final float EASE = 0.4F;        // fraction of remaining angle per tick
    private static final float TOLERANCE = 0.3F;   // degrees

    /** @return true when the view already points at the target. */
    public static boolean stepTowards(LocalPlayer player, Vec3 target) {
        Vec3 eye = player.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float destYaw = Mth.wrapDegrees((float) (Math.atan2(dz, dx) * 57.29577951308232D) - 90.0F);
        float destPitch = Mth.clamp((float) (-(Math.atan2(dy, horizontal) * 57.29577951308232D)), -90.0F, 90.0F);

        float diffYaw = Mth.wrapDegrees(destYaw - player.getYRot());
        float diffPitch = destPitch - player.getXRot();

        if (Math.abs(diffYaw) <= TOLERANCE && Math.abs(diffPitch) <= TOLERANCE) {
            return true;
        }

        player.setYRot(player.getYRot() + ease(diffYaw));
        player.setXRot(Mth.clamp(player.getXRot() + ease(diffPitch), -90.0F, 90.0F));
        return false;
    }

    private static float ease(float diff) {
        float abs = Math.abs(diff);
        if (abs <= MIN_STEP) return diff;
        float step = Mth.clamp(abs * EASE, MIN_STEP, MAX_STEP);
        return Math.signum(diff) * Math.min(step, abs);
    }

    /** Kept only so the HUD hook in LibrarianTradeFinder still links. Rotation is now tick based. */
    public static void render() {
    }
}
