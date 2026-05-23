package net.rtp.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
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

        int cx = (int) Math.floor(player.getX());
        int cz = (int) Math.floor(player.getZ());

        RtpConfig.Dimension dim = RtpConfig.getDimension(world);
        String dimName;
        switch (dim) {
            case NETHER: dimName = "§c地狱§f"; break;
            case END:    dimName = "§5末地§f"; break;
            default:     dimName = "§a主世界§f";
        }

        player.sendMessage(Text.literal("§6[RTP] §f正在" + dimName + "中寻找传送位置..."), false);

        RtpConfig.TeleportResult result = RtpMod.CONFIG.findDestination(world, cx, cz, RANDOM);

        if (result == null) {
            String hint;
            switch (dim) {
                case NETHER:
                    hint = "§c[RTP] 地狱中未找到安全位置，请移动后重试";
                    break;
                case END:
                    hint = "§c[RTP] 末地中未找到岛屿位置，请移动后重试";
                    break;
                default:
                    hint = "§c[RTP] 未找到传送位置，请重试";
            }
            player.sendMessage(Text.literal(hint), false);
            return 0;
        }

        String modeHint;
        if (!result.needsSlowFall) {
            modeHint = dim == RtpConfig.Dimension.NETHER ? "（地狱直传）" : "（地表传送）";
        } else if (result.slowFallAmplifier >= 2) {
            modeHint = "（末地强力缓降）";
        } else {
            modeHint = "（高空缓降）";
        }
        player.sendMessage(Text.literal("§a[RTP] §f找到目标" + modeHint + "，即将开始倒计时..."), false);

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
}