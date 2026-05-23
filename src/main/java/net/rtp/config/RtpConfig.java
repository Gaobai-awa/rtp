package net.rtp.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.block.Block;
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
        return Dimension.UNKNOWN;
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

    private TeleportResult findOverworldDestination(ServerWorld world, int centerX, int centerZ, Random random) {
        for (int i = 0; i < searchAttempts; i++) {
            int dx = random.nextInt(maxRadius * 2) - maxRadius;
            int dz = random.nextInt(maxRadius * 2) - maxRadius;
            int x = centerX + dx;
            int z = centerZ + dz;

            ensureChunkLoaded(world, x, z);

            // 高空模式：生成 XZ 随机位置，Y 固定传送到 highAltitudeY
            int targetY = highAltitudeY;
            return new TeleportResult(x + 0.5, targetY, z + 0.5, true, true, 1);
        }
        // 兜底：搜索地表
        for (int i = 0; i < searchAttempts; i++) {
            int dx = random.nextInt(maxRadius * 2) - maxRadius;
            int dz = random.nextInt(maxRadius * 2) - maxRadius;
            int x = centerX + dx;
            int z = centerZ + dz;

            ensureChunkLoaded(world, x, z);
            BlockPos surface = findSurfaceBlock(world, x, z);
            if (surface != null && hasAirColumnAbove(world, surface, 5)) {
                return new TeleportResult(
                    surface.getX() + 0.5, surface.getY() + 1.0, surface.getZ() + 0.5,
                    true, false, 1   // 需要缓降，1级，落地检测开启
                );
            }
        }
        return null;
    }

    // ─────────────── 地狱 ───────────────

    private TeleportResult findNetherDestination(ServerWorld world, int centerX, int centerZ, Random random) {
        for (int i = 0; i < searchAttempts; i++) {
            int dx = random.nextInt(maxRadius * 2) - maxRadius;
            int dz = random.nextInt(maxRadius * 2) - maxRadius;
            int x = centerX + dx;
            int z = centerZ + dz;

            // 地狱预加载 5x5 区块确保地形完整
            ensureChunksLoadedNether(world, x, z);

            // 在 y=30 到 y=120 之间找固体地面
            BlockPos floor = findNetherFloor(world, x, z);
            if (floor == null) continue;

            // 最终安全检查：玩家落脚位置（x+0.5, y+1, z+0.5）不能卡在方块里
            if (!isNetherFloorSafeForPlayer(world, floor.getX(), floor.getY(), floor.getZ())) continue;

            return new TeleportResult(
                floor.getX() + 0.5, floor.getY() + 1.0, floor.getZ() + 0.5,
                false, false, 0   // 不需要缓降，不需要落地检测
            );
        }
        return null;
    }

    /**
     * 在地狱中搜索固体地面（y 从 maxY 往下找第一个有碰撞箱的方块）。
     * 跳过空气、岩浆、虚空空气、基岩。
     */
    private BlockPos findNetherFloor(ServerWorld world, int x, int z) {
        BlockPos.Mutable mut = new BlockPos.Mutable();
        for (int y = 120; y >= 30; y--) {
            mut.set(x, y, z);
            if (isNetherFloorBlock(world, mut)) {
                return new BlockPos(x, y, z);
            }
        }
        return null;
    }

    /** 判断某个位置是否为可作为落脚点的地狱固体方块（非空、非岩浆、非基岩）。 */
    private boolean isNetherFloorBlock(ServerWorld world, BlockPos.Mutable pos) {
        Block b = world.getBlockState(pos).getBlock();
        if (b == Blocks.AIR || b == Blocks.LAVA || b == Blocks.MAGMA_BLOCK
                || b == Blocks.BEDROCK || b == Blocks.VOID_AIR) return false;
        // 必须是真正有碰撞箱的固体方块（不是荧石、灵魂沙等）
        return !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    /**
     * 地狱：预加载 5x 5 区块（半径 2 格）。
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

    /**
     * 地狱落脚安全检查：确保玩家碰撞箱（2格高）的位置没有被方块占据。
     * 检查以 (bx, by, bz) 为地面中心，y=by 为脚底，y=by+1 为头顶。
     * 周围 3x3 范围内不能有岩浆。
     */
    private boolean isNetherFloorSafeForPlayer(ServerWorld world, int bx, int by, int bz) {
        // 基础高度保护
        if (by <= 5) return false; // 基岩层以上太近
        if (by >= 118) return false; // 太接近天花板

        // 检查玩家落脚位置（脚底和头顶）是否被固体占据
        BlockPos footPos = new BlockPos(bx, by, bz);
        BlockPos headPos = new BlockPos(bx, by + 1, bz);
        if (!world.getBlockState(footPos).getCollisionShape(world, footPos).isEmpty()) return false;
        if (!world.getBlockState(headPos).getCollisionShape(world, headPos).isEmpty()) return false;

        // 周围 3x3 范围内检查岩浆（玩家可能会偏移）
        BlockPos.Mutable mut = new BlockPos.Mutable();
        for (int odx = -1; odx <= 1; odx++) {
            for (int odz = -1; odz <= 1; odz++) {
                for (int ody = -1; ody <= 2; ody++) {
                    mut.set(bx + odx, by + ody, bz + odz);
                    if (world.getBlockState(mut).isOf(Blocks.LAVA)) return false;
                }
            }
        }
        return true;
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
            x + 0.5, 120.0, z + 0.5,
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