package net.rtp.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.rtp.RtpMod;
import net.rtp.config.RtpConfig;
import net.rtp.util.CountdownTask;

import java.util.Random;

public class RtpCommand {

    private static final Random RANDOM = new Random();

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        // /rtp —— 正常模式（各维度使用优化后的算法）
        dispatcher.register(
            net.minecraft.server.command.CommandManager.literal("rtp")
                .executes(ctx -> execute(ctx.getSource(), false))
        );

        // /rtp fall —— 高空模式（Y=200 + 缓降 2 级，不限维度）
        dispatcher.register(
            net.minecraft.server.command.CommandManager.literal("rtp")
                .then(net.minecraft.server.command.CommandManager.literal("fall")
                    .executes(ctx -> execute(ctx.getSource(), true)))
        );
    }

    public static int execute(ServerCommandSource source, boolean forceHighAltitude) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        ServerWorld world = (ServerWorld) player.getWorld();

        if (CountdownTask.isCountdownActive(player.getUuid())) {
            player.sendMessage(Text.literal("§c[RTP] 你已经在传送倒计时中，请等待完成"), false);
            return 0;
        }

        int cd = RtpMod.CONFIG.cooldownSeconds;
        if (cd > 0) {
            int remaining = CountdownTask.getCooldownRemaining(player.getUuid());
            if (remaining > 0) {
                player.sendMessage(Text.literal("§c[RTP] 请等待 " + remaining + " 秒后再使用"), false);
                return 0;
            }
        }

        int cx = (int) Math.floor(player.getX());
        int cz = (int) Math.floor(player.getZ());
        RtpConfig.Dimension dim = RtpConfig.getDimension(world);
        String dimHint;
        switch (dim) {
            case NETHER:   dimHint = "§c地狱§f";  break;
            case END:      dimHint = "§5末地§f"; break;
            case UNKNOWN:  dimHint = "§d自定义维度§f"; break;
            default:       dimHint = "§a主世界§f";
        }

        if (forceHighAltitude) {
            player.sendMessage(Text.literal("§6[RTP] §f正在" + dimHint + "中随机选点，高空降落模式..."), false);
            player.sendMessage(Text.literal("§7请勿移动，服务器可能卡顿一段时间..."), false);
            sendTitle(player, Text.literal("§e§l高空模式..."), Text.literal("§7" + dimHint + " - 请勿移动"));
        } else {
            player.sendMessage(Text.literal("§6[RTP] §f正在§f" + dimHint + "§f中寻找安全位置..."), false);
            player.sendMessage(Text.literal("§7请勿移动，服务器可能卡顿一段时间..."), false);
            sendTitle(player, Text.literal("§e§l正在搜索..."), Text.literal("§7" + dimHint + " - 请勿移动"));
        }

        // 搜索
        RtpConfig.TeleportResult result;
        if (forceHighAltitude) {
            result = findHighAltitudeDestination(world, cx, cz, RANDOM, dim);
        } else {
            result = RtpMod.CONFIG.findDestination(world, cx, cz, RANDOM);
        }

        clearTitle(player);

        if (result == null) {
            player.sendMessage(Text.literal("§c[RTP] 未能找到安全位置，请移动后重试"), false);
            return 0;
        }

        // 找到位置后：3 秒倒计时
        String modeName = forceHighAltitude ? "高空模式" : "标准模式";
        player.sendMessage(Text.literal("§a[RTP] §f找到目标！§b[" + modeName + "]§f 坐标 (" +
            (int) result.x + ", " + (int) result.y + ", " + (int) result.z + ")，3 秒后传送..."), false);
        sendTitle(player,
            Text.literal("§b§l传送中..."),
            Text.literal("§7目标: " + (int) result.x + ", " + (int) result.y + ", " + (int) result.z));

        CountdownTask.TeleportTarget target = new CountdownTask.TeleportTarget(
            result.x, result.y, result.z,
            0.0f, 0.0f,
            world.getRegistryKey().getValue().toString(),
            result.needsSlowFall,
            result.slowFallAmplifier,
            result.needsLandingCheck
        );

        boolean started = CountdownTask.startCountdown(player, target);
        if (!started) {
            player.sendMessage(Text.literal("§c[RTP] 倒计时启动失败，请重试"), false);
            return 0;
        }

        return 1;
    }

    // ─────────────── /rtp fall 高空模式 ───────────────
    // 纯随机 XZ（半径 maxRadius），Y 固定 highAltitudeY，缓降 2 级，落地检测
    private static RtpConfig.TeleportResult findHighAltitudeDestination(
            ServerWorld world, int centerX, int centerZ, Random random, RtpConfig.Dimension dim) {

        int maxR = RtpMod.CONFIG.maxRadius;
        int highY = 200; // 高空模式固定 Y=200

        // 直接随机 XZ，预加载后返回结果，无需搜索
        int dx = random.nextInt(maxR * 2) - maxR;
        int dz = random.nextInt(maxR * 2) - maxR;
        int x = centerX + dx;
        int z = centerZ + dz;

        // 预加载 5x5 区块
        ensureChunkLoadedStatic(world, x, z);

        return new RtpConfig.TeleportResult(
            x + 0.5, highY, z + 0.5,
            true,   // needsSlowFall
            true,   // needsLandingCheck
            2       // amplifier = 2（强力缓降）
        );
    }

    private static void ensureChunkLoadedStatic(ServerWorld world, int x, int z) {
        int cx = x >> 4, cz = z >> 4;
        for (int ox = -2; ox <= 2; ox++) {
            for (int oz = -2; oz <= 2; oz++) {
                int nx = cx + ox, nz = cz + oz;
                if (!world.getChunkManager().isChunkLoaded(nx, nz)) {
                    try { world.getChunk(nx, nz); } catch (Exception ignored) {}
                }
            }
        }
    }

    private static void sendTitle(ServerPlayerEntity player, Text title, Text subtitle) {
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 40, 10));
        player.networkHandler.sendPacket(new TitleS2CPacket(title));
        player.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));
    }

    private static void clearTitle(ServerPlayerEntity player) {
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(0, 0, 0));
    }
}