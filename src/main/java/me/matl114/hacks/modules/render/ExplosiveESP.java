package me.matl114.hacks.modules.render;

import com.mojang.blaze3d.vertex.PoseStack;
import me.matl114.events.Event;
import me.matl114.events.impl.Render3D;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.combat.CombatManager;
import me.matl114.hacks.utils.config.TracingOption;
import me.matl114.hacks.utils.config.WrapColor;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.hacks.utils.render.RenderElements;
import me.matl114.managers.Configs;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.*;
import me.matl114.utils.render.RenderCollector;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class ExplosiveESP extends BaseModule {
    public ExplosiveESP() {
        super("ExplosiveESP");
        bindFlag(enable);
    }

    public final ModulePath root = makePath(Configs.RENDER_CONFIG, "combat-render.explosive-esp");

    public final FlagRef enable = flagBuilder(root.addEnable()).build();

    public final KeyBindRef hotkey =
            toggleHotkey(root.addHotkey(), new MultiKeyBind(), root.addEnable()).build();

    public final IntRef distance = intBuilder(root.add("distance"))
            .defaultValue(16)
            .validator(Configs.INT_POSITIVE)
            .build();

    public final NBTRef<TracingOption> option = builder(root.add("options"), TracingOption.class)
            .defaultValue(new TracingOption(true, false))
            .build();

    public final NBTRef<WrapColor> anchorColor = builder(root.add("anchor-color"), WrapColor.class)
            .defaultValue(new WrapColor(ChatFormatting.GOLD))
            .build();

    public final NBTRef<WrapColor> crystalColor = builder(root.add("crystal-color"), WrapColor.class)
            .defaultValue(new WrapColor(ChatFormatting.LIGHT_PURPLE))
            .build();

    public final FlagRef highlightCrystal =
            flagBuilder(root.add("highlight-crystal")).build();

    private final RenderCollector<AABB> boxOutlineCollector = RenderCollectors.createBoxCollector(true, false, false);
    private final RenderCollector<Entity> faceCollector = RenderCollectors.createEntityFaceCollector();
    private final RenderCollector<RenderElements.Text> textCollector = RenderCollectors.createTextCollector();
    private final RenderCollector<Vec3> traceCollector = RenderCollectors.createTracerCollector();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPostGameTick(), this::onTick);
        registerListener(RenderListener.getRender3DEvent(), this::onRender);
        registerListener(CombatManager.getRequestEnableEvent(), this::onRequestCombatService);
    }

    public void onTick() {
        boxOutlineCollector.clear();
        faceCollector.clear();
        textCollector.clear();
        traceCollector.clear();
        if (checkNull() || !enable.get()) {
            return;
        }
        var anchors = CombatManager.INSTANCE.trackedExplosives;
        var crystals = CombatManager.INSTANCE.trackedEndCrystals;
        double maxDistanceSq = MathUtils.s2(distance.get());
        TracingOption tracingOption = option.get();
        int anchorRgb = anchorColor.get().color().getValue();
        int crystalRgb = crystalColor.get().color().getValue();

        for (var pos : anchors.keySet()) {
            if (pos.distToCenterSqr(mc.player.position()) > maxDistanceSq) {
                continue;
            }
            AABB box = new AABB(pos);
            if (tracingOption.box()) {
                boxOutlineCollector.submit(box, ColorUtils.withAlphaInt(anchorRgb, 255));
            }
            if (tracingOption.line()) {
                traceCollector.submit(box.getCenter(), ColorUtils.withAlphaInt(anchorRgb, 255));
            }
            submitDamageText(Vec3.atCenterOf(pos), 5.0F, anchorRgb);
        }

        for (var crystal : crystals) {
            if (crystal.position().distanceToSqr(mc.player.position()) > maxDistanceSq) {
                continue;
            }

            submitDamageText(crystal.position(), 6.0F, crystalRgb);
        }
        for (var crystal : mc.level.entitiesForRendering()) {
            if (crystal instanceof EndCrystal end) {
                if (highlightCrystal.get()) {
                    faceCollector.submit(crystal, ColorUtils.withAlphaInt(crystalRgb, 64));
                }
                if (tracingOption.box()) {
                    boxOutlineCollector.submit(crystal.getBoundingBox(), ColorUtils.withAlphaInt(crystalRgb, 160));
                }

                if (tracingOption.line()) {
                    traceCollector.submit(
                            crystal.getBoundingBox().getCenter(), ColorUtils.withAlphaInt(crystalRgb, 255));
                }
            }
        }
    }

    private void submitDamageText(Vec3 explosionPos, float power, int color) {
        float damage = power == ExplosionUtils.RESPAWN_ANCHOR_POWER
                ? ExplosionUtils.respawnAnchorDamage(
                        mc.player.getBoundingBox(), explosionPos, mc.level, ExplosionUtils.ALL_TERRAIN)
                : ExplosionUtils.crystalDamage(
                        mc.player.getBoundingBox(), explosionPos, mc.level, ExplosionUtils.ALL_TERRAIN);
        textCollector.submit(
                new RenderElements.Text(
                        Component.literal("%.1f/%.1f/%.1f"
                                .formatted(
                                        damage,
                                        DamageUtils.getMultipliedDamageByDifficulty(mc.level, damage),
                                        DamageUtils.getFinalDamage(
                                                mc.player,
                                                damage,
                                                DamageUtils.createDamageSource(
                                                        DamageTypes.PLAYER_EXPLOSION, null, mc.player)))),
                        explosionPos,
                        0.66F),
                ColorUtils.withAlphaInt(color, 255));
    }

    public void onRender(Event<Render3D> event) {
        if (checkNull() || !enable.get()) {
            return;
        }
        PoseStack stack = event.context().stack();
        RenderUtils.startDrawVirtual(stack);
        try {
            textCollector.render3D(stack, event.context().partialTicks());
            faceCollector.render3D(stack, event.context().partialTicks());
            boxOutlineCollector.render3D(stack, event.context().partialTicks());
            traceCollector.render3D(stack, event.context().partialTicks());
        } finally {
            RenderUtils.stopDrawVirtual(stack);
        }
    }

    private void onRequestCombatService(Event<CombatManager.Service> event) {
        event.context().enableExplosiveSearch(enable.get());
    }
}
