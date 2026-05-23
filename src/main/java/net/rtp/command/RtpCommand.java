package net.rtp.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.world.World;
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
        RtpConfig cfg = RtpMod.CONFIG;
        ServerWorld world = (ServerWorld) player.getWorld();

        // 检查是否已有倒计时在进行
        if (CountdownTask.isCountdownActive(player.getUuid())) {
            player.sendMessage(Text.literal("§c[RTP] 你已经在传送倒计时中，请等待完成"), false);
            return 0;
        }

        int cx = (int) Math.floor(player.getX());
        int cz = (int) Math.floor(player.getZ());

        // 立即在聊天框给搜索反馈
        player.sendMessage(Text.literal("§6[RTP] §f正在寻找传送位置..."), false);

        // 搜索目的地
        RtpConfig.TeleportResult result = cfg.findDestination(world, cx, cz, RANDOM);

        if (result == null) {
            if (cfg.highAltitudeMode) {
                player.sendMessage(Text.literal("§c[RTP] 未能生成有效目标位置，请重试"), false);
            } else {
                player.sendMessage(Text.literal("§c[RTP] 在半径 " + cfg.maxRadius + " 格内未找到安全地表位置，请尝试移动后重试"), false);
            }
            return 0;
        }

        player.sendMessage(Text.literal("§a[RTP] §f找到目标！即将开始倒计时..."), false);

        // 构建传送目标
        CountdownTask.TeleportTarget target = new CountdownTask.TeleportTarget(
            result.x, result.y, result.z,
            0.0f, 0.0f,
            world.getRegistryKey().getValue().toString(),
            result.needsSlowFall
        );

        boolean started = CountdownTask.startCountdown(player, target);
        if (!started) {
            player.sendMessage(Text.literal("§c[RTP] 倒计时启动失败，请重试"), false);
            return 0;
        }

        return 1;
    }
}