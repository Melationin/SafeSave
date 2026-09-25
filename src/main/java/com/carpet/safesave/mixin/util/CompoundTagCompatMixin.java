package com.carpet.safesave.mixin.util;

import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;

//? if <1.21.5 {
/*import com.carpet.safesave.util.TagCompatAccess;

// 1.21.5+ 保持空 mixin，让 mixin 清单与版本无关。
@Mixin(CompoundTag.class)
public abstract class CompoundTagCompatMixin implements TagCompatAccess {
}
*///?} else {
@Mixin(CompoundTag.class)
public abstract class CompoundTagCompatMixin {
}
//?}
