package com.carpet.safesave.mixin.blockentity;

import com.carpet.safesave.safesave.blockentity.BlockEntityManager;
import com.carpet.safesave.safesave.blockentity.BlockEntityOrderHolder;
import com.carpet.safesave.safesave.blockentity.PistonOrderHolder;
import com.carpet.safesave.util.NbtView;
import com.carpet.safesave.util.SafeSaveNbt;
//? if <1.21.6 {
/*import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
*///?} else {
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
//?}
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin implements BlockEntityOrderHolder {

    @Unique private long SS$order = Long.MIN_VALUE;

    @Override
    public long SS$blockEntityOrder() {
        return this.SS$order;
    }

    @Unique
    private boolean SS$hasTickOrder() {
        return (Object) this instanceof HopperBlockEntity || (Object) this instanceof PistonMovingBlockEntity;
    }

    @Unique
    private void SS$attachOrder(final ServerLevel level) {
        if (!this.SS$hasTickOrder() || !SafeSaveNbt.enabled(level)) return;
        if (this.SS$order == Long.MIN_VALUE) {
            this.SS$order = BlockEntityManager.nextBlockEntityOrder(level);
        } else {
            BlockEntityManager.observeBlockEntityOrder(level, this.SS$order);
        }
        BlockEntityManager.markTickOrderDirty(level);
    }

    @Inject(method = "setLevel", at = @At("TAIL"))
    private void SS$onLevelAttached(final Level level, final CallbackInfo ci) {
        if (level instanceof ServerLevel serverLevel) {
            this.SS$attachOrder(serverLevel);
            if ((Object) this instanceof PistonOrderHolder piston) {
                piston.SS$onLevelAttached(serverLevel);
            }
        }
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void SS$saveOrder(final
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
        var self = (BlockEntity) (Object) this;
        if (!this.SS$hasTickOrder() || !SafeSaveNbt.enabled(self.getLevel())) return;
        if (this.SS$order == Long.MIN_VALUE) {
            this.SS$attachOrder((ServerLevel) self.getLevel());
        }
        SafeSaveNbt.child(NbtView.writer(output)).putLong("order", this.SS$order);
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void SS$loadOrder(final
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
        if (!this.SS$hasTickOrder()) return;
        NbtView.Reader tag = SafeSaveNbt.childOrNull(NbtView.reader(input));
        this.SS$order = tag == null ? Long.MIN_VALUE : tag.getLongOr("order", Long.MIN_VALUE);
        if (((BlockEntity) (Object) this).getLevel() instanceof ServerLevel level) {
            this.SS$attachOrder(level);
        }
    }
}
