package com.carpet.safesave.util;

import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 实体/BE 侧存 NBT 读写样板：统一 {@code safesave} 子节点的取/建逻辑。
 */
public final class SafeSaveNbt {
    private SafeSaveNbt() {
    }
    public static final String KEY_SAFE_SAVE = "safeSave";
    public static boolean enabled() {
        return com.carpet.safesave.safesave.SafeSaveManager.enabled();
    }

    /**
     * 写入侧：优先复用已有 {@code safesave} 子节点；没有则创建一个。
     */
    public static ValueOutput child(final ValueOutput output) {
        if (output instanceof TagValueOutput tagValueOutput) {
            ValueOutput existing = tagValueOutput.getChild(KEY_SAFE_SAVE);
            if (existing != null) {
                return existing;
            }
            return tagValueOutput.child(KEY_SAFE_SAVE);
        }
        return output.child(KEY_SAFE_SAVE);
    }

    /**
     * 读取侧：没有 {@code safesave} 子节点时返回 {@code null}。
     */
    public static ValueInput childOrNull(final ValueInput input) {
        return input.child(KEY_SAFE_SAVE).orElse(null);
    }
}
