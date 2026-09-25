package com.carpet.safesave.safesave.chunk;

import net.minecraft.nbt.CompoundTag;


public interface SerializableChunkDataAccess {

    CompoundTag SS$getSafeSaveTag();

    void SS$setSafeSaveTag(CompoundTag tag);
}
