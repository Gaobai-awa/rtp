package net.rtp.util;

import net.minecraft.block.BlockState;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.rtp.config.RtpConfig;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class CountdownTask {

    public static final int COUNTDOWN_TICKS = 60; // 3 seconds at 20 TPS
    private static final double SPIRAL_RADIUS = 1.5;
    private static final double SPIRAL_ANGLE_PER_TICK = Math.toRadians(30);
    private static final double SPIRAL_HEIGHT_PER_TICK = 0.1;
    private static final double MOVE_THRESHOLD_SQ = 0.01; // squared distance to detect movement

    private static final Map<UUID, CountdownState> activeTasks = new HashMap<>();

    public static class TeleportTarget {
        public final double x, y, z;
        public final float yaw, pitch;
        public final String worldId;

        public TeleportTarget(double x, double y, double z, float yaw, float pitch, String worldId) {
            this.x = x; this.y = y; this.z = z;
            this.yaw = yaw; this.pitch = pitch;
            this.worldId = worldId;
        }
    }

    private static class CountdownState {
        final UUID playerUuid;
        final TeleportTarget target;
        final Vec3d startPos;
        final String playerName;
        int remainingTicks;
        int totalTicks;

        CountdownState(ServerPlayerEntity player, TeleportTarget target) {
            this.playerUuid = player.getUuid();
            this.playerName = player.getName().getString();
            this.target = target;
            this.startPos = player.getPos();
            this.remainingTicks = COUNTDOWN_TICKS;
            this.totalTicks = 0;
        }

        /**
         * Check if the player is still valid (online, alive, same world constraints don't matter).
         */
        boolean isValid(MinecraftServer server) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUuid);
            if (player == null) return false;
            if (!player.isAlive()) return false;
            return true;
        }

        ServerPlayerEntity getPlayer(MinecraftServer server) {
            return server.getPlayerManager().getPlayer(playerUuid);
        }
    }

    public static boolean startCountdown(ServerPlayerEntity player, TeleportTarget target) {
        if (activeTasks.containsKey(player.getUuid())) {
            return false;
        }
        activeTasks.put(player.getUuid(), new CountdownState(player, target));
        RtpMod.LOGGER.info("RTP countdown started for {} -> ({:.1f}, {:.1f}, {:.1f})",
            player.getName().getString(), target.x, target.y, target.z);
        return true;
    }

    public static void tickAll(MinecraftServer server) {
        if (activeTasks.isEmpty()) return;

        Iterator<Map.Entry<UUID, CountdownState>> iter = activeTasks.entrySet().iterator();
        while (iter.hasNext()) {
            CountdownState state = iter.next().getValue();

            // 1. Check if player is still online and alive
            ServerPlayerEntity player = state.getPlayer(server);
            if (player == null) {
                // Player disconnected
                iter.remove();
                RtpMod.LOGGER.info("RTP cancelled for {} (disconnected)", state.playerName);
                continue;
            }
            if (!player.isAlive()) {
                // Player died during countdown
                player.sendMessage(Text.literal("\u00a7c[RTP] \u4f60\u5728\u5012\u8ba1\u65f6\u95f4\u6b7b\u4ea1\uff0c\u4f20\u9001\u5df2\u53d6\u6d88"), false);
                iter.remove();
                RtpMod.LOGGER.info("RTP cancelled for {} (died)", state.playerName);
                continue;
            }

            // 2. Check if player moved during countdown
            Vec3d currentPos = player.getPos();
            if (currentPos.squaredDistanceTo(state.startPos) > MOVE_THRESHOLD_SQ) {
                player.sendMessage(Text.literal("\u00a7c[RTP] \u4f60\u5728\u5012\u8ba1\u65f6\u95f4\u79fb\u52a8\u4e86\uff01\u4f20\u9001\u5df2\u53d6\u6d88\uff01"), false);
                iter.remove();
                RtpMod.LOGGER.info("RTP cancelled for {} (moved)", state.playerName);
                continue;
            }

            state.remainingTicks--;
            state.totalTicks++;

            // 3. Show countdown title message every second
            if (state.remainingTicks > 0 && state.remainingTicks % 20 == 0) {
                int seconds = (state.remainingTicks / 20) + 1;
                player.sendMessage(Text.literal("\u00a76[RTP] \u5373\u5c06\u4f20\u9001..." + "\u00a7f" + seconds + " \u79d2"), true);
            }

            // 4. Spiral particle effect every 2 ticks (0.1s)
            if (state.totalTicks % 2 == 0) {
                ServerWorld world = (ServerWorld) player.getWorld();
                double angle = state.totalTicks * SPIRAL_ANGLE_PER_TICK;
                double rise = state.totalTicks * SPIRAL_HEIGHT_PER_TICK;

                for (int side = 0; side < 2; side++) {
                    double sign = (side == 0) ? 1.0 : -1.0;
                    double px = currentPos.x + Math.cos(angle + side * Math.PI) * SPIRAL_RADIUS;
                    double pz = currentPos.z + Math.sin(angle + side * Math.PI) * SPIRAL_RADIUS;
                    double py = currentPos.y + 0.5 + rise;

                    world.spawnParticles(ParticleTypes.END_ROD, px, py, pz, 1, 0.0, 0.0, 0.0, 0.0);
                }
            }

            // 5. Execute teleport when countdown reaches 0
            if (state.remainingTicks <= 0) {
                executeTeleport(server, player, state.target);
                iter.remove();
            }
        }
    }

    private static void executeTeleport(MinecraftServer server, ServerPlayerEntity player, TeleportTarget tt) {
        // Find target world
        ServerWorld targetWorld = null;
        if (tt.worldId != null) {
            for (ServerWorld w : server.getWorlds()) {
                if (w.getRegistryKey().getValue().toString().equals(tt.worldId)) {
                    targetWorld = w;
                    break;
                }
            }
        }
        // Fallback to player's current world
        if (targetWorld == null) {
            targetWorld = (ServerWorld) player.getWorld();
        }

        int bx = (int) Math.floor(tt.x);
        int by = (int) Math.floor(tt.y);
        int bz = (int) Math.floor(tt.z);

        // Double-check safety at final position
        if (!RtpConfig.isSafeLocation(targetWorld, bx, by, bz)) {
            player.sendMessage(Text.literal("\u00a7c[RTP] \u76ee\u6807\u4f4d\u7f6e\u5df2\u4e0d\u5b89\u5168\uff0c\u4f20\u9001\u53d6\u6d88"), false);
            RtpMod.LOGGER.warn("RTP target became unsafe for {} at {:.1f} {:.1f} {:.1f}",
                player.getName().getString(), tt.x, tt.y, tt.z);
            return;
        }

        // Teleport
        player.teleport(targetWorld, tt.x, tt.y, tt.z, tt.yaw, tt.pitch,$
        player.sendMessage(Text.literal("\u00a7a\u00a7l[RTP] \u00a7f\u00a7l\u4f20\u9001\u6210\u529f\uff01"), false);

        // Particle burst on successful teleport
        for (int i = 0; i < 30; i++) {
            double angle = i * (2 * Math.PI / 30);
            double ex = tt.x + Math.cos(angle) * 2.0;
            double ez = tt.z + Math.sin(angle) * 2.0;
            double ey = tt.y + 1.0;
            targetWorld.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, ex, ey, ez, 1, 0.0, 0.0, 0.0, 0.0);
        }

        RtpMod.LOGGER.info("RTP completed for {} to world={} ({:.1f}, {:.1f}, {:.1f})",
            player.getName().getString(), targetWorld.getRegistryKey().getValue().toString(), tt.x, tt.y, tt.z);
    }

    /** Cancel all active countdown tasks. Used on server shutdown. */
    public static void cancelAll() {
        int count = activeTasks.size();
        activeTasks.clear();
        if (count > 0) {
            RtpMod.LOGGER.info("Cancelled {} active RTP countdown tasks", count);
        }
    }

    public static boolean isCountdownActive(UUID uuid) {
        return activeTasks.containsKey(uuid);
    }
}