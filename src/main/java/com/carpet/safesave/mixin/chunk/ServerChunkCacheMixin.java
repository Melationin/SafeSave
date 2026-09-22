package com.carpet.safesave.mixin.chunk;

import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import com.carpet.safesave.safesave.region.RegionLifecycle;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {
    @Shadow @Final private ServerLevel level;

    @Shadow
    public abstract LevelChunk getChunkNow(int x, int z);

    /**
     * 内部 {@code getBlockState}/{@code getBlockEntity} 调用会不断续 UNKNOWN 票，在我们自己的
     * 区域票据之上把一台活动机器变成永久区块加载器 —— 这会让 {@code RegionTicketPolicy} 的
     * "绝不自我续期"不变式在运行时失效。这里拦掉这类票。
     *
     * <p><strong>为什么挂在方法本体而不是调用点上</strong>：原实现是 {@code @WrapOperation}，
     * 选择器为 {@code method = "getChunkFutureMainThread"}。锂的 {@code world.chunk_access}
     * 优化（{@code MixinConfigOption.enabled} 默认为 {@code true}，装锂即生效）{@code @Overwrite}
     * 了整个 {@code getChunk}，并新增 {@code getChunkBlocking → createChunkLoadTicket → addTicket}
     * 这条调用路径 —— 它不在原选择器作用域内，守卫对它完全无效。改挂 {@code addTicket} 本体后，
     * 守卫对"谁调 {@code addTicket}"不再敏感：锂、C2ME 或任何其它改动区块加载的模组新增调用路径
     * 都自动被覆盖，也不再依赖 mixin 应用顺序。
     *
     * <p><strong>为什么还要求区块已 FULL 加载</strong>：原版（{@code getChunkFutureMainThread}）
     * 与锂（{@code getChunkBlocking}）在 {@code addTicket} 之后都做了
     * {@code if (chunkAbsent(holder, level)) throw new IllegalStateException("No chunk holder
     * after ticket has been added")}。无条件跳过会给这条路径开一个抛异常窗口。而已知区块 FULL
     * 时 holder 级别 ≤ 33（{@code ChunkLevel.FULL_CHUNK_LEVEL}），任何 {@code targetStatus}
     * 对应的票据级别都是 {@code 33 + radius ≥ 33}，必然不 absent，跳过是安全的；区块尚未加载时
     * 则放行，加载行为与关闭本 mod 时一致。{@code getChunkNow} 在非主线程返回 null，此处会退化为
     * "不跳过"，方向也是保守的。
     */
    @Inject(method = "addTicket(Lnet/minecraft/server/level/Ticket;Lnet/minecraft/world/level/ChunkPos;)V",
            at = @At("HEAD"), cancellable = true)
    private void SS$avoidSelfRetention(final Ticket ticket, final ChunkPos pos, final CallbackInfo ci) {
        if (ticket.getType() != TicketType.UNKNOWN) {
            return;
        }
        if (!RegionLifecycle.coveredByRegionTicket(this.level, pos.pack())) {
            return;
        }
        if (this.getChunkNow(pos.x(), pos.z()) != null) {
            ci.cancel();
        }
    }
}
