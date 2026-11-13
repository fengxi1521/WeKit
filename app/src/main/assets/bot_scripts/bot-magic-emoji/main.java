import java.io.*;
import java.util.*;

// ============================================================
// 魔法表情：引用对方消息后发送两字指令，生成带头像的动图表情
// ============================================================

final String ALLOW_GROUP_KEY = "allow_group_list";
final String DISABLED_GROUP_KEY = "disabled_group_list";
Map emojiEffects;

String loadConfigValue(String key) {
    try {
        File configFile = new File(pluginDir, "config.prop");
        if (configFile.exists()) {
            Properties props = new Properties();
            props.load(new FileInputStream(configFile));
            return props.getProperty(key, "").trim();
        }
    } catch (Throwable ignored) {}
    return getString(key, "");
}

boolean isDisabledGroup(String roomWxid) {
    String list = loadConfigValue(DISABLED_GROUP_KEY);
    if (list.isEmpty()) return false;
    for (String g : list.split(",")) {
        if (g.trim().equals(roomWxid)) return true;
    }
    return false;
}

boolean isAllowGroup(String roomWxid) {
    if (isDisabledGroup(roomWxid)) return false;
    String list = loadConfigValue(ALLOW_GROUP_KEY);
    if (list.isEmpty()) return true;
    for (String g : list.split(",")) {
        if (g.trim().equals(roomWxid)) return true;
    }
    return false;
}

void onLoad() {
    emojiEffects = new HashMap();
    emojiEffects.put("摸摸头", "PAT");
    emojiEffects.put("挠头", "PAT");
    emojiEffects.put("亲亲", "ZOOM");
    emojiEffects.put("爱心", "ZOOM");
    emojiEffects.put("大佬", "ZOOM");
    emojiEffects.put("拽哥", "ZOOM");
    emojiEffects.put("生气", "RAGE");
    emojiEffects.put("捶你", "RAGE");
    emojiEffects.put("威胁", "RAGE");
    emojiEffects.put("打死", "RAGE");
    emojiEffects.put("去玩", "FLY");
    emojiEffects.put("射", "FLY");
    emojiEffects.put("飞机", "FLY");
    emojiEffects.put("来了", "FLY");
    emojiEffects.put("踢飞", "FLY");
    emojiEffects.put("打球", "FLY");
    emojiEffects.put("摇乐", "SPIN");
    emojiEffects.put("呼圈", "SPIN");
    emojiEffects.put("扭屁", "SPIN");
    emojiEffects.put("摇起", "SPIN");
    emojiEffects.put("按脚", "TILT");
    emojiEffects.put("口我", "TILT");
    emojiEffects.put("吃了", "TILT");
    emojiEffects.put("需要", "TILT");
    emojiEffects.put("咸鱼", "TILT");
    log("魔法表情已加载，引用消息后发送两字指令即可");
}

void onUnload() {
    log("魔法表情已卸载");
}

void sendMenu(String talker) {
    sendText(talker,
        "==== 魔法表情 ====\n" +
        "用法：长按对方消息 → 引用 → 发送两字指令\n\n" +
        "摸摸头 / 挠头 / 亲亲 / 爱心 / 大佬 / 拽哥\n" +
        "生气 / 捶你 / 威胁 / 打死 / 去玩 / 射\n" +
        "飞机 / 来了 / 踢飞 / 打球 / 摇乐 / 呼圈\n" +
        "扭屁 / 摇起 / 按脚 / 口我 / 吃了 / 需要 / 咸鱼\n\n" +
        "表情会使用被引用者的头像生成并发送");
}

void onHandleMsg(Object msg) {
    if (!msg.isGroupChat()) return;

    String talker = msg.getTalker();
    if (!isAllowGroup(talker)) return;

    // 引用回复的类型是 QUOTE，不是 TEXT；指令文本在 refermsg 的 title 里
    String text = null;
    String target = null;
    if (msg.isQuote()) {
        Object quote = msg.getQuoteMsg();
        if (quote == null) return;
        text = quote.getTitle().trim();
        target = quote.getSendTalker();
    } else if (msg.isText()) {
        text = msg.getContent().trim();
    }
    if (text == null) return;

    if (text.equals("魔法菜单") || text.equals("表情菜单")) {
        sendMenu(talker);
        return;
    }

    Object effectObj = emojiEffects.get(text);
    if (effectObj == null) return;

    if (target == null || target.length() == 0) {
        sendText(talker, "请长按对方的消息选择「引用」，再发送「" + text + "」");
        return;
    }

    sendMagicEmoji(talker, target, effectObj.toString(), text);
}
