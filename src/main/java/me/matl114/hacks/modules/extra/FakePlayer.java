package me.matl114.hacks.modules.extra;

import com.mojang.authlib.GameProfile;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.*;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import me.matl114.accessors.access.PlayerInteractEntityC2SPacketAccess;
import me.matl114.accessors.events.ClientConnectionAccess;
import me.matl114.accessors.hacks.EntityInternalAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.gui.Constants;
import me.matl114.gui.basic.*;
import me.matl114.gui.elements.IconElement;
import me.matl114.gui.interfaces.ElementFactory;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.combat.SpearEnhance;
import me.matl114.hacks.modules.move.PlayerStateManager;
import me.matl114.hacks.utils.config.EntryPrimitiveMap;
import me.matl114.hacks.utils.config.NBTTypes;
import me.matl114.hacks.utils.config.StringFormat;
import me.matl114.hacks.utils.entity.FakePlayerEntity;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.*;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.*;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.stream.Streams;

public class FakePlayer extends BaseModule {
    public FakePlayer() {
        super("FakePlayer");
        bindFlag(enable);
    }

    public final ModulePath root = makePath(Configs.EXTRA_CONFIG, "other.fake-player");

    public final FlagRef enable = flagBuilder(root.addEnable()).hideConfig().build();

    public final KeyBindRef hotkey =
            toggleHotkey(root.addHotkey(), new MultiKeyBind(), root.addEnable()).build();

    public final FlagRef removeIfPresent =
            flagBuilder(root.add("remove-if-present"), true).build();

    public final StringRef name = builder(root.add("name"), StringRef.TYPE)
            .defaultValue("juhaoshabi666")
            .build();

    public final FlagRef hasPhysics = flagBuilder(root.add("has-physics")).build();

    public final FlagRef copyEquipment = flagBuilder(root.add("copy-equipment")).build();

    public final DoubleRef maxHealth = doubleBuilder(root.add("max-health"))
            .defaultValue(20.0D)
            .validator(Configs.doubleRange(0.0D, Double.MAX_VALUE))
            .build();

    public final FlagRef autoTotem = flagBuilder(root.add("auto-totem")).build();

    public final FlagRef regeneration = flagBuilder(root.add("regeneration")).build();

    public final FlagRef overrideEffect =
            flagBuilder(root.add("override-effects")).build();

    public final NBTRef<EntryPrimitiveMap<MobEffect, Integer>> constantEffects = builder(
                    root.add("constant-effects"), EntryPrimitiveMap.<MobEffect, Integer>parameter())
            .defaultValue(new EntryPrimitiveMap<>(BuiltInRegistries.MOB_EFFECT, NBTTypes.INT_TYPE, Map.of()))
            .build();

    public final FlagRef logHit = flagBuilder(root.add("log-hit")).build();

    public final NBTRef<StringFormat> hitLog = builder(root.add("hit-format"), StringFormat.class)
            .defaultValue(new StringFormat(
                    List.of("name", "damage_type", "damage", "final_damage"),
                    "&f{name} was hit, hp-{final_damage}",
                    true))
            .build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundAttackPacket.class),
                this::onHit,
                Integer.MIN_VALUE);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundInteractPacket.class),
                this::onInteract,
                Integer.MAX_VALUE);
        registerListener(
                Listener.getPacketPreHandlePoint().getChannel(ClientboundExplodePacket.class),
                this::onExplode,
                Integer.MIN_VALUE);
        registerListener(Listener.getPreGameTick(), this::onTickKinetic);
    }

    @Override
    public void onEnableModule() {
        super.onEnableModule();
        if (!removeIfPresent.get()) enable.set(false);
        if (checkNull()) {
            return;
        }
        if (removeIfPresent.get() && Streams.of(mc.level.entitiesForRendering()).anyMatch(s -> s instanceof FakePlayerEntity)) {
            Streams.of(mc.level.entitiesForRendering())
                    .filter(s -> s instanceof FakePlayerEntity)
                    .map(FakePlayerEntity.class::cast)
                    .forEach(this::removeFakePlayer);
            return;
        }
        createNewFakePlayer();
    }

    @Override
    public void onDisableModule() {
        super.onDisableModule();
        if (removeIfPresent.get()) {
            Streams.of(mc.level.entitiesForRendering())
                    .filter(s -> s instanceof FakePlayerEntity)
                    .map(FakePlayerEntity.class::cast)
                    .forEach(this::removeFakePlayer);
            return;
        }
    }

    @Override
    public void addCustomWidgets(
            Consumer<DrawableWidget> acceptor,
            int dx,
            int dy,
            int dblank,
            ElementFactory factory) {
        super.addCustomWidgets(acceptor, dx, dy, dblank, factory);
        if (mc.level == null) {
            acceptor.accept(factory.createTitle("widget.fake-player.fake-player-list.enter-world", 0, dblank, dx, dy));
            return;
        }
        acceptor.accept(factory.createTitle("widget.fake-player.fake-player-list.title", 0, dblank, dx, dy));
        DynamicListWidget list = new DynamicListWidget(0, dblank, dx);
        for (var re : mc.level.entitiesForRendering()) {
            if (re instanceof FakePlayerEntity fakePlayer) {
                createWidgetForFakePlayer(fakePlayer, list, dx, dy, dblank);
            }
        }
        int dxx = (dx - dy) / 2;
        acceptor.accept(list);
        acceptor.accept(ExecutableWidget.instance(dxx, dblank, dy, dy)
                .setElementHandler(IconElement.fixedGui(Constants.ADD_SPRITE, ButtonAction.run(() -> {
                            FakePlayerEntity fakePlayer = createNewFakePlayer();
                            createWidgetForFakePlayer(fakePlayer, list, dx, dy, dblank);
                        }))
                        .withTooltips(TooltipHandler.of(ChatUtils.parseTooltipsTranslation(
                                "widget.fake-player.fake-player-list.add.tooltips", "")))));
    }

    private DrawableWidget createWidgetForFakePlayer(
            FakePlayerEntity fakePlayer, @Nullable DynamicListWidget list, int dx, int dy, int dblank) {
        DynamicContentWidget<DrawableWidget> widget = new DynamicContentWidget<>(() -> null, 0, 0);
        SubScreenWidget subScreenWidget = new SubScreenWidget(0, 0, dx, dy + dblank);
        subScreenWidget.addDrawableChild(createExecuteButton(
                () -> Component.translatable(
                        "widget.fake-player.fake-player-list.info",
                        fakePlayer.getDisplayName(),
                        "%.2f".formatted(fakePlayer.getX()),
                        "%.2f".formatted(fakePlayer.getY()),
                        "%.2f".formatted(fakePlayer.getZ()),
                        fakePlayer.getHealth()),
                List::of,
                ButtonAction.empty(),
                0,
                dblank,
                dx - dy,
                dy));
        subScreenWidget.addDrawableChild(ExecutableWidget.instance(dx - dy, dblank, dy, dy)
                .setElementHandler(IconElement.fixedGui(Constants.REMOVE_SPRITE, ButtonAction.run(() -> {
                            if (list != null) list.remove(widget);
                            removeFakePlayer(fakePlayer);
                        }))
                        .withTooltips(TooltipHandler.of(ChatUtils.parseTooltipsTranslation(
                                "widget.fake-player.fake-player-list.remove.tooltips", "")))));
        widget.setContentSupplier(() -> !fakePlayer.isRemoved() ? subScreenWidget : null);
        if (list != null) {
            list.addDrawableChild(widget);
        }
        return widget;
    }

    private void removeFakePlayer(FakePlayerEntity fakePlayer) {
        fakePlayer.setHealth(0.0F);
        mc.level.removeEntity(fakePlayer.getId(), Entity.RemovalReason.KILLED);
        fakePlayer.setRemoved(Entity.RemovalReason.KILLED);
    }

    private FakePlayerEntity createNewFakePlayer() {
        FakePlayerEntity fakePlayer = new FakePlayerEntity(mc.level, new GameProfile(UUID.randomUUID(), name.get()));
        fakePlayer.setFreeze(!hasPhysics.get());
        fakePlayer.getAttributes().getInstance(Attributes.MAX_HEALTH).setBaseValue(maxHealth.get());
        fakePlayer.setTickTask(this::onFakePlayerTick);
        if (copyEquipment.get()) {
            fakePlayer.copyEquipmentFrom(mc.player);
        }
        fakePlayer.copyDataFrom(mc.player);
        // enable absorption
        fakePlayer.getAttributes().getInstance(Attributes.MAX_ABSORPTION).setBaseValue(1024);
        mc.level.addEntity(fakePlayer);
        return fakePlayer;
    }

    private void onFakePlayerTick(FakePlayerEntity fakePlayer) {
        if (fakePlayer.isDeadOrDying()) {
            Tasks.scheduleDelayed(() -> removeFakePlayer(fakePlayer), 0);
            return;
        }
        if (fakePlayer.hasEffect(MobEffects.REGENERATION)) {
            MobEffectInstance instance = fakePlayer.getEffect(MobEffects.REGENERATION);
            if (instance != null) {
                int duration = instance.getDuration();
                int amplifier = instance.getAmplifier();
                int i = 50 >> amplifier;
                if (i == 0 || duration % i == 0) {
                    if (fakePlayer.getHealth() < fakePlayer.getMaxHealth()) {
                        fakePlayer.heal(1.0F);
                    }
                }
            }
        }
        if (autoTotem.get()) {
            fakePlayer.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        }
        if (regeneration.get()) {
            fakePlayer.setHealth(fakePlayer.getMaxHealth());
        }
        if (overrideEffect.get()) {
            var effectMap = constantEffects.get();
            BuiltInRegistries.MOB_EFFECT.listElements().forEach(s -> {
                Integer val = effectMap.getEntryValue(s.value());
                if (val != null && val > 0) {
                    if (!fakePlayer.hasEffect(s)) {
                        fakePlayer.forceAddEffect(new MobEffectInstance(s, 114514, val - 1), null);
                    }
                } else {
                    if (fakePlayer.hasEffect(s)) {
                        fakePlayer.removeEffect(s);
                    }
                }
            });
        }
    }

    public void onDamage(FakePlayerEntity fakePlayer, DamageSource source, float rawDamage) {
        float realDamage = fakePlayer.damage(rawDamage, source);
        if (realDamage > 0) {
            var packet = new ClientboundDamageEventPacket(fakePlayer, source);
            ClientConnectionAccess.of(mc.getConnection().getConnection())
                    .applyPacket(packet, mc.getConnection());
            if (logHit.get()) {
                log(hitLog.get()
                        .formatText(
                                fakePlayer.getDisplayName(),
                                source.typeHolder().unwrapKey().get().identifier().getPath(),
                                rawDamage,
                                realDamage));
            }
        }
    }

    private void onHit(Event<ServerboundAttackPacket> attackPacket) {
        if (checkNull()) return;
        // 26.2: 攻击语义由独立的 ServerboundAttackPacket 承载
        if (mc.level.getEntity(attackPacket.context.entityId()) instanceof FakePlayerEntity fake) {
            attackPacket.cancel();
            onAttack(fake);
            PlayerStateManager.INSTANCE.sendSprintStatus(false, false);
        }
    }

    private void onInteract(Event<ServerboundInteractPacket> interactPacket) {
        if (checkNull()) return;
        // 右键假人：假人是客户端实体，服务端没有它
        if (mc.level.getEntity(interactPacket.context.entityId()) instanceof FakePlayerEntity) {
            interactPacket.cancel();
        }
    }

    private void onAttack(FakePlayerEntity fake) {
        ItemStack weapon = mc.player.getItemInHand(InteractionHand.MAIN_HAND);
        // todo: add other types
        float damage = DamageUtils.getRealAttackDamage(
                mc.player,
                fake,
                weapon,
                PlayerStateManager.INSTANCE.fallDistance,
                PlayerStateManager.INSTANCE.getTicksSinceLastAttack(),
                false);
        onDamage(fake, DamageUtils.createDirectDamageSource(DamageTypes.PLAYER_ATTACK, mc.player), damage);
    }

    private void onExplode(Event<ClientboundExplodePacket> event) {
        if (checkNull()) return;
        List<FakePlayerEntity> fakes = new ArrayList<>();
        for (var re : mc.level.entitiesForRendering()) {
            if (re instanceof FakePlayerEntity) {
                fakes.add((FakePlayerEntity) re);
            }
        }
        if (fakes.isEmpty()) {
            return;
        }
        BlockPos explodeCenter = BlockPos.containing(event.context.center());
        float radius = event.context.radius();
        BlockState explosionState = mc.level.getBlockState(explodeCenter);
        boolean centeredExplosion = Objects.equals(event.context.center(), Vec3.atCenterOf(explodeCenter));
        boolean respawnAnchor = centeredExplosion && explosionState.getBlock() instanceof RespawnAnchorBlock;
        boolean bed = centeredExplosion && explosionState.getBlock() instanceof BedBlock;
        if (respawnAnchor || bed) {
            if (radius <= 0) {
                radius = respawnAnchor ? ExplosionUtils.RESPAWN_ANCHOR_POWER : ExplosionUtils.BED_POWER;
            }
        } else {
            if (radius <= 0) {
                radius = ExplosionUtils.END_CRYSTAL_POWER;
            }
        }
        ExplosionUtils.BlockStateAccess blockAccess =
                ExplosionUtils.fromWorldWithAutomaticOverrides(mc.level, event.context.center());
        for (var re : fakes) {
            float damage = ExplosionUtils.calculateExplosionRawDamage(
                    radius, event.context.center(), re.getBoundingBox(), blockAccess, ExplosionUtils.ALL_TERRAIN);
            if (damage > 0) {
                onDamage(
                        re,
                        DamageUtils.createDamageSource(
                                respawnAnchor || bed ? DamageTypes.BAD_RESPAWN_POINT : DamageTypes.PLAYER_ATTACK,
                                null,
                                mc.player),
                        damage);
            }
        }
    }

    protected Object2LongMap<Entity> piercingCooldowns = new Object2LongOpenHashMap<>();

    private void onTickKinetic(Event<LocalPlayer> event) {
        if (checkNull()) return;
        if (SpearEnhance.isUsingSpear(mc.player) && SpearEnhance.canSpearKineticAttack(mc.player)) {
            Vec3 startEye = new Vec3(
                            PlayerStateManager.INSTANCE.lastX,
                            PlayerStateManager.INSTANCE.lastY,
                            PlayerStateManager.INSTANCE.lastZ)
                    .add(0, mc.player.getEyeHeight(mc.player.getPose()), 0);
            Vec3 direction = PlayerStateManager.INSTANCE.getLastRotationVector();
            double minRange = 2.0;
            double maxRange = 4.5;
            Vec3 movement = PlayerStateManager.INSTANCE.lastKnownRealMovementSpeed;
            double speedBonus = Math.max(0, movement.dot(direction));
            double finalMaxRange = maxRange + speedBonus;
            Vec3 startPoint = startEye.add(direction.scale(minRange));
            Vec3 endPoint = startEye.add(direction.scale(finalMaxRange));
            float hitboxMargin = 0.125F;
            AABB box = AABB.ofSize(startPoint, (double) hitboxMargin, (double) hitboxMargin, (double) hitboxMargin)
                    .expandTowards(endPoint.subtract(startPoint))
                    .inflate(1.0);
            float max = Math.max(0, hitboxMargin);
            for (var e : mc.level.getEntities(mc.player, box)) {
                if (e instanceof FakePlayerEntity livingEntity) {
                    if (livingEntity.getBoundingBox().clip(startPoint, endPoint).isPresent()) {
                        onSpearKinetic(livingEntity, direction);
                    } else if (max > 0) {
                        var box2 = livingEntity.getBoundingBox().inflate(hitboxMargin);
                        var re = box2.clip(startPoint, endPoint);
                        if (re.isPresent()) {
                            Vec3 vec3d = re.get();
                            Vec3 vec3d2 = box2.getCenter();
                            Optional<Vec3> optional3 =
                                    livingEntity.getBoundingBox().clip(vec3d, vec3d2);
                            if (optional3.isPresent()) {
                                onSpearKinetic(livingEntity, direction);
                            }
                        }
                    }
                }
            }
        } else {
            piercingCooldowns.clear();
        }
    }

    public boolean isInPiercingCooldown(Entity target, int cooldownTicks) {
        if (this.piercingCooldowns.isEmpty()) {
            return false;
        } else if (this.piercingCooldowns.containsKey(target)) {
            return mc.level.getGameTime() - this.piercingCooldowns.getLong(target) < (long) cooldownTicks;
        } else {
            return false;
        }
    }

    public void startPiercingCooldown(Entity target) {
        this.piercingCooldowns.put(target, mc.level.getGameTime());
    }

    private void onSpearKinetic(FakePlayerEntity fakePlayer, Vec3 rotation) {
        if (isInPiercingCooldown(fakePlayer, 10)) {
            return;
        }
        startPiercingCooldown(fakePlayer);
        double d = rotation.dot(PlayerStateManager.INSTANCE.lastKnownRealMovementSpeed.scale(20.0));
        Vec3 predictorMovement =
                EntityInternalAccess.of(fakePlayer).getPositionPredictor().getKnownDeltaMovement();
        double g = rotation.dot(predictorMovement.scale(20.0D));
        double h = Math.max(0.0, d - g);
        boolean canDamage = h > 4.6;
        if (!canDamage) {
            return;
        }
        double e = mc.player.getAttributeBaseValue(Attributes.ATTACK_DAMAGE);
        // use netherite spear data
        float damage = (float) (e + Mth.floor(h * 1.2F));
        onDamage(
                fakePlayer,
                DamageUtils.createDirectDamageSource(
                        ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.withDefaultNamespace("spear")),
                        mc.player),
                damage);
    }
}
