package net.rtp.util;

import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
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

    /** 3 秒倒计时 = 60 tick */
    private static final int COUNTDOWN_TICKS = 60;
    private static final double SPIRAL_RADIUS = 0.9;
    private static final double SPIRAL_HEIGHT_PER_TICK = 0.08;
    private static final double SPIRAL_ANGLE_PER_TICK = Math.toRadians(25);
    /** 移动检测阈值：0.2 格 */
    private static final double MOVE_THRESHOLD_SQ = 0.04;
    /** 落地检测：Y 坐标在多少 tick 内不变算落地 */
    private static final int LANDING_STABLE_TICKS = 20; // 1 秒

    private static final Map<UUID, CountdownState> countdownTasks = new HashMap<>();
    /** 缓降中的玩家：UUID → 状态（缓降中/已落地待清除） */
    private static final Map<UUID, FallingState> fallingPlayers = new HashMap<>();

    // ───────────────────────────────────────────────
    // Public API
    // ───────────────────────────────────────────────

    public static class TeleportTarget {
        public final double x, y, z;
        public final float yaw, pitch;
        public final String worldId;
        /** 是否需要缓降效果 */
        public final boolean needsSlowFall;
        /** 缓降等级：1=普通，2=强力（末地） */
        public final int slowFallAmplifier;
        /** 是否需要落地检测（关闭缓降、清除效果）。地狱不需要。 */
        public final boolean needsLandingCheck;

        public TeleportTarget(double x, double y, double z, float yaw, float pitch,
                               String worldId, boolean needsSlowFall, int slowFallAmplifier,
                               boolean needsLandingCheck) {
            this.x = x; this.y = y; this.z = z;
            this.yaw = yaw; this.pitch = pitch;
            this.worldId = worldId;
            this.needsSlowFall = needsSlowFall;
            this.slowFallAmplifier = Math.max(1, slowFallAmplifier); // 只保下限，不限上限（允许4级缓降）
            this.needsLandingCheck = needsLandingCheck;
        }
    }

    public static boolean startCountdown(ServerPlayerEntity player, TeleportTarget target) {
        UUID uuid = player.getUuid();
        if (countdownTasks.containsKey(uuid)) {
            return false;
        }
        countdownTasks.put(uuid, new CountdownState(player, target));
        String dimHint = "";
        if (target.worldId != null) {
            if (target.worldId.contains("nether")) dimHint = "（地狱）";
            else if (target.worldId.contains("end")) dimHint = "（末地）";
            else dimHint = target.needsSlowFall ? "（高空模式）" : "";
        } else {
            dimHint = target.needsSlowFall ? "（高空模式）" : "";
        }
        sendTitle(player, Text.literal("§e传送中..." + dimHint), Text.literal("§7请保持原地不动"));
        RtpMod.LOGGER.info("RTP countdown started for {} -> ({}, {}, {}) [amp={}]",
            player.getName().getString(),
            String.format("%.1f", target.x), String.format("%.1f", target.y), String.format("%.1f", target.z),
            target.slowFallAmplifier);
        return true;
    }

    public static boolean isCountdownActive(UUID uuid) {
        return countdownTasks.containsKey(uuid);
    }

    public static void cancelAll() {
        int n = countdownTasks.size();
        int m = fallingPlayers.size();
        countdownTasks.clear();
        fallingPlayers.clear();
        if (n > 0 || m > 0) {
            RtpMod.LOGGER.info("Cancelled {} countdown tasks and {} falling players", n, m);
        }
    }

    // ───────────────────────────────────────────────
    // Server tick entry point
    // ───────────────────────────────────────────────

    public static void tickAll(MinecraftServer server) {
        tickCountdown(server);
        tickFallingPlayers(server);
    }

    // ───────────────────────────────────────────────
    // Countdown phase
    // ───────────────────────────────────────────────

    private static void tickCountdown(MinecraftServer server) {
        if (countdownTasks.isEmpty()) return;

        Iterator<Map.Entry<UUID, CountdownState>> iter = countdownTasks.entrySet().iterator();
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

            // 移动检测
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

            // 每秒更新 Title
            if (state.remainingTicks > 0 && state.remainingTicks % 20 == 0) {
                int seconds = (state.remainingTicks / 20) + 1;
                if (seconds == 1) {
                    sendTitle(player, Text.literal("§a即将传送"), Text.literal("§e§l1"));
                } else if (seconds == 2) {
                    sendTitle(player, Text.literal("§e即将传送"), Text.literal("§f" + seconds));
                } else {
                    sendTitle(player, Text.literal("§e传送中..."), Text.literal("§7" + seconds));
                }
            }

            // 每 2 tick 生成螺旋粒子
            if (state.totalTicks % 2 == 0) {
                spawnSpiralParticles(player, state.totalTicks / 2);
            }

            // 倒计时结束 → 执行传送
            if (state.remainingTicks <= 0) {
                executeTeleport(player, state.target);
                iter.remove();
            }
        }
    }

    // ───────────────────────────────────────────────
    // Falling / landing check phase
    // ───────────────────────────────────────────────

    private static void tickFallingPlayers(MinecraftServer server) {
        if (fallingPlayers.isEmpty()) return;

        Iterator<Map.Entry<UUID, FallingState>> iter = fallingPlayers.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<UUID, FallingState> entry = iter.next();
            UUID uuid = entry.getKey();
            FallingState state = entry.getValue();

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player == null) {
                iter.remove();
                RtpMod.LOGGER.info("Falling player {} left server, cleared falling state", uuid);
                continue;
            }

            // 死亡检测：移除缓降效果和状态
            if (!player.isAlive()) {
                clearSlowFallEffect(player);
                iter.remove();
                RtpMod.LOGGER.info("Player {} died during falling, cleared effects", state.playerName);
                continue;
            }

            if (!state.needsLandingCheck) {
                // 不需要落地检测（如地狱），跳过
                continue;
            }

            Vec3d pos = player.getPos();
            double currentY = pos.y;
            state.ticks++;

            if (state.lastY < currentY - 0.1) {
                // 正在上升，重置稳定计数器
                state.stableTicks = 0;
            } else if (Math.abs(currentY - state.lastY) <= 0.05) {
                // Y 基本不变（±0.05 误差），认为是停止/落地
                state.stableTicks++;
            } else {
                // 正常下降中，重置稳定计数器
                state.stableTicks = 0;
            }

            state.lastY = currentY;

            // 落地判定：连续 20 tick（1 秒）Y 坐标不变
            if (state.stableTicks >= LANDING_STABLE_TICKS) {
                clearSlowFallEffect(player);
                iter.remove();
                RtpMod.LOGGER.info("Player {} landed at Y={}, cleared slow-fall effects",
                    state.playerName, String.format("%.1f", currentY));
                continue;
            }

            // 异常保护：缓降效果时间过长（> 10 分钟），强制清除
            if (state.ticks > 12000) {
                clearSlowFallEffect(player);
                iter.remove();
                RtpMod.LOGGER.warn("Forcibly cleared falling state for {} after {} ticks",
                    state.playerName, state.ticks);
            }
        }
    }

    private static void clearSlowFallEffect(ServerPlayerEntity player) {
        player.removeStatusEffect(StatusEffects.SLOW_FALLING);
        player.removeStatusEffect(StatusEffects.LEVITATION);
        clearTitle(player);
        sendTitle(player, Text.literal("§a落地成功"), Text.literal("§7缓降效果已解除"));
    }

    // ───────────────────────────────────────────────
    // Teleport execution
    // ───────────────────────────────────────────────

    private static void executeTeleport(ServerPlayerEntity player, TeleportTarget tt) {
        clearTitle(player);

        ServerWorld targetWorld = findWorld(player.getServer(), tt.worldId);
        if (targetWorld == null) {
            targetWorld = (ServerWorld) player.getWorld();
        }

        player.teleport(targetWorld, tt.x, tt.y, tt.z, tt.yaw, tt.pitch);

        // 粒子爆发
        for (int i = 0; i < 24; i++) {
            double angle = i * (2 * Math.PI / 24);
            double ex = tt.x + Math.cos(angle) * 1.8;
            double ez = tt.z + Math.sin(angle) * 1.8;
            targetWorld.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING,
                ex, tt.y + 1.0, ez, 1, 0.0, 0.0, 0.0, 0.0);
        }
        for (int i = 0; i < 5; i++) {
            targetWorld.spawnParticles(ParticleTypes.END_ROD,
                tt.x, tt.y + 0.5 + i * 0.4, tt.z,
                3, 0.1, 0.1, 0.1, 0.01);
        }

        // 缓降效果：slowFallAmplifier 为配置中的缓降等级（1=缓降1级, 2=缓降2级, 4=缓降4级）
        // 对应 Minecraft amplifier：1→0, 2→1, 4→3
        if (tt.needsSlowFall) {
            int amp = Math.max(0, tt.slowFallAmplifier - 1);
            player.addStatusEffect(new StatusEffectInstance(
                StatusEffects.SLOW_FALLING, 12000, amp, false, false, true));
            if (amp >= 2) {
                // amp>=2（缓降3级或4级）：额外叠加漂浮效果（2秒）帮助快速下降
                player.addStatusEffect(new StatusEffectInstance(
                    StatusEffects.LEVITATION, 40, 1, false, false, true));
            }
            if (tt.needsLandingCheck) {
                // 开启落地检测（主世界和末地岛屿边缘）
                fallingPlayers.put(player.getUuid(),
                    new FallingState(player.getUuid(), player.getName().getString(), tt.needsLandingCheck));
                sendTitle(player,
                    Text.literal("§b§l高空传送！"),
                    Text.literal("§7缓降中...落地后自动解除"));
            } else {
                sendTitle(player,
                    Text.literal("§b传送完成"),
                    Text.literal("§7缓降效果开启"));
            }
        } else {
            sendTitle(player,
                Text.literal("§a§l传送成功！"),
                Text.literal("§7" + (int) tt.x + ", " + (int) tt.y + ", " + (int) tt.z));
        }

        player.sendMessage(Text.literal("§a[RTP] §f传送成功！"), false);
        RtpMod.LOGGER.info("RTP completed for {} -> ({}, {}, {}) [amp={}, landingCheck={}]",
            player.getName().getString(),
            String.format("%.1f", tt.x), String.format("%.1f", tt.y), String.format("%.1f", tt.z),
            tt.slowFallAmplifier, tt.needsSlowFall);
    }

    // ───────────────────────────────────────────────
    // Internal helpers
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

    private static class FallingState {
        final UUID playerUuid;
        final String playerName;
        final boolean needsLandingCheck;
        double lastY;
        int stableTicks;   // 连续 tick 中 Y 不变计数
        int ticks;         // 总经过 tick

        FallingState(UUID playerUuid, String playerName, boolean needsLandingCheck) {
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.needsLandingCheck = needsLandingCheck;
            this.lastY = -1;
            this.stableTicks = 0;
            this.ticks = 0;
        }
    }

    private static void spawnSpiralParticles(ServerPlayerEntity player, int step) {
        ServerWorld world = (ServerWorld) player.getWorld();
        Vec3d pos = player.getPos();

        double angle = step * SPIRAL_ANGLE_PER_TICK;
        double rise = step * SPIRAL_HEIGHT_PER_TICK;

        for (int s = 0; s < 2; s++) {
            double px = pos.x + Math.cos(angle + s * Math.PI) * SPIRAL_RADIUS;
            double pz = pos.z + Math.sin(angle + s * Math.PI) * SPIRAL_RADIUS;
            double py = pos.y + 0.3 + rise;
            world.spawnParticles(ParticleTypes.END_ROD, px, py, pz, 1, 0.01, 0.01, 0.01, 0.0);
        }

        double angle2 = angle + 1.2;
        double r2 = 0.45;
        for (int s = 0; s < 2; s++) {
            double px = pos.x + Math.cos(angle2 + s * Math.PI) * r2;
            double pz = pos.z + Math.sin(angle2 + s * Math.PI) * r2;
            double py = pos.y + 0.5 + rise;
            world.spawnParticles(ParticleTypes.ENCHANTED_HIT, px, py, pz, 1, 0.005, 0.005, 0.005, 0.0);
        }
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