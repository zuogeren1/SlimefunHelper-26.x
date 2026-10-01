package me.matl114.hacks.modules.combat;

import java.util.ArrayList;
import java.util.List;
import me.matl114.accessors.access.PlayerInteractItemC2SPacketAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.events.impl.EventContainer;
import me.matl114.hacks.CombatTasks;
import me.matl114.hacks.MovTasks;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.api.ModulePreset;
import me.matl114.hacks.utils.config.Regex;
import me.matl114.hacks.utils.enums.LegalInteractMode;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.*;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.utils.Debug;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.utils.InventoryUtils;
import me.matl114.utils.ItemStackUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.*;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.LingeringPotionItem;
import net.minecraft.world.item.SplashPotionItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public class ProjectileEnhance extends BaseModule {
    public final ModulePath projectile = makePath(Configs.COMBAT_CONFIG, "projectile");

    public ProjectileEnhance() {
        super("ProjectileEnhance");
        bindFlag(enable);
    }

    public FlagRef enable = flagBuilder(projectile.add("projectile-enhance")).build();

    public KeyBindRef hotkey = moduleEntry(
                    projectile.add("projectile-enhance-hotkey"),
                    new MultiKeyBind(),
                    projectile.add("projectile-enhance"))
            .build();

    public FlagRef enableAim = flagBuilder(projectile.add("aim-enable")).build();

    public FlagRef enableTp = flagBuilder(projectile.add("tp-enable")).build();

    public EnumRef<LegalInteractMode> mode = builder(projectile.add("targeting-mode"), LegalInteractMode.class)
            .defaultValue(LegalInteractMode.USEITEM_PACKET)
            .build();

    public DoubleRef tpDistance = builder(projectile.add("tp-accelerate"), DoubleRef.TYPE)
            .defaultValue(150.0D)
            .show(enableTp::get)
            .build();

    public FlagRef enhanceTp = flagBuilder(projectile.add("tp-accelerate-exact-tp"))
            .show(enableTp::get)
            .build();

    public NBTRef<Regex> useItemId = builder(projectile.add("tp-accelerate-exact-tp"), Regex.class)
            .defaultValue(new Regex("^(LOGITECH_LASER_GUN)$"))
            .build();

    public FlagRef tridentDupe =
            flagBuilder(projectile.add("trident-auto-dupe")).build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundPlayerActionPacket.class), this::onTridentDupe);
        registerListener(
                Listener.getPacketPoint().getChannel(ServerboundUseItemPacket.class), this::onPlayerInteractItem);
        registerListener(Listener.getCustomListener().getChannel(ModulePreset.class), this::onModulePreset);
    }

    public void onModulePreset(Event<EventContainer<ModulePreset>> event) {
        mode.set(LegalInteractMode.getFromPreset(event.context.getValue()));
    }

    public static float getShootingPowerCrossbow(ItemStack a) {
        ChargedProjectiles stack = a.get(DataComponents.CHARGED_PROJECTILES);
        return (stack != null && stack.contains(Items.FIREWORK_ROCKET)) ? 1.6F : 3.15F;
    }

    public void onTridentDupe(Event<ServerboundPlayerActionPacket> actionC2SPacketEvent) {
        var actionC2SPacket = actionC2SPacketEvent.context();
        if (actionC2SPacket.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM
                && mc.player != null
                && tridentDupe.get()
                && mc.player.getMainHandItem().getItem() instanceof TridentItem trident) {
            // dupe trident
            mc.gameMode.handleContainerInput(
                    mc.player.containerMenu.containerId,
                    3,
                    InventoryUtils.getSelectedSlot(),
                    ContainerInput.SWAP,
                    mc.player);
            Tasks.scheduleDelayed(
                    () -> mc.gameMode.handleContainerInput(
                            mc.player.containerMenu.containerId,
                            3,
                            InventoryUtils.getSelectedSlot(),
                            ContainerInput.SWAP,
                            mc.player),
                    1);
        }
    }

    private boolean passUseItemIdCheck(ItemStack stack) {

        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (useItemId.get().test(id)) {
            return true;
        }
        String sfid = ItemStackUtils.getSfId(stack);

        return sfid != null && useItemId.get().test(sfid);
    }
    // todo: test crossbow, may wrong
    public void onPlayerInteractItem(Event<ServerboundUseItemPacket> packetMutableObject) {
        // targeting
        if (packetMutableObject.isCancelled()) return;

        if (enable.get()) {
            ServerboundUseItemPacket packet = packetMutableObject.context();
            InteractionHand hand = packet.getHand();
            ItemStack stack = PlayerInteractItemC2SPacketAccess.of(packetMutableObject.context)
                    .getItemStack();
            // access to the item before it is used up to 0 count
            if (!stack.isEmpty()) {
                // make a stackCopy of origin item with 1 count
                if (enableAim.get()) {
                    // pass check, autoaim
                    boolean makeReaim = false;
                    float velocity = 3600000000f;
                    if (passUseItemIdCheck(stack)) {
                        // line predict
                        // can override crossbow-like items

                        makeReaim = true;
                    } else if (stack.getItem() instanceof CrossbowItem
                            || stack.getItem() instanceof SplashPotionItem
                            || stack.getItem() instanceof LingeringPotionItem) {
                        // aim crossbow, SplashPotion, LingerPotion
                        // direct = false;
                        makeReaim = true;
                        velocity = getShootingPowerCrossbow(stack);
                    }
                    if (makeReaim) {
                        Entity target = CombatTasks.getTargetSelector().searchAimableEntity(false);
                        if (target != null) {
                            Debug.chat(Component.literal("[Proj Aim] Aim at %s"
                                            .formatted(target instanceof Player player ? "player " : "entity "))
                                    .append(EntityUtils.getEntityDisplayable(target))
                                    .withStyle(ChatFormatting.GREEN));

                            Vec3 facing = CombatTasks.getPositionPredict()
                                    .predictAimPositionForEntity(target, velocity)
                                    .subtract(mc.player.getEyePosition());
                            Vec2 redirectTarget = CombatTasks.calculatePitchYawPredict(velocity, Vec3.ZERO, facing);
                            if (Float.isNaN(redirectTarget.x)
                                    || Float.isInfinite(redirectTarget.x)
                                    || Float.isNaN(redirectTarget.y)
                                    || Float.isInfinite(redirectTarget.y)) {
                                Debug.chat("[Proj Aim] Proj failed to reach the target");
                            } else {
                                // recreate packet to en, do something
                                packet = new ServerboundUseItemPacket(
                                        hand, packet.getSequence(), redirectTarget.y, redirectTarget.x);
                            }
                        } else {
                            Debug.chat(Component.literal("[Proj Aim] Target absent"));
                        }
                    }
                }
                if (enableTp.get()) {
                    if (stack.getItem() instanceof EnderpearlItem pearl
                            || stack.getItem() instanceof SplashPotionItem
                            || stack.getItem() instanceof ExperienceBottleItem
                            || stack.getItem() instanceof LingeringPotionItem
                            || stack.getItem() instanceof EggItem) {
                        pearl_tp:
                        {
                            boolean exactTp = enhanceTp.get();
                            double range = tpDistance.get();
                            Vec3 facing = EntityUtils.pitchYawToRotation(
                                    packet.getXRot(), packet.getYRot()); // mc.player.getRotationVector();
                            Vec3 facingNorm = facing.normalize();
                            Vec3 oppositeFacing = Vec3.ZERO.subtract(facingNorm);
                            Vec3 finalMove = Vec3.ZERO;
                            Vec3 currentPlayerPos = mc.player.position();

                            test_tp_position:
                            {
                                // optimize the collision check by caching List of Boxes
                                MovTasks.CollisionContext context = new MovTasks.CollisionCache(
                                        mc.player,
                                        currentPlayerPos,
                                        currentPlayerPos.add(oppositeFacing.scale(range + 1.0d)),
                                        true);
                                double test = range;
                                for (; test > 10.0D; test -= 1.0D) {
                                    if (exactTp) {
                                        Vec3 oppositeMultiply = oppositeFacing.scale(test);
                                        if (MovTasks.validMoveTo(
                                                context,
                                                currentPlayerPos.add(oppositeMultiply),
                                                Vec3.ZERO.subtract(oppositeMultiply))) {
                                            finalMove = oppositeMultiply;
                                            break test_tp_position;
                                        }
                                    } else {
                                        if (MovTasks.validMoveToAndBack(
                                                context, currentPlayerPos, oppositeFacing.scale(test))) {
                                            finalMove = oppositeFacing.scale(test);
                                            break test_tp_position;
                                        }
                                    }
                                }
                                // t < 10
                                // check again
                                test = 10.0D;
                                for (; test > 0.0D; test -= 0.5D) {
                                    Vec3 oppositeMultiply = oppositeFacing.scale(test);
                                    if (exactTp) {
                                        if (MovTasks.validMoveTo(
                                                context,
                                                currentPlayerPos.add(oppositeMultiply),
                                                Vec3.ZERO.subtract(oppositeMultiply))) {
                                            finalMove = oppositeMultiply;
                                            break test_tp_position;
                                        }
                                    } else {
                                        if (MovTasks.validMoveToAndBack(context, currentPlayerPos, oppositeMultiply)) {
                                            finalMove = oppositeMultiply;
                                            break test_tp_position;
                                        }
                                    }
                                    Vec3 oppoHorizontal = new Vec3(oppositeMultiply.x, 0.0d, oppositeMultiply.z);
                                    Vec3 simulateMove =
                                            context.simulateMovement(mc.player, currentPlayerPos, oppoHorizontal);
                                    if (MovTasks.validMovementAsServer(oppoHorizontal, simulateMove)) {
                                        Vec3 simulateDownMove = context.simulateMovement(
                                                mc.player,
                                                currentPlayerPos.add(simulateMove),
                                                new Vec3(0, oppositeMultiply.y, 0));
                                        Vec3 wholeMovement = simulateMove.add(simulateDownMove);
                                        // y does not matter , xz matters
                                        if (MovTasks.validMoveTo(
                                                context,
                                                currentPlayerPos.add(wholeMovement),
                                                wholeMovement.scale(-1))) {
                                            finalMove = wholeMovement;
                                            break test_tp_position;
                                        }
                                    }
                                }
                                // should strengthen move when test < 10,
                            }
                            if (finalMove.lengthSqr() > 1E-4) {
                                // 随便写的阈值 速度太快不需要转向
                                List<Vec3> tpSequence = MovTasks.generateTpSequence(
                                        currentPlayerPos, currentPlayerPos.add(finalMove), false, 161, true);
                                if (!tpSequence.isEmpty()) {
                                    Debug.chat(Component.literal("[Proj TP] Projectile Velocity Simulate %.2f"
                                                    .formatted(finalMove.length()))
                                            .withStyle(ChatFormatting.GREEN));
                                    List<MovTasks.MovInfo> movements = new ArrayList<>();
                                    int size = tpSequence.size();
                                    for (int i = 0; i < size; ++i) {
                                        movements.add(
                                                i == 0
                                                        ? MovTasks.MovInfo.createNotOnGround(tpSequence.get(i))
                                                        : MovTasks.MovInfo.create(tpSequence.get(i)));
                                    }
                                    movements.add(MovTasks.MovInfo.create(currentPlayerPos.add(0, 9E-8, 0)));

                                    MovTasks.scheduleFarawayMoveInternal(
                                            movements, false, MovTasks.MovingContext.create(currentPlayerPos), true);

                                    MovTasks.setupAutoResync();
                                    break pearl_tp;
                                }

                                // send packets to simulate movements
                            }
                            Debug.chat(Component.literal("[Proj TP] Projectile Velocity fail to simulate"));
                        }
                    }
                }
            }
            packetMutableObject.context(packet);
        }
    }
}
