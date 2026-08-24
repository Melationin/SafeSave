package com.carpet.safesave.mixin;

import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.safesave.SafeTickContainer;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * 让 safe-save 获得对单个区块刻容器的写入访问。
 *
 * <p>Shadow 字段使用通配符，因为 JVM 字段描述符会擦除泛型；这使 mixin 保持非泛型，同时能匹配
 * {@code LevelChunkTicks<T>}。
 */
@Mixin(LevelChunkTicks.class)
public abstract class LevelChunkTicksMixin implements SafeTickContainer {

    @Shadow
    @Final
    private Queue<ScheduledTick<?>> tickQueue;

    @Shadow
    @Final
    private Set<ScheduledTick<?>> ticksPerPosition;

    /** 原版中非 final：由 {@code unpack()} 置空。 */
    @Shadow
    private List<SavedTick<?>> pendingTicks;

    @Override
    public boolean SS$hasPendingTicks() {
        return this.pendingTicks != null;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void SS$replaceAll(final List<?> scheduledTicks) {
        this.tickQueue.clear();
        this.ticksPerPosition.clear();
        // 丢弃任何仍在等待解包的内容：提供的列表才是权威。
        this.pendingTicks = null;

        LevelChunkTicks self = (LevelChunkTicks) (Object) this;
        for (Object entry : scheduledTicks) {
            self.schedule((ScheduledTick) entry);
        }
    }

    @Override
    public List<?> SS$snapshotQueue() {
        // tickQueue 在原版中是带初始化的 `private final`，因此它只有在环境中其他东西干扰了
        // LevelChunkTicks 时才会为 null（例如作用于其构造器/字段的其他 mixin，或本构建未针对编译的
        // 模组/MC 版本）。为了它让自动保存崩溃远比跳过这一个区块糟糕得多，所以大声报告并降级处理。
        if (this.tickQueue == null) {
            DebugLog.warnOnce("null-tickQueue",
                    "LevelChunkTicks.tickQueue is null on {} - skipping this chunk's scheduled ticks. "
                            + "vanilla declares it 'private final' with an initializer, so another mod's mixin or a "
                            + "version mismatch is the likely cause; please report the full log and mod list.",
                    this.getClass().getName());
            return List.of();
        }
        return new ArrayList<>(this.tickQueue);
    }
}
