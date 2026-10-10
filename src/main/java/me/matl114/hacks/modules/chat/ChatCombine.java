package me.matl114.hacks.modules.chat;

import me.matl114.utils.ClientUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.matl114.accessors.events.ChatHudAccess;
import me.matl114.events.Event;
import me.matl114.events.Listener;
import me.matl114.hacks.api.BaseModule;
import me.matl114.hacks.api.ModulePath;
import me.matl114.managers.Configs;
import me.matl114.managers.config.FlagRef;
import me.matl114.utils.ChatUtils;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;

public class ChatCombine extends BaseModule {
    public final ModulePath chatCombine = makePath(Configs.CHAT_CONFIG, "chat-combine");
    private static final String formatCombinedMessage = " &r&7&l[x&a%d&7&l]";
    private static final Pattern matcherCombinedMessageSuffix = Pattern.compile("^\\s*?\\[x(\\d*?)\\]$");

    public ChatCombine() {
        super("ChatCombine");
        bindFlag(enable);
    }

    public final FlagRef enable = flagBuilder(chatCombine.add("enable")).build();

    @Override
    public void registerAll() {
        super.registerAll();
        registerListener(Listener.getMessageAddToVisible(), this::onAddVisibleMessage);
    }

    public void onAddVisibleMessage(Event<GuiMessage> textEvent) {
        if (!enable.get()) {
            return;
        }
        GuiMessage line = textEvent.context();
        Component text = line.content();
        // use translated
        String rawString = ChatUtils.getOrderedTextString(text.getVisualOrderText());
        //       rawString = rawString.replaceAll("§.", "");
        ChatComponent hud = ClientUtils.getChat(mc);
        int amount = 0;
        if (hud != null) {
            var visibleHistory = ChatHudAccess.of(hud).getVisibleLines();
            try {
                ListIterator<GuiMessage.Line> lineIterator = visibleHistory.listIterator();
                List<FormattedCharSequence> textList = new ArrayList<>();
                while (lineIterator.hasNext()) {
                    var visible = lineIterator.next();
                    textList.add(0, visible.content());
                    String rawLine1 = ChatUtils.getOrderedTextString(textList.toArray(FormattedCharSequence[]::new));
                    // remove all fucking shits
                    if (rawLine1.length() > rawString.length() + 10 + formatCombinedMessage.length()) {
                        break;
                    }
                    if (rawLine1.startsWith(rawString)) {
                        String suffix = rawLine1.substring(rawString.length());
                        if (suffix.isEmpty()) {
                            // absolutely equals
                            amount += 1;
                            lineIterator.remove();
                            while (lineIterator.hasPrevious()) {
                                lineIterator.previous();
                                lineIterator.remove();
                            }
                            //                       do {
                            //                           lineIterator.remove();
                            //                       }while (lineIterator.hasPrevious());
                            textList.clear();
                        } else {
                            // check if with suffix
                            Matcher matcher = matcherCombinedMessageSuffix.matcher(suffix);
                            if (matcher.find()) {
                                try {
                                    // combine amount and remove line
                                    amount += Integer.parseInt(matcher.group(1));
                                    lineIterator.remove();
                                    while (lineIterator.hasPrevious()) {
                                        lineIterator.previous();
                                        lineIterator.remove();
                                    }
                                    textList.clear();
                                } catch (Throwable e) {
                                    // break combine
                                    continue;
                                }
                            }
                        }
                    } else {
                        // break combine
                        continue;
                    }
                }
            } catch (UnsupportedOperationException e) {
                // 该列表不支持迭代器删除（第三方模组可能替换了 trimmedMessages），放弃本次合并，原样放行
            }
        }
        if (amount > 0) {

            String newLineLegacy = formatCombinedMessage.formatted(amount + 1);
            MutableComponent newLine = ChatUtils.copyText(text);
            newLine.append(ChatUtils.stringToText(newLineLegacy));
            textEvent.context(new GuiMessage(line.addedTime(), newLine, line.signature(), line.source(), line.tag()));
        }
    }
}
