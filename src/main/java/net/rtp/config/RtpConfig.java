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
import java.util.Locale;
import java.util.Random;

public class RtpConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = RtpMod.CONFIG_DIR.resolve("config.json");

    public int maxRadius = 3000;
    public int minY = -64;
    public int maxY = 320;
    public int searchAttempts = 512;

    // 黑名单（部分匹配，大写比较）
    private static final String[] OVERWORLD_BAD = {
        "WATER", "LAVA", "GRASS", "TALL_GRASS", "LILY", "SNOW", "CAKE",
        "SAPLING", "MUSHROOM", "REEDS", "CACTUS", "CORAL", "KELP", "SEA_PICKLE"
    };
    private static final String[] NETHER_BAD = {
        "LAVA", "MAGMA", "FIRE", "SOUL", "BED", "SOUL_LANTERN", "SOUL_TORCH", "RESPAWN_ANCHOR"
    };
    private static final String[] END_BAD = {
        "SHULKER", "BEDROCK"  // 黑曜石柱用空检测排除即可
    };

    public static RtpConfig load() {
        try {
            if (Files.exists(FILE)) {
                String json = Files.readString(FILE);
                RtpConfig cfg = GSON.fromJson(json, RtpConfig.class);
                if (cfg != null) {
                    RtpMod.LOGGER.info("Loaded RTP config: maxRadius={}, searchAttempts={}",
                        cfg.maxRadius, cfg.searchAttempts);
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

    // ─────────────── 维度判断 ───────────────

    public static Dimension getDimension(ServerWorld world) {
        RegistryKey<World> key = world.getRegistryKey();
        if (key == World.NETHER) return Dimension.NETHER;
        if (key == World.END)    return Dimension.END;
        if (key == World.OVERWORLD) return Dimension.OVERWORLD;
        return Dimension.UNKNOWN;
    }

    public static enum Dimension {
        OVERWORLD, NETHER, END, UNKNOWN
    }

    // ─────────────── 入口 ───────────────

    public TeleportResult findDestination(ServerWorld world, int centerX, int centerZ, Random random) {
        Dimension dim = getDimension(world);
        switch (dim) {
            case NETHER:    return findNetherDestination(world, centerX, centerZ, random);
            case END:       return findEndDestination(world, centerX, centerZ, random);
            default:         return findOverworldDestination(world, centerX, centerZ, random);
        }
    }

    // ─────────────── 主世界（BetterRTP 核心算法）──
    // 1. 生成随机 XZ
    // 2. world.getHighestBlockAt() 获取最高非空方块（高度图）
    // 3. 若最高方块非固体（水/草/灌木），退到 y-1
    // 4. 确认 y 在 [minY, maxY]，且不在黑名单
    // 5. 传送到 (x+0.5, y+1, z+0.5)

    private TeleportResult findOverworldDestination(ServerWorld world, int centerX, int centerZ, Random random) {
        BlockPos.Mutable mut = new BlockPos.Mutable();

        // 从世界最大高度向下扫描（Fabric 1.20.1 没有 getHighestY()，用 320 作为起始）
        final int topY = 320;

        for (int attempt = 0; attempt < searchAttempts; attempt++) {
            int dx = random.nextInt(maxRadius * 2) - maxRadius;
            int dz = random.nextInt(maxRadius * 2) - maxRadius;
            int x = centerX + dx;
            int z = centerZ + dz;

            ensureChunksLoaded(world, x, z);

            // BetterRTP 风格：使用高度图，从 topY 向下找第一个非空方块
            mut.set(x, topY, z);

            // 从最高处向下找到第一个非空方块
            while (mut.getY() > minY) {
                BlockState s = world.getBlockState(mut);
                if (!s.isAir() && !s.isLiquid()) break;
                mut.setY(mut.getY() - 1);
            }

            int highestBlockY = mut.getY();
            if (highestBlockY < minY) continue;

            BlockState highestState = world.getBlockState(mut);

            // 若最高方块非固体（如草/水下/灌木），看脚下 y-1
            int surfaceY = highestBlockY;
            if (!highestState.isFullCube(world, mut)) {
                // 草/水/植物：脚下才是真正的地面
                surfaceY = highestBlockY - 1;
                if (surfaceY < minY) continue;
                mut.setY(surfaceY);
            }

            BlockState surfaceState = world.getBlockState(mut);

            // y 范围检查
            if (surfaceY < minY || surfaceY > maxY) continue;

            // 黑名单检查
            if (isBadBlock(surfaceState, OVERWORLD_BAD)) continue;

            // 头顶 1 格必须是空气
            mut.setY(surfaceY + 1);
            if (!world.getBlockState(mut).isAir()) continue;
            // 再上面 1 格也检查一下（BetterRTP 确保 2 格高度）
            mut.setY(surfaceY + 2);
            if (!world.getBlockState(mut).isAir()) continue;

            // 脚下必须是固体
            mut.setY(surfaceY - 1);
            BlockState below = world.getBlockState(mut);
            if (below.isAir() || below.isLiquid()) continue;
            if (isBadBlock(below, OVERWORLD_BAD)) continue;

            return new TeleportResult(
                x + 0.5, surfaceY + 1.0, z + 0.5,
                false, false, 0
            );
        }
        return null;
    }

    // ─────────────── 地狱（BetterRTP NETHER 算法）────
    // 从 minY+1 向上扫，找「当前非空+脚下固体+头顶空气」
    // 这样找到的是 ceiling 和 floor 之间的空洞（玩家可站立）

    private TeleportResult findNetherDestination(ServerWorld world, int centerX, int centerZ, Random random) {
        final int scanMinY = 32;
        final int scanMaxY = 118;

        for (int attempt = 0; attempt < searchAttempts; attempt++) {
            int dx = random.nextInt(maxRadius * 2) - maxRadius;
            int dz = random.nextInt(maxRadius * 2) - maxRadius;
            int x = centerX + dx;
            int z = centerZ + dz;

            ensureChunksLoaded(world, x, z);

            BlockPos.Mutable cur = new BlockPos.Mutable(x, scanMinY + 1, z);
            for (int y = scanMinY + 1; y < scanMaxY; y++) {
                cur.setY(y);

                BlockState curState = world.getBlockState(cur);
                // 跳过空气
                if (curState.isAir()) continue;
                // 跳过液体（岩浆/水）
                if (curState.isLiquid()) continue;
                // 跳过黑名单方块
                if (isBadBlock(curState, NETHER_BAD)) continue;

                // 脚下方块必须是固体（非空）
                cur.setY(y - 1);
                BlockState below = world.getBlockState(cur);
                if (below.isAir() || below.isLiquid()) continue;
                if (isBadBlock(below, NETHER_BAD)) continue;

                // 头顶（y+1）必须是空气
                cur.setY(y + 1);
                if (!world.getBlockState(cur).isAir()) continue;

                // 玩家安全检查：周围 3x3 无岩浆
                if (!isNetherPlayerSafe(world, x, y, z)) continue;

                // 传送位置：y（脚在 y，y+1 是空气，即 y+1 是玩家头部位置）
                return new TeleportResult(x + 0.5, y + 0.0, z + 0.5, false, false, 0);
            }
        }
        return null;
    }

    /** 地狱：玩家落脚点周围 3x3 无岩浆 */
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

    // ─────────────── 末地（BetterRTP END 算法，删除缓降）────
    // 与地狱相同：从 minY+1 向上扫，找「当前非空+脚下固体+头顶空气」

    private TeleportResult findEndDestination(ServerWorld world, int centerX, int centerZ, Random random) {
        final int scanMinY = 32;
        final int scanMaxY = 200;

        for (int attempt = 0; attempt < searchAttempts; attempt++) {
            int dx = random.nextInt(maxRadius * 2) - maxRadius;
            int dz = random.nextInt(maxRadius * 2) - maxRadius;
            int x = centerX + dx;
            int z = centerZ + dz;

            ensureChunksLoaded(world, x, z);

            BlockPos.Mutable cur = new BlockPos.Mutable(x, scanMinY + 1, z);
            for (int y = scanMinY + 1; y < scanMaxY; y++) {
                cur.setY(y);

                BlockState curState = world.getBlockState(cur);
                // 跳过空气/虚空空气
                if (curState.isAir()) continue;
                // 跳过液体
                if (curState.isLiquid()) continue;
                // 跳过黑名单
                if (isBadBlock(curState, END_BAD)) continue;

                // 脚下方块必须是固体
                cur.setY(y - 1);
                BlockState below = world.getBlockState(cur);
                if (below.isAir() || below.isLiquid()) continue;
                if (isBadBlock(below, END_BAD)) continue;

                // 头顶必须空气
                cur.setY(y + 1);
                if (!world.getBlockState(cur).isAir()) continue;

                // 脚下是末地石或虚空（但头顶有空气）→ 有效传送点
                // 传送到 y（玩家脚底），y+1 是空气
                return new TeleportResult(x + 0.5, y + 0.0, z + 0.5, false, false, 0);
            }
        }
        return null;
    }

    // ─────────────── 通用工具 ───────────────

    /** 方块名是否在黑名单中（部分匹配，大写比较）。 */
    private boolean isBadBlock(BlockState state, String[] bad) {
        String name = state.getBlock().getName().getString().toUpperCase(Locale.ROOT);
        for (String b : bad) {
            if (name.contains(b)) return true;
        }
        return false;
    }

    /**
     * 预加载 5x5 区块。
     */
    private void ensureChunksLoaded(ServerWorld world, int x, int z) {
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

    // ─────────────── 结果封装 ───────────────

    public static class TeleportResult {
        public final double x, y, z;
        public final boolean needsSlowFall;
        public final boolean needsLandingCheck;
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