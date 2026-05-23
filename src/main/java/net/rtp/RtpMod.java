package net.rtp;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.rtp.command.RtpCommand;
import net.rtp.config.RtpConfig;
import net.rtp.util.CountdownTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class RtpMod implements ModInitializer {
    public static final String MOD_ID = "rtp";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
    public static Path CONFIG_DIR;
    public static RtpConfig CONFIG;

    @Override
    public void onInitialize() {
        CONFIG_DIR = Paths.get("config", MOD_ID);
        try {
            Files.createDirectories(CONFIG_DIR);
        } catch (IOException e) {
            LOGGER.error("Failed to create config directory", e);
        }

        CONFIG = RtpConfig.load();

        LOGGER.info("Random Teleport mod loaded! Command: /rtp (radius={})", CONFIG.maxRadius);
        registerCommands();
        registerTick();
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            RtpCommand.register(dispatcher);
        });
    }

    private void registerTick() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            CountdownTask.tickAll(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            CountdownTask.cancelAll();
        });
    }
}