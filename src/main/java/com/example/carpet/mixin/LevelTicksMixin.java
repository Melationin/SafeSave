package com.example.carpet.mixin;

import com.example.carpet.debug.DebugLog;
import com.example.carpet.debug.DebugSwitches;
import com.example.carpet.debug.TickOwnerAware;
import com.example.carpet.safesave.TickContainerHolder;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Debug instrumentation + container access for the level-wide scheduled-tick index.
 *
 * <p>Shadow fields are declared with wildcards on purpose: the target is {@code LevelTicks<T>} but
 * JVM field descriptors erase generics, so wildcards match while keeping this a plain
 * (non-generic) mixin.
 */
@Mixin(LevelTicks.class)
public abstract class LevelTicksMixin implements TickOwnerAware, TickContainerHolder {

    @Shadow
    @Final
    private Long2ObjectMap<LevelChunkTicks<?>> allContainers;

    @Shadow
    @Final
    private List<ScheduledTick<?>> alreadyRunThisTick;

    @Unique
    private ServerLevel carpetExample$owner;

    @Unique
    private String carpetExample$label = "?";

    // ------------------------------------------------------------------ ducks

    @Override
    public void carpetExample$bindOwner(final ServerLevel level, final String kind) {
        this.carpetExample$owner = level;
        this.carpetExample$label = DebugLog.dimensionName(level) + "/" + kind;
    }

    @Override
    public String carpetExample$ownerLabel() {
        return this.carpetExample$label;
    }

    @Override
    public long carpetExample$ownerGameTime() {
        return this.carpetExample$owner == null ? Long.MIN_VALUE : this.carpetExample$owner.getGameTime();
    }

    @Override
    public Long2ObjectMap<?> carpetExample$containers() {
        return this.allContainers;
    }

    // ------------------------------------------------------- debug: tick added

    /**
     * Reproduces vanilla's accept/drop decision so the log can distinguish a real insertion from a
     * schedule call swallowed by the {@code (type, pos)} de-duplication rule.
     */
    @Inject(method = "schedule", at = @At("HEAD"))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void carpetExample$onSchedule(final ScheduledTick<?> tick, final CallbackInfo ci) {
        if (!DebugSwitches.DEBUG || !DebugSwitches.isEnabled(DebugSwitches.Channel.SCHEDULED_TICKS)) {
            return;
        }
        LevelChunkTicks container = this.allContainers.get(ChunkPos.pack(tick.pos()));
        boolean accepted = container != null && !container.hasScheduledTick(tick.pos(), tick.type());
        DebugLog.scheduledTickAdded(this.carpetExample$label, tick, accepted, this.carpetExample$ownerGameTime());
    }

    // ----------------------------------------------------- debug: tick execute

    /**
     * Injects immediately before {@code output.accept(entry.pos(), entry.type())} inside
     * {@code runCollectedTicks}.
     *
     * <p>Vanilla appends the entry to {@code alreadyRunThisTick} on the line above that call, so the
     * tail of that list <em>is</em> the tick about to run — which avoids fragile local-capture and
     * still yields the full {@code ScheduledTick} (priority + subTickOrder included).
     */
    @Inject(
            method = "runCollectedTicks",
            at = @At(value = "INVOKE", target = "Ljava/util/function/BiConsumer;accept(Ljava/lang/Object;Ljava/lang/Object;)V")
    )
    private void carpetExample$onRunCollectedTick(final BiConsumer<BlockPos, ?> output, final CallbackInfo ci) {
        if (!DebugSwitches.DEBUG || !DebugSwitches.isEnabled(DebugSwitches.Channel.SCHEDULED_TICKS)) {
            return;
        }
        if (this.alreadyRunThisTick.isEmpty()) {
            return;
        }
        ScheduledTick<?> tick = this.alreadyRunThisTick.get(this.alreadyRunThisTick.size() - 1);
        DebugLog.scheduledTickRun(this.carpetExample$label, tick, this.carpetExample$ownerGameTime());
    }
}
