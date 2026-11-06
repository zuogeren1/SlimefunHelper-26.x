package me.matl114.mixins.fix;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Environment(EnvType.CLIENT)
@Mixin(ClientPacketListener.class)
public abstract class ParticleCountLimitMixin {

    @ModifyVariable(method = "handleParticleEvent", at = @At("HEAD"), index = 1, argsOnly = true)
    private ClientboundLevelParticlesPacket onParticle(ClientboundLevelParticlesPacket packet) {
        //#if MC >= 260300
        //$$ if (packet.count() > 1000) {
        //$$     return new ClientboundLevelParticlesPacket(
        //$$             packet.particle(),
        //$$             packet.overrideLimiter(),
        //$$             packet.alwaysShow(),
        //$$             packet.x(),
        //$$             packet.y(),
        //$$             packet.z(),
        //$$             packet.xDist(),
        //$$             packet.yDist(),
        //$$             packet.zDist(),
        //$$             packet.xMaxSpeed(),
        //$$             packet.yMaxSpeed(),
        //$$             packet.zMaxSpeed(),
        //$$             1000,
        //$$             packet.randomizationType());
        //$$ }
        //#else
        if (packet.getCount() > 1000) {
            return new ClientboundLevelParticlesPacket(
                    packet.getParticle(),
                    packet.isOverrideLimiter(),
                    packet.alwaysShow(),
                    packet.getX(),
                    packet.getY(),
                    packet.getZ(),
                    packet.getXDist(),
                    packet.getYDist(),
                    packet.getZDist(),
                    packet.getMaxSpeed(),
                    1000);
        }
        //#endif
        return packet;
    }
}
