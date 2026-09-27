package com.carpet.safesave.mixin.blockentity;

import com.carpet.safesave.safesave.blockentity.PistonManager;
import com.carpet.safesave.safesave.blockentity.PistonOrderHolder;
import com.carpet.safesave.util.NbtView;
import com.carpet.safesave.util.SafeSaveNbt;
import net.minecraft.server.level.ServerLevel;
//? if <1.21.6 {
/*import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
*///?} else {
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
//?}
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;



@Mixin(PistonMovingBlockEntity.class)
public abstract class PistonMovingBlockEntityMixin implements PistonOrderHolder {


    @Shadow
    private float progress;

    @Shadow
    private float progressO;

    @Shadow
    private long lastTicked;

    @Unique
    private long SS$order = Long.MIN_VALUE;

    @Unique
    private long SS$snapshotTime = Long.MIN_VALUE;

    @Unique private boolean SS$restorePending;
    @Unique private float SS$loadedProgress = Float.NaN;
    @Unique private float SS$loadedProgressO;
    @Unique private long SS$loadedLastTicked;
    @Unique private long SS$loadedSnapshotTime = Long.MIN_VALUE;
    @Unique private long SS$loadedOrder = Long.MIN_VALUE;

    @Override
    public void SS$rebaseTime(long gameTime) {
        if (this.SS$snapshotTime != Long.MIN_VALUE) {
            this.lastTicked = com.carpet.safesave.util.ResumeTime.rebase(
                    this.lastTicked, this.SS$snapshotTime, gameTime);
            this.SS$snapshotTime = Long.MIN_VALUE;
        }
    }

    @Override
    public long SS$pistonOrder() {
        return this.SS$order;
    }

    @Override
    public void SS$onLevelAttached(final ServerLevel level) {
        if (!SafeSaveNbt.enabled(level)) {
            this.SS$restorePending = false;
            return;
        }
        if (this.SS$restorePending) {
            if (!Float.isNaN(this.SS$loadedProgress)) {
                this.progress = this.SS$loadedProgress;
                this.progressO = this.SS$loadedProgressO;
            }
            this.lastTicked = this.SS$loadedLastTicked;
            this.SS$snapshotTime = this.SS$loadedSnapshotTime;
            if (this.SS$loadedOrder != Long.MIN_VALUE) {
                this.SS$order = this.SS$loadedOrder;
                PistonManager.observePistonOrder(level, this.SS$order);
            }
            this.SS$restorePending = false;
            PistonManager.markPistonTickOrderDirty(level);
        }
        if (this.SS$order == Long.MIN_VALUE) {
            this.SS$order = PistonManager.nextPistonOrder(level);
        }
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void save(final
                      //? if <1.21.6 {
                      /*CompoundTag
                      *///?} else {
                      ValueOutput
                      //?}
                              output,
                      //? if <1.21.6 {
                      /*final HolderLookup.Provider registries,
                      *///?}
                      final CallbackInfo ci) {
        var self = (PistonMovingBlockEntity) (Object) this;
        if (!SafeSaveNbt.enabled(self.getLevel())) {
            return;
        }
        if (self.getLevel() instanceof ServerLevel level) {
            this.SS$onLevelAttached(level);
        }
        NbtView.Writer tag = SafeSaveNbt.child(NbtView.writer(output));
        tag.putFloat("progress", this.progress);
        tag.putFloat("progress_o", this.progressO);
        tag.putLong("lastTicked", this.lastTicked);
        if (this.SS$snapshotTime != Long.MIN_VALUE) {
            tag.putLong("snapshotGameTime", this.SS$snapshotTime);
        } else if (self.getLevel() != null) {
            tag.putLong("snapshotGameTime", self.getLevel().getGameTime());
        }
        tag.putLong("order", this.SS$order);
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void carpetExample$load(final
                                    //? if <1.21.6 {
                                    /*CompoundTag
                                    *///?} else {
                                    ValueInput
                                    //?}
                                            input,
                                    //? if <1.21.6 {
                                    /*final HolderLookup.Provider registries,
                                    *///?}
                                    final CallbackInfo ci) {
        NbtView.Reader tag = SafeSaveNbt.childOrNull(NbtView.reader(input));
        if (tag != null) {
            this.SS$loadedProgress = tag.getFloatOr("progress", Float.NaN);
            this.SS$loadedProgressO = tag.getFloatOr("progress_o", this.SS$loadedProgress);
            this.SS$loadedLastTicked = tag.getLongOr("lastTicked", this.lastTicked);
            this.SS$loadedSnapshotTime = tag.getLongOr("snapshotGameTime", Long.MIN_VALUE);
            this.SS$loadedOrder = tag.getLongOr("order", Long.MIN_VALUE);
            this.SS$restorePending = true;
        }
        var self = (PistonMovingBlockEntity) (Object) this;
        if (self.getLevel() instanceof ServerLevel level) {
            this.SS$onLevelAttached(level);
        }
    }
}
