package me.matl114.events.impl;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import me.matl114.utils.ChatUtils;
import me.matl114.versioned.api.VRecord;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.EntityTypes;

@Getter
@Setter
@AllArgsConstructor
@Accessors(fluent = true)
public class ChatRecv {
    Component text;
    final Optional<GameProfile> senderProfile;
    final Optional<String> senderName;
    final boolean system;
    final Optional<Holder<ChatType>> messageType;
    public static final Minecraft mc = Minecraft.getInstance();

    public static ChatRecv parseSystemMessage(Component text) {
        var handler = mc.getConnection();
        if (handler == null) return new ChatRecv(text, Optional.empty(), Optional.empty(), true, Optional.empty());
        Optional<Pair<Optional<String>, Optional<GameProfile>>> result = ChatUtils.textStream(text)
                .map(Component::getStyle)
                .filter(s -> s.getClickEvent() != null || s.getHoverEvent() != null || s.getInsertion() != null)
                .map(ChatRecv::parseNameInStyle)
                .filter(Objects::nonNull)
                .map(s -> Pair.of(
                        Optional.ofNullable(s.map(
                                v -> {
                                    return handler.getPlayerInfo(v) != null ? v : null;
                                },
                                v -> Optional.ofNullable(handler.getPlayerInfo(v))
                                        .map(PlayerInfo::getProfile)
                                        .map(VRecord::getName)
                                        .orElse(null))),
                        Optional.ofNullable(s.map(handler::getPlayerInfo, handler::getPlayerInfo))
                                .map(PlayerInfo::getProfile)))
                .filter(s -> s.getFirst().isPresent() || s.getSecond().isPresent())
                .findFirst();
        return new ChatRecv(
                text, result.flatMap(Pair::getSecond), result.flatMap(Pair::getFirst), true, Optional.empty());
    }

    private static final Pattern MESSAGE_PATTERN = Pattern.compile("^/([a-zA-Z:0-9]+)\\s([^\\s]+)\\s");

    public static Either<String, UUID> parseNameInStyle(Style style) {
        if (style.getClickEvent() != null) {
            var click = style.getClickEvent();
            if (click instanceof ClickEvent.SuggestCommand suggest) {
                String suggestCommand = suggest.command();
                Matcher matcher = MESSAGE_PATTERN.matcher(suggestCommand);
                if (matcher.find()) {
                    String name = matcher.group(2);
                    return Either.left(name);
                }
            }
        }
        if (style.getHoverEvent() != null) {
            var hover = style.getHoverEvent();
            if (hover instanceof HoverEvent.ShowEntity entityHover
                    && entityHover.entity() instanceof HoverEvent.EntityTooltipInfo entity
                    && entity.type == EntityTypes.PLAYER) {
                var uid = entity.uuid;
                if (uid != null) {
                    return Either.right(uid);
                }
            }
        }
        if (style.getInsertion() != null) {
            return Either.left(style.getInsertion());
        }
        return null;
    }
}
