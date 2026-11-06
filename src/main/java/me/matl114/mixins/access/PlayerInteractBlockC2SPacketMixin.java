package me.matl114.mixins.access;

import lombok.Getter;
import lombok.Setter;
import me.matl114.accessors.access.PlayerInteractBlockC2SPacketAccess;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;

@Setter
@Getter
@Environment(EnvType.CLIENT)
@Mixin(ServerboundUseItemOnPacket.class)
public abstract class PlayerInteractBlockC2SPacketMixin implements PlayerInteractBlockC2SPacketAccess {

    @Override
    @Mutable
    @Accessor("hand")
    public abstract void setHand(InteractionHand hand);

    @Override
    @Mutable
    //#if MC >= 260300
    //$$ @Accessor("hitResult")
    //#else
    @Accessor("blockHit")
    //#endif
    public abstract void setBlockHitResult(BlockHitResult blockHitResult);

    @Override
    @Mutable
    @Accessor("sequence")
    public abstract void setSequence(int sequence);

    @Unique
    UseContext useContext;
}
