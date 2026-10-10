package me.matl114.utils;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * 已加载区块的<b>边界边</b>缓存：把「客户端当前已加载的区块集合」变成一组世界坐标下的线段，
 * 只保留<b>边界边</b>（内部相邻区块共享的边两两抵消），供地图类界面直接遍历绘制。
 *
 * <p>算法与 {@link me.matl114.hacks.modules.survival.XaeroHelper} 里那套完全一致（它已经跑通）：
 * 每条边用 {@code (2 * chunkX + dx, 2 * chunkZ + dz)} 打包成一个 {@code long}，
 * 四个方向各加一次，<b>同一个 key 第二次出现时移除</b>（等价于异或）——
 * 两个相邻区块共享的那条边必然出现两次而互相抵消，最后剩下的就只有「一边是已加载、另一边不是」的边界边。
 * 这样画的时候只需要走边界（几百条量级），而不是每个区块四条边（几千条）。
 *
 * <p>解包的几何（与 XaeroHelper#unpackEdge 逐行同式，方向数组 {@code {0,-1,0,1} / {1,0,-1,0}}）：
 * <pre>
 *   dx = 0, dz = +1  ->  线段 z = (chunkZ + 1) * 16，x ∈ [chunkX * 16, chunkX * 16 + 16]   （南边）
 *   dx = 0, dz = -1  ->  线段 z = chunkZ * 16，      x ∈ [chunkX * 16, chunkX * 16 + 16]   （北边）
 *   dx = +1, dz = 0  ->  线段 x = (chunkX + 1) * 16，z ∈ [chunkZ * 16, chunkZ * 16 + 16]   （东边）
 *   dx = -1, dz = 0  ->  线段 x = chunkX * 16，      z ∈ [chunkZ * 16, chunkZ * 16 + 16]   （西边）
 * </pre>
 * 全部是轴对齐线段，长度恒为 16 格；坐标是<b>方块坐标</b>（不是区块坐标）。
 *
 * <p>本类自成一体：不依赖 XaeroHelper、不依赖 conflux-map，只吃 {@link ChunkAccess} 的迭代器
 * （{@link CommonUtils#chunks(boolean)} 的返回类型），吐出一组线段。
 * 更新只在区块集合真的变化时重建（内部用打包后的 {@code long} 集合比较，不产生 {@link ChunkPos} 垃圾）。
 */
public final class LoadedChunkEdgeCache {
    /** 一条线段占 4 个 int：x1, z1, x2, z2 */
    private static final int STRIDE = 4;
    /** 四个方向（南 / 西 / 北 / 东），与 XaeroHelper 的 dx / dz 数组一致 */
    private static final int[] NEIGHBOUR_DX = {0, -1, 0, 1};
    private static final int[] NEIGHBOUR_DZ = {1, 0, -1, 0};
    /** 重建时边集合的预估容量系数（每条边最多出现两次） */
    private static final int EDGE_CAPACITY_FACTOR = 2;

    /** 上一次的区块集合（打包成 MathUtils.packInt(x, z) 的 long），只用来判等 */
    private LongSet lastChunks = new LongOpenHashSet();

    /** x1, z1, x2, z2 依次排列的线段数组，有效长度 = segmentCount * STRIDE */
    private int[] segments = new int[0];
    private int segmentCount;
    private int version;

    /**
     * 用一份新的已加载区块集合刷新缓存。
     *
     * <p>只在集合真的变了的时候重建（比较用打包后的 long 集合，逐元素比较）。
     * 空集合也会被正常接受：那意味着「一条边界边都不画」。
     *
     * @param chunks 已加载区块，直接喂 {@code CommonUtils.chunks(false)} 即可；null 视为无变化
     * @return true 表示这次真的重建了线段（调用方可以据此打日志）
     */
    public boolean update(Iterable<? extends ChunkAccess> chunks) {
        if (chunks == null) {
            return false;
        }
        LongSet current = new LongOpenHashSet();
        for (ChunkAccess chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            ChunkPos pos = chunk.getPos();
            if (pos == null) {
                continue;
            }
            // 用 record 访问器 x()/z()，不依赖 overrideList.accesswidener 对 ChunkPos.x/z 的开洞
            current.add(MathUtils.packInt(pos.x(), pos.z()));
        }
        // LongOpenHashSet 走 fastutil 的 Set 语义 equals：逐元素比较，不依赖迭代顺序
        if (current.equals(this.lastChunks)) {
            return false;
        }
        this.lastChunks = current;
        rebuild(current);
        return true;
    }

    /** 丢掉全部状态（模块卸载 / 换维度时用） */
    public void clear() {
        this.lastChunks = new LongOpenHashSet();
        this.segments = new int[0];
        this.segmentCount = 0;
        ++this.version;
    }

    /** 当前缓存里的线段数 */
    public int segmentCount() {
        return segmentCount;
    }

    /** 缓存内容的版本号，每次重建 +1（日志 / 调试用） */
    public int version() {
        return version;
    }

    public int x1(int index) {
        return segments[index * STRIDE];
    }

    public int z1(int index) {
        return segments[index * STRIDE + 1];
    }

    public int x2(int index) {
        return segments[index * STRIDE + 2];
    }

    public int z2(int index) {
        return segments[index * STRIDE + 3];
    }

    private void rebuild(LongSet chunks) {
        LongSet edges = new LongOpenHashSet(Math.max(16, chunks.size() * EDGE_CAPACITY_FACTOR));
        for (long key : chunks) {
            int chunkX = MathUtils.unpackFirst(key);
            int chunkZ = MathUtils.unpackSecond(key);
            for (int i = 0; i < NEIGHBOUR_DX.length; ++i) {
                long edge = packEdge(chunkX, chunkZ, NEIGHBOUR_DX[i], NEIGHBOUR_DZ[i]);
                // 同一个 key 第二次出现 = 两个相邻区块共享的边，抵消掉；剩下的就是边界边
                if (!edges.remove(edge)) {
                    edges.add(edge);
                }
            }
        }
        int[] result = new int[edges.size() * STRIDE];
        int index = 0;
        for (long edge : edges) {
            index = unpackEdge(edge, result, index);
        }
        this.segments = result;
        this.segmentCount = result.length / STRIDE;
        ++this.version;
    }

    /** 与 XaeroHelper#packEdge 同式：把「区块坐标 + 一个方向」打包成一个 long */
    private static long packEdge(int chunkX, int chunkZ, int dx, int dz) {
        return MathUtils.packInt(2 * chunkX + dx, 2 * chunkZ + dz);
    }

    /**
     * 与 XaeroHelper#unpackEdge 同式：把打包的边还原成一条方块坐标下的轴对齐线段，写进 out。
     *
     * @return 下一个可写的下标（index + STRIDE）
     */
    private static int unpackEdge(long packed, int[] out, int index) {
        int packedX = MathUtils.unpackFirst(packed);
        int packedZ = MathUtils.unpackSecond(packed);
        int chunkX = packedX >> 1;
        int chunkZ = packedZ >> 1;
        int nextChunkX = packedX - chunkX;
        int nextChunkZ = packedZ - chunkZ;
        if (chunkX == nextChunkX) {
            // 打包点的 X 落在区块内部 -> 这条边是南北向的（z 恒定，x 走满 16 格）
            int lowZ = Math.min(nextChunkZ, chunkZ);
            out[index] = chunkX << 4;
            out[index + 1] = (lowZ + 1) << 4;
            out[index + 2] = (chunkX + 1) << 4;
            out[index + 3] = (lowZ + 1) << 4;
        } else {
            // 否则是东西向的（x 恒定，z 走满 16 格）
            int lowX = Math.min(nextChunkX, chunkX);
            out[index] = (lowX + 1) << 4;
            out[index + 1] = chunkZ << 4;
            out[index + 2] = (lowX + 1) << 4;
            out[index + 3] = (chunkZ + 1) << 4;
        }
        return index + STRIDE;
    }
}
