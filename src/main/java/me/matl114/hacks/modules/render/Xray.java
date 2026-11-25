package me.matl114.hacks.modules.render;

import java.util.List;
import me.matl114.events.Event;
import me.matl114.events.RenderListener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.hacks.utils.config.EntrySet;
import me.matl114.managers.Configs;
import me.matl114.managers.config.ConfigEnum;
import me.matl114.managers.config.EnumRef;
import me.matl114.managers.config.FlagRef;
import me.matl114.managers.config.IntRef;
import me.matl114.managers.config.KeyBindRef;
import me.matl114.managers.config.NBTRef;
import me.matl114.managers.input.MultiKeyBind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.Shapes;

public class Xray extends BaseModule {
    public static Xray INSTANCE;

    public static final List<Block> ORES = List.of(
            Blocks.COAL_ORE,
            Blocks.DEEPSLATE_COAL_ORE,
            Blocks.IRON_ORE,
            Blocks.DEEPSLATE_IRON_ORE,
            Blocks.GOLD_ORE,
            Blocks.DEEPSLATE_GOLD_ORE,
            Blocks.LAPIS_ORE,
            Blocks.DEEPSLATE_LAPIS_ORE,
            Blocks.REDSTONE_ORE,
            Blocks.DEEPSLATE_REDSTONE_ORE,
            Blocks.DIAMOND_ORE,
            Blocks.DEEPSLATE_DIAMOND_ORE,
            Blocks.EMERALD_ORE,
            Blocks.DEEPSLATE_EMERALD_ORE,
            Blocks.COPPER_ORE,
            Blocks.DEEPSLATE_COPPER_ORE,
            Blocks.NETHER_GOLD_ORE,
            Blocks.NETHER_QUARTZ_ORE,
            Blocks.ANCIENT_DEBRIS);

    /**
     * 当前线程正在 tesselate 的方块 alpha（-1 = 不干预）。
     *
     * <p>26.x 的 {@code tesselateBlock → tesselateAmbientOcclusion / tesselateFlat → putQuadWithTint}
     * 之间没有能携带「当前方块」的参数，而区块重建本身跑在多个工作线程上，
     * 用线程局部量传递比共享字段安全（共享字段会被并发的区块重建互相覆盖）。
     */
    private static final ThreadLocal<Integer> CURRENT_ALPHA = ThreadLocal.withInitial(() -> -1);

    private final ModulePath xrayPath = makePath(Configs.RENDER_CONFIG, "xray");

    public Xray() {
        super("Xray");
        bindFlag(enable);
        INSTANCE = this;
    }

    public final FlagRef enable = flagBuilder(xrayPath.addEnable()).build();

    public final KeyBindRef hotkey = moduleEntry(xrayPath.addHotkey(), new MultiKeyBind(), xrayPath.addEnable())
            .build();

    public final NBTRef<EntrySet<Block>> whitelist = builder(xrayPath, EntrySet.<Block>parameter())
            .path(xrayPath.add("whitelist").toPath())
            .defaultValue(new EntrySet<>(BuiltInRegistries.BLOCK, ORES))
            .updateListener(ignored -> refreshIfActive())
            .build();

    public final IntRef opacity = builder(xrayPath.add("opacity"), IntRef.TYPE)
            .defaultValue(25)
            .validator(Configs.intRange(0, 255))
            .updateListener(ignored -> refreshIfActive())
            .build();

    public final EnumRef<FluidOpacity> fluidOpacity = builder(xrayPath.add("fluid-opacity"), FluidOpacity.class)
            .defaultValue(FluidOpacity.Both)
            .updateListener(ignored -> refreshIfActive())
            .build();

    public final FlagRef exposedOnly = builder(xrayPath.add("exposed-only"), Boolean.class)
            .defaultValue(false)
            .updateListener(ignored -> refreshIfActive())
            .build();

    public final FlagRef affectBlockEntities = builder(xrayPath.add("affect-block-entities"), Boolean.class)
            .defaultValue(true)
            .updateListener(ignored -> refreshIfActive())
            .build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(RenderListener.getBlockEntityRenderListener(), this::onBlockEntityRender);
    }

    private void onBlockEntityRender(Event<BlockEntity> event) {
        if (!affectBlockEntities.get() || event.isCancelled()) return;

        BlockEntity blockEntity = event.context();
        Block block = blockEntity.getBlockState().getBlock();
        if (isActive() && opacity.get() == 0 && isBlocked(block, blockEntity.getBlockPos())) {
            event.cancel();
        }
    }

    @Override
    public void onEnableModule() {
        refreshWorldRenderer();
    }

    @Override
    public void onDisableModule() {
        refreshWorldRenderer();
    }

    private void refreshIfActive() {
        if (isActive()) refreshWorldRenderer();
    }

    /**
     * 让已编译的区块按当前配置重新着色：alpha 只在区块编译期写得进去，
     * 开关或配置变化后不标脏重编就看不到效果。
     *
     * <p>标脏走 {@code ClientLevel#setSectionRangeDirty}：三个版本签名与语义一致（按 section 坐标逐个标脏，
     * 内部各自转发到原版的标脏路径），因此这一段不需要按版本分叉。
     * 遮挡图必须单独失效：只标脏不失效的话，被可见性图剔除的 section 不会重编，新露出的矿洞要等镜头移动才出现。
     * 26.2 起能直接拿到 {@code sectionOcclusionGraph()}（{@code invalidate()} 即重新传播可见性），
     * 26.1.2 没有这个公开访问器，改用等价的 {@code needsUpdate()}（内部同样调用遮挡图的 {@code invalidate()}）。
     */
    private void refreshWorldRenderer() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return;

        int viewDistance = client.options.getEffectiveRenderDistance();
        int sectionX = SectionPos.blockToSectionCoord(client.player.getBlockX());
        int sectionZ = SectionPos.blockToSectionCoord(client.player.getBlockZ());
        int minSectionY = client.level.getMinSectionY();
        int maxSectionY = minSectionY + client.level.getSectionsCount() - 1;

        client.level.setSectionRangeDirty(
                sectionX - viewDistance,
                minSectionY,
                sectionZ - viewDistance,
                sectionX + viewDistance,
                maxSectionY,
                sectionZ + viewDistance);
//#if MC >= 260200
        client.levelRenderer.sectionOcclusionGraph().invalidate();
//#else
//$$        client.levelRenderer.needsUpdate();
//#endif
    }

    public boolean isBlocked(Block block, BlockPos pos) {
        return !(whitelist.get().list().contains(block) && (!exposedOnly.get() || pos == null || isExposed(pos)));
    }

    private boolean isExposed(BlockPos pos) {
        if (mc.level == null) return false;

        for (Direction direction : Direction.values()) {
            BlockPos adjacentPos = pos.relative(direction);
            BlockState adjacentState = mc.level.getBlockState(adjacentPos);
            if (!adjacentState.isSolidRender()) return true;
        }

        return false;
    }

    public boolean modifyDrawSide(
            BlockState state, BlockAndTintGetter world, BlockPos pos, Direction facing, boolean original) {
        if (!original && !isBlocked(state.getBlock(), pos)) {
            BlockPos adjacentPos = pos.relative(facing);
            BlockState adjacentState = world.getBlockState(adjacentPos);
            // 面剔除形状按对象身份缓存，满方块恒返回同一个 Shapes.block() 实例，这里比引用即可。
            return adjacentState.getFaceOcclusionShape(facing.getOpposite()) != Shapes.block()
                    || adjacentState.getBlock() != state.getBlock()
                    || !adjacentState.isSolidRender()
                    || isBlocked(adjacentState.getBlock(), adjacentPos);
        }

        return original;
    }

    public static boolean isXrayActive() {
        return INSTANCE != null && INSTANCE.isActive();
    }

    /** 返回 -1 表示不干预；0 表示完全不画；1..254 表示该 alpha；255 等同于不干预。 */
    public static int getAlpha(BlockState state, BlockPos pos) {
        Xray xray = INSTANCE;
        if (xray != null && xray.isActive() && xray.isBlocked(state.getBlock(), pos)) {
            return xray.opacity.get();
        }

        return -1;
    }

    public static int getFluidAlpha(FluidState state, BlockPos pos) {
        Xray xray = INSTANCE;
        if (xray != null
                && xray.isActive()
                && xray.shouldApplyFluidOpacity(state)
                && xray.isBlocked(state.createLegacyBlock().getBlock(), pos)) {
            return xray.opacity.get();
        }

        return -1;
    }

    public static void setCurrentAlpha(int alpha) {
        CURRENT_ALPHA.set(alpha);
    }

    public static int currentAlpha() {
        return CURRENT_ALPHA.get();
    }

    public static void clearCurrentAlpha() {
        CURRENT_ALPHA.set(-1);
    }

    private boolean shouldApplyFluidOpacity(FluidState state) {
        return switch (fluidOpacity.get()) {
            case None -> false;
            case Water -> state.is(FluidTags.WATER);
            case Lava -> state.is(FluidTags.LAVA);
            case Both -> state.is(FluidTags.WATER) || state.is(FluidTags.LAVA);
        };
    }

    public enum FluidOpacity implements ConfigEnum {
        None,
        Water,
        Lava,
        Both;

        @Override
        public String getConfigEnumType() {
            return "xray_fluid_opacity";
        }
    }
}
