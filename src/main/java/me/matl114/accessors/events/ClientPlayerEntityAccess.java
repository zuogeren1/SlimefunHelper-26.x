package me.matl114.accessors.events;

import javax.annotation.Nonnull;
import me.matl114.accessors.access.LivingEntityAccess;
import me.matl114.hacks.utils.entity.LegalMovementManager;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public interface ClientPlayerEntityAccess extends LivingEntityAccess<LocalPlayer> {
    public LegalMovementManager getLegalMovementManager();

    public void onPlayerInputPackets();

    default void resyncSprint() {
        setLastSprintFlag(!((Entity) this).isSprinting());
    }

    public void setLastSprintFlag(boolean lastSprint);

    default void resyncSneak() {
        setLastSneakFlag(!((LocalPlayer) this).input.keyPresses.shift());
    }

    public void setLastSneakFlag(boolean lastSprint);

    default void resyncOnGround() {
        setLastOnGroundFlag(!((Entity) this).onGround());
    }

    public void setLastOnGroundFlag(boolean lastOnGround);

    default void resyncPos() {
        setLastPos(Vec3.ZERO);
    }

    public void setLastPos(Vec3 vec3d);

    default void resyncRot() {
        setLastRot(0, 0);
    }

    public void setLastRot(float pitch, float yaw);

    public void setResyncMovementPacketTicks(int ticks);

    public void setForceSendPacketUpdate(boolean force);

    public void resyncInput();

    @Nonnull
    public static ClientPlayerEntityAccess of(@Nonnull LocalPlayer player) {
        return (ClientPlayerEntityAccess) player;
    }
}
