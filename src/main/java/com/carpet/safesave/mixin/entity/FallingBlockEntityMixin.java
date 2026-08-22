package com.carpet.safesave.mixin.entity;

import com.carpet.safesave.safesave.SafeSaveManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static com.carpet.safesave.util.Util.KEY_SAFE_SAVE;

@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityMixin {

    @Shadow
    public boolean forceTickAfterTeleportToDuplicate;

    @Shadow
    public abstract BlockPos getStartPos();

    @Shadow
    public abstract void setStartPos(BlockPos pos);

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void save(final ValueOutput output, final CallbackInfo ci) {
        if (!SafeSaveManager.enabled()) {
            return;
        }
        if(!(output instanceof TagValueOutput tagValueOutput)) return ;
        ValueOutput safe = tagValueOutput.getChild(KEY_SAFE_SAVE);
        if(safe == null) safe = tagValueOutput.child(KEY_SAFE_SAVE);
        safe.putBoolean("force_tick_after_teleport_to_duplicate", this.forceTickAfterTeleportToDuplicate);
        safe.store("start_pos", BlockPos.CODEC, this.getStartPos());
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void load(final ValueInput input, final CallbackInfo ci) {
        if (!SafeSaveManager.enabled()) {
            return;
        }
        ValueInput safe = input.child(KEY_SAFE_SAVE).orElse(null);
        if (safe == null) {
            return;
        }
        this.forceTickAfterTeleportToDuplicate = safe.getBooleanOr(
                "force_tick_after_teleport_to_duplicate",
                this.forceTickAfterTeleportToDuplicate
        );
        safe.read("start_pos", BlockPos.CODEC).ifPresent(this::setStartPos);
    }
}
