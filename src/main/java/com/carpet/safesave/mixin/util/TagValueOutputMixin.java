package com.carpet.safesave.mixin.util;

//? if >=1.21.6 {
import com.carpet.safesave.util.ValueOutputAccess;
import com.mojang.serialization.DynamicOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(TagValueOutput.class)
public abstract class TagValueOutputMixin implements ValueOutputAccess
{
    @Shadow
    @Final
    public CompoundTag output;

    @Shadow
    protected abstract ProblemReporter reporterForChild(String name);

    @Shadow
    @Final
    private DynamicOps<Tag> ops;

    @Override
    public @Nullable ValueOutput getChild(String name){
        var valueOutput = this.output.get(name);
        if(valueOutput == null){
            return null;
        }else {
            return new TagValueOutput(this.reporterForChild(name), this.ops, (CompoundTag) valueOutput);
        }
    }
}
//?} else {
/*import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;

// 1.21.5 没有 TagValueOutput，直接用 CompoundTag#getCompound 取活子标签，
// 所以那里保持空 mixin，让 mixin 清单与版本无关。
@Mixin(CompoundTag.class)
public abstract class TagValueOutputMixin {
}
*///?}
