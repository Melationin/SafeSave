package com.carpet.safesave.safesave;

import com.carpet.safesave.util.OrderSequence;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一个服务端会话内的 safe-save 会话级状态。
 *
 * <p>当前实现只在服务端运行，但同一个 JVM 可能先后经历多个单人世界。每个世界加载时
 * （{@code SafeSaveManager.onServerLoaded}）创建并 {@link #bind} 一个新会话；关闭流程刻意
 * <strong>不</strong>清除引用，因为关闭保存（{@code saveAllChunks} 的 HEAD 之后）仍需要
 * 会话里的存储与计数。下一个世界加载时会重新绑定。
 *
 * <p>全局唯一静态根 {@link #current()} 是刻意保留的：{@code PistonMovingBlockEntity} 的
 * {@code loadAdditional} 在方块实体获得所属世界之前运行，没有 {@code level} 可以寻址，
 * 只能通过它找到会话级活塞序号。
 */
public final class SafeSaveSession {

    private static SafeSaveSession current;

    /** 世界级元数据存储（sidecar）。 */
    public SafeSaveStore store;
    /** 在首刻前冻结被处理之前为 {@code true}。 */
    public boolean freezeArmed = true;

    /** 供 {@code /safesave status} 使用的诊断数据（从区块 NBT 读取的计数）。 */
    public final AtomicInteger loadedTickCount = new AtomicInteger();
    public final AtomicInteger loadedBlockEventCount = new AtomicInteger();

    /** 计划刻 / 方块事件恢复与丢弃的会话级诊断计数。 */
    public final AtomicInteger restoredTickCount = new AtomicInteger();
    public final AtomicInteger droppedTickCount = new AtomicInteger();
    public final AtomicInteger restoredBlockEventCount = new AtomicInteger();
    public final AtomicInteger droppedBlockEventCount = new AtomicInteger();

    /** 活塞创建序号（会话级：PME 的 {@code loadAdditional} 时 level 尚为 null）。 */
    public final OrderSequence pistonOrder = new OrderSequence();
    /** 活塞刻循环器重建代数（会话级，原因同上）。 */
    public final AtomicLong pistonOrderGeneration = new AtomicLong();

    private SafeSaveSession() {
    }

    /** @return 当前绑定的会话；服务端加载前或客户端上可能为 {@code null} */
    public static SafeSaveSession current() {
        return current;
    }

    /** 绑定新的会话（服务端加载时调用）。 */
    public static void bind(final SafeSaveSession session) {
        current = session;
    }

    /** 创建并绑定一个空会话。 */
    public static SafeSaveSession begin() {
        SafeSaveSession session = new SafeSaveSession();
        session.store = new SafeSaveStore();
        session.freezeArmed = true;
        session.loadedTickCount.set(0);
        session.loadedBlockEventCount.set(0);
        session.restoredTickCount.set(0);
        session.droppedTickCount.set(0);
        session.restoredBlockEventCount.set(0);
        session.droppedBlockEventCount.set(0);
        session.pistonOrderGeneration.set(0);
        bind(session);
        return session;
    }
}
