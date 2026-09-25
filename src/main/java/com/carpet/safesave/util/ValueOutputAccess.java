package com.carpet.safesave.util;

//? if >=1.21.6 {
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

public interface ValueOutputAccess
{
    default  @Nullable ValueOutput getChild(String name){
        throw new AssertionError("Implemented in Mixin");
    }
}
//?}
