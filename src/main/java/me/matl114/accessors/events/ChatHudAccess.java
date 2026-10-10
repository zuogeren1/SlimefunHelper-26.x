package me.matl114.accessors.events;

import java.util.List;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;

public interface ChatHudAccess {
    public void setUniqueMessageId(String id);

    public List<GuiMessage.Line> getVisibleLines();

    public void clearUniqueMessages(String id);

    public static ChatHudAccess of(ChatComponent chatHud) {
        return (ChatHudAccess) chatHud;
    }
}
