package com.carpet.safesave.mixin.util;

import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;

//? if <1.21.5 {
/*import com.carpet.safesave.util.TagCompatAccess;

// Injects the 1.21.5 CompoundTag convenience API (getIntOr, store, read, ...) into 1.21.4 and older
// so the same call sites compile everywhere. On 1.21.5+ CompoundTag has these natively and the real
// methods would shadow the defaults, so the injection is skipped entirely there and this stays an
// empty no-op mixin as far as the (single, version-independent) mixin list is concerned.
@Mixin(CompoundTag.class)
public abstract class CompoundTagCompatMixin implements TagCompatAccess {
}
*///?} else {
@Mixin(CompoundTag.class)
public abstract class CompoundTagCompatMixin {
}
//?}
