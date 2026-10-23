package me.matl114.hacks.modules.survival;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import me.matl114.accessors.interfaces.MetadataHolder;
import me.matl114.events.Event;
import me.matl114.events.impl.Render3D;
import me.matl114.events.Listener;
import me.matl114.events.RenderListener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.modules.combat.TargetSelector;
import me.matl114.hacks.modules.interact.Interact;
import me.matl114.hacks.modules.inv.InvExtra;
import me.matl114.hacks.utils.config.EntityTypeEntrySet;
import me.matl114.hacks.utils.config.EntityTypeRegex;
import me.matl114.hacks.utils.config.Regex;
import me.matl114.hacks.utils.config.WrapColor;
import me.matl114.hacks.utils.enums.GhostHandMode;
import me.matl114.hacks.utils.move.PathingSchedular;
import me.matl114.hacks.utils.move.goal.IPathGoal;
import me.matl114.hacks.utils.render.RenderCollectors;
import me.matl114.managers.Configs;
import me.matl114.managers.Tasks;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.managers.input.MultiKeyBind;
import me.matl114.hacks.utils.EntityUtils;
import me.matl114.utils.FarmingUtils;
import me.matl114.utils.InventoryUtils;
import me.matl114.utils.RenderUtils;
import me.matl114.utils.annotations.NeedTest;
import me.matl114.utils.collections.IndexEntry;
import me.matl114.utils.render.RenderCollector;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

@NeedTest
public class AutoBreed extends BaseModule {
    private static final int SEARCH_RADIUS = 32;
    private static final int PEN_SEARCH_RADIUS = 5;
    private static final int PEN_MAX_AREA = 256;
    private static final int PEN_MAX_SIDE = 16;
    private static final String BABY_BREED_COUNT_KEY = "slimefunhelper:auto_breed/baby_breed_count";

    public AutoBreed() {
        super("AutoBreed");
        bindFlag(enable);
    }

    public final ModulePath root = makePath(Configs.SURVIVAL_CONFIG, "survival-interact-utils.auto-breed");

    public final FlagRef enable = flagBuilder(root.addEnable()).build();

    public final KeyBindRef hotkey =
            moduleEntry(root.addHotkey(), new MultiKeyBind(), root.addEnable()).build();

    public final FlagRef enableBaritone =
            flagBuilder(root.add("enable-baritone")).build();

    public final FlagRef enableBreed = flagBuilder(root.add("enable-breed")).build();

    public final FlagRef enableFeedBaby =
            flagBuilder(root.add("enable-feed-baby")).build();

    public final IntRef babyFeedLimit = builder(root.add("baby-feed-limit"), Integer.class)
            .defaultValue(3)
            .validator(Configs.INT_NONNEGATIVE)
            .build();

    public final FlagRef render = flagBuilder(root.add("render")).build();

    public final NBTRef<WrapColor> renderColor = builder(root.add("render-color"), WrapColor.class)
            .defaultValue(new WrapColor(ChatFormatting.GREEN))
            .build();

    public final NBTRef<WrapColor> renderPenColor = builder(root.add("render-pen-color"), WrapColor.class)
            .defaultValue(new WrapColor(ChatFormatting.YELLOW))
            .build();

    public final NBTRef<WrapColor> renderOtherAnimalColor = builder(
                    root.add("render-other-animal-color"), WrapColor.class)
            .defaultValue(new WrapColor(ChatFormatting.AQUA))
            .build();

    public final NBTRef<EntityTypeEntrySet> entityWhitelist = builder(
                    root.add("entity-whitelist"), EntityTypeEntrySet.class)
            .defaultValue(new EntityTypeEntrySet(new Regex("^(turtle)$")))
            .build();

    private final PathingSchedular pathingSchedular = new PathingSchedular();
    private Animal targetAnimal;
    private AnimalPen targetPen;
    private int lastInteractTick;
    private final RenderCollector<Entity> entityRenderCollector =
            RenderCollectors.createEntityBoxCollector(true, false, false);
    private final RenderCollector<AABB> renderCollector = RenderCollectors.createBoxCollector(true, false, false);

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getPreHandleInputEvents(), this::onPreInputEvent);
        registerListener(Listener.getPostGameTick(), this::updateRenderCollector);
        registerListener(RenderListener.getRender3DEvent(), this::onRender);
    }

    @Override
    public void onDisableModule() {
        super.onDisableModule();
        clearTarget();
    }

    private void clearTarget() {
        targetAnimal = null;
        targetPen = null;
        pathingSchedular.disable();
    }

    private boolean isBreedTarget(Animal animal) {
        return EntityUtils.isEntityValid(animal)
                && entityWhitelist.get().test(animal.getType())
                && FarmingUtils.isBreedable(animal)
                && isInteractionTarget(animal);
    }

    private boolean isInteractionTarget(Animal animal) {
        if (animal.isBaby()) {
            return enableFeedBaby.get() && getBabyBreedCount(animal) < babyFeedLimit.get();
        }
        return enableBreed.get() && !animal.isInLove();
    }

    private void refreshTarget() {
        if (targetAnimal != null && isBreedTarget(targetAnimal)) {
            targetPen = locateAnimalPen(targetAnimal);
            if (targetPen != null) {
                Animal bestInPen = findBestTargetInPen(targetPen);
                if (bestInPen != null) {
                    targetAnimal = bestInPen;
                }
            }
            return;
        }
        targetAnimal = null;
        targetPen = null;

        List<Animal> animals = mc.level.getEntitiesOfClass(
                Animal.class,
                mc.player.getBoundingBox().inflate(SEARCH_RADIUS, SEARCH_RADIUS / 2.0, SEARCH_RADIUS),
                this::isBreedTarget);
        Animal best = animals.stream()
                .min(Comparator.comparingDouble(animal -> animal.distanceToSqr(mc.player)))
                .orElse(null);
        if (best == null) {
            return;
        }
        targetPen = locateAnimalPen(best);
        if (targetPen != null) {
            Animal bestInPen = findBestTargetInPen(targetPen);
            targetAnimal = bestInPen != null ? bestInPen : best;
        } else {
            targetAnimal = best;
        }
    }

    public void onPreInputEvent(me.matl114.events.Event<Void> event) {
        if (checkNull() || !enable.get()) {
            return;
        }
        refreshTarget();
        tickBreed();
        pathingSchedular.tickPathing(mc.player);
    }

    private void updateRenderCollector() {
        entityRenderCollector.clear();
        renderCollector.clear();
        if (checkNull() || !enable.get() || !render.get() || targetAnimal == null) return;
        entityRenderCollector.submit(targetAnimal, renderColor.get().withAlpha(255));
        if (targetPen != null && targetPen.innerBox().contains(targetAnimal.position())) {
            renderCollector.submit(targetPen.outerBox(), renderPenColor.get().withAlpha(255));
            for (Animal animal : getBreedTargetsInPen(targetPen)) {
                if (animal != targetAnimal) {
                    entityRenderCollector.submit(
                            animal, renderOtherAnimalColor.get().withAlpha(255));
                }
            }
        }
    }

    public void tickBreed() {
        if (targetAnimal == null
                || pathingSchedular.isPathing()
                || !TargetSelector.INSTANCE.isWithinAttackRange(mc.player.position(), targetAnimal)) {
            return;
        }
        IndexEntry<ItemStack> breedItem = findBreedItem(targetAnimal);
        if (breedItem == null || lastInteractTick + 5 >= Tasks.getTick() || Interact.INSTANCE == null) {
            return;
        }
        Runnable restore = InvExtra.INSTANCE.swapItemToHand(breedItem.index(), false, GhostHandMode.INV_SWAP);
        if (restore == null) {
            return;
        }
        boolean completed = Interact.INSTANCE.interactEntity(targetAnimal);
        Interact.INSTANCE.finishEntityGhostHand(completed, restore);
        if (targetAnimal.isBaby()) {
            incrementBabyBreedCount(targetAnimal);
        }
        lastInteractTick = Tasks.getTick();
    }

    private int getBabyBreedCount(Animal animal) {
        if (!(animal instanceof MetadataHolder holder)) {
            return 0;
        }
        Integer count = holder.getMetadata().get(this, BABY_BREED_COUNT_KEY);
        return count == null ? 0 : count;
    }

    private void incrementBabyBreedCount(Animal animal) {
        if (animal instanceof MetadataHolder holder) {
            holder.getMetadata().put(this, BABY_BREED_COUNT_KEY, getBabyBreedCount(animal) + 1);
        }
    }

    public void onRender(Event<Render3D> event) {
        if (!enable.get() || !render.get() || targetAnimal == null) {
            return;
        }
        RenderUtils.startDrawVirtual(event.context.stack());
        try {
            entityRenderCollector.render3D(event.context.stack(), event.context.partialTicks());
            renderCollector.render3D(event.context.stack(), event.context.partialTicks());
        } finally {
            RenderUtils.stopDrawVirtual(event.context.stack());
        }
    }

    private List<Animal> getBreedTargetsInPen(AnimalPen pen) {
        return mc.level.getEntitiesOfClass(Animal.class, pen.innerBox(), this::isBreedTarget);
    }

    private Predicate<ItemStack> breedItemPredicate(Animal animal) {
        Set<ItemStack> breedItems = FarmingUtils.getBreedItems(animal);
        return stack -> !stack.isEmpty() && breedItems.stream().anyMatch(item -> stack.is(item.getItem()));
    }

    private IndexEntry<ItemStack> findBreedItem(Animal animal) {
        return InventoryUtils.findPlayerItem(breedItemPredicate(animal), true, false);
    }

    private boolean shouldReplenish(Animal animal) {
        return findBreedItem(animal) == null;
    }

    public boolean shouldReplenish() {
        return targetAnimal != null && shouldReplenish(targetAnimal);
    }

    public boolean discharge() {
        return false;
    }

    public IPathGoal processGoal() {
        if (targetAnimal == null) {
            return null;
        }
        if (targetPen != null) {
            BlockPos perimeterStop = targetPen.selectPerimeterStop(mc.player.position());
            if (perimeterStop != null) {
                return PathingSchedular.pathToOrNearStopGoal(perimeterStop, 1.5);
            }
        }
        return PathingSchedular.pathToOrNearStopGoal(targetAnimal.blockPosition(), 1.5);
    }

    {
        pathingSchedular
                .active(() -> enableBaritone.get() && targetAnimal != null)
                .replenish(this::shouldReplenish)
                .discharge(this::discharge)
                .replenishmentSourcePredicate((pos, inventory) -> targetAnimal != null
                        && InventoryUtils.findItem(inventory, breedItemPredicate(targetAnimal), false) != null)
                .processGoal(this::processGoal);
    }

    private Animal findBestTargetInPen(AnimalPen pen) {
        if (pen == null) {
            return null;
        }
        List<Animal> animals = mc.level.getEntitiesOfClass(Animal.class, pen.innerBox(), this::isBreedTarget);
        return animals.stream()
                .min(Comparator.comparingDouble(animal -> animal.distanceToSqr(mc.player)))
                .orElse(null);
    }

    private AnimalPen locateAnimalPen(Animal anchor) {
        if (mc.level == null || mc.player == null || anchor == null) {
            return null;
        }
        BlockPos origin = anchor.blockPosition();
        BlockPos west = findWall(origin, Direction.WEST);
        BlockPos east = findWall(origin, Direction.EAST);
        BlockPos north = findWall(origin, Direction.NORTH);
        BlockPos south = findWall(origin, Direction.SOUTH);
        if (west == null || east == null || north == null || south == null) {
            return null;
        }
        int minX = west.getX();
        int maxX = east.getX();
        int minZ = north.getZ();
        int maxZ = south.getZ();
        if (maxX <= minX || maxZ <= minZ) {
            return null;
        }
        int width = maxX - minX + 1;
        int depth = maxZ - minZ + 1;
        if (width * depth > PEN_MAX_AREA || (width > PEN_MAX_SIDE && depth > PEN_MAX_SIDE)) {
            return null;
        }
        if (!isRectangularPen(minX, maxX, minZ, maxZ, origin.getY())) {
            return null;
        }
        return new AnimalPen(minX, maxX, minZ, maxZ, origin.getY());
    }

    private BlockPos findWall(BlockPos origin, Direction direction) {
        for (int i = 1; i <= PEN_SEARCH_RADIUS; i++) {
            BlockPos pos = origin.relative(direction, i);
            if (isWallBlock(pos)) {
                return pos;
            }
        }
        return null;
    }

    private boolean isRectangularPen(int minX, int maxX, int minZ, int maxZ, int y) {
        for (int x = minX; x <= maxX; x++) {
            if (!isWallBlock(new BlockPos(x, y, minZ)) || !isWallBlock(new BlockPos(x, y, maxZ))) {
                return false;
            }
        }
        for (int z = minZ; z <= maxZ; z++) {
            if (!isWallBlock(new BlockPos(minX, y, z)) || !isWallBlock(new BlockPos(maxX, y, z))) {
                return false;
            }
        }
        return true;
    }

    private boolean isWallBlock(BlockPos pos) {
        if (mc.level == null || mc.player == null) {
            return false;
        }
        var state = mc.level.getBlockState(pos);
        if (state.isAir() || state.liquid()) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(mc.level, pos, CollisionContext.of(mc.player));
        if (shape.isEmpty()) {
            return false;
        }
        AABB box = shape.bounds();
        double width = box.maxX - box.minX;
        double height = box.maxY - box.minY;
        double depth = box.maxZ - box.minZ;
        return Math.max(Math.max(width, height), depth) >= 1.0D;
    }

    private record AnimalPen(int minX, int maxX, int minZ, int maxZ, int y) {
        AABB outerBox() {
            return new AABB(minX, y, minZ, maxX + 1.0D, y + 2.0D, maxZ + 1.0D);
        }

        AABB innerBox() {
            return new AABB(minX + 1.0D, y, minZ + 1.0D, maxX, y + 2.0D, maxZ);
        }

        BlockPos selectPerimeterStop(Vec3 playerPos) {
            int standY = y + 1;
            int px = BlockPos.containing(playerPos).getX();
            int pz = BlockPos.containing(playerPos).getZ();
            BlockPos west = new BlockPos(minX - 1, standY, clamp(pz, minZ, maxZ));
            BlockPos east = new BlockPos(maxX + 1, standY, clamp(pz, minZ, maxZ));
            BlockPos north = new BlockPos(clamp(px, minX, maxX), standY, minZ - 1);
            BlockPos south = new BlockPos(clamp(px, minX, maxX), standY, maxZ + 1);
            BlockPos best = west;
            double bestScore = Vec3.atCenterOf(west).distanceToSqr(playerPos);
            double eastScore = Vec3.atCenterOf(east).distanceToSqr(playerPos);
            if (eastScore < bestScore) {
                best = east;
                bestScore = eastScore;
            }
            double northScore = Vec3.atCenterOf(north).distanceToSqr(playerPos);
            if (northScore < bestScore) {
                best = north;
                bestScore = northScore;
            }
            double southScore = Vec3.atCenterOf(south).distanceToSqr(playerPos);
            if (southScore < bestScore) {
                best = south;
            }
            return best;
        }

        private int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
