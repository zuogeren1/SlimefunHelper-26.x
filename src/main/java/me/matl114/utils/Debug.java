package me.matl114.utils;

import java.util.Arrays;
import lombok.Getter;
import me.matl114.SlimefunHelper;
import me.matl114.hacks.ChatTasks;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import me.matl114.hacks.utils.ClientUtils;

@ApiMethod
public class Debug {
    @Getter
    private static Logger logger = LoggerFactory.getLogger("SlimefunHelper");

    public static void info(String string) {
        logger.info(string);
    }

    public static void chat(Object string) {
        if (string instanceof Component txt) {
            sendPlayer(txt);
        } else {
            sendPlayer(Component.literal(string == null ? "null" : string.toString()));
        }
    }

    public static void sendPlayer(Component text) {
        if (Minecraft.getInstance().player != null) {
            // do not log async
            if (Minecraft.getInstance().isSameThread()) {
                ClientUtils.getChat().addClientSystemMessage(text);
            } else {
                Minecraft.getInstance().execute(() -> {
                    ClientUtils.getChat().addClientSystemMessage(text);
                });
            }
        } else {
            ChatTasks.sendDelayChatMessage(text);
        }
    }

    public static void chat(Object... values) {
        MutableComponent text = Component.literal("");
        boolean f = true;
        for (var tx : values) {
            if (f) {
                f = false;
            } else {
                text.append(Component.nullToEmpty(" "));
            }

            if (tx instanceof Component) {
                text.append(((Component) tx));
            } else {
                text.append(Component.literal(tx == null ? "null" : tx.toString()));
            }
        }
        sendPlayer(text);
    }
    //    public static void chat(String... string){
    //        if(Minecraft.getInstance().player!=null){
    //            Minecraft.getInstance().player.sendMessage(Text.of(String.join(" ", string)));
    //        }
    //    }

    public static void info(Object... objs) {
        info(String.join(
                " ",
                Arrays.stream(objs).map(o -> o == null ? "null" : o.toString()).toArray(String[]::new)));
    }

    public static void info(Object object) {
        if (object instanceof Throwable throwable) {
            logger.info("Printing stack trace:", throwable);
            return;
        }
        logger.info(object != null ? object.toString() : "null");
    }

    public static void debug(Object... objs) {
        if (SlimefunHelper.DEV_ENV) {
            info((Object[]) objs);
        }
    }

    public static void debug(Object object) {
        if (SlimefunHelper.DEV_ENV) {
            info((Object) object);
        }
    }

    public static void stackTrace() {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        for (StackTraceElement stackTraceElement : stackTraceElements) {
            Debug.info(stackTraceElement.toString());
        }
    }

    public static boolean test(Object obj) {
        info(obj);
        return false;
    }
}
