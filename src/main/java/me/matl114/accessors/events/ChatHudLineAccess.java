package me.matl114.accessors.events;

import java.util.Set;
import net.minecraft.client.multiplayer.chat.GuiMessage;

public interface ChatHudLineAccess {
    public void addUniqueMessageId(String uniqueMessageId);

    public Set<String> getUniqueMessageIds();

    public static ChatHudLineAccess of(GuiMessage chatHudLine) {
        return (ChatHudLineAccess) (Object) chatHudLine;
    }

    public static ChatHudLineAccess of(GuiMessage.Line uniqueMessageId) {
        return (ChatHudLineAccess) (Object) uniqueMessageId;
    }
}
