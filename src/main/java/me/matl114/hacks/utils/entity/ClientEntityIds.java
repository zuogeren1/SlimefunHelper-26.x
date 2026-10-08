package me.matl114.hacks.utils.entity;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端自建实体（假人、摄像机实体）的 id 分配。
 *
 * <p>这两类实体不在服务端的实体表里，需要一个不与服务端 id 相撞的 id；原来的写法是
 * {@code setId(-getId())}，在 26.2 之前 {@code getId()} 未分配时返回 0，等价于没设 id。
 * 26.2 起 {@code Entity#getId()} 在 id 未分配（0）时会抛 {@code IllegalStateException}，
 * 构造期读它必炸，因此改为自增负数：既拿到一个确定的 id，又保持在负数区间里避开服务端 id。
 */
public final class ClientEntityIds {
    private static final AtomicInteger NEXT = new AtomicInteger();

    private ClientEntityIds() {}

    public static int next() {
        return -NEXT.incrementAndGet();
    }
}
