package com.carpet.safesave.safesave;

import net.minecraft.server.level.ServerLevel;

/**
 * 从 {@code ServerLevel} 上取 safe-save 维度级状态的 duck 接口。
 *
 * <p>实现：{@code ServerLevelMixin} 的 {@code @Unique} 字段。
 */
public interface SafeSaveLevelAccess {

    SafeSaveLevelState SS$safeSaveLevelState();

    /** 取 {@code level} 的维度级状态；实现类保证字段在构造期初始化，{@code null} 安全。 */
    static SafeSaveLevelState of(final ServerLevel level) {
        return ((SafeSaveLevelAccess) level).SS$safeSaveLevelState();
    }
}
