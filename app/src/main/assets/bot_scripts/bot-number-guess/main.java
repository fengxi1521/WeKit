import java.io.*;
import java.util.*;

// ============================================================
// 猜数字小游戏
// ============================================================

final String ALLOW_GROUP_KEY = "allow_group_list";
final String DISABLED_GROUP_KEY = "disabled_group_list";
final String GAME_KEY = "guess_num_answer";
final String GAME_SWITCH = "game_switch";

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

String getOwnerWxid() {
    String configured = getString("root_wxid", "").trim();
    if (!configured.isEmpty()) return configured;
    return getLoginWxid();
}

boolean isAdmin(String wxid) {
    return wxid != null && wxid.equals(getOwnerWxid());
}

void resetNumber() {
    putInt(GAME_KEY, new Random().nextInt(100) + 1);
}

void onLoad() {
    if (getInt(GAME_KEY, 0) < 1) resetNumber();
    log("猜数字小游戏已加载，发送「游戏菜单」查看玩法");
}

void onUnload() {
    log("猜数字小游戏已卸载");
}

void sendMenu(String talker) {
    sendText(talker,
        "==== 猜数字游戏 ====\n" +
        "1. 发送「猜50」来猜数字，范围 1~100\n" +
        "2. 发送「重置数字」重新生成答案（管理员）\n" +
        "3. 发送「游戏开关」开启或关闭（管理员）\n" +
        "提示：猜错会告诉你偏大还是偏小");
}

void onHandleMsg(Object msg) {
    if (!msg.isGroupChat() || !msg.isText()) return;

    String talker = msg.getTalker();
    if (!isAllowGroup(talker)) return;

    String sender = msg.getSender();
    String text = msg.getContent().trim();

    if (text.equals("游戏菜单") || text.equals("猜数字菜单")) {
        sendMenu(talker);
        return;
    }

    if (text.equals("游戏开关")) {
        if (!isAdmin(sender)) {
            sendText(talker, "仅管理员可以切换游戏开关");
            return;
        }
        boolean open = getInt(GAME_SWITCH, 1) == 1;
        putInt(GAME_SWITCH, open ? 0 : 1);
        sendText(talker, open ? "猜数字游戏已关闭" : "猜数字游戏已开启");
        return;
    }

    if (text.equals("重置数字")) {
        if (!isAdmin(sender)) {
            sendText(talker, "仅管理员可以重置数字");
            return;
        }
        resetNumber();
        sendText(talker, "已重新生成数字，快来猜！");
        return;
    }

    if (getInt(GAME_SWITCH, 1) == 0) return;

    if (text.startsWith("猜")) {
        String numStr = text.substring(1).trim();
        int target = getInt(GAME_KEY, 50);
        int inputNum;
        try {
            inputNum = Integer.parseInt(numStr);
        } catch (Exception e) {
            sendText(talker, "格式不对，正确写法：猜30");
            return;
        }
        if (inputNum < 1 || inputNum > 100) {
            sendText(talker, "数字范围是 1~100");
            return;
        }
        String nick = getFriendDisplayName(sender, talker);
        if (inputNum > target) {
            sendText(talker, "[AtWx=" + sender + "] " + nick + " 猜大了，再小一点");
        } else if (inputNum < target) {
            sendText(talker, "[AtWx=" + sender + "] " + nick + " 猜小了，再大一点");
        } else {
            sendText(talker, "🎉 " + nick + " 猜对了！答案就是 " + target + "，已更换新数字");
            resetNumber();
        }
    }
}
