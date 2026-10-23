package me.matl114.hacks.modules.survival;

import java.util.UUID;
import me.matl114.accessors.access.ProjectileAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.events.impl.Render3D;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.utils.config.WrapColor;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.hacks.utils.render.RenderElements;
import me.matl114.managers.Configs;
import me.matl114.managers.config.DoubleRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.RenderUtils;
import me.matl114.utils.render.RenderCollector;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.Vec3;

public class PearlESP extends BaseModule {
    public static PearlESP INSTANCE;

    public final ModulePath renderUtils = makePath(Configs.SURVIVAL_CONFIG, "render-utils");
    public final ModulePath pearlEsp = renderUtils.add("pearl-esp");

    public PearlESP() {
        super("PearlESP");
        INSTANCE = this;
        bindFlag(enable);
    }

    public final FlagRef enable = flagBuilder(pearlEsp.addEnable()).build();

    public final KeyBindRef hotkey = toggleHotkey(pearlEsp.addHotkey(), new MultiKeyBind(), pearlEsp.addEnable())
            .build();

    public final DoubleRef textScale = doubleBuilder(pearlEsp.add("text-scale"))
            .defaultValue(0.75D)
            .validator(Configs.doubleRange(0.1D, 4.0D))
            .build();

    public final NBTRef<WrapColor> color = builder(pearlEsp.add("color"), WrapColor.class)
            .defaultValue(new WrapColor(ChatFormatting.AQUA))
            .build();

    private final RenderCollector<RenderElements.Text> textCollector = RenderCollectors.createTextCollector();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPostGameTick(), this::onTick);
        registerListener(RenderListener.getRender3DEvent(), this::onRender3D);
    }

    @Override
    public void onDisableModule() {
        super.onDisableModule();
        textCollector.clear();
    }

    public void onTick() {
        textCollector.clear();
        if (checkNull() || !enable.get() || WorldManager.INSTANCE == null) {
            return;
        }

        int textColor = color.get().withAlpha(255);
        float scale = (float) textScale.get();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof ThrownEnderpearl pearl) {
                Component displayText = buildText(pearl);
                if (displayText == null) {
                    continue;
                }
                Vec3 textPos = pearl.position();
                textCollector.submit(new RenderElements.Text(displayText, textPos, scale), textColor);
            }
        }
    }

    public void onRender3D(Event<Render3D> event) {
        if (!enable.get()) {
            return;
        }
        RenderUtils.startDrawVirtual(event.context().stack());
        try {
            textCollector.render3D(event.context().stack(), event.context().partialTicks());
        } finally {
            RenderUtils.stopDrawVirtual(event.context().stack());
        }
    }

    private Component buildText(ThrownEnderpearl villager) {
        if (villager.getOwner() instanceof Player pl) {
            return Component.empty()
                    .append(pl.getScoreboardName())
                    .append(Component.translatable("message.module.pearl-esp.display.online"));
        }
        ProjectileAccess access = ProjectileAccess.of(villager);
        boolean hasOwner;
        boolean online;
        UUID uid = WorldManager.INSTANCE.getThrownEntityOwner(villager);
        hasOwner = uid != null || access.getOwnerEid().isPresent();
        if (uid != null && mc.getConnection().getPlayerInfo(uid) != null) {
            online = true;
        } else if (access.getOwnerEid().isPresent()) {
            online = true;
        } else {
            online = false;
        }
        if (!hasOwner && !online) {
            return Component.translatable("message.module.pearl-esp.display.no-owner");
        } else {
            MutableComponent txt = Component.empty();
            String name = WorldManager.INSTANCE.getThrownEntityOwnerName(villager);
            name = name == null ? "" : name;
            txt = txt.append(name);
            if (online) {
                txt = txt.append(Component.translatable("message.module.pearl-esp.display.online"));
            } else {
                txt = txt.append(Component.translatable("message.module.pearl-esp.display.offline"));
            }
            return txt;
        }
    }
}
