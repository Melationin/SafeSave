package com.carpet.safesave.safesave.chunk;

import net.minecraft.nbt.CompoundTag;

/**
 * 由 {@code SerializableChunkDataMixin} 实现：{@code copyOf} 写、{@code write()} 读，替代原先的
 * {@code IdentityHashMap} 交接表；record 实例经线程池提交，写读之间有 happens-before。
 *
 * <p>本接口故意放在 mixin 包之外：Mixin 禁止外部代码直接引用 mixin 包
 * （{@code com.carpet.safesave.mixin.*}）里的非 mixin 类。
 */
public interface SerializableChunkDataAccess {

    CompoundTag SS$getSafeSaveTag();

    void SS$setSafeSaveTag(CompoundTag tag);
}
