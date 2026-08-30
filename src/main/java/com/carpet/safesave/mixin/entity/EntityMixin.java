package com.carpet.safesave.mixin.entity;

import com.carpet.safesave.util.SafeSaveNbt;
import com.carpet.safesave.safesave.entity.EntityOrderHolder;
import com.carpet.safesave.safesave.entity.EntityOrderManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;



@Mixin(Entity.class)
public abstract class EntityMixin implements EntityOrderHolder
{

    /** 实体 tick 序号；未知（本会话新生成）时为 {@link Long#MIN_VALUE}。 */
    @Unique
    private long SS$entityOrder = Long.MIN_VALUE;

    @Override
    public long SS$entityOrder() {
        return this.SS$entityOrder;
    }

    @Override
    public void SS$assignEntityOrder(final long order) {
        this.SS$entityOrder = order;
    }

    @Shadow
    public int tickCount;

    @Shadow
    protected boolean firstTick;

    @Shadow
    protected Vec3 stuckSpeedMultiplier;

    @Shadow
    public float moveDist;

    @Shadow
    public float flyDist;

    @Shadow
    public boolean noPhysics;

    @Shadow
    private boolean requiresPrecisePosition;

    @Shadow
    public boolean isInPowderSnow;

    @Shadow
    public boolean wasInPowderSnow;

    @Shadow
    protected boolean wasTouchingWater;

    @Shadow
    protected boolean wasEyeInWater;

    @Shadow
    public Optional<BlockPos> mainSupportingBlockPos;

    @Shadow
    private boolean onGroundNoBlocks;

    @Shadow
    private double[] pistonDeltas;

    @Shadow
    private long pistonDeltasGameTime;

    @Shadow
    public abstract Vec3 getDeltaMovement();

    @Shadow
    public abstract Pose getPose();

    @Shadow
    public abstract void setDeltaMovement(Vec3 deltaMovement);

    @Shadow
    public abstract void setPose(Pose pose);




    @Inject(method = "saveWithoutId", at = @At("TAIL"))
    private void save(final ValueOutput output, final CallbackInfo ci) {
        if (!SafeSaveNbt.enabled()) {
            return;
        }
        ValueOutput safe = SafeSaveNbt.child(output);
        safe.store("motion", Vec3.CODEC, this.getDeltaMovement());
        safe.putInt("tick_count", this.tickCount);
        safe.putBoolean("first_tick", this.firstTick);
        safe.store("stuck_speed_multiplier", Vec3.CODEC, this.stuckSpeedMultiplier);
        safe.store("piston_deltas", Vec3.CODEC,
                new Vec3(this.pistonDeltas[0], this.pistonDeltas[1], this.pistonDeltas[2]));
        safe.putLong("piston_deltas_game_time", this.pistonDeltasGameTime);
        safe.putBoolean("no_physics", this.noPhysics);
        safe.putBoolean("requires_precise_position", this.requiresPrecisePosition);
        safe.putFloat("move_dist", this.moveDist);
        safe.putFloat("fly_dist", this.flyDist);
        safe.putBoolean("in_powder_snow", this.isInPowderSnow);
        safe.putBoolean("was_in_powder_snow", this.wasInPowderSnow);
        safe.putBoolean("was_touching_water", this.wasTouchingWater);
        safe.putBoolean("was_eye_in_water", this.wasEyeInWater);
        ValueOutput finalSafe = safe;
        this.mainSupportingBlockPos.ifPresent(pos -> finalSafe.store("main_supporting_block_pos", BlockPos.CODEC, pos));
        safe.putBoolean("on_ground_no_blocks", this.onGroundNoBlocks);
        safe.store("pose", Pose.CODEC, this.getPose());
        safe.putLong("entity_order", this.SS$entityOrder);

    }


    @Inject(method = "load", at = @At("TAIL"))
    private void load(final ValueInput input, final CallbackInfo ci) {
        if (!SafeSaveNbt.enabled()) {
            return;
        }
        ValueInput safe = SafeSaveNbt.childOrNull(input);
        if (safe == null) {
            return;
        }
        safe.read("motion", Vec3.CODEC).ifPresent(this::setDeltaMovement);
        this.tickCount = safe.getIntOr("tick_count", this.tickCount);
        this.firstTick = safe.getBooleanOr("first_tick", this.firstTick);
        safe.read("stuck_speed_multiplier", Vec3.CODEC).ifPresent(v -> this.stuckSpeedMultiplier = v);
        safe.read("piston_deltas", Vec3.CODEC).ifPresent(v -> {
            this.pistonDeltas[0] = v.x;
            this.pistonDeltas[1] = v.y;
            this.pistonDeltas[2] = v.z;
        });
        this.pistonDeltasGameTime = safe.getLongOr("piston_deltas_game_time", this.pistonDeltasGameTime);
        this.noPhysics = safe.getBooleanOr("no_physics", this.noPhysics);
        this.requiresPrecisePosition = safe.getBooleanOr("requires_precise_position", this.requiresPrecisePosition);
        this.moveDist = safe.getFloatOr("move_dist", this.moveDist);
        this.flyDist = safe.getFloatOr("fly_dist", this.flyDist);
        this.isInPowderSnow = safe.getBooleanOr("in_powder_snow", this.isInPowderSnow);
        this.wasInPowderSnow = safe.getBooleanOr("was_in_powder_snow", this.wasInPowderSnow);
        this.wasTouchingWater = safe.getBooleanOr("was_touching_water", this.wasTouchingWater);
        this.wasEyeInWater = safe.getBooleanOr("was_eye_in_water", this.wasEyeInWater);
        safe.read("main_supporting_block_pos", BlockPos.CODEC).ifPresent(pos -> this.mainSupportingBlockPos = Optional.of(pos));
        this.onGroundNoBlocks = safe.getBooleanOr("on_ground_no_blocks", this.onGroundNoBlocks);
        safe.read("pose", Pose.CODEC).ifPresent(this::setPose);
        long order = safe.getLongOr("entity_order", this.SS$entityOrder);
        if (order != Long.MIN_VALUE) {
            this.SS$entityOrder = order;
            EntityOrderManager.observeOrder((Entity) (Object) this, order);
        }
    }
}
