package net.rtp.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.rtp.RtpMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

public class RtpConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = RtpMod.CONFIG_DIR.resolve("config.json");

    public int maxRadius = 3000;
    public int minY = -64;
    public int maxY = 320;
    public int searchAttempts = 512;
    public int highAltitudeY = 200;

    public static RtpConfig load() {
        try {
            if (Files.exists(FILE)) {
                String json = Files.readString(FILE);
                RtpConfig cfg = GSON.fromJson(json, RtpConfig.class);
                if (cfg != null) {
                    RtpMod.LOGGER.info("Loaded RTP config: maxRadius={}, highAltitudeY={}",
                        cfg.maxRadius, cfg.highAltitudeY);
                    return cfg;
                }
            }
        } catch (IOException e) {
            RtpMod.LOGGER.error("Failed to load RTP config", e);
        }
        RtpMod.LOGGER.info("Creating default RTP config");
        RtpConfig cfg = new RtpConfig();
        cfg.save();
        return cfg;
    }

    public void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(this));
        } catch (IOException e) {
            RtpMod.LOGGER.error("Failed to save RTP config", e);
        }
    }

    /**
     * 判断当前世界类型。
     */
    public static Dimension getDimension(ServerWorld world) {
        RegistryKey<World> key = world.getRegistryKey();
        if (key == World.NETHER) return Dimension.NETHER;
        if (key == World.END)    return Dimension.END;
        if (key == World.OVERWORLD) return Dimension.OVERWORLD;
        return Dimension.UNKNOWN; // 其他 mod 自定义维度兜底
    }

    public static enum Dimension {
        OVERWORLD, NETHER, END, UNKNOWN
    }

    /**
     * 搜索传送目的地。
     *
     * @return TeleportResult，null 表示未找到。
     *         targetY 已经是传送用的绝对坐标（y + 1）。
     */
    public TeleportResult findDestination(ServerWorld world, int centerX, int centerZ, Random random) {
        Dimension dim = getDimension(world);
        switch (dim) {
            case NETHER: return findNetherDestination(world, centerX, centerZ, random);
            case END:    return findEndDestination(world, centerX, centerZ, random);
            default:     return findOverworldDestination(world, centerX, centerZ, random);
        }
    }

    // ─────────────── 主世界 ───────────────
    // 主世界也采用 BetterRTP 算法：
    // 从 y=maxY 向下扫描，找 "脚下固体 + 头顶 1 格空气" 的位置，直接传送，无需缓降。

    private TeleportResult findOverworldDestination(ServerWorld world, int centerX, int centerZ, Random random) {
        for (int attempt = 0; attempt < searchAttempts; attempt++) {
            int dx = random.nextInt(maxRadius * 2) - maxRadius;
            int dz = random.nextInt(maxRadius * 2) - maxRadius;
            int x = centerX + dx;
            int z = centerZ + dz;

            ensureChunkLoaded(world, x, z);

            BlockPos surface = findOverworldSurface(world, x, z);
            if (surface != null) {
                return new TeleportResult(
                    surface.getX() + 0.5, surface.getY() + 1.0, surface.getZ() + 0.5,
                    false, false, 0   // 直传，不需要缓降
                );
            }
        }
        return null;
    }

    /**
     * 主世界地表搜索：从 maxY 向下，找脚下固体 + 头顶 1 格空气。
     * 排除洞穴/低位。
     */
    private BlockPos findOverworldSurface(ServerWorld world, int x, int z) {
        BlockPos.Mutable mut = new BlockPos.Mutable();
        // 黑名单（部分匹配）
        String[] bad = { "LAVA", "WATER", "GRASS", "TALL_GRASS", "LILY", "SNOW", "CAKE" };

        for (int y = 256; y >= minY; y--) {
            mut.set(x, y, z);
            BlockState state = world.getBlockState(mut);
            if (state.isAir()) continue;
            if (state.isLiquid()) continue;
            if (isBadBlock(state, bad)) continue;

            // 脚下
            mut.set(x, y - 1, z);
            BlockState below = world.getBlockState(mut);
            if (below.isAir() || below.isLiquid() || isBadBlock(below, bad)) continue;
            if (isBadBlock(state, bad)) continue;

            // 头顶 1 格空气
            mut.set(x, y + 1, z);
            if (!world.getBlockState(mut).isAir()) continue;

            return new BlockPos(x, y, z);
        }
        return null;
    }

    // ─────────────── 地狱 ───────────────
    // 算法参考 BetterRTP：从 minY+1 往上扫空气，
    // 找到空气后检查脚下(y-1)是固体、头顶(y+1)是空气。
    // 这样能找到 ceiling（固体天花板）和 solid floor（落脚点）之间的空洞。

    private TeleportResult findNetherDestination(ServerWorld world, int centerX, int centerZ, Random random) {
        // 地狱高度：minY=30（略高于基岩层），maxY=118（天花板以下）
        final int minY = 30;
        final int maxY = 118;
        // 黑名单方块名（部分匹配，跳过）
        String[] badBlockNames = {
            "LAVA", "MAGMA", "FIRE", "SOUL", "BED"
        };

        for (int attempt = 0; attempt < searchAttempts; attempt++) {
            int dx = random.nextInt(maxRadius * 2) - maxRadius;
            int dz = random.nextInt(maxRadius * 2) - maxRadius;
            int x = centerX + dx;
            int z = centerZ + dz;

            // 预加载 5x5 区块
            ensureChunksLoadedNether(world, x, z);

            // 从 minY+1 往上扫（BetterRTP 核心算法）
            BlockPos.Mutable cur = new BlockPos.Mutable(x, minY + 1, z);
            for (int y = minY + 1; y < maxY; y++) {
                cur.set(x, y, z);

                // 当前方块不是空气/不是固体（液体如岩浆）
                BlockState curState = world.getBlockState(cur);
                if (curState.isAir()) continue; // 当前是空气，跳过
                if (curState.isLiquid()) continue; // 岩浆/水跳过
                // 检查是否是黑名单方块
                if (isBadBlock(curState, badBlockNames)) continue;

                // 脚下方块（y-1）必须是固体
                cur.set(x, y - 1, z);
                BlockState belowState = world.getBlockState(cur);
                if (belowState.isAir() || belowState.isLiquid()) continue; // 脚下是空气/岩浆，跳过
                if (isBadBlock(belowState, badBlockNames)) continue;

                // 头顶（y+1）必须是空气
                cur.set(x, y + 1, z);
                BlockState aboveState = world.getBlockState(cur);
                if (!aboveState.isAir()) continue; // 头顶不是空气，跳过

                // 最终确认落脚点安全（玩家碰撞箱2格高，周围无岩浆）
                if (!isNetherPlayerSafe(world, x, y, z)) continue;

                return new TeleportResult(
                    x + 0.5, y + 0.0, z + 0.5,
                    false, false, 0   // 地狱直传，不需要缓降/落地检测
                );
            }
        }
        return null;
    }

    /** 判断方块名是否在黑名单中（部分匹配，大写比较）。 */
    private boolean isBadBlock(BlockState state, String[] bad) {
        String name = state.getBlock().getName().getString().toUpperCase(java.util.Locale.ROOT);
        for (String b : bad) {
            if (name.contains(b)) return true;
        }
        return false;
    }

    /** 地狱落脚安全检查：玩家位置(x+0.5, y, z+0.5) 周围3x3无岩浆。 */
    private boolean isNetherPlayerSafe(ServerWorld world, int bx, int by, int bz) {
        BlockPos.Mutable mut = new BlockPos.Mutable();
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                for (int oy = -1; oy <= 2; oy++) {
                    mut.set(bx + ox, by + oy, bz + oz);
                    if (world.getBlockState(mut).isOf(Blocks.LAVA)) return false;
                }
            }
        }
        return true;
    }

    /**
     * 地狱：预加载 5x5 区块。
     */
    private void ensureChunksLoadedNether(ServerWorld world, int x, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        for (int ox = -2; ox <= 2; ox++) {
            for (int oz = -2; oz <= 2; oz++) {
                int nx = cx + ox;
                int nz = cz + oz;
                if (!world.getChunkManager().isChunkLoaded(nx, nz)) {
                    try { world.getChunk(nx, nz); } catch (Exception ignored) {}
                }
            }
        }
    }

    // ─────────────── 末地 ───────────────

    private TeleportResult findEndDestination(ServerWorld world, int centerX, int centerZ, Random random) {
        // 末地主岛高度约 50-70，周围是虚空
        // 策略：优先在主岛范围内找地表，否则传送到虚空高空（+ 强力缓降）
        int startY = 70;
        int endY = 40;
        BlockPos.Mutable mut = new BlockPos.Mutable();

        for (int i = 0; i < searchAttempts; i++) {
            int dx = random.nextInt(maxRadius * 2) - maxRadius;
            int dz = random.nextInt(maxRadius * 2) - maxRadius;
            int x = centerX + dx;
            int z = centerZ + dz;

            ensureChunkLoaded(world, x, z);

            // 在 40-75 范围搜索固体地面
            for (int y = startY; y >= endY; y--) {
                mut.set(x, y, z);
                Block b = world.getBlockState(mut).getBlock();
                Block above = world.getBlockState(mut.up()).getBlock();
                Block above2 = world.getBlockState(mut.up(2)).getBlock();

                // 末地：排除虚空、已存在的末地石、柱子
                if (b == Blocks.AIR || b == Blocks.VOID_AIR || b == Blocks.END_STONE) continue;

                // 落脚安全吗？
                if (!world.getBlockState(mut).getCollisionShape(world, mut).isEmpty() &&
                    world.getBlockState(mut.up()).getCollisionShape(world, mut.up()).isEmpty() &&
                    world.getBlockState(mut.up(2)).getCollisionShape(world, mut.up(2)).isEmpty()) {

                    // 确认远离黑曜石柱（柱子在 50,60,70 等高度，岛屿外缘）
                    // 用简单的虚空检测：y+3 之上还是空气 → 可能是岛屿边缘
                    BlockPos highCheck = new BlockPos(x, y + 5, z);
                    if (!world.getBlockState(highCheck).isOf(Blocks.AIR) &&
                        !world.getBlockState(highCheck).isOf(Blocks.END_STONE)) {
                        // 这可能是黑曜石柱，跳过
                        continue;
                    }

                    return new TeleportResult(
                        x + 0.5, y + 1.0, z + 0.5,
                        true, true, 4   // 缓降4级(amp=3)，落地检测开启
                    );
                }
            }
        }

        // 实在找不到岛屿 → 高空传送（+ 缓降4级 amp=3）
        int dx = random.nextInt(maxRadius * 2) - maxRadius;
        int dz = random.nextInt(maxRadius * 2) - maxRadius;
        int x = centerX + dx;
        int z = centerZ + dz;
        ensureChunksLoadedEnd(world, x, z);

        return new TeleportResult(
            x + 0.5, 200.0, z + 0.5,
            true, true, 4   // 缓降4级(amp=3)，落地检测开启
        );
    }

    // ─────────────── 通用工具 ───────────────

    /**
     * 搜索主世界地表方块（固体 + 上方多格空气）。
     */
    private BlockPos findSurfaceBlock(ServerWorld world, int x, int z) {
        BlockPos.Mutable mut = new BlockPos.Mutable();
        for (int y = maxY; y >= minY; y--) {
            mut.set(x, y, z);
            Block b = world.getBlockState(mut).getBlock();
            if (b == Blocks.AIR || b == Blocks.WATER || b == Blocks.LAVA || b == Blocks.BEDROCK) continue;
            if (b == Blocks.GRASS || b == Blocks.TALL_GRASS || b == Blocks.LILY_PAD) continue;

            if (!world.getBlockState(mut).isFullCube(world, mut)) continue;
            if (!world.getBlockState(mut.up()).getCollisionShape(world, mut.up()).isEmpty()) continue;
            if (!world.getBlockState(mut.up(2)).getCollisionShape(world, mut.up(2)).isEmpty()) continue;

            return new BlockPos(x, y, z);
        }
        return null;
    }

    /**
     * 检查 surface 上方 n 格是否全空气。
     */
    private boolean hasAirColumnAbove(ServerWorld world, BlockPos surface, int count) {
        BlockPos.Mutable mut = new BlockPos.Mutable(surface.getX(), surface.getY(), surface.getZ());
        for (int dy = 1; dy <= count; dy++) {
            mut.set(surface.getX(), surface.getY() + dy, surface.getZ());
            if (!world.getBlockState(mut).getCollisionShape(world, mut).isEmpty()) return false;
        }
        return true;
    }

    /**
     * 预加载 5x5 区块（半径 2）以保证末地地形完整加载。
     */
    private void ensureChunksLoadedEnd(ServerWorld world, int x, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        for (int ox = -2; ox <= 2; ox++) {
            for (int oz = -2; oz <= 2; oz++) {
                int nx = cx + ox;
                int nz = cz + oz;
                if (!world.getChunkManager().isChunkLoaded(nx, nz)) {
                    try { world.getChunk(nx, nz); } catch (Exception ignored) {}
                }
            }
        }
    }

    /**
     * 确保目标区块已加载（通用方法，3x3）。
     */
    private void ensureChunkLoaded(ServerWorld world, int x, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                int nx = cx + ox;
                int nz = cz + oz;
                if (!world.getChunkManager().isChunkLoaded(nx, nz)) {
                    try { world.getChunk(nx, nz); } catch (Exception ignored) {}
                }
            }
        }
    }

    // ─────────────── 结果封装 ───────────────

    public static class TeleportResult {
        /** 传送目标坐标 */
        public final double x, y, z;
        /** 是否需要缓降效果 */
        public final boolean needsSlowFall;
        /** 是否需要落地检测（关闭缓降、清除效果）。地狱不需要。 */
        public final boolean needsLandingCheck;
        /** 缓降等级：1=普通，2=强力（末地/无鞘翅时） */
        public final int slowFallAmplifier;

        public TeleportResult(double x, double y, double z,
                               boolean needsSlowFall,
                               boolean needsLandingCheck,
                               int slowFallAmplifier) {
            this.x = x; this.y = y; this.z = z;
            this.needsSlowFall = needsSlowFall;
            this.needsLandingCheck = needsLandingCheck;
            this.slowFallAmplifier = slowFallAmplifier;
        }
    }
}