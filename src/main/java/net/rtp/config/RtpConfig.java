package net.rtp.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.block.BlockState;
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
    public int searchAttempts = 100;

    public static RtpConfig load() {
        try {
            if (Files.exists(FILE)) {
                String json = Files.readString(FILE);
                RtpConfig cfg = GSON.fromJson(json, RtpConfig.class);
                if (cfg != null) {
                    RtpMod.LOGGER.info("Loaded RTP config: maxRadius={}, minY={}, maxY={}",
                        cfg.maxRadius, cfg.minY, cfg.maxY);
                    return cfg;
                }
            }
        } catch (IOException e) {
            RtpMod.LOGGER.error("Failed to load RTP config", e);
        }
        RtpMod.LOGGER.info("Creating default RTP config with maxRadius=3000");
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
     * Search for a valid surface position near (centerX, centerZ).
     * Returns the block position of the solid surface block.
     * The caller should teleport to (surface.x + 0.5, surface.y + 1, surface.z + 0.5).
     */
    public BlockPos findSurface(World world, int centerX, int centerZ, Random random) {
        for (int i = 0; i < searchAttempts; i++) {
            int dx = random.nextInt(maxRadius * 2) - maxRadius;
            int dz = random.nextInt(maxRadius * 2) - maxRadius;
            int x = centerX + dx;
            int z = centerZ + dz;

            // Scan from maxY downward for first solid block with air above
            for (int y = maxY; y >= minY; y--) {
                BlockPos pos = new BlockPos(x, y, z);
                BlockState block = world.getBlockState(pos);
                BlockState above = world.getBlockState(pos.up());

                if (!block.isAir() && !block.isLiquid()
                        && above.isAir() && !above.isLiquid()) {
                    return pos;
                }
            }
        }
        return null;
    }

    /**
     * Check if a 2x2 area at (x, y, z) is safe for a player to stand in.
     * Both y and y+1 layers must be air or non-colliding blocks.
     */
    public static boolean isSafeLocation(World world, int x, int y, int z) {
        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                BlockPos pos = new BlockPos(x + dx, y, z + dz);
                BlockPos posAbove = new BlockPos(x + dx, y + 1, z + dz);
                BlockState block = world.getBlockState(pos);
                BlockState above = world.getBlockState(posAbove);

                if (!block.isAir() || !above.isAir()) {
                    return false;
                }
            }
        }
        return true;
    }
}