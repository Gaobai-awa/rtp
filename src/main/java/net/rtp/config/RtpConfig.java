package net.rtp.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.rtp.RtpMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

public class RtpConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = RtpMod.CONFIG_DIR.resolve("config.json");

    /** 传送半径（格） */
    public int maxRadius = 3000;
    /** 搜索最低 Y */
    public int minY = -64;
    /** 搜索最高 Y */
    public int maxY = 320;
    /** 每次搜索的尝试次数 */
    public int searchAttempts = 512;
    /**
     * 高空模式：传送到目标位置上方 Y=200，给予 5 秒缓降效果（鞘翅 + 漂浮）。
     * 优点：完全避免地下/洞穴/卡方块问题，无需复杂地表检测。
     * 关闭此选项时使用传统地表检测模式。
     */
    public boolean highAltitudeMode = true;
    /** 高空模式下的传送高度 */
    public int highAltitudeY = 200;

    public static RtpConfig load() {
        try {
            if (Files.exists(FILE)) {
                String json = Files.readString(FILE);
                RtpConfig cfg = GSON.fromJson(json, RtpConfig.class);
                if (cfg != null) {
                    // 确保高版本字段有默认值
                    if (!cfg.highAltitudeMode && cfg.searchAttempts < 300) {
                        cfg.searchAttempts = 512;
                    }
                    RtpMod.LOGGER.info("Loaded RTP config: maxRadius={}, highAltitudeMode={}, searchAttempts={}",
                        cfg.maxRadius, cfg.highAltitudeMode, cfg.searchAttempts);
                    return cfg;
                }
            }
        } catch (IOException e) {
            RtpMod.LOGGER.error("Failed to load RTP config", e);
        }
        RtpMod.LOGGER.info("Creating default RTP config (highAltitudeMode=true)");
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
     * 在异步线程中搜索安全地表位置。
     * 在高空模式下，返回目标 XZ 上方 highAltitudeY 处；在传统模式下，返回找到的地表方块坐标。
     */
    public TeleportResult findDestination(ServerWorld world, int centerX, int centerZ, Random random) {
        if (highAltitudeMode) {
            // 高空模式：只找一个大致位置，不追求精确地表
            int dx = random.nextInt(maxRadius * 2) - maxRadius;
            int dz = random.nextInt(maxRadius * 2) - maxRadius;
            int x = centerX + dx;
            int z = centerZ + dz;
            // 确保区块已加载（或预加载）
            ensureChunkLoaded(world, x, z);
            return new TeleportResult(x + 0.5, highAltitudeY + 0.0, z + 0.5, true);
        } else {
            // 传统模式：搜索安全地表
            for (int i = 0; i < searchAttempts; i++) {
                int dx = random.nextInt(maxRadius * 2) - maxRadius;
                int dz = random.nextInt(maxRadius * 2) - maxRadius;
                int x = centerX + dx;
                int z = centerZ + dz;

                ensureChunkLoaded(world, x, z);

                BlockPos surface = findSurfaceBlock(world, x, z);
                if (surface != null) {
                    // 确认上方至少 3 格空气（避免洞穴/树冠问题）
                    if (hasAirColumnAbove(world, surface)) {
                        return new TeleportResult(
                            surface.getX() + 0.5,
                            surface.getY() + 1.0,
                            surface.getZ() + 0.5,
                            false
                        );
                    }
                }
            }
            return null;
        }
    }

    /**
     * 搜索地表方块（固体方块 + 上方空气），返回其坐标。
     * 只搜索 y=0 到 y=256 的主世界高度范围。
     */
    private BlockPos findSurfaceBlock(ServerWorld world, int x, int z) {
        BlockPos.Mutable mut = new BlockPos.Mutable();
        for (int y = 256; y >= minY; y--) {
            mut.set(x, y, z);
            Block b = world.getBlockState(mut).getBlock();
            Block above = world.getBlockState(mut.up()).getBlock();
            Block above2 = world.getBlockState(mut.up(2)).getBlock();

            // 排除空气、液体、基岩和植被
            if (b == Blocks.AIR || b == Blocks.WATER || b == Blocks.LAVA || b == Blocks.BEDROCK) continue;
            if (b == Blocks.GRASS || b == Blocks.TALL_GRASS || b == Blocks.LILY_PAD) continue;

            // 判断底座是否固体：直接用方块硬度 + 碰撞箱
            if (!world.getBlockState(mut).isFullCube(world, mut)) continue;

            // 上方两格：检查碰撞箱是否为空（非空=有碰撞）
            if (!world.getBlockState(mut.up()).getCollisionShape(world, mut.up()).isEmpty()) continue;
            if (!world.getBlockState(mut.up(2)).getCollisionShape(world, mut.up(2)).isEmpty()) continue;

            return new BlockPos(x, y, z);
        }
        return null;
    }

    /**
     * 检查目标位置上方至少 3 格是否全是空气（非固体）。
     * 用于避免树冠、洞穴顶盖、低顶洞窟。
     */
    private boolean hasAirColumnAbove(ServerWorld world, BlockPos surface) {
        BlockPos.Mutable mut = new BlockPos.Mutable();
        for (int dy = 1; dy <= 4; dy++) {
            mut.set(surface.getX(), surface.getY() + dy, surface.getZ());
            if (!world.getBlockState(mut).getCollisionShape(world, mut).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 确保目标坐标所在区块已加载（同步加载，不会抛异常）。
     */
    private void ensureChunkLoaded(ServerWorld world, int x, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        if (!world.getChunkManager().isChunkLoaded(cx, cz)) {
            world.getChunk(cx, cz);
        }
        // 也预加载周围一圈
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                int nx = cx + ox;
                int nz = cz + oz;
                if (!world.getChunkManager().isChunkLoaded(nx, nz)) {
                    try {
                        world.getChunk(nx, nz);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    /** 传送结果封装 */
    public static class TeleportResult {
        public final double x, y, z;
        /** 是否为高空模式（需要缓降效果） */
        public final boolean needsSlowFall;

        public TeleportResult(double x, double y, double z, boolean needsSlowFall) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.needsSlowFall = needsSlowFall;
        }
    }
}