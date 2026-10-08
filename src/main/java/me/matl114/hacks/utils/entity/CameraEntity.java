package me.matl114.hacks.utils.entity;

import java.util.UUID;
import javax.annotation.Nonnull;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;

public class CameraEntity extends AbstractClientPlayer {
    Player player;
    GameType mode;
    boolean moveable;
    PlayerInfo entry;

    public CameraEntity(ClientLevel clientWorld, @Nonnull LocalPlayer player, GameType mode, boolean moveable) {
        super(clientWorld, player.getGameProfile());
        // avoid id collision
        // 26.2 起构造期 id 还没分配，getId() 会抛异常；改用自增负数（语义见 ClientEntityIds）
        setId(ClientEntityIds.next());
        this.mode = mode;
        this.moveable = moveable;
        setUUID(UUID.randomUUID());
        copyEquipments(player.getInventory());
        this.player = player;
        setPos(player.position());
        setXRot(player.getXRot());
        setYRot(player.getYRot());
        setOldPosAndRot();
    }

    public Inventory getInventory() {
        return this.player != null ? player.getInventory() : super.getInventory();
    }

    public void copyEquipments(Inventory p) {
        // copy inventory before we set the delegate player
        getInventory().replaceWith(p);
    }

    @Override
    public boolean isSpectator() {
        return mode == GameType.SPECTATOR;
    }

    @Override
    public boolean isCreative() {
        return mode == GameType.CREATIVE;
    }

    @Override
    protected PlayerInfo getPlayerInfo() {
        return this.player != null
                ? Minecraft.getInstance().getConnection().getPlayerInfo(this.player.getUUID())
                : null;
    }

    public float getXRot() {
        return (!moveable && player != null) ? player.getXRot() : super.getXRot();
    }

    public float getYRot() {
        return (!moveable && player != null) ? player.getYRot() : super.getYRot();
    }

    @Override
    public void tick() {
        if ((!(this.player instanceof LocalPlayer clientPlayer) || clientPlayer.connection.hasClientLoaded())) {
            this.setHealth(this.player.getHealth());
            if (!this.moveable) {
                this.setXRot(this.player.getXRot());
                this.setYRot(this.player.getYRot());
                this.setYHeadRot(this.player.getYHeadRot());
                this.setYBodyRot(this.player.getVisualRotationYInDegrees());
                this.setPos(this.player.position());
            }
            super.tick();
        }
    }

    @Override
    public void aiStep() {
        super.aiStep();
    }

    public boolean isLocalPlayer() {
        return moveable;
    }

    public boolean canSimulateMovement() {
        return moveable;
    }
}
