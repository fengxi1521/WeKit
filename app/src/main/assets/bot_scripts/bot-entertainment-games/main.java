// ============================================================
// 娱乐游戏插件 v2.0.0 - 成语接龙 & 猜人物
// 命令: #成语接龙 / #猜人物 / #结束 / #排行榜 / #提示 / #猜人物总排行 / #帮助
// 猜人物新增: 发"下一题"跳题 / 提示次数 / 猜人物总排行 / 题库量展示
// 数据文件拆分到 data/ 目录，通过 loadJava 加载
// ============================================================

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

// ======================== 生效群过滤 ========================

final String ALLOW_GROUP_KEY = "allow_group_list";
final String DISABLED_GROUP_KEY = "disabled_group_list";

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

boolean isBotAllowedGroup(String roomWxid) {
    String disabled = loadConfigValue(DISABLED_GROUP_KEY);
    if (!disabled.isEmpty()) {
        for (String g : disabled.split(",")) {
            if (g.trim().equals(roomWxid)) return false;
        }
    }
    String allowed = loadConfigValue(ALLOW_GROUP_KEY);
    if (allowed.isEmpty()) return true;
    for (String g : allowed.split(",")) {
        if (g.trim().equals(roomWxid)) return true;
    }
    return false;
}

// ======================== 加载数据文件 ========================

// 加载成语库
loadJava("data/idioms.java");

// 加载人物题库
loadJava("data/characters.java");

// 全局已用成语索引（跨轮次不重复）
Set usedIdiomGlobalIndices = new HashSet();

// 全局已出人物索引（跨轮次不重复，全部出完重置）
Set usedCharacterIndices = new HashSet();

int pickCharacterIndex() {
    if (usedCharacterIndices.size() >= CHARACTERS.length) {
        usedCharacterIndices.clear();
    }
    List available = new ArrayList();
    for (int i = 0; i < CHARACTERS.length; i++) {
        if (!usedCharacterIndices.contains(Integer.valueOf(i))) {
            available.add(Integer.valueOf(i));
        }
    }
    if (available.isEmpty()) {
        available.add(Integer.valueOf(0));
    }
    int idx = ((Integer) available.get(new Random().nextInt(available.size()))).intValue();
    usedCharacterIndices.add(Integer.valueOf(idx));
    return idx;
}

// ======================== 游戏会话类 ========================

class GameSession {
    String type;
    String talker;
    long startTime;
    boolean running;
    Timer timer;

    // 成语接龙
    String lastIdiom;
    String lastIdiomEnd;
    Set usedIdioms = new HashSet();

    // 猜人物
    String currentChar;
    String hint1;
    String hint2;
    boolean hint2Revealed;
    Set closeHinted = new HashSet();

    // 猜人物 - 跳题次数 (sender -> Integer)
    Map skipCount = new HashMap();
    // 猜人物 - 提示次数 (sender -> Integer)
    Map hintCount = new HashMap();

    // 排行榜（仅限本局，猜对题数）
    Map correctCount = new HashMap();
    Map names = new HashMap();
    int questionIndex = 0;

    GameSession(String type, String talker) {
        this.type = type;
        this.talker = talker;
        this.running = false;
    }
}

Map activeGames = new ConcurrentHashMap();

// ======================== 猜人物总排行（按群隔离，持久化存储） ========================

// 猜人物总排行数据结构: Map<群talker, Map<senderWxid, Integer(总分)>>
Map totalRanking = new ConcurrentHashMap();
// 猜人物总排行名字缓存: Map<群talker, Map<senderWxid, String(名字)>>
Map totalRankingNames = new ConcurrentHashMap();

void loadTotalRanking() {
    try {
        String data = getString("totalRanking", "");
        if (data != null && !data.isEmpty()) {
            // 格式: talker|wxid|name|score;talker|wxid|name|score;...
            String[] entries = data.split(";");
            for (int i = 0; i < entries.length; i++) {
                String[] parts = entries[i].split("\\|", 4);
                if (parts.length >= 4) {
                    String tk = parts[0];
                    String wxid = parts[1];
                    String nm = parts[2];
                    int sc = Integer.parseInt(parts[3]);
                    Map groupMap = (Map) totalRanking.get(tk);
                    if (groupMap == null) {
                        groupMap = new HashMap();
                        totalRanking.put(tk, groupMap);
                    }
                    groupMap.put(wxid, Integer.valueOf(sc));
                    Map nameMap = (Map) totalRankingNames.get(tk);
                    if (nameMap == null) {
                        nameMap = new HashMap();
                        totalRankingNames.put(tk, nameMap);
                    }
                    nameMap.put(wxid, nm);
                }
            }
        }
    } catch (Exception e) {
        log("loadTotalRanking error: " + e.toString());
    }
}

void saveTotalRanking() {
    try {
        StringBuffer sb = new StringBuffer();
        Iterator it = totalRanking.keySet().iterator();
        while (it.hasNext()) {
            String tk = (String) it.next();
            Map groupMap = (Map) totalRanking.get(tk);
            Map nameMap = (Map) totalRankingNames.get(tk);
            if (groupMap == null) continue;
            Iterator it2 = groupMap.keySet().iterator();
            while (it2.hasNext()) {
                String wxid = (String) it2.next();
                Integer score = (Integer) groupMap.get(wxid);
                String nm = nameMap != null ? (String) nameMap.get(wxid) : wxid;
                if (nm == null) nm = wxid;
                if (sb.length() > 0) sb.append(";");
                sb.append(tk).append("|").append(wxid).append("|").append(nm).append("|").append(score);
            }
        }
        putString("totalRanking", sb.toString());
    } catch (Exception e) {
        log("saveTotalRanking error: " + e.toString());
    }
}

// 将本局排行结果累加到猜人物总排行（按群隔离）
void updateTotalRanking(GameSession session) {
    String talker = session.talker;
    Map groupMap = (Map) totalRanking.get(talker);
    if (groupMap == null) {
        groupMap = new HashMap();
        totalRanking.put(talker, groupMap);
    }
    Map nameMap = (Map) totalRankingNames.get(talker);
    if (nameMap == null) {
        nameMap = new HashMap();
        totalRankingNames.put(talker, nameMap);
    }
    Iterator it = session.correctCount.keySet().iterator();
    while (it.hasNext()) {
        String wxid = (String) it.next();
        Integer score = (Integer) session.correctCount.get(wxid);
        String nm = (String) session.names.get(wxid);
        if (nm == null) nm = wxid;
        Integer old = (Integer) groupMap.get(wxid);
        int total = (old != null ? old.intValue() : 0) + (score != null ? score.intValue() : 0);
        groupMap.put(wxid, Integer.valueOf(total));
        nameMap.put(wxid, nm);
    }
    saveTotalRanking();
}

// 显示猜人物总排行
void showTotalRanking(String talker) {
    Map groupMap = (Map) totalRanking.get(talker);
    Map nameMap = (Map) totalRankingNames.get(talker);
    StringBuffer sb = new StringBuffer();
    sb.append("━━━━━━━━━━━━━━━━\n");
    sb.append("猜人物总排行榜\n");
    sb.append("━━━━━━━━━━━━━━━━\n");
    if (groupMap == null || groupMap.isEmpty()) {
        sb.append("暂无排行记录\n");
        sb.append("多参与猜人物游戏来上榜吧！");
    } else {
        List list = new ArrayList(groupMap.entrySet());
        Collections.sort(list, new Comparator() {
            public int compare(Object a, Object b) {
                Map.Entry ea = (Map.Entry) a;
                Map.Entry eb = (Map.Entry) b;
                Integer va = (Integer) ea.getValue();
                Integer vb = (Integer) eb.getValue();
                return vb.compareTo(va);
            }
        });
        int rank = 1;
        int maxRank = Math.min(list.size(), 20);
        Iterator it = list.iterator();
        int shown = 0;
        while (it.hasNext() && shown < maxRank) {
            Map.Entry entry = (Map.Entry) it.next();
            String wxid = (String) entry.getKey();
            String name = nameMap != null ? (String) nameMap.get(wxid) : wxid;
            if (name == null) name = wxid;
            String medal = rank == 1 ? "【1】" : rank == 2 ? "【2】" : rank == 3 ? "【3】" : " " + rank + ". ";
            sb.append(medal).append(name).append(" - 总猜对").append(entry.getValue()).append("题\n");
            rank++;
            shown++;
        }
        if (list.size() > 20) {
            sb.append("...共").append(list.size()).append("人上榜\n");
        }
    }
    sb.append("\n━━━━━━━━━━━━━━━━\n");
    sb.append("猜人物总排行按各会话独立统计，不跨群混合");
    sendText(talker, sb.toString());
}

// ======================== 插件生命周期 ========================

void onLoad() {
    log("娱乐游戏插件 v2.0.0 已加载");
    log("成语库: " + IDIOMS.length + "个成语");
    log("人物库: " + CHARACTERS.length + "个人物");
    loadTotalRanking();
}

void onUnload() {
    Iterator it = activeGames.values().iterator();
    while (it.hasNext()) {
        GameSession s = (GameSession) it.next();
        if (s.timer != null) {
            s.timer.cancel();
        }
        s.running = false;
    }
    activeGames.clear();
    saveTotalRanking();
    log("娱乐游戏插件 v2.0.0 已卸载");
}

// ======================== 消息处理 ========================

void onHandleMsg(Object msg) {
    try {
        String talker = (String) invokeMethod(msg, "getTalker", 0);
        String sender = (String) invokeMethod(msg, "getSender", 0);
        String content = (String) invokeMethod(msg, "getContent", 0);
        boolean isGroup = (boolean) invokeMethod(msg, "isGroupChat", 0);

        if (isGroup && !isBotAllowedGroup(talker)) return;

        if (content == null || content.isEmpty()) return;
        content = content.trim();

        String displayName = getDisplayName(talker, sender, isGroup);

        // 处理"下一题"跳题指令（不需要#前缀）
        GameSession session0 = (GameSession) activeGames.get(talker);
        if (session0 != null && session0.running && "character".equals(session0.type) && "下一题".equals(content)) {
            handleSkipQuestion(session0, talker, sender, displayName);
            return;
        }

        if (content.startsWith("#") || content.startsWith("＃") || "提示".equals(content)) {
            handleCommand(content, talker, sender, displayName, isGroup);
            return;
        }

        GameSession session = (GameSession) activeGames.get(talker);
        if (session == null || !session.running) return;

        if ("idiom".equals(session.type)) {
            handleIdiomGuess(content, session, sender, displayName, talker);
        } else if ("character".equals(session.type)) {
            handleCharacterGuess(content, session, sender, displayName, talker);
        }
    } catch (Exception e) {
        log("onHandleMsg error: " + e.toString());
    }
}

String getDisplayName(String talker, String sender, boolean isGroup) {
    if (!isGroup) return sender;
    try {
        String name = getFriendDisplayName(sender, talker);
        if (name == null || name.isEmpty()) {
            name = getGroupMemberName(talker, sender);
        }
        if (name == null || name.isEmpty()) {
            name = sender;
        }
        return name;
    } catch (Exception e) {
        return sender;
    }
}

// ======================== 命令处理 ========================

void handleCommand(String content, String talker, String sender, String displayName, boolean isGroup) {
    String cmd = content.startsWith("#") || content.startsWith("＃") ? content.substring(1).trim() : content.trim();
    GameSession existing = (GameSession) activeGames.get(talker);

    if ("成语接龙".equals(cmd)) {
        if (existing != null && existing.running) {
            sendText(talker, "当前会话已有游戏在进行中，请先发送 #结束 再开始新游戏！");
            return;
        }
        startIdiomGame(talker, sender, displayName);
    } else if ("猜人物".equals(cmd)) {
        if (existing != null && existing.running) {
            sendText(talker, "当前会话已有游戏在进行中，请先发送 #结束 再开始新游戏！");
            return;
        }
        startCharacterGame(talker, sender, displayName);
    } else if ("结束".equals(cmd)) {
        if (existing == null || !existing.running) {
            sendText(talker, "当前没有进行中的游戏！");
            return;
        }
        endGame(existing, talker, "游戏被手动结束");
    } else if ("排行榜".equals(cmd)) {
        if (existing == null || !existing.running) {
            sendText(talker, "当前没有进行中的游戏，无法查看排行榜！");
            return;
        }
        showLeaderboard(existing, talker);
    } else if ("提示".equals(cmd)) {
        if (existing == null || !existing.running) {
            sendText(talker, "当前没有进行中的游戏！");
            return;
        }
        if ("character".equals(existing.type)) {
            handleHintRequest(existing, talker, sender, displayName);
        } else if ("idiom".equals(existing.type)) {
            String lastChar = existing.lastIdiom.substring(existing.lastIdiom.length() - 1);
            sendText(talker, "当前成语：「" + existing.lastIdiom + "」\n请接「" + lastChar + "」字开头的成语，加油！");
        }
    } else if ("猜人物总排行".equals(cmd)) {
        showTotalRanking(talker);
    } else if ("帮助".equals(cmd)) {
        sendHelp(talker);
    }
}

void sendHelp(String talker) {
    sendText(talker, "娱乐游戏 v2.0.0 - 游戏帮助\n\n"
        + "━━ 命令列表 ━━\n"
        + "#成语接龙 - 开始成语接龙游戏\n"
        + "#猜人物 - 开始猜人物游戏（题库" + CHARACTERS.length + "人）\n"
        + "#结束 - 结束当前游戏\n"
        + "#排行榜 - 查看当前游戏排行榜\n"
        + "#提示 - 获取猜人物的关键提示\n"
        + "#猜人物总排行 - 查看猜人物总排行榜\n"
        + "#帮助 - 显示此帮助\n\n"
        + "━━ 猜人物规则 ━━\n"
        + "每局开始每人1次跳题机会\n"
        + "发送「下一题」即可跳题（无需#）\n"
        + "每答对5题 +1次跳题机会\n"
        + "每人初始3次获取提示机会\n"
        + "每答对3题 +1次提示机会\n"
        + "答对3题/5题时会@提醒\n\n"
        + "每局限时60秒，猜对后重置计时\n"
        + "猜对一题计一题，猜中后继续下一题\n"
        + "每局结束排行榜自动计入猜人物总排行\n"
        + "猜人物总排行按各群独立统计，不跨群混合");
}

// ======================== 成语接龙游戏 ========================

void startIdiomGame(String talker, String sender, String displayName) {
    if (IDIOMS.length < 5) {
        sendText(talker, "成语库数据不足，无法开始游戏！");
        return;
    }

    GameSession session = new GameSession("idiom", talker);
    session.running = true;
    session.startTime = System.currentTimeMillis();

    if (usedIdiomGlobalIndices.size() >= IDIOMS.length) {
        usedIdiomGlobalIndices.clear();
    }

    Random rand = new Random();
    int idx;
    List available = new ArrayList();
    for (int i = 0; i < IDIOMS.length; i++) {
        if (!usedIdiomGlobalIndices.contains(Integer.valueOf(i))) {
            available.add(Integer.valueOf(i));
        }
    }
    if (available.isEmpty()) {
        available.add(Integer.valueOf(rand.nextInt(IDIOMS.length)));
    }
    idx = ((Integer) available.get(rand.nextInt(available.size()))).intValue();

    usedIdiomGlobalIndices.add(Integer.valueOf(idx));
    session.lastIdiom = IDIOMS[idx][0];
    session.lastIdiomEnd = IDIOMS[idx][2];
    session.usedIdioms.add(session.lastIdiom);
    session.correctCount.put(sender, Integer.valueOf(0));
    session.names.put(sender, displayName);

    activeGames.put(talker, session);

    String lastChar = session.lastIdiom.substring(session.lastIdiom.length() - 1);
    sendText(talker, "成语接龙开始！\n"
        + "限时60秒，猜对后重置计时！\n\n"
        + "第一个成语：" + session.lastIdiom + "\n"
        + "请接「" + lastChar + "」字开头的成语\n\n"
        + "每答对一题计一题\n"
        + "每局不会重复出现相同成语！\n"
        + "发送 #结束 可提前结束游戏");

    startTimer(session, talker, 60);
}

void handleIdiomGuess(String content, GameSession session, String sender, String displayName, String talker) {
    if (!session.running) return;

    String idiom = content.trim();
    if (idiom.length() != 4) return;

    boolean found = false;
    int foundIdx = -1;
    for (int i = 0; i < IDIOMS.length; i++) {
        if (IDIOMS[i][0].equals(idiom)) {
            found = true;
            foundIdx = i;
            break;
        }
    }
    if (!found) return;

    if (session.usedIdioms.contains(idiom)) {
        sendText(talker, "「" + idiom + "」已经用过了，换一个吧！");
        return;
    }
    if (usedIdiomGlobalIndices.size() >= IDIOMS.length) {
        usedIdiomGlobalIndices.clear();
    }
    if (usedIdiomGlobalIndices.contains(Integer.valueOf(foundIdx))) {
        String expP = null;
        for (int i = 0; i < IDIOMS.length; i++) {
            if (IDIOMS[i][0].equals(session.lastIdiom)) {
                expP = IDIOMS[i][2];
                break;
            }
        }
        boolean hasAvailable = false;
        for (int i = 0; i < IDIOMS.length; i++) {
            if (i == foundIdx) continue;
            if (!usedIdiomGlobalIndices.contains(Integer.valueOf(i))
                && expP != null && expP.equals(IDIOMS[i][1])) {
                hasAvailable = true;
                break;
            }
        }
        if (!hasAvailable) {
            usedIdiomGlobalIndices.clear();
        } else {
            sendText(talker, "「" + idiom + "」之前已经出现过了，换一个吧！");
            return;
        }
    }

    String firstChar = idiom.substring(0, 1);
    String expectedChar = session.lastIdiom.substring(session.lastIdiom.length() - 1);

    String firstPinyin = null;
    String expectedPinyin = null;
    for (int i = 0; i < IDIOMS.length; i++) {
        if (IDIOMS[i][0].equals(idiom)) {
            firstPinyin = IDIOMS[i][1];
        }
        if (IDIOMS[i][0].equals(session.lastIdiom)) {
            expectedPinyin = IDIOMS[i][2];
        }
    }

    if (!firstChar.equals(expectedChar)) {
        if (firstPinyin == null || expectedPinyin == null || !firstPinyin.equals(expectedPinyin)) {
            sendText(talker, "「" + idiom + "」的首字「" + firstChar + "」不匹配，"
                + "需要接「" + expectedChar + "」字开头的成语！");
            return;
        }
    }

    session.usedIdioms.add(idiom);
    if (foundIdx >= 0) {
        usedIdiomGlobalIndices.add(Integer.valueOf(foundIdx));
    }
    session.lastIdiom = idiom;
    session.lastIdiomEnd = null;
    for (int i = 0; i < IDIOMS.length; i++) {
        if (IDIOMS[i][0].equals(idiom)) {
            session.lastIdiomEnd = IDIOMS[i][2];
            break;
        }
    }

    Integer oldCount = (Integer) session.correctCount.get(sender);
    int newCount = (oldCount != null ? oldCount.intValue() : 0) + 1;
    session.correctCount.put(sender, Integer.valueOf(newCount));
    session.names.put(sender, displayName);

    String lastChar = idiom.substring(idiom.length() - 1);
    sendText(talker, displayName + " 答对了！累计猜对" + newCount + "题\n"
        + "下一个成语：" + idiom + "\n"
        + "请接「" + lastChar + "」字开头的成语\n"
        + "计时已重置，剩余60秒！");

    startTimer(session, talker, 60);
}

// ======================== 猜人物游戏 ========================

void startCharacterGame(String talker, String sender, String displayName) {
    if (CHARACTERS.length < 5) {
        sendText(talker, "人物库数据不足，无法开始游戏！");
        return;
    }

    GameSession session = new GameSession("character", talker);
    session.running = true;
    session.startTime = System.currentTimeMillis();
    session.hint2Revealed = false;

    // 初始化跳题次数和提示次数
    session.skipCount.put(sender, Integer.valueOf(1));   // 初始1次跳题
    session.hintCount.put(sender, Integer.valueOf(3));   // 初始3次提示

    int idx = pickCharacterIndex();
    session.currentChar = CHARACTERS[idx][0];
    session.hint1 = CHARACTERS[idx][1];
    session.hint2 = CHARACTERS[idx][2];
    session.correctCount.put(sender, Integer.valueOf(0));
    session.names.put(sender, displayName);
    session.questionIndex = 0;

    activeGames.put(talker, session);

    sendText(talker, "猜人物游戏开始！\n"
        + "题库共" + CHARACTERS.length + "个人物\n"
        + "限时60秒，猜对后重置计时！\n\n"
        + "提示1：" + session.hint1 + "\n\n"
        + "发送「提示」可提前获取关键提示（每人3次）\n"
        + "发送「下一题」可跳过本题（每人1次）\n"
        + "30秒后自动公布关键提示！\n"
        + "猜对一题计一题，猜中后继续下一题！\n"
        + "每答对3题+1次提示，每答对5题+1次跳题\n"
        + "发送 #结束 可提前结束游戏");

    // 30秒后自动揭示关键提示
    final String t = talker;
    final int curQ = session.questionIndex;
    delay(30000, new Runnable() {
        public void run() {
            GameSession s = (GameSession) activeGames.get(t);
            if (s != null && s.running && "character".equals(s.type) && !s.hint2Revealed && s.questionIndex == curQ) {
                s.hint2Revealed = true;
                sendText(t, "关键提示已公布！\n\n"
                    + "关键提示：" + s.hint2 + "\n\n"
                    + "答案首字提示：答案的第一个字是「" + firstCharOf(s.currentChar) + "」\n\n"
                    + "快猜猜他是谁！");
            }
        }
    });

    startTimer(session, talker, 60);
}

// 处理跳题请求
void handleSkipQuestion(GameSession session, String talker, String sender, String displayName) {
    if (!session.running) return;

    // 新玩家首次使用时初始化为1次跳题机会
    Integer skips = (Integer) session.skipCount.get(sender);
    if (skips == null) {
        skips = Integer.valueOf(1);
        session.skipCount.put(sender, skips);
    }

    if (skips.intValue() <= 0) {
        sendText(talker, "[AtWx=" + sender + "] " + displayName + " 你没有跳题机会了！\n"
            + "每答对5题可获得1次跳题机会");
        return;
    }

    // 消耗一次跳题次数
    session.skipCount.put(sender, Integer.valueOf(skips.intValue() - 1));
    int remaining = skips.intValue() - 1;

    sendText(talker, displayName + " 使用了跳题机会！\n"
        + "答案：" + session.currentChar + "\n"
        + "剩余跳题机会：" + remaining + "次\n\n"
        + "━━━━━━━━━━━━━━━━\n"
        + "下一题来了！计时已重置！");

    nextCharacterQuestion(session, talker);
    startTimer(session, talker, 60);
}

// 处理提示请求（带次数限制）
void handleHintRequest(GameSession session, String talker, String sender, String displayName) {
    if (session.hint2Revealed) {
        sendText(talker, "关键提示已经公布了，快去猜吧！");
        return;
    }

    // 新玩家首次使用时初始化为3次提示机会
    Integer hints = (Integer) session.hintCount.get(sender);
    if (hints == null) {
        hints = Integer.valueOf(3);
        session.hintCount.put(sender, hints);
    }

    if (hints.intValue() <= 0) {
        sendText(talker, "[AtWx=" + sender + "] " + displayName + " 你没有获取提示的机会了！\n"
            + "每答对3题可获得1次提示机会");
        return;
    }

    // 消耗一次提示次数
    session.hintCount.put(sender, Integer.valueOf(hints.intValue() - 1));
    int remaining = hints.intValue() - 1;

    session.hint2Revealed = true;
    sendText(talker, "[AtWx=" + sender + "] " + displayName + " 获取了关键提示！（剩余" + remaining + "次）\n\n"
        + "关键提示：" + session.hint2 + "\n\n"
        + "答案首字提示：答案的第一个字是「" + firstCharOf(session.currentChar) + "」\n\n"
        + "快猜猜他是谁！");
}

void handleCharacterGuess(String content, GameSession session, String sender, String displayName, String talker) {
    if (!session.running) return;

    String guess = content.trim();
    String answer = session.currentChar;

    String guessNorm = guess.replaceAll("[\\s·•.。，,、!！?？]", "");
    String answerNorm = answer.replaceAll("[\\s·•.。，,、!！?？]", "");
    if (guess.equals(answer) || (!guessNorm.isEmpty() && guessNorm.equals(answerNorm))) {
        Integer oldCount = (Integer) session.correctCount.get(sender);
        int newCount = (oldCount != null ? oldCount.intValue() : 0) + 1;
        session.correctCount.put(sender, Integer.valueOf(newCount));
        session.names.put(sender, displayName);

        // 新玩家首次答对时初始化跳题/提示次数（初始1次跳题、3次提示）
        if (!session.skipCount.containsKey(sender)) {
            session.skipCount.put(sender, Integer.valueOf(1));
        }
        if (!session.hintCount.containsKey(sender)) {
            session.hintCount.put(sender, Integer.valueOf(3));
        }

        // 检查是否获得新的跳题或提示机会
        // 每答对5题 +1跳题机会
        boolean gotSkip = false;
        if (newCount > 0 && newCount % 5 == 0) {
            Integer skips = (Integer) session.skipCount.get(sender);
            int curSkips = skips != null ? skips.intValue() : 0;
            session.skipCount.put(sender, Integer.valueOf(curSkips + 1));
            gotSkip = true;
        }

        // 每答对3题 +1提示机会
        boolean gotHint = false;
        if (newCount > 0 && newCount % 3 == 0) {
            Integer hints = (Integer) session.hintCount.get(sender);
            int curHints = hints != null ? hints.intValue() : 0;
            session.hintCount.put(sender, Integer.valueOf(curHints + 1));
            gotHint = true;
        }

        StringBuffer congrats = new StringBuffer();
        congrats.append("恭喜 ").append(displayName).append(" 猜中了！\n\n");
        congrats.append("答案：").append(answer).append("\n");
        congrats.append("累计猜对：").append(newCount).append("题\n");

        // 获得机会时@提醒
        if (gotHint) {
            Integer hints = (Integer) session.hintCount.get(sender);
            congrats.append("\n[AtWx=" + sender + "] " + displayName + " 答对3题，获得1次提示机会！当前提示机会：" + hints + "次");
        }
        if (gotSkip) {
            Integer skips = (Integer) session.skipCount.get(sender);
            congrats.append("\n[AtWx=" + sender + "] " + displayName + " 答对5题，获得1次跳题机会！当前跳题机会：" + skips + "次");
        }

        congrats.append("\n\n━━━━━━━━━━━━━━━━\n");
        congrats.append("下一题来了！\n计时已重置，剩余60秒！");

        sendText(talker, congrats.toString());

        nextCharacterQuestion(session, talker);
        startTimer(session, talker, 60);
    } else {
        // 模糊猜到几个字时，艾特玩家反馈"答案已经很接近了"
        // Bug修复：只有当hint2已揭示后才触发"答案已接近"提示，出题时hint2未揭示不触发
        if (session.hint2Revealed && isCloseGuess(guess, answer) && !session.closeHinted.contains(sender)) {
            session.closeHinted.add(sender);
            sendText(talker, "[AtWx=" + sender + "] " + displayName + " 答案已经很接近了，再想想！");
        }
    }
}

// 出下一题（提取公共逻辑）
void nextCharacterQuestion(GameSession session, String talker) {
    int idx = pickCharacterIndex();
    session.currentChar = CHARACTERS[idx][0];
    session.hint1 = CHARACTERS[idx][1];
    session.hint2 = CHARACTERS[idx][2];
    session.hint2Revealed = false;
    session.closeHinted.clear();
    session.questionIndex++;

    sendText(talker, "提示1：" + session.hint1 + "\n\n"
        + "发送「提示」可提前获取关键提示\n"
        + "发送「下一题」可跳过本题\n"
        + "30秒后自动公布关键提示！");

    // 30秒后自动揭示关键提示（新题目的计时器）
    final String t = talker;
    final int curQ = session.questionIndex;
    delay(30000, new Runnable() {
        public void run() {
            GameSession s = (GameSession) activeGames.get(t);
            if (s != null && s.running && "character".equals(s.type)
                && !s.hint2Revealed && s.questionIndex == curQ) {
                s.hint2Revealed = true;
                sendText(t, "关键提示已公布！\n\n"
                    + "关键提示：" + s.hint2 + "\n\n"
                    + "答案首字提示：答案的第一个字是「" + firstCharOf(s.currentChar) + "」\n\n"
                    + "快猜猜他是谁！");
            }
        }
    });
}

boolean isCloseGuess(String guess, String answer) {
    if (guess == null || answer == null || guess.isEmpty() || answer.isEmpty()) return false;
    String g = guess.replaceAll("[\\s·•.。，,、!！?？]", "");
    String a = answer.replaceAll("[\\s·•.。，,、!！?？]", "");
    if (g.isEmpty() || a.isEmpty()) return false;
    if (g.length() < 2) return false;
    return a.contains(g) || g.contains(a);
}

String firstCharOf(String answer) {
    if (answer == null || answer.isEmpty()) return "?";
    return answer.substring(0, 1);
}

// ======================== 计时器 ========================

void startTimer(GameSession session, String talker, int seconds) {
    if (session.timer != null) {
        session.timer.cancel();
    }

    session.timer = new Timer();
    final String t = talker;
    session.timer.schedule(new TimerTask() {
        public void run() {
            GameSession s = (GameSession) activeGames.get(t);
            if (s != null && s.running) {
                endGame(s, t, "时间到！游戏结束！");
            }
        }
    }, seconds * 1000L);
}

// ======================== 游戏结束 ========================

void endGame(GameSession session, String talker, String reason) {
    if (!session.running) return;
    session.running = false;
    if (session.timer != null) {
        session.timer.cancel();
        session.timer = null;
    }

    StringBuffer sb = new StringBuffer();
    sb.append("游戏结束！\n");
    sb.append(reason).append("\n\n");
    if ("character".equals(session.type) && session.currentChar != null && !session.currentChar.isEmpty()) {
        sb.append("━━━━━━━━━━━━━━━━\n");
        sb.append("本局最终答案：").append(session.currentChar).append("\n");
        sb.append("━━━━━━━━━━━━━━━━\n\n");
    }
    sb.append("本局排行榜\n");
    sb.append("━━━━━━━━━━━━━━━━\n");

    if (session.correctCount.isEmpty()) {
        sb.append("暂无猜对记录\n");
    } else {
        List list = new ArrayList(session.correctCount.entrySet());
        Collections.sort(list, new Comparator() {
            public int compare(Object a, Object b) {
                Map.Entry ea = (Map.Entry) a;
                Map.Entry eb = (Map.Entry) b;
                Integer va = (Integer) ea.getValue();
                Integer vb = (Integer) eb.getValue();
                return vb.compareTo(va);
            }
        });

        int rank = 1;
        Iterator it = list.iterator();
        while (it.hasNext()) {
            Map.Entry entry = (Map.Entry) it.next();
            String name = (String) session.names.get(entry.getKey());
            if (name == null) name = (String) entry.getKey();
            String medal = rank == 1 ? "【1】" : rank == 2 ? "【2】" : rank == 3 ? "【3】" : " " + rank + ". ";
            sb.append(medal).append(name)
              .append(" - 猜对").append(entry.getValue()).append("题\n");
            rank++;
        }
    }

    sb.append("━━━━━━━━━━━━━━━━\n");

    // 猜人物游戏结束时，更新猜人物总排行并提示
    if ("character".equals(session.type)) {
        updateTotalRanking(session);
        sb.append("本局成绩已计入猜人物总排行\n");
        sb.append("发送 #猜人物总排行 查看猜人物总排行榜\n\n");
    }

    sb.append("发送 #成语接龙 或 #猜人物 开始新游戏");

    sendText(talker, sb.toString());
    activeGames.remove(talker);
}

void showLeaderboard(GameSession session, String talker) {
    StringBuffer sb = new StringBuffer();
    String gameName = "idiom".equals(session.type) ? "成语接龙" : "猜人物";
    sb.append("━━━━━━━━━━━━━━━━\n");
    sb.append(gameName).append(" 当前排行榜\n");
    sb.append("━━━━━━━━━━━━━━━━\n");

    List list = new ArrayList(session.correctCount.entrySet());
    Collections.sort(list, new Comparator() {
        public int compare(Object a, Object b) {
            Map.Entry ea = (Map.Entry) a;
            Map.Entry eb = (Map.Entry) b;
            Integer va = (Integer) ea.getValue();
            Integer vb = (Integer) eb.getValue();
            return vb.compareTo(va);
        }
    });

    int rank = 1;
    Iterator it = list.iterator();
    while (it.hasNext()) {
        Map.Entry entry = (Map.Entry) it.next();
        String name = (String) session.names.get(entry.getKey());
        if (name == null) name = (String) entry.getKey();
        String medal = rank == 1 ? "【1】" : rank == 2 ? "【2】" : rank == 3 ? "【3】" : " " + rank + ". ";
        sb.append(medal).append(name)
          .append(" - 猜对").append(entry.getValue()).append("题\n");
        rank++;
    }

    sb.append("━━━━━━━━━━━━━━━━\n");
    sb.append("剩余时间继续进行中...");

    sendText(talker, sb.toString());
}
