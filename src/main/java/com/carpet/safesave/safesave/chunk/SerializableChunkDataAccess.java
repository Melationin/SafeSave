package com.carpet.safesave.safesave.chunk;

import net.minecraft.nbt.CompoundTag;

/*
 * 由 SerializableChunkDataMixin 实现：copyOf（服务器线程）写、write()
 * （后台写线程）读；record 实例经线程池提交，写读之间有 happens-before。
 *
 * 本接口故意放在 mixin 包之外：Mixin 禁止外部代码直接引用 mixin 包
 * （com.carpet.safesave.mixin.*）里的非 mixin 类。
 */
public interface SerializableChunkDataAccess {

    CompoundTag SS$getSafeSaveTag();

    void SS$setSafeSaveTag(CompoundTag tag);
}
