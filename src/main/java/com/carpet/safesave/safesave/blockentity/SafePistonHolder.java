package com.carpet.safesave.safesave.blockentity;

/**
 * 注入 {@code PistonMovingBlockEntity} 的鸭子接口：暴露 safe-save 所需的活塞状态快照/恢复。
 */
public interface SafePistonHolder extends PistonOrderHolder {

    /** 生成当前活塞的 safe-save 快照。 */
    SafePiston SS$snapshotPiston();

    /** 用快照覆盖当前活塞的 safe-save 字段（progress/progressO/lastTicked/order）。 */
    void SS$restorePiston(SafePiston piston);

    /** 设置全局创建序号。 */
    void SS$setPistonOrder(long order);
}
