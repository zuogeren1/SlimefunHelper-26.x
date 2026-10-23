package me.matl114.hacks.modules.render;

import com.mojang.blaze3d.vertex.PoseStack;
import java.net.URI;
import java.util.*;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.managers.Configs;
import me.matl114.managers.config.FlagRef;
import me.matl114.utils.Debug;
import net.minecraft.ChatFormatting;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.*;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;

public class RenderExtra extends BaseModule {
    public static RenderExtra INSTANCE;
    public final ModulePath resource = makePath(Configs.RENDER_CONFIG, "resource");
    public final ModulePath serverResource = resource.add("server");
    public final ModulePath render = makePath(Configs.RENDER_CONFIG, "render");

    public RenderExtra() {
        super("RenderExtra");
        INSTANCE = this;
    }

    public final FlagRef enableRejectResourcePack =
            flagBuilder(serverResource.add("ignore-server-request")).build();

    public final FlagRef nightVision =
            builder(render.add("nightvision"), Boolean.class).defaultValue(true).build();

    public final FlagRef noWurstHud =
            flagBuilder(render.add("disable-wurst-hud")).build();

    public final FlagRef enhancedDebugHud =
            flagBuilder(render.add("enhanced-debug-hud")).build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(
                Listener.getPacketPoint().getChannel(ClientboundResourcePackPushPacket.class), this::onResourceRequest);
    }

    public void onResourceRequest(Event<ClientboundResourcePackPushPacket> resourceEvent) {
        // note that resourcePack may be sent during configuration time
        if (enableRejectResourcePack.get()) {
            Connection connection = resourceEvent.getArgs(0);
            var sendPacket = resourceEvent.context();
            connection.send(
                    new ServerboundResourcePackPacket(sendPacket.id(), ServerboundResourcePackPacket.Action.ACCEPTED));
            connection.send(new ServerboundResourcePackPacket(
                    sendPacket.id(), ServerboundResourcePackPacket.Action.DOWNLOADED));
            connection.send(new ServerboundResourcePackPacket(
                    sendPacket.id(), ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED));
            Debug.chat(
                    Component.literal("Successfully reject server resourcepack").withStyle(ChatFormatting.GREEN),
                    sendPacket.id());
            Style st = Style.EMPTY;
            try {
                st = st.withClickEvent(new ClickEvent.OpenUrl(URI.create(sendPacket.url())));
            } catch (Exception e) {
            }
            Debug.chat(
                    Component.literal("Download url:").withStyle(ChatFormatting.GREEN),
                    Component.literal(sendPacket.url()).setStyle(st).withStyle(ChatFormatting.YELLOW));
            resourceEvent.cancel();
        }
    }
}
