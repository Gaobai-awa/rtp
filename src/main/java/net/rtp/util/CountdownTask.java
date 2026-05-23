package net.rtp.util;

import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.rtp.RtpMod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class CountdownTask {

    /** 3 seconds = 60 ticks at 20 TPS */
    private static final int COUNTDOWN_TICKS = 60;
    private static final double SPIRAL_RADIUS = 0.9;
    private static final double SPIRAL_HEIGHT_PER_TICK = 0.08;
    private static final double SPIRAL_ANGLE_PER_TICK = Math.toRadians(25);
    /** Squared distance threshold: 0.2^2 = 0.04 */
    private static final double MOVE_THRESHOLD_SQ = 0.04;

    private static final Map<UUID, CountdownState> activeTasks = new HashMap<>();

    // ───────────────────────────────────────────────
    // Public API
    // ───────────────────────────────────────────────

    public static class TeleportTarget {
        public final double x, y, z;
        public final float yaw, pitch;
        public final String worldId;

        public TeleportTarget(double x, double y, double z, float yaw, float pitch, String worldId) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.worldId = worldId;
        }
    }

    public static boolean startCountdown(ServerPlayerEntity player, TeleportTarget target) {
        UUID uuid = player.getUuid();
        if (activeTasks.containsKey(uuid)) {
            return false;
        }
        activeTasks.put(uuid, new CountdownState(player, target));
        sendTitle(player,
            Text.literal("§e传送中..."),
            Text.literal("§7请保持原地不动"));
        RtpMod.LOGGER.info("RTP countdown started for {} -> ({}, {}, {})",
            player.getName().getString(),
            String.format("%.1f", target.x),
            String.format("%.1f", target.y),
            String.format("%.1f", target.z));
        return true;
    }

    public static boolean isCountdownActive(UUID uuid) {
        return activeTasks.containsKey(uuid);
    }

    public static void cancelAll() {
        int count = activeTasks.size();
        activeTasks.clear();
        if (count > 0) {
            RtpMod.LOGGER.info("Cancelled {} active RTP countdown tasks", count);
        }
    }

    // ───────────────────────────────────────────────
    // Tick loop
    // ───────────────────────────────────────────────

    public static void tickAll(MinecraftServer server) {
        if (activeTasks.isEmpty()) return;

        Iterator<Map.Entry<UUID, CountdownState>> iter = activeTasks.entrySet().iterator();
        while (iter.hasNext()) {
            CountdownState state = iter.next().getValue();

            ServerPlayerEntity player = state.getPlayer(server);
            if (player == null) {
                iter.remove();
                RtpMod.LOGGER.info("RTP cancelled for {} (disconnected)", state.playerName);
                continue;
            }
            if (!player.isAlive()) {
                player.sendMessage(Text.literal("§c[RTP] 你在倒计时期间死亡，传送已取消"), false);
                clearTitle(player);
                iter.remove();
                RtpMod.LOGGER.info("RTP cancelled for {} (died)", state.playerName);
                continue;
            }

            // Check movement
            Vec3d current = player.getPos();
            if (current.squaredDistanceTo(state.startPos) > MOVE_THRESHOLD_SQ) {
                player.sendMessage(Text.literal("§c[RTP] 你在倒计时期间移动了！传送已取消！"), false);
                clearTitle(player);
                sendTitle(player, Text.literal("§c传送取消"), Text.literal("§7移动了"));
                iter.remove();
                RtpMod.LOGGER.info("RTP cancelled for {} (moved)", state.playerName);
                continue;
            }

            state.remainingTicks--;
            state.totalTicks++;

            // Every second: update Title with remaining seconds
            int seconds = (state.remainingTicks / 20) + 1;
            if (state.remainingTicks > 0 && state.remainingTicks % 20 == 0) {
                if (seconds == 1) {
                    sendTitle(player, Text.literal("§a即将传送"), Text.literal("§e§l1"));
                } else if (seconds == 2) {
                    sendTitle(player, Text.literal("§e即将传送"), Text.literal("§f" + seconds));
                } else {
                    sendTitle(player, Text.literal("§e传送中..."), Text.literal("§7" + seconds));
                }
            }

            // Every 2 ticks: spawn spiral particle effect around player
            if (state.totalTicks % 2 == 0) {
                spawnSpiralParticles(player, state.totalTicks / 2);
            }

            // Countdown finished
            if (state.remainingTicks <= 0) {
                executeTeleport(player, state.target);
                iter.remove();
            }
        }
    }

    // ───────────────────────────────────────────────
    // Internal
    // ───────────────────────────────────────────────

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

        ServerPlayerEntity getPlayer(MinecraftServer server) {
            return server.getPlayerManager().getPlayer(playerUuid);
        }
    }

    private static void spawnSpiralParticles(ServerPlayerEntity player, int step) {
        ServerWorld world = (ServerWorld) player.getWorld();
        Vec3d pos = player.getPos();

        double angle = step * SPIRAL_ANGLE_PER_TICK;
        double rise = step * SPIRAL_HEIGHT_PER_TICK;

        // Two counter-rotating END_ROD pillars
        for (int s = 0; s < 2; s++) {
            double sign = (s == 0) ? 1.0 : -1.0;
            double px = pos.x + Math.cos(angle + s * Math.PI) * SPIRAL_RADIUS;
            double pz = pos.z + Math.sin(angle + s * Math.PI) * SPIRAL_RADIUS;
            double py = pos.y + 0.3 + rise;

            world.spawnParticles(ParticleTypes.END_ROD,
                px, py, pz,
                1, 0.01, 0.01, 0.01, 0.0);
        }

        // Inner ENCHANTED_HIT sparkles (phase shifted)
        double angle2 = angle + 1.2;
        double r2 = 0.45;
        for (int s = 0; s < 2; s++) {
            double px = pos.x + Math.cos(angle2 + s * Math.PI) * r2;
            double pz = pos.z + Math.sin(angle2 + s * Math.PI) * r2;
            double py = pos.y + 0.5 + rise;
            world.spawnParticles(ParticleTypes.ENCHANTED_HIT,
                px, py, pz,
                1, 0.005, 0.005, 0.005, 0.0);
        }
    }

    private static void executeTeleport(ServerPlayerEntity player, TeleportTarget tt) {
        ServerWorld targetWorld = findWorld(player.getServer(), tt.worldId);
        if (targetWorld == null) {
            targetWorld = (ServerWorld) player.getWorld();
        }

        // Final safety check
        int bx = (int) Math.floor(tt.x);
        int by = (int) Math.floor(tt.y);
        int bz = (int) Math.floor(tt.z);
        if (!net.rtp.config.RtpConfig.isSafeLocation(targetWorld, bx, by, bz)) {
            player.sendMessage(Text.literal("§c[RTP] 目标位置已不安全，传送取消"), false);
            clearTitle(player);
            RtpMod.LOGGER.warn("RTP target became unsafe for {} at ({}, {}, {})",
                player.getName().getString(),
                String.format("%.1f", tt.x),
                String.format("%.1f", tt.y),
                String.format("%.1f", tt.z));
            return;
        }

        player.teleport(targetWorld, tt.x, tt.y, tt.z, tt.yaw, tt.pitch);
        clearTitle(player);

        // Success title
        sendTitle(player, Text.literal("§a§l传送成功！"),
            Text.literal("§7" + bx + ", " + by + ", " + bz));

        // Particle burst at destination
        Vec3d dest = new Vec3d(tt.x, tt.y, tt.z);
        for (int i = 0; i < 24; i++) {
            double angle = i * (2 * Math.PI / 24);
            double ex = tt.x + Math.cos(angle) * 1.8;
            double ez = tt.z + Math.sin(angle) * 1.8;
            double ey = tt.y + 1.0;
            targetWorld.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING,
                ex, ey, ez, 1, 0.0, 0.0, 0.0, 0.0);
        }
        // Vertical column
        for (int i = 0; i < 5; i++) {
            targetWorld.spawnParticles(ParticleTypes.END_ROD,
                tt.x, tt.y + 0.5 + i * 0.4, tt.z,
                3, 0.1, 0.1, 0.1, 0.01);
        }

        player.sendMessage(Text.literal("§a[RTP] §f传送成功！"), false);
        RtpMod.LOGGER.info("RTP completed for {} -> world={} ({}, {}, {})",
            player.getName().getString(),
            targetWorld.getRegistryKey().getValue().toString(),
            String.format("%.1f", tt.x),
            String.format("%.1f", tt.y),
            String.format("%.1f", tt.z));
    }

    private static ServerWorld findWorld(MinecraftServer server, String worldId) {
        if (worldId == null) return null;
        for (ServerWorld w : server.getWorlds()) {
            if (w.getRegistryKey().getValue().toString().equals(worldId)) {
                return w;
            }
        }
        return null;
    }

    private static void sendTitle(ServerPlayerEntity player, Text title, Text subtitle) {
        player.networkHandler.sendPacket(new TitleS2CPacket(title));
        player.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(5, 40, 10));
    }

    private static void clearTitle(ServerPlayerEntity player) {
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(0, 0, 0));
    }
}
