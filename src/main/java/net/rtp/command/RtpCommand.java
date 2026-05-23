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
import net.rtp.RtpMod;
import net.rtp.config.RtpConfig;
import net.rtp.util.CountdownTask;

import java.util.Random;

public class RtpCommand {

    private static final Random RANDOM = new Random();

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            net.minecraft.server.command.CommandManager.literal("rtp")
                .executes(ctx -> execute(ctx.getSource()))
        );
    }

    public static int execute(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        ServerWorld world = (ServerWorld) player.getWorld();

        if (CountdownTask.isCountdownActive(player.getUuid())) {
            player.sendMessage(Text.literal("§c[RTP] 你已经在传送倒计时中，请等待完成"), false);
            return 0;
        }

        // cooldown 检查
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

        // 步骤 1：搜索前提示（告诉玩家不要动，服务器可能卡顿）
        player.sendMessage(Text.literal("§6[RTP] §f正在§f" + dimHint + "§f中寻找安全位置..."), false);
        player.sendMessage(Text.literal("§7请勿移动，服务器可能卡顿一段时间..."), false);
        sendTitle(player,
            Text.literal("§e§l正在搜索..."),
            Text.literal("§7" + dimHint + " - 请勿移动"));

        // 步骤 2：搜索（同步，可能卡顿）
        RtpConfig.TeleportResult result = RtpMod.CONFIG.findDestination(world, cx, cz, RANDOM);

        // 搜索完毕，清除搜索 Title
        clearTitle(player);

        if (result == null) {
            player.sendMessage(Text.literal("§c[RTP] 未能找到安全位置，请移动后重试"), false);
            return 0;
        }

        // 步骤 3：找到位置后才开始倒计时（5 秒）
        player.sendMessage(Text.literal("§a[RTP] §f找到目标！坐标 (" +
            (int) result.x + ", " + (int) result.y + ", " + (int) result.z + ")，5 秒后传送..."), false);
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

        // 传送完成或倒计时结束时，CountdownTask.executeTeleport 里设置 cooldown
        return 1;
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