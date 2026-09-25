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

/*
 * 兼容 Lithium 等模组对 LevelChunkTicks 的重写：读取走 getAll 公共 API；
 * 重建用 removeIf 清空 + schedule 重填（两者都会触发 onTickAdded，
 * 保持父级 LevelTicks 缓存一致）。原版 removeIf 只清 tickQueue、
 * 不同步 (type,pos) 去重集合，需补清 ticksPerPosition——但 Lithium 会将其
 * 置为 null，访问前必须判空。
 */
@Mixin(LevelChunkTicks.class)
public abstract class LevelChunkTicksMixin implements SafeTickContainer {

    @Shadow
    private List<SavedTick<?>> pendingTicks;

    // Lithium 会将其置为 null，访问前必须判空。
    @Shadow
    @Final
    private Set<ScheduledTick<?>> ticksPerPosition;

    @Shadow
    public abstract void removeIf(Predicate<ScheduledTick<?>> test);

    @Override
    public boolean SS$hasPendingTicks() {
        return this.pendingTicks != null;
    }

    // count() 是公共 API，原版与 Lithium 的实现均不含遍历。
    @Override
    public boolean SS$isEmpty() {
        return ((LevelChunkTicks<?>) (Object) this).count() == 0;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void SS$replaceAll(final List<?> scheduledTicks) {
        LevelChunkTicks self = (LevelChunkTicks) (Object) this;
        // 原版 removeIf 只清 tickQueue、不同步去重集合，须补清；Lithium 版已同步清理并置空 ticksPerPosition。
        this.removeIf(_->true);
        if (this.ticksPerPosition != null) {
            this.ticksPerPosition.clear();
        }
        // 丢弃任何仍在等待解包的内容
        this.pendingTicks = null;

        for (Object entry : scheduledTicks) {
            if (entry instanceof ScheduledTick<?> tick) {
                self.schedule((ScheduledTick) tick);
            }
        }
    }

    @Override
    public List<?> SS$snapshotQueue() {
        try {
            LevelChunkTicks<?> self = (LevelChunkTicks<?>) (Object) this;
            return self.getAll().toList();
        } catch (Exception e) {
            // 与其他 mod 的调度重写冲突时可能读不到：返回 null 让调用方跳过该区块、保留旧条目，
            DebugLog.warnOnce("tickQueue-unreadable",
                    "LevelChunkTicks.getAll() failed ({}) - skipping this chunk's scheduled ticks. "
                            + "Another mod's tick scheduler rewrite is the likely cause.",
                    e.toString());
            return null;
        }
    }
}
