package com.carpet.safesave.mixin.chunk;

import net.minecraft.nbt.CompoundTag;

/**
 * 在 {@code SerializableChunkData} record 实例上暂存 safe-save 子节点的访问器。
 *
 * <p>由 {@code SerializableChunkDataMixin} 实现：{@code copyOf}（服务器线程）把计算好的 tag
 * 写到返回值实例上，随后 {@code write()}（后台线程）从同一实例读回，替代原先的
 * {@code IdentityHashMap} 交接表。record 实例经线程池提交，写读之间有 happens-before。
 */
public interface SerializableChunkDataAccess {

    CompoundTag SS$getSafeSaveTag();

    void SS$setSafeSaveTag(CompoundTag tag);
}
