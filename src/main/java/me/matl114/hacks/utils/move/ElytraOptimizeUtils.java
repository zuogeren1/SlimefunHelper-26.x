package me.matl114.hacks.utils.move;

import java.util.function.Function;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hacks.modules.move.ElytraExtra;
import me.matl114.hacks.modules.move.ElytraFlight;
import me.matl114.hacks.modules.move.LegacySnapRotManager;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.hooks.ViaFabricPlusHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Experimental
public class ElytraOptimizeUtils {
    public static final Minecraft mc = Minecraft.getInstance();
    public static boolean shouldAbortV3Optimize = false;

    private static final float V5_PITCH = -89.9F;
    private static final int V5_YAW_CANDIDATE_COUNT = 360;
    private static final double[] V5_EXTRA_YAWS = {0.0D, 90.0D, 180.0D, 270.0D};
    private static final double V5_FALLBACK_REQUEST_SPEED = 1.7D;
    private static final double V5_EPSILON = 1E-9D;

    public static Vec3 calculateBestPullupSpeed(Vec3 vec3d) {

        if (ElytraExtra.INSTANCE.autoRescaleAl.get().isIn(ElytraExtra.Al.V3)) {
            return calculateBestV3ClimbingSpeed(vec3d);
        }
        if (ElytraExtra.INSTANCE.autoRescaleAl.get().isIn(ElytraExtra.Al.V4)) {
            return calculateBestV4ClimbingSpeed(vec3d);
        }
        if (ElytraExtra.INSTANCE.autoRescaleAl.get().isIn(ElytraExtra.Al.V5)) {
            Vec2 py = EntityUtils.rotationToPitchYaw(vec3d.normalize());
            if (py.x < V5_PITCH) {
                return vec3d;
            }
            double pitchDeg = -5;
            if (py.x > pitchDeg) {
                return vec3d;
            }
            return EntityUtils.pitchYawToRotation(V5_PITCH, py.y);
        }
        if (ElytraExtra.INSTANCE.autoRescaleAl.get().isIn(ElytraExtra.Al.V5_PULLUP_ONLY)) {
            return calculateBestV5ClimbingSpeed(vec3d);
        }

        double horizontal = vec3d.horizontalDistance();
        if (horizontal < 1E-6) {
            vec3d = vec3d.with(Direction.Axis.X, 5);
            horizontal = vec3d.horizontalDistance();
        }
        double pitchDeg = ElytraExtra.INSTANCE.autoRescaleAl.get().isIn(ElytraExtra.Al.V1) ? -36 : -54.5;
        double pitchRad = Math.toRadians(pitchDeg);
        // 使用 -tan(pitch) 来抵消符号，或者直接用 tan(54.5)
        double newY = -horizontal * Math.tan(pitchRad);
        // 等价写法：double newY = horizontal * Math.tan(Math.toRadians(54.5));

        // 4. 返回新的向量（保持 x 和 z 不变，仅替换 y）
        return vec3d.with(Direction.Axis.Y, newY);
    }

    public static float calculateBestPullUpAngle(float yaw) {
        Vec3 bestRotation = calculateBestPullupSpeed(EntityUtils.pitchYawToRotation(-45, yaw));
        return EntityUtils.rotationToPitchYaw(bestRotation).x;
    }

    public static Vec3 calculateBestV3ClimbingSpeed(Vec3 rotation) {
        Vec2 py = EntityUtils.rotationToPitchYaw(rotation);
        float pitchDeg = py.x;
        float yawDeg = py.y;

        // 2. 计算水平方向的最大分量 M = max(|sin(yaw)|, |cos(yaw)|)
        double yawRad = Math.toRadians(yawDeg);
        double sinY = Math.abs(Math.sin(yawRad));
        double cosY = Math.abs(Math.cos(yawRad));
        double M = Math.max(sinY, cosY);

        // 3. 计算临界俯仰角（向上，负值）
        // 令 max(|look.x|, |look.z|) = M * |cos(pitch)| = 0.5
        // 因为 M >= sqrt(2)/2 ≈ 0.707，所以 0.5/M <= 0.707 < 1，恒有解
        double cosPitchCrit = 0.5 / M;
        // 向上飞，pitch 为负，取 -arccos
        double newPitchRad = -Math.acos(cosPitchCrit);
        float newPitchDeg = (float) Math.toDegrees(newPitchRad);

        // 4. 用新的俯仰角和原始偏航角重新组合视线方向向量
        return EntityUtils.pitchYawToRotation(newPitchDeg, yawDeg);
    }

    public static Vec3 calculateBestV4ClimbingSpeed(Vec3 rotation) {
        Vec2 py = EntityUtils.rotationToPitchYaw(rotation);
        float yawDeg = py.y;
        double yawRad = Math.toRadians(yawDeg);
        double sinY = Math.abs(Math.sin(yawRad));
        double cosY = Math.abs(Math.cos(yawRad));
        double m = Math.max(sinY, cosY);

        // applyAxisLimit4 refreshes lastLook via snapAt(...), so the climb-side
        // horizontal saturation boundary changes from cos(pitch)=0.5/M to
        // cos(pitch)=1/M-1.
        double cosPitchCrit = Math.max(-1.0, Math.min(1.0, 1.0 / m - 1.0));
        float newPitchDeg = (float) -Math.toDegrees(Math.acos(cosPitchCrit));
        return EntityUtils.pitchYawToRotation(Math.clamp(newPitchDeg, -88, 88), yawDeg);
    }

    public static Vec3 calculateBestV5ClimbingSpeed(Vec3 rotation) {
        Vec2 py = EntityUtils.rotationToPitchYaw(rotation);
        double requestSpeed = getV5RequestSpeed();
        double autoRescaleAmount = ElytraExtra.INSTANCE.autoRescaleAmount.get();
        Vec3 lastVelocity = PlayerStateManager.INSTANCE.lastKnownClientVelocity;
        float previousYaw = py.y;
        double anchorYaw = lastVelocity.horizontalDistance() > V5_EPSILON
                ? Math.toDegrees(Math.atan2(-lastVelocity.x, lastVelocity.z))
                : previousYaw;

        float bestInputYaw = wrapV5Yaw(py.y);
        double bestScore = Double.NEGATIVE_INFINITY;
        float bestExtraYaw = 0.0F;
        for (int index = 0; index < V5_YAW_CANDIDATE_COUNT; index++) {
            float inputYaw = wrapV5Yaw(anchorYaw + index);
            for (double extraYaw : V5_EXTRA_YAWS) {
                double score = scoreV5Candidate(
                        lastVelocity, inputYaw, (float) extraYaw, V5_PITCH, py.y, requestSpeed, autoRescaleAmount);
                double yawDelta = absoluteYawDelta(inputYaw, previousYaw);
                double bestYawDelta = absoluteYawDelta(bestInputYaw, previousYaw);
                if (score > bestScore + V5_EPSILON
                        || (Math.abs(score - bestScore) <= V5_EPSILON
                                && (yawDelta < bestYawDelta - V5_EPSILON
                                        || (Math.abs(yawDelta - bestYawDelta) <= V5_EPSILON
                                                && extraYaw < bestExtraYaw)))) {
                    bestInputYaw = inputYaw;
                    bestExtraYaw = (float) extraYaw;
                    bestScore = score;
                }
            }
        }
        return EntityUtils.pitchYawToRotation(V5_PITCH, bestInputYaw);
    }

    private record V5Bounds(double uMinX, double uMaxX, double uMinY, double uMaxY, double uMinZ, double uMaxZ) {}

    private static double getV5RequestSpeed() {
        if (ElytraFlight.INSTANCE != null) {
            return Math.max(V5_EPSILON, ElytraFlight.INSTANCE.packetMotion.get());
        }
        return V5_FALLBACK_REQUEST_SPEED;
    }

    private static float wrapV5Yaw(double yaw) {
        double wrapped = yaw % 360.0D;
        if (wrapped < 0.0D) wrapped += 360.0D;
        return (float) wrapped;
    }

    private static double absoluteYawDelta(double left, double right) {
        double delta = (left - right + 180.0D) % 360.0D - 180.0D;
        return Math.abs(delta);
    }

    private static Vec3 simulateV5Velocity(Vec3 velocity, Vec3 rotation) {
        return PlayerStateManager.INSTANCE.lastInWater || PlayerStateManager.INSTANCE.lastInLava
                ? EntityUtils.simulateTravelInFluidVelocity(
                        velocity, PlayerStateManager.INSTANCE.lastInWater, PlayerStateManager.INSTANCE.lastInLava, true)
                : EntityUtils.calculateGlidingVelocity(mc.player, velocity, rotation, true);
    }

    private static V5Bounds calculateV5Bounds(
            Vec3 lastVelocity,
            Vec3 simulated,
            Vec3 currentLook,
            Vec3 extraLook,
            Vec3 currentMotion,
            double autoRescaleAmount) {
        double antiTickSkipping = 0.05D;
        currentLook = currentLook.normalize();
        extraLook = extraLook.normalize();

        double minX = Math.min(-antiTickSkipping, currentLook.x) + Math.min(-antiTickSkipping, extraLook.x);
        double minY = Math.min(-antiTickSkipping, currentLook.y) + Math.min(-antiTickSkipping, extraLook.y);
        double minZ = Math.min(-antiTickSkipping, currentLook.z) + Math.min(-antiTickSkipping, extraLook.z);
        double maxX = Math.max(antiTickSkipping, currentLook.x) + Math.max(antiTickSkipping, extraLook.x);
        double maxY = Math.max(antiTickSkipping, currentLook.y) + Math.max(antiTickSkipping, extraLook.y);
        double maxZ = Math.max(antiTickSkipping, currentLook.z) + Math.max(antiTickSkipping, extraLook.z);

        double threshold = Math.min(autoRescaleAmount, currentMotion.length());
        minX = Math.max(-threshold, minX * threshold);
        maxX = Math.min(threshold, maxX * threshold);
        minY = Math.max(-threshold, minY * threshold);
        maxY = Math.min(threshold, maxY * threshold);
        minZ = Math.max(-threshold, minZ * threshold);
        maxZ = Math.min(threshold, maxZ * threshold);

        double eMinX = Math.min(0.0D, minX - lastVelocity.x);
        double eMaxX = Math.max(0.0D, maxX - lastVelocity.x);
        double eMinY = Math.min(0.0D, minY - lastVelocity.y);
        double eMaxY = Math.max(0.0D, maxY - lastVelocity.y);
        double eMinZ = Math.min(0.0D, minZ - lastVelocity.z);
        double eMaxZ = Math.max(0.0D, maxZ - lastVelocity.z);
        double thresholdLeft = ElytraExtra.INSTANCE.autoRescaleThreshold.get();

        return new V5Bounds(
                simulated.x + eMinX + thresholdLeft,
                simulated.x + eMaxX - thresholdLeft,
                simulated.y + eMinY + thresholdLeft,
                simulated.y + eMaxY - thresholdLeft,
                simulated.z + eMinZ + thresholdLeft,
                simulated.z + eMaxZ - thresholdLeft);
    }

    private static double scoreV5Candidate(
            Vec3 lastVelocity,
            float inputYaw,
            float extraYaw,
            float pitch,
            float currentInputYaw,
            double requestSpeed,
            double autoRescaleAmount) {
        Vec3 currentLook = EntityUtils.pitchYawToRotation(pitch, inputYaw).normalize();
        Vec3 extraLook = EntityUtils.pitchYawToRotation(0.0F, extraYaw).normalize();
        Vec3 request = currentLook.scale(requestSpeed);
        Vec3 simulated = simulateV5Velocity(lastVelocity, currentLook);
        V5Bounds bounds =
                calculateV5Bounds(lastVelocity, simulated, currentLook, extraLook, request, autoRescaleAmount);
        return Math.max(bounds.uMinX() * bounds.uMinX(), bounds.uMaxX() * bounds.uMaxX())
                + Math.max(bounds.uMinZ() * bounds.uMinZ(), bounds.uMaxZ() * bounds.uMaxZ());
    }

    private static float getV5ExtraYaw(Vec3 currentMotion, float pitch, float yaw, double autoRescaleAmount) {
        Vec3 lastVelocity = PlayerStateManager.INSTANCE.lastKnownClientVelocity;
        float inputYaw = yaw;
        float bestExtraYaw = 0.0F;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (double extraYaw : V5_EXTRA_YAWS) {
            double score = scoreV5Candidate(
                    lastVelocity, inputYaw, (float) extraYaw, pitch, yaw, currentMotion.length(), autoRescaleAmount);
            if (score > bestScore + V5_EPSILON
                    || (Math.abs(score - bestScore) <= V5_EPSILON && extraYaw < bestExtraYaw)) {
                bestExtraYaw = (float) extraYaw;
                bestScore = score;
            }
        }
        return bestExtraYaw;
    }

    public static Vec3 calculateBestDownForwardSpeed(Vec3 vec3d, boolean natural) {
        if (ElytraExtra.INSTANCE.autoRescaleAl.get().isIn(ElytraExtra.Al.V3)) {
            return calculateBestV3DownForwardSpeed(vec3d, natural);
        }
        if (ElytraExtra.INSTANCE.autoRescaleAl.get().isIn(ElytraExtra.Al.V4, ElytraExtra.Al.V5)) {
            return calculateBestV4DownForwardSpeed(vec3d, natural);
        }
        if (ElytraExtra.INSTANCE.autoRescaleAl.get().isIn(ElytraExtra.Al.V1)) {
            double horizontal = vec3d.horizontalDistance();
            if (horizontal < 1E-6) {
                vec3d = vec3d.with(Direction.Axis.X, 5);
                horizontal = vec3d.horizontalDistance();
            }
            double pitchDeg = 36;
            double pitchRad = Math.toRadians(pitchDeg);
            // 使用 -tan(pitch) 来抵消符号，或者直接用 tan(54.5)
            double newY = -horizontal * Math.tan(pitchRad);
            // 等价写法：double newY = horizontal * Math.tan(Math.toRadians(54.5));

            // 4. 返回新的向量（保持 x 和 z 不变，仅替换 y）
            return vec3d.with(Direction.Axis.Y, newY);
        }
        return vec3d;
    }

    public static Vec3 calculateBestDownwardSpeed(Vec3 vec3d) {
        Vec3 result = calculateBestDownForwardSpeed(vec3d, false);
        if (result.y >= -1E-6) {
            double horizontal = result.horizontalDistance();
            if (horizontal < 1E-6) {
                result = result.with(Direction.Axis.X, 5.0D);
                horizontal = result.horizontalDistance();
            }
            result = result.with(Direction.Axis.Y, -horizontal * Math.tan(Math.toRadians(30.5D)));
        }
        return result;
    }

    public static Vec3 calculateBestV3DownForwardSpeed(Vec3 vec3d, boolean natural) {
        double horizontal = vec3d.horizontalDistance();
        if (horizontal < 1E-6) {
            vec3d = vec3d.with(Direction.Axis.X, 5);
            horizontal = vec3d.horizontalDistance();
        }
        Vec2 py = EntityUtils.rotationToPitchYaw(vec3d.normalize());
        if (py.x > 75) {
            return vec3d;
        }
        double pitchDeg = 30.5;
        if (natural && py.x < pitchDeg) {
            return vec3d;
        }
        double pitchRad = Math.toRadians(pitchDeg);
        // 使用 -tan(pitch) 来抵消符号，或者直接用 tan(54.5)
        double newY = -horizontal * Math.tan(pitchRad);
        // 等价写法：double newY = horizontal * Math.tan(Math.toRadians(54.5));

        // 4. 返回新的向量（保持 x 和 z 不变，仅替换 y）
        return vec3d.with(Direction.Axis.Y, newY);
    }

    public static Vec3 calculateBestV4DownForwardSpeed(Vec3 vec3d, boolean natural) {
        double horizontal = vec3d.horizontalDistance();
        if (horizontal < 1E-6) {
            vec3d = vec3d.with(Direction.Axis.X, 5);
            horizontal = vec3d.horizontalDistance();
        }
        Vec2 py = EntityUtils.rotationToPitchYaw(vec3d.normalize());
        if (py.x < -70) {
            return vec3d;
        }
        double pitchDeg = 5;
        if (natural && py.x < pitchDeg) {
            return vec3d;
        }
        return EntityUtils.pitchYawToRotation(5, py.y);
    }

    public static void setOverridingFireworkVelocity(Vec3 vec3d) {
        ElytraExtra.INSTANCE.setOverridingFireworkVelocity(vec3d);
    }

    public static Vec3 applyAxisLimit30(
            Vec3 currentMotion, float pitch, float yaw, double autoRescaleAmount, boolean realApply) {
        Vec3 currentRotation = EntityUtils.pitchYawToRotation(pitch, yaw);
        Vec3 lastTickVelocity = PlayerStateManager.INSTANCE.lastKnownClientVelocity;
        Vec3 thisTickSimulationVelocity =
                PlayerStateManager.INSTANCE.lastInWater || PlayerStateManager.INSTANCE.lastInLava
                        ? EntityUtils.simulateTravelInFluidVelocity(
                                lastTickVelocity,
                                PlayerStateManager.INSTANCE.lastInWater,
                                PlayerStateManager.INSTANCE.lastInLava,
                                true)
                        : EntityUtils.calculateGlidingVelocity(mc.player, lastTickVelocity, currentRotation, true);

        // --- fireworksBox 构造 (保持不变) ---
        Vec3 lastPitchYaw = EntityUtils.pitchYawToRotation(
                PlayerStateManager.INSTANCE.lastPitch, PlayerStateManager.INSTANCE.lastYaw);
        double antiTickSkipping = 0.05;
        Vec3 currentLook = currentRotation.normalize();
        Vec3 lastLook = lastPitchYaw.normalize();
        double minX = Math.min(-antiTickSkipping, currentLook.x()) + Math.min(-antiTickSkipping, lastLook.x());
        double minY = Math.min(-antiTickSkipping, currentLook.y()) + Math.min(-antiTickSkipping, lastLook.y());
        double minZ = Math.min(-antiTickSkipping, currentLook.z()) + Math.min(-antiTickSkipping, lastLook.z());
        double maxX = Math.max(antiTickSkipping, currentLook.x()) + Math.max(antiTickSkipping, lastLook.x());
        double maxY = Math.max(antiTickSkipping, currentLook.y()) + Math.max(antiTickSkipping, lastLook.y());
        double maxZ = Math.max(antiTickSkipping, currentLook.z()) + Math.max(antiTickSkipping, lastLook.z());

        double threshold = Math.min(autoRescaleAmount, currentMotion.length());
        minX *= threshold;
        maxX *= threshold;
        minY *= threshold;
        maxY *= threshold;
        minZ *= threshold;
        maxZ *= threshold;
        minX = Math.max(-threshold, minX);
        maxX = Math.min(threshold, maxX);
        minY = Math.max(-threshold, minY);
        maxY = Math.min(threshold, maxY);
        minZ = Math.max(-threshold, minZ);
        maxZ = Math.min(threshold, maxZ);
        // Box box = new Box(minX, minY, minZ, maxX, maxY, maxZ);
        Vec3 v1 = lastTickVelocity;
        Vec3 v3 = thisTickSimulationVelocity;
        double eMinX = Math.min(0, minX - v1.x);
        double eMaxX = Math.max(0, maxX - v1.x);
        double eMinY = Math.min(0, minY - v1.y);
        double eMaxY = Math.max(0, maxY - v1.y);
        double eMinZ = Math.min(0, minZ - v1.z);
        double eMaxZ = Math.max(0, maxZ - v1.z);
        double zeroPointThreeTest = 0.0;
        double thresoldLeft = ElytraExtra.INSTANCE.autoRescaleThreshold.get();
        double uMinX = v3.x + eMinX - zeroPointThreeTest + thresoldLeft;
        double uMaxX = v3.x + eMaxX + zeroPointThreeTest - thresoldLeft;
        double uMinY = v3.y + eMinY + thresoldLeft;
        double uMaxY = v3.y + eMaxY - thresoldLeft;
        double uMinZ = v3.z + eMinZ - zeroPointThreeTest + thresoldLeft;
        double uMaxZ = v3.z + eMaxZ + zeroPointThreeTest - thresoldLeft;
        // I dont understand.
        double dx = currentMotion.x, dz = currentMotion.z;
        double exceedX = 0.0, exceedZ = 0.0;

        if (dx > 0) exceedX = dx / uMaxX;
        else if (dx < 0) exceedX = dx / uMinX; // 注意 dx 为负，uMinX 也为负，比值 >1 若 dx < uMinX

        if (dz > 0) exceedZ = dz / uMaxZ;
        else if (dz < 0) exceedZ = dz / uMinZ;

        // ??????????????????????????????????????????????????????????????????????????
        // dy

        double maxScale = Math.max(exceedX, exceedZ);

        // 退化情况：所有 scale 为 0
        if (maxScale < 1E-6) {
            if (realApply) setOverridingFireworkVelocity(null);
            return currentMotion;
        }
        Vec3 predictedMotion = EntityUtils.calculateGlidingVelocity(mc.player, currentMotion, currentRotation, true);
        Vec3 clampedMotion = currentMotion.scale(1 / maxScale);
        // todo : add more angle restrict
        if (clampedMotion.y > 0) {
            clampedMotion = clampedMotion.with(Direction.Axis.Y, uMaxY);
        } else if (clampedMotion.y < 0) {
            clampedMotion = clampedMotion.with(Direction.Axis.Y, uMinY);
        }
        clampedMotion = ElytraExtra.INSTANCE.applySpeedLimit(clampedMotion);
        // 已在盒内，无需缩放
        if (clampedMotion.lengthSqr() < predictedMotion.lengthSqr()) {
            if (realApply) setOverridingFireworkVelocity(null);
            return currentMotion;
        }

        // 需要缩小至盒子边界
        if (realApply) setOverridingFireworkVelocity(clampedMotion); // <-- 保存边界值
        return clampedMotion;
    }

    public static Vec3 applyAxisLimit3(Vec3 currentMotion, float pitch, float yaw, double autoRescaleAmount) {
        return applyAxisLimit3_0(currentMotion, pitch, yaw, autoRescaleAmount, true);
    }

    private static Vec3 applyAxisLimit3_0(
            Vec3 currentMotion, float pitch, float yaw, double autoRescaleAmount, boolean apply) {
        if (currentMotion.lengthSqr() < 1E-6) {
            if (apply) setOverridingFireworkVelocity(null);
            return currentMotion;
        }
        if (shouldAbortV3Optimize) {
            return ElytraExtra.INSTANCE.applyAxisLimit2(currentMotion, pitch, yaw, apply);
        }
        ;
        if (pitch > 0) {
            if (pitch > 60) {
                return ElytraExtra.INSTANCE.applyAxisLimit2(currentMotion, pitch, yaw, apply);
            } else if (pitch < 1) {
                return ElytraExtra.INSTANCE.applyAxisLimit2(currentMotion, pitch, yaw, apply);
            }
        } else {
            if (pitch < -70) {
                return ElytraExtra.INSTANCE.applyAxisLimit2(currentMotion, pitch, yaw, apply);
            } else if (pitch > -1) {
                return ElytraExtra.INSTANCE.applyAxisLimit2(currentMotion, pitch, yaw, apply);
            }
        }
        return applyAxisLimit30(currentMotion, pitch, yaw, autoRescaleAmount, apply);
    }

    public static Vec3 calculateTowardsTargetV3Direction(Vec3 targetPosition, double speed) {
        Vec3 targetDirection = targetPosition.normalize();
        Vec2 pitchYaw = EntityUtils.rotationToPitchYaw(targetDirection);
        float p = pitchYaw.x;
        float y = pitchYaw.y;
        float startPitch;
        float endPitch;
        float minDelta = 1;
        float minDot = 3.0F;
        // todo: add up direction, add down direction
        if (pitchYaw.x > 0) {
            // downwards
            startPitch = 1.14F;
            endPitch = 85.8F;
        } else {
            startPitch = -85.6F;
            endPitch = -1.14F;
        }
        if (p < startPitch || p > endPitch) {
            return targetDirection;
        }
        float startDot;
        float endDot;

        Function<Float, Float> dotFunction = (pitch) -> {
            Vec3 simulateVec3d = EntityUtils.pitchYawToRotation(pitch, y).normalize();
            Vec3 tickSpeed = simulateAxisLimitSpeed(simulateVec3d.scale(speed), pitch, y);
            float movingPitch = EntityUtils.rotationToPitch(tickSpeed.normalize());
            return movingPitch - p;
        };
        startDot = dotFunction.apply(startPitch);
        endDot = dotFunction.apply(endPitch);
        if (startDot * endDot >= 0) {
            // No Idea
            return targetDirection;
        }
        float midPitch = p;
        float midDot = dotFunction.apply(midPitch);
        while (!(Math.abs(midDot) < minDot || Math.abs(startPitch - endPitch) < minDelta)) {
            if (midDot == 0) break;
            if (midDot * startDot < 0) {
                endPitch = midPitch;
                endDot = midDot;
            } else {
                startPitch = midPitch;
                startDot = midDot;
            }
            midPitch = (startPitch + endPitch) / 2;
            midDot = dotFunction.apply(midPitch);
        }
        return EntityUtils.pitchYawToRotation(midPitch, y);
    }

    public static Vec3 calculateLookTowardsTargetV3Direction(Vec3 targetPosition, double speed) {
        Vec3 targetDirection = targetPosition.normalize();
        Vec2 pitchYaw = EntityUtils.rotationToPitchYaw(targetDirection);
        float p = pitchYaw.x;
        float y = pitchYaw.y;
        float startPitch;
        float endPitch;
        float minDelta = 1;
        float minDot = 3.0F;
        // todo: add up direction, add down direction
        if (pitchYaw.x > 0) {
            // downwards
            startPitch = 1.14F;
            endPitch = 85.8F;
        } else {
            startPitch = -85.6F;
            endPitch = -1.14F;
        }
        if (p < startPitch || p > endPitch) {
            return targetDirection;
        }
        float startDot;
        float endDot;

        Function<Float, Float> dotFunction = (pitch) -> {
            Vec3 simulateVec3d = EntityUtils.pitchYawToRotation(pitch, y).normalize();
            Vec3 tickSpeed = simulateAxisLimitSpeed(simulateVec3d.scale(speed), pitch, y);
            Vec3 predictLook = targetPosition.subtract(tickSpeed);
            float realNeedPitch = EntityUtils.rotationToPitch(predictLook.normalize());
            return realNeedPitch - pitch;
        };
        startDot = dotFunction.apply(startPitch);
        endDot = dotFunction.apply(endPitch);
        if (startDot * endDot >= 0) {
            // No Idea
            return targetDirection;
        }
        float midPitch = p;
        float midDot = dotFunction.apply(midPitch);
        while (!(Math.abs(midDot) < minDot || Math.abs(startPitch - endPitch) < minDelta)) {
            if (midDot == 0) break;
            if (midDot * startDot < 0) {
                endPitch = midPitch;
                endDot = midDot;
            } else {
                startPitch = midPitch;
                startDot = midDot;
            }
            midPitch = (startPitch + endPitch) / 2;
            midDot = dotFunction.apply(midPitch);
        }
        return EntityUtils.pitchYawToRotation(midPitch, y);
    }

    public static Vec3 simulateAxisLimitSpeed(Vec3 currentMotion, float pitch, float yaw) {
        return switch (ElytraExtra.INSTANCE.autoRescaleAl.get()) {
            case V1 -> ElytraExtra.INSTANCE.applyAxisLimit1(currentMotion, pitch, yaw, false);
            case V2 -> ElytraExtra.INSTANCE.applyAxisLimit2(currentMotion, pitch, yaw, false);
            case V3 -> applyAxisLimit3_0(
                    currentMotion, pitch, yaw, ElytraExtra.INSTANCE.autoRescaleAmount.get(), false);
            case V4 -> applyAxisLimit4_0(
                    currentMotion, pitch, yaw, ElytraExtra.INSTANCE.autoRescaleAmount.get(), false);
            case V5, V5_PULLUP_ONLY -> applyAxisLimit5_0(
                    currentMotion, pitch, yaw, ElytraExtra.INSTANCE.autoRescaleAmount.get(), false);
        };
    }

    public void onPreTravel() {}

    public static void onPreElytraMovement(Event<Vec3> currentMotion) {
        if (packetToSend != null) {
            mc.getConnection().send(packetToSend);
            packetToSend = null;
        }
    }

    private static ServerboundMovePlayerPacket packetToSend;

    public static Vec3 applyAxisLimit4(Vec3 currentMotion, float pitch, float yaw, double autoRescaleAmount) {
        return applyAxisLimit4_0(currentMotion, pitch, yaw, autoRescaleAmount, true);
    }

    public static Vec3 applyAxisLimit4_0(
            Vec3 currentMotion, float pitch, float yaw, double autoRescaleAmount, boolean apply) {
        if (currentMotion.lengthSqr() < 1E-6) {
            if (apply) setOverridingFireworkVelocity(null);
            return currentMotion;
        }
        Vec3 currentRotation = EntityUtils.pitchYawToRotation(pitch, yaw);
        if (shouldAbortV3Optimize) {
            return ElytraExtra.INSTANCE.applyAxisLimit2(currentMotion, pitch, yaw, apply);
        }
        if (!ViaFabricPlusHooks.isSupportDupRot()) {
            return applyAxisLimit3_0(currentMotion, pitch, yaw, autoRescaleAmount, apply);
        }
        Vec3 extraTargeting = null;
        if (pitch > 0) {
            if (pitch > 60) {
                return ElytraExtra.INSTANCE.applyAxisLimit2(currentMotion, pitch, yaw, apply);
            } else {
                double len = 1.01 - Math.abs(currentRotation.y);
                Vec3 targetTo = new Vec3(0, -len, 0);
                double lenSqr = targetTo.lengthSqr();
                if (lenSqr < 1) {
                    Vec3 horizontal =
                            EntityUtils.pitchYawToRotation(0, yaw).normalize().scale(Math.sqrt(1 - lenSqr));
                    targetTo = targetTo.add(horizontal);
                }
                targetTo = targetTo.normalize();
                extraTargeting = targetTo;
            }
        } else {
            if (pitch > -7) {
                return ElytraExtra.INSTANCE.applyAxisLimit2(currentMotion, pitch, yaw, apply);
            } else {
                //
                Vec3 horizontal = new Vec3(currentRotation.x, 0, currentRotation.z);
                Vec3 targetHorizontal = EntityUtils.pitchYawToRotation(0, yaw);
                double scale = Math.min(1.01 / Math.abs(targetHorizontal.x), 1.01 / Math.abs(targetHorizontal.z));
                targetHorizontal = targetHorizontal.scale(scale);
                Vec3 targetTo = targetHorizontal.subtract(horizontal);
                double lenSqr = targetTo.lengthSqr();
                if (lenSqr < 1) {
                    targetTo = targetTo.add(0, Math.sqrt(1 - lenSqr), 0);
                }
                targetTo = targetTo.normalize();
                extraTargeting = targetTo;
            }
        }
        float lastPitch = PlayerStateManager.INSTANCE.lastPitch;
        float lastYaw = PlayerStateManager.INSTANCE.lastYaw;
        if (extraTargeting != null) {
            float extraPitch = EntityUtils.rotationToPitch(extraTargeting);
            float extraYaw = EntityUtils.rotationToYaw(extraTargeting);
            if (apply) packetToSend = LegacySnapRotManager.INSTANCE.createSnapAt(extraPitch, extraYaw);
            PlayerStateManager.INSTANCE.lastPitch = extraPitch;
            PlayerStateManager.INSTANCE.lastYaw = extraYaw;
        }
        Vec3 result = applyAxisLimit30(currentMotion, pitch, yaw, autoRescaleAmount, apply);
        PlayerStateManager.INSTANCE.lastPitch = lastPitch;
        PlayerStateManager.INSTANCE.lastYaw = lastYaw;
        return result;
    }

    public static Vec3 applyAxisLimit50_Greedy(
            Vec3 currentMotion, float pitch, float yaw, double autoRescaleAmount, boolean realApply) {
        Vec3 currentRotation = EntityUtils.pitchYawToRotation(pitch, yaw);
        Vec3 lastTickVelocity = PlayerStateManager.INSTANCE.lastKnownClientVelocity;
        Vec3 thisTickSimulationVelocity =
                PlayerStateManager.INSTANCE.lastInWater || PlayerStateManager.INSTANCE.lastInLava
                        ? EntityUtils.simulateTravelInFluidVelocity(
                                lastTickVelocity,
                                PlayerStateManager.INSTANCE.lastInWater,
                                PlayerStateManager.INSTANCE.lastInLava,
                                true)
                        : EntityUtils.calculateGlidingVelocity(mc.player, lastTickVelocity, currentRotation, true);

        // --- fireworksBox 构造 (保持不变) ---
        Vec3 lastPitchYaw = EntityUtils.pitchYawToRotation(
                PlayerStateManager.INSTANCE.lastPitch, PlayerStateManager.INSTANCE.lastYaw);
        double antiTickSkipping = 0.05;
        Vec3 currentLook = currentRotation.normalize();
        Vec3 lastLook = lastPitchYaw.normalize();
        double minX = Math.min(-antiTickSkipping, currentLook.x()) + Math.min(-antiTickSkipping, lastLook.x());
        double minY = Math.min(-antiTickSkipping, currentLook.y()) + Math.min(-antiTickSkipping, lastLook.y());
        double minZ = Math.min(-antiTickSkipping, currentLook.z()) + Math.min(-antiTickSkipping, lastLook.z());
        double maxX = Math.max(antiTickSkipping, currentLook.x()) + Math.max(antiTickSkipping, lastLook.x());
        double maxY = Math.max(antiTickSkipping, currentLook.y()) + Math.max(antiTickSkipping, lastLook.y());
        double maxZ = Math.max(antiTickSkipping, currentLook.z()) + Math.max(antiTickSkipping, lastLook.z());

        double threshold = Math.min(autoRescaleAmount, currentMotion.length());
        minX *= threshold;
        maxX *= threshold;
        minY *= threshold;
        maxY *= threshold;
        minZ *= threshold;
        maxZ *= threshold;
        minX = Math.max(-threshold, minX);
        maxX = Math.min(threshold, maxX);
        minY = Math.max(-threshold, minY);
        maxY = Math.min(threshold, maxY);
        minZ = Math.max(-threshold, minZ);
        maxZ = Math.min(threshold, maxZ);
        // AABB box = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        Vec3 v1 = lastTickVelocity;
        Vec3 v3 = thisTickSimulationVelocity;
        double eMinX = Math.min(0, minX - v1.x);
        double eMaxX = Math.max(0, maxX - v1.x);
        double eMinY = Math.min(0, minY - v1.y);
        double eMaxY = Math.max(0, maxY - v1.y);
        double eMinZ = Math.min(0, minZ - v1.z);
        double eMaxZ = Math.max(0, maxZ - v1.z);
        double zeroPointThreeTest = 0.0;
        double thresoldLeft = ElytraExtra.INSTANCE.autoRescaleThreshold.get();
        double uMinX = v3.x + eMinX - zeroPointThreeTest + thresoldLeft;
        double uMaxX = v3.x + eMaxX + zeroPointThreeTest - thresoldLeft;
        double uMinY = v3.y + eMinY + thresoldLeft;
        double uMaxY = v3.y + eMaxY - thresoldLeft;
        double uMinZ = v3.z + eMinZ - zeroPointThreeTest + thresoldLeft;
        double uMaxZ = v3.z + eMaxZ + zeroPointThreeTest - thresoldLeft;
        double dx = currentMotion.x, dz = currentMotion.z;
        double exceedX = 0.0, exceedZ = 0.0;

        if (dx > 0) exceedX = dx / uMaxX;
        else if (dx < 0) exceedX = dx / uMinX; // 注意 dx 为负，uMinX 也为负，比值 >1 若 dx < uMinX

        if (dz > 0) exceedZ = dz / uMaxZ;
        else if (dz < 0) exceedZ = dz / uMinZ;

        // ??????????????????????????????????????????????????????????????????????????
        // dy

        Vec3 clampedMotion = currentMotion;
        // todo : add more angle restrict
        if (clampedMotion.y > 0) {
            clampedMotion = clampedMotion.with(Direction.Axis.Y, uMaxY);
        } else if (clampedMotion.y < 0) {
            clampedMotion = clampedMotion.with(Direction.Axis.Y, uMinY);
        }
        if (Math.abs(uMaxX) > Math.abs(uMinX)) {
            clampedMotion = clampedMotion.with(Direction.Axis.X, uMaxX);
        } else {
            clampedMotion = clampedMotion.with(Direction.Axis.X, uMinX);
        }
        if (Math.abs(uMaxZ) > Math.abs(uMinZ)) {
            clampedMotion = clampedMotion.with(Direction.Axis.Z, uMaxZ);
        } else {
            clampedMotion = clampedMotion.with(Direction.Axis.Z, uMinZ);
        }
        clampedMotion = ElytraExtra.INSTANCE.applySpeedLimit(clampedMotion);
        // 已在盒内，无需缩放

        //        Debug.info(
        //            "check"
        //                + " current=" + currentMotion
        //
        //                + " exceedX=" + exceedX
        //                + " exceedZ=" + exceedZ
        //                + " uX=[" + uMinX + ", " + uMaxX + "]"
        //                + " uZ=[" + uMinZ + ", " + uMaxZ + "]"
        //                + " candidate=" + clampedMotion+ " current horizontal=" +clampedMotion.horizontalDistance()
        //        );
        // 需要缩小至盒子边界
        if (realApply) setOverridingFireworkVelocity(clampedMotion); // <-- 保存边界值
        return clampedMotion;
    }

    public static Vec3 applyAxisLimit5_0(
            Vec3 currentMotion, float pitch, float yaw, double autoRescaleAmount, boolean apply) {
        if (currentMotion.lengthSqr() < 1E-6) {
            if (apply) setOverridingFireworkVelocity(null);
            return currentMotion;
        }
        Vec3 currentRotation = EntityUtils.pitchYawToRotation(pitch, yaw);
        if (shouldAbortV3Optimize) {
            return ElytraExtra.INSTANCE.applyAxisLimit2(currentMotion, pitch, yaw, apply);
        }
        if (!ViaFabricPlusHooks.isSupportDupRot()) {
            return applyAxisLimit3_0(currentMotion, pitch, yaw, autoRescaleAmount, apply);
        }
        Vec3 extraTargeting = null;
        if (pitch > 0) {
            if (pitch > 60) {
                return ElytraExtra.INSTANCE.applyAxisLimit2(currentMotion, pitch, yaw, apply);
            } else {
                double len = 1.01 - Math.abs(currentRotation.y);
                Vec3 targetTo = new Vec3(0, -len, 0);
                double lenSqr = targetTo.lengthSqr();
                if (lenSqr < 1) {
                    Vec3 horizontal =
                            EntityUtils.pitchYawToRotation(0, yaw).normalize().scale(Math.sqrt(1 - lenSqr));
                    targetTo = targetTo.add(horizontal);
                }
                targetTo = targetTo.normalize();
                extraTargeting = targetTo;
            }
        } else {
            if (pitch > -7) {
                return ElytraExtra.INSTANCE.applyAxisLimit2(currentMotion, pitch, yaw, apply);
            } else {
                float extraYaw = getV5ExtraYaw(currentMotion, pitch, yaw, autoRescaleAmount);
                extraTargeting = EntityUtils.pitchYawToRotation(0.0F, extraYaw);
            }
        }
        float lastPitch = PlayerStateManager.INSTANCE.lastPitch;
        float lastYaw = PlayerStateManager.INSTANCE.lastYaw;
        if (extraTargeting != null) {
            float extraPitch = EntityUtils.rotationToPitch(extraTargeting);
            float extraYaw = EntityUtils.rotationToYaw(extraTargeting);
            if (apply) packetToSend = LegacySnapRotManager.INSTANCE.createSnapAt(extraPitch, extraYaw);
            PlayerStateManager.INSTANCE.lastPitch = extraPitch;
            PlayerStateManager.INSTANCE.lastYaw = extraYaw;
        }
        Vec3 result = applyAxisLimit50_Greedy(currentMotion, pitch, yaw, autoRescaleAmount, apply);
        PlayerStateManager.INSTANCE.lastPitch = lastPitch;
        PlayerStateManager.INSTANCE.lastYaw = lastYaw;
        return result;
    }

    static {
        Listener.getPlayerTravelingTick().registerHandler(ElytraOptimizeUtils::onPreElytraMovement);
    }
}
