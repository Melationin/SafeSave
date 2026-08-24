package com.carpet.safesave.mixin.scheduled;

import com.carpet.safesave.debug.DebugLog;
import com.carpet.safesave.safesave.scheduled.SafeTickContainer;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.SavedTick;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 让 safe-save 获得对单个区块刻容器的写入访问。
 *
 * <p>兼容性能优化模组（如 Lithium 的 tick_scheduler）对 {@code LevelChunkTicks} 的重写：
 * 读取走 {@code getAll} 公共 API；重建用 {@code removeIf} 清空 + {@code schedule} 重填
 * （两者都触发 {@code onTickAdded} 保持父级 {@code LevelTicks} 缓存一致）。
 * 原版 {@code removeIf} 只清 {@code tickQueue}、不同步 {@code (type,pos)} 去重集合，
 * 因此还需补清 {@code ticksPerPosition} 字段——但 Lithium 会把它置为 null，访问前必须判空。
 */
@Mixin(LevelChunkTicks.class)
public abstract class LevelChunkTicksMixin implements SafeTickContainer {

    /** 原版中非 final：由 {@code unpack()} 置空。Lithium/C2ME 均保留该字段，可直接访问。 */
    @Shadow
    private List<SavedTick<?>> pendingTicks;

    /** 原版 (type,pos) 去重集合；Lithium 会将其置为 null，访问前必须判空。 */
    @Shadow
    @Final
    private Set<ScheduledTick<?>> ticksPerPosition;

    @Shadow
    public abstract void removeIf(Predicate<ScheduledTick<?>> test);

    @Override
    public boolean SS$hasPendingTicks() {
        return this.pendingTicks != null;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void SS$replaceAll(final List<?> scheduledTicks) {
        LevelChunkTicks self = (LevelChunkTicks) (Object) this;
        // removeIf 与 schedule 在两种实现下分别操作各自的数据结构：
        // 原版 removeIf 只清 tickQueue、不同步 (type,pos) 去重集合，必须补清；
        // Lithium 的 removeIf 同步清理其自建结构，且把 ticksPerPosition 置为 null（此处跳过）。
        this.removeIf(_->true);
        if (this.ticksPerPosition != null) {
            this.ticksPerPosition.clear();
        }
        // 丢弃任何仍在等待解包的内容：提供的列表才是权威。
        this.pendingTicks = null;

        for (Object entry : scheduledTicks) {
            if (entry instanceof ScheduledTick<?> tick) {
                // schedule 会按 (type, pos) 去重，并触发 onTickAdded 保持父级 LevelTicks 缓存一致。
                self.schedule((ScheduledTick) tick);
            }
        }
    }

    @Override
    public List<?> SS$snapshotQueue() {
        // 与 replaceAll 同理，走公共 API：原版 getAll 遍历 tickQueue，Lithium 遍历自建结构，
        // 两者都返回"当前已排队的全部刻"，绝对时间完好。
        try {
            LevelChunkTicks<?> self = (LevelChunkTicks<?>) (Object) this;
            return self.getAll().toList();
        } catch (Exception e) {
            // 容器处于无法读取的状态（如与其他 mod 的调度重写冲突）：
            // 返回 null 让调用方跳过该区块、保留存储中的旧条目，而不是以空快照覆盖。
            DebugLog.warnOnce("tickQueue-unreadable",
                    "LevelChunkTicks.getAll() failed ({}) - skipping this chunk's scheduled ticks. "
                            + "Another mod's tick scheduler rewrite is the likely cause.",
                    e.toString());
            return null;
        }
    }
}
