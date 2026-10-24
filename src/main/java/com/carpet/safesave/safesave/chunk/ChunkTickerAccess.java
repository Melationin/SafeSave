package com.carpet.safesave.safesave.chunk;

import com.carpet.safesave.safesave.blockentity.BlockEntityManager.PendingTicker;

import java.util.List;

public interface ChunkTickerAccess {
    void SS$stageBlockEntityTicker(PendingTicker ticker);

    List<PendingTicker> SS$takeBlockEntityTickers();
}
