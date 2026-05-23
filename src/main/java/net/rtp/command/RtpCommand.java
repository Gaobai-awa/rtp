package net.rtp.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.rtp.RtpMod;
import net.rtp.config.RtpConfig;
import net.rtp.util.CountdownTask;

import java.util.Random;

public class RtpCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            net.minecraft.server.command.CommandManager.literal("rtp")
                .executes(ctx -> execute(ctx.getSource()))
        );
    }

    private static final Random RANDOM = new Random();

    public static int execute(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        RtpConfig cfg = RtpMod.CONFIG;
        World world = player.getWorld();

        // Check if already in countdown
        if (CountdownTask.isCountdownActive(player.getUuid())) {
            player.sendMessage(Text.literal("\u00a7c[RTP] \u4f60\u5df2\u7ecf\u5728\u5012\u8ba1\u4e2d\uff0c\u8bf7\u7b49\u5f85\u4f20\u9001\u5b8c\u6210"), false);
            return 0;
        }

        int cx = (int) Math.floor(player.getX());
        int cz = (int) Math.floor(player.getZ());

        BlockPos surface = cfg.findSurface(world, cx, cz, RANDOM);

        if (surface == null) {
            player.sendMessage(Text.literal("\u00a7c[RTP] \u5728\u8303\u56f4\u5185\u672a\u627e\u5230\u6709\u6548\u5730\u8868\uff0c\u8bf7\u79fb\u52a8\u5230\u5176\u4ed6\u4f4d\u7f6e\u540e\u91cd\u8bd5"), false);
            return 0;
        }

        // Teleport target: above the surface block, centered
        double tx = surface.getX() + 0.5;
        double ty = surface.getY() + 1.0;
        double tz = surface.getZ() + 0.5;

        // Pre-verify safety of target position
        if (!RtpConfig.isSafeLocation(
                (ServerWorld) world,
                (int) Math.floor(tx), (int) Math.floor(ty), (int) Math.floor(tz)
        )) {
            player.sendMessage(Text.literal("\u00a7c[RTP] \u76ee\u6807\u4f4d\u7f6e\u4e0d\u5b89\u5168\uff0c\u8bf7\u91cd\u8bd5"), false);
            return 0;
        }

        CountdownTask.TeleportTarget target = new CountdownTask.TeleportTarget(
            tx, ty, tz,
            0.0f, 0.0f,
            world.getRegistryKey().getValue().toString()
        );

        boolean started = CountdownTask.startCountdown(player, target);
        if (!started) {
            player.sendMessage(Text.literal("\u00a7c[RTP] \u5012\u8ba1\u65f6\u542f\u52a8\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5"), false);
        }
        return started ? 1 : 0;
    }
}