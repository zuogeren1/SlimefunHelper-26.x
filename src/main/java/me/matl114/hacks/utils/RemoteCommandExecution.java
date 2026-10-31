package me.matl114.hacks.utils;

import com.mojang.authlib.GameProfile;
import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import me.matl114.utils.ChatUtils;
import me.matl114.utils.commands.CommandPermissionCalculator;
import me.matl114.utils.commands.params.api.CommandExecution;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector2f;
import org.joml.Vector3d;

public final class RemoteCommandExecution implements CommandExecution {
    public static final String COMMON_PERMISSION = "remotecommand.common";
    public static final String TEAMMATE_PERMISSION = "remotecommand.teammate";
    public static final String CONTROLLER_PERMISSION = "remotecommand.controller";

    private final CommandExecution delegate;

    @Nullable
    private final GameProfile senderProfile;

    private final String senderName;
    private final List<String> permissions;
    private final Consumer<Component> feedbackHandler;

    public RemoteCommandExecution(
            Player executor,
            @Nullable GameProfile senderProfile,
            String senderName,
            List<String> permissions,
            Consumer<Component> feedbackHandler) {
        this.delegate = CommandExecution.sender(executor);
        this.senderProfile = senderProfile;
        this.senderName = senderName;
        this.permissions = List.copyOf(permissions);
        this.feedbackHandler = feedbackHandler == null ? message -> {} : feedbackHandler;
    }

    @Nullable
    public GameProfile getSenderProfile() {
        return senderProfile;
    }

    public String getSenderName() {
        return senderName;
    }

    @Override
    public Player getExecutor() {
        return delegate.getExecutor();
    }

    @Override
    public boolean hasPermission(String permission) {
        return CommandPermissionCalculator.hasPermission(permissions, permission);
    }

    @Override
    public void sendMessage(@NotNull String message) {
        feedbackHandler.accept(ChatUtils.stringToText(message));
    }

    @Override
    public void sendMessage(Component message) {
        feedbackHandler.accept(message);
    }

    @Override
    public Vector2f getExecuteRot() {
        return delegate.getExecuteRot();
    }

    @Override
    public Vector3d getExecutePos() {
        return delegate.getExecutePos();
    }

    @Override
    public Level getExecuteWorld() {
        return delegate.getExecuteWorld();
    }
}
