package me.matl114.mixins.interfaces;

import me.matl114.accessors.interfaces.TileInventory;
import me.matl114.utils.world.ContainerPosition;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Environment(EnvType.CLIENT)
@Mixin(DispenserMenu.class)
public abstract class DispenserCraftScreenHandlerMixin implements TileInventory.Handler {
    @Unique
    private BlockPos pos;

    @Unique
    public BlockPos getPos() {
        return this.pos;
    }

    @Unique
    private Block cacheBlockType;

    @Unique
    public Block getBlockType() {
        return cacheBlockType;
    }

    @Unique
    private ClientLevel world;

    @Unique
    public ClientLevel getWorld() {
        return this.world;
    }

    @Unique
    private ContainerPosition containerPosition;

    @Unique
    public ContainerPosition getContainerPosition() {
        return this.containerPosition;
    }

    @Override
    public void sync(TileInventory tileInventory) {
        this.pos = tileInventory.getPos();
        this.cacheBlockType = tileInventory.getBlockType();
        this.world = tileInventory.getWorld();
        this.containerPosition = tileInventory.getContainerPosition();
    }
}
