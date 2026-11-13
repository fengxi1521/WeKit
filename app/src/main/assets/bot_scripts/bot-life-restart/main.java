// ==========================================
// 人生重开模拟器 - Hchat 微信脚本插件
// 版本: 2.1.0
// 作者: 人生重开
// 更新时间: 2026-08-05
//
// v2.1.0 核心机制:
// 1. 天赋选择: 开局从6个随机天赋中选3个（博弈拉扯）
// 2. 混合事件: 危险事件自动触发(躲不掉)，安全事件作为人生选项
// 3. 事件关联: 只触发选中的事件+自动事件，通过EVT条件链连贯
// 4. 生命值: LIF = 20 + 体质*2，60岁后每年-1
// 5. 修仙线: 入道后寿元提升到400，可活到500岁渡劫
// ==========================================

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

// ===== 全局变量 =====
JSONObject eventsData;
JSONObject agesData;
JSONObject talentsData;
JSONArray charactersData;
Map gameEnabled;    // talker -> Boolean
Map activeGames;    // talker -> Map(senderWxid -> gameState)
Map leaderboards;   // talker -> JSONArray
Random random;
boolean dataLoaded;

// ===== 生效群（与模块设置页共享 config.prop）=====
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

// ===== 生命周期 =====

void onLoad() {
    eventsData = null;
    agesData = null;
    talentsData = null;
    charactersData = null;
    gameEnabled = new HashMap();
    activeGames = new HashMap();
    leaderboards = new HashMap();
    random = new Random();
    dataLoaded = false;

    loadData();
    loadGameEnabled();
    loadLeaderboards();

    if (dataLoaded) {
        log("人生重开模拟器已加载 | 事件:" + eventsData.size()
            + " 年龄:" + agesData.size()
            + " 天赋:" + talentsData.size()
            + " 角色:" + charactersData.size());
    } else {
        log("人生重开模拟器: 数据加载失败，请检查data目录");
    }
}

void onUnload() {
    saveAllLeaderboards();
    log("人生重开模拟器已卸载");
}

// ===== 消息处理 =====

void onHandleMsg(Object msg) {
    if (!dataLoaded) return;
    if (!msg.isText()) return;
    if (msg.isGroupChat() && !isAllowGroup(msg.getTalker())) return;

    String content = msg.getContent();
    if (content == null || content.length() == 0) return;
    content = content.trim();

    String talker = msg.getTalker();
    String senderWxid = msg.getSender();
    if (talker == null || senderWxid == null) return;

    String playerName = getPlayerName(senderWxid, talker);

    // 指令路由
    if ("人生开关".equals(content)) {
        handleToggle(talker, senderWxid);
        return;
    }

    if ("开始模拟".equals(content)) {
        handleStart(talker, senderWxid, playerName);
        return;
    }

    if ("人生排行榜".equals(content)) {
        handleLeaderboard(talker);
        return;
    }

    if ("结束游戏".equals(content)) {
        handleQuit(talker, senderWxid);
        return;
    }

    if ("游戏帮助".equals(content)) {
        handleHelp(talker);
        return;
    }

    // 数字选择
    if (content.matches(".*[0-9].*")) {
        Map game = getActiveGame(talker, senderWxid);
        if (game == null) return;

        // 天赋选择阶段
        if (Boolean.TRUE.equals(game.get("awaitingTalentChoice"))) {
            handleTalentChoice(talker, senderWxid, content, playerName);
            return;
        }

        // 人生选项阶段
        if (Boolean.TRUE.equals(game.get("waitingForChoice"))) {
            handleChoice(talker, senderWxid, content, playerName);
            return;
        }
    }
}

// ===== 游戏控制 =====

void handleToggle(String talker, String senderWxid) {
    if (!senderWxid.equals(getLoginWxid())) {
        return;
    }

    boolean currentlyEnabled = isGameEnabled(talker);
    boolean newEnabled = !currentlyEnabled;
    gameEnabled.put(talker, newEnabled);
    saveGameEnabled();

    if (newEnabled) {
        sendText(talker, "人生重开模拟器已启动\n发送 开始模拟 开始游戏\n发送 游戏帮助 查看帮助");
    } else {
        sendText(talker, "人生重开模拟器已关闭");
    }
}

void handleStart(String talker, String senderWxid, String playerName) {
    if (!isGameEnabled(talker)) {
        sendText(talker, "游戏未开启，请等待管理员发送\"人生开关\"开启游戏");
        return;
    }

    // 如果已有进行中的游戏，先结束（不计入排行榜）
    Map oldGame = getActiveGame(talker, senderWxid);
    if (oldGame != null) {
        removeActiveGame(talker, senderWxid);
    }

    // 创建新游戏（先不分配天赋，等玩家选择）
    Map state = createGameState(talker, senderWxid, playerName);
    setActiveGame(talker, senderWxid, state);

    // 生成6个随机天赋候选
    List candidates = pickTalentCandidates(6);
    state.put("talentCandidates", candidates);
    state.put("awaitingTalentChoice", true);

    String display = formatTalentSelect(state, candidates);
    sendText(talker, display);
}

/**
 * 玩家选择天赋（发送如 135 或 1,3,5）
 */
void handleTalentChoice(String talker, String senderWxid, String content, String playerName) {
    Map state = getActiveGame(talker, senderWxid);
    if (state == null || !Boolean.TRUE.equals(state.get("awaitingTalentChoice"))) {
        return;
    }

    List candidates = (List) state.get("talentCandidates");
    List digits = parseTalentDigits(content);

    if (digits.isEmpty()) {
        sendText(talker, "请输入1-6的数字选择天赋，如：135");
        return;
    }

    // 选择最多3个天赋
    Set talentSet = new HashSet();
    for (int i = 0; i < digits.size() && talentSet.size() < 3; i++) {
        int d = getInt(digits.get(i));
        if (d >= 1 && d <= candidates.size()) {
            String tid = (String) candidates.get(d - 1);
            if (!talentSet.contains(tid)) talentSet.add(tid);
        }
    }

    // 不足3个则随机补充
    while (talentSet.size() < 3 && candidates.size() > 0) {
        String tid = (String) candidates.get(random.nextInt(candidates.size()));
        talentSet.add(tid);
    }

    state.put("talents", talentSet);
    state.put("awaitingTalentChoice", false);

    // 应用天赋效果
    applyTalentEffects(state, talentSet);

    // 计算初始生命值
    int str = getInt(state.get("STR"));
    int lif = 20 + str * 2;
    state.put("LIF", lif);
    state.put("maxLIF", lif);

    // 处理出生事件（age 0）
    processBirthEvents(state);

    // 检查是否出生即死
    if (checkDeath(state)) {
        String deathMsg = formatDeathScreen(state);
        sendText(talker, deathMsg);
        endGame(talker, state);
        return;
    }

    // 设置出生信息
    String birthText = (String) state.get("lastEventText");
    if (birthText == null || birthText.length() == 0) {
        birthText = "你出生了。";
    }
    state.put("lastEventText", birthText);
    state.put("lastChoice", false);

    // 进入第一年
    state.put("age", 1);
    nextYear(talker, state);
}

void handleChoice(String talker, String senderWxid, String content, String playerName) {
    Map state = getActiveGame(talker, senderWxid);
    if (state == null || !Boolean.TRUE.equals(state.get("waitingForChoice"))) {
        return;
    }

    List currentOptions = (List) state.get("currentOptions");

    // 无选项的年份 - 任意正整数继续
    if (currentOptions == null || currentOptions.isEmpty()) {
        state.put("waitingForChoice", false);
        // 风平浪静的一年：清掉旧选择结果，避免"上一年"残留
        state.put("lastEventText", "这一年风平浪静，无事发生。");
        state.put("lastChoice", false);
        int age = getInt(state.get("age"));
        state.put("age", age + 1);
        nextYear(talker, state);
        return;
    }

    // 解析数字
    int choice = 0;
    try {
        choice = Integer.parseInt(content.replaceAll("[^0-9]", ""));
    } catch (Exception e) {
        sendText(talker, "请输入 1-" + currentOptions.size() + " 选择");
        return;
    }

    if (choice < 1 || choice > currentOptions.size()) {
        sendText(talker, "请输入 1-" + currentOptions.size() + " 选择");
        return;
    }

    state.put("waitingForChoice", false);

    // 通过事件ID获取选中事件（避免引用/顺序问题）
    String chosenId = (String) currentOptions.get(choice - 1);
    JSONObject chosenEvent = eventsData.getJSONObject(chosenId);
    if (chosenEvent == null) {
        state.put("lastEventText", "什么事都没有发生。");
        int age = getInt(state.get("age"));
        state.put("age", age + 1);
        nextYear(talker, state);
        return;
    }

    // 玩家看到的选择文本
    String chosenText = getEventPreview(chosenEvent);

    // 解析分支（深度解析，根据属性/天赋/历史重定向）
    JSONObject resolved = resolveBranches(chosenEvent, state);
    if (resolved == null) {
        // 分支条件导致事件不触发
        state.put("lastEventText", chosenText + "（没有后续）");
    } else if (resolved == chosenEvent) {
        // 无分支重定向，直接应用
        String appliedText = applyEventSilent(state, resolved);
        state.put("lastEventText", appliedText);
    } else {
        // 分支重定向到变体事件：显示选择文本 + 分支结果
        String appliedText = applyEventSilent(state, resolved);
        state.put("lastEventText", chosenText + " → " + appliedText);
    }
    // 这是玩家自己的选择
    state.put("lastChoice", true);

    // 处理AGE字段（时间跳跃/倒流）
    int eventAge = resolved == null ? 0 : resolved.getIntValue("AGE");
    if (eventAge != 0) {
        int currentAge = getInt(state.get("age"));
        state.put("age", currentAge + eventAge);
    }

    // 检查死亡
    if (checkDeath(state)) {
        String deathMsg = formatDeathScreen(state);
        sendText(talker, deathMsg);
        endGame(talker, state);
        return;
    }

    // 进入下一年
    int age = getInt(state.get("age"));
    state.put("age", age + 1);
    nextYear(talker, state);
}

void handleLeaderboard(String talker) {
    JSONArray board = (JSONArray) leaderboards.get(talker);
    if (board == null || board.isEmpty()) {
        sendText(talker, "暂无排行榜数据，快去开始第一局游戏吧！");
        return;
    }

    StringBuilder sb = new StringBuilder();
    sb.append("人生排行榜 TOP10\n");
    sb.append("━━━━━━━━━━━━━━━━\n");

    int max = Math.min(board.size(), 10);
    for (int i = 0; i < max; i++) {
        JSONObject entry = board.getJSONObject(i);
        String name = entry.getString("name");
        int age = entry.getIntValue("age");
        String medal = "";
        if (i == 0) medal = "👑";
        else if (i == 1) medal = "🥈";
        else if (i == 2) medal = "🥉";
        else medal = "  ";
        sb.append(medal).append(" ").append(name).append(" - ").append(age).append("岁\n");
    }

    if (board.size() > 10) {
        sb.append("...(共").append(board.size()).append("条记录)\n");
    }

    sb.append("━━━━━━━━━━━━━━━━");
    sendText(talker, sb.toString());
}

void handleQuit(String talker, String senderWxid) {
    Map state = getActiveGame(talker, senderWxid);
    if (state == null) {
        return;
    }

    String playerName = (String) state.get("playerName");
    int age = getInt(state.get("age"));
    removeActiveGame(talker, senderWxid);
    sendText(talker, playerName + " 游戏结束(享年" + age + "岁)\n本次不计入排行榜");
}

void handleHelp(String talker) {
    StringBuilder sb = new StringBuilder();
    sb.append("人生重开模拟器 - 帮助\n");
    sb.append("━━━━━━━━━━━━━━━━\n");
    sb.append("人生开关 - 开启/关闭\n");
    sb.append("开始模拟 - 新的人生\n");
    sb.append("135 - 开局选天赋\n");
    sb.append("1/2/3 - 每年选路线\n");
    sb.append("结束游戏 - 结束本局\n");
    sb.append("人生排行榜 - 查看排名\n");
    sb.append("游戏帮助 - 此帮助\n");
    sb.append("━━━━━━━━━━━━━━━━\n");
    sb.append("【你的选择】上一年的决定\n");
    sb.append("【命运】躲不掉的事\n");
    sb.append("【人生选择】你现在选的路\n");
    sb.append("━━━━━━━━━━━━━━━━\n");
    sb.append("危险命运躲不掉！\n");
    sb.append("活过100岁解锁修仙线\n");
    sb.append("存活越久排名越高");
    sendText(talker, sb.toString());
}

// ===== 游戏逻辑 =====

Map createGameState(String talker, String senderWxid, String playerName) {
    Map state = new HashMap();
    state.put("talker", talker);
    state.put("senderWxid", senderWxid);
    state.put("playerName", playerName);
    state.put("age", 0);
    state.put("step", 0);

    // 随机选择角色模板（提供基础属性）
    int charIdx = random.nextInt(charactersData.size());
    JSONObject character = charactersData.getJSONObject(charIdx);

    state.put("character", character.getString("name"));
    state.put("CHR", character.getIntValue("CHR"));
    state.put("INT", character.getIntValue("INT"));
    state.put("STR", character.getIntValue("STR"));
    state.put("MNY", character.getIntValue("MNY"));
    state.put("SPR", 5);
    state.put("gender", "男");

    state.put("talents", new HashSet());
    state.put("eventHistory", new HashSet());
    state.put("alive", true);
    state.put("waitingForChoice", false);
    state.put("currentOptions", null);
    state.put("lastEventText", "");
    state.put("lastChoice", false);

    return state;
}

/**
 * 从全部天赋中随机抽取count个作为候选。
 */
List pickTalentCandidates(int count) {
    List all = new ArrayList(talentsData.keySet());
    List picked = new ArrayList();
    Set used = new HashSet();

    while (picked.size() < count && picked.size() < all.size()) {
        String tid = (String) all.get(random.nextInt(all.size()));
        if (used.contains(tid)) continue;
        used.add(tid);
        picked.add(tid);
    }
    return picked;
}

/**
 * 解析玩家发送的天赋选择数字，如 "135" / "1,3,5" / "1 3 5"。
 */
List parseTalentDigits(String content) {
    List digits = new ArrayList();
    for (int i = 0; i < content.length(); i++) {
        char c = content.charAt(i);
        if (c >= '1' && c <= '6') {
            int d = c - '0';
            if (!digits.contains(d)) {
                digits.add(d);
            }
        }
    }
    return digits;
}

void applyTalentEffects(Map state, Set talentSet) {
    // 天赋效果属性映射（电子表格标签与实际属性存在偏移）:
    // effect:SPR -> 额外属性点（随机分配）
    // effect:MNY -> 快乐(SPR)
    // effect:CHR -> 家境(MNY)
    // effect:STR -> 颜值(CHR)
    // effect:INT -> 体质(STR)
    // effect:RDM -> 智力(INT)

    int extraPoints = 0;

    for (Object tidObj : talentSet) {
        String tid = (String) tidObj;
        JSONObject talent = talentsData.getJSONObject(tid);
        if (talent == null) continue;

        int sprEffect = talent.getIntValue("SPR");
        int mnyEffect = talent.getIntValue("MNY");
        int chrEffect = talent.getIntValue("CHR");
        int strEffect = talent.getIntValue("STR");
        int intEffect = talent.getIntValue("INT");
        int rdmEffect = talent.getIntValue("RDM");

        state.put("SPR", getInt(state.get("SPR")) + mnyEffect);
        state.put("MNY", getInt(state.get("MNY")) + chrEffect);
        state.put("CHR", getInt(state.get("CHR")) + strEffect);
        state.put("STR", getInt(state.get("STR")) + intEffect);
        state.put("INT", getInt(state.get("INT")) + rdmEffect);
        extraPoints += sprEffect;
    }

    // 分配额外属性点
    for (int i = 0; i < extraPoints; i++) {
        int prop = random.nextInt(4);
        if (prop == 0) state.put("CHR", getInt(state.get("CHR")) + 1);
        else if (prop == 1) state.put("INT", getInt(state.get("INT")) + 1);
        else if (prop == 2) state.put("STR", getInt(state.get("STR")) + 1);
        else state.put("MNY", getInt(state.get("MNY")) + 1);
    }

    // 属性下限保护
    state.put("CHR", Math.max(0, getInt(state.get("CHR"))));
    state.put("INT", Math.max(0, getInt(state.get("INT"))));
    state.put("STR", Math.max(0, getInt(state.get("STR"))));
    state.put("MNY", Math.max(0, getInt(state.get("MNY"))));
    state.put("SPR", Math.max(0, getInt(state.get("SPR"))));
}

void processBirthEvents(Map state) {
    int age = 0;
    JSONArray ageEvents = agesData.getJSONArray(String.valueOf(age));
    if (ageEvents == null) return;

    Set talentSet = (Set) state.get("talents");
    Set eventHistory = (Set) state.get("eventHistory");

    boolean forceMale = talentSet.contains("1003");
    boolean forceFemale = talentSet.contains("1004");

    StringBuilder birthLog = new StringBuilder();

    for (int i = 0; i < ageEvents.size(); i++) {
        JSONObject ageEvent = ageEvents.getJSONObject(i);
        String eid = ageEvent.getString("id");
        double prob = ageEvent.getDoubleValue("prob");

        JSONObject eventDef = eventsData.getJSONObject(eid);
        if (eventDef == null) continue;

        if ("1".equals(eventDef.getString("noRandom"))) continue;

        if (forceMale && "10002".equals(eid)) continue;
        if (forceFemale && "10001".equals(eid)) continue;

        if (random.nextDouble() > prob) continue;

        if (!checkInclude(eventDef, state)) continue;
        if (checkExclude(eventDef, state)) continue;

        JSONObject resolved = resolveBranches(eventDef, state);
        if (resolved == null) continue;

        String appliedText = applyEventSilent(state, resolved);

        if (appliedText != null && appliedText.length() > 0) {
            if (birthLog.length() > 0) birthLog.append("\n");
            birthLog.append(appliedText);
        }

        if ("10001".equals(resolved.getString("id"))) {
            state.put("gender", "男");
        } else if ("10002".equals(resolved.getString("id"))) {
            state.put("gender", "女");
        }
    }

    state.put("lastEventText", birthLog.toString());
}

// ===== 核心：混合事件系统 =====

void nextYear(String talker, Map state) {
    int age = getInt(state.get("age"));
    int step = getInt(state.get("step"));
    step++;
    state.put("step", step);

    // 生命值随年龄衰减（60岁后每年-1）
    int lif = getInt(state.get("LIF"));
    if (age > 60) {
        lif -= 1;
        state.put("LIF", lif);
    }

    // 检查死亡（寿命耗尽或500岁大限）
    if (lif <= 0 || age >= 500) {
        if (age >= 500 && isCultivating(state)) {
            state.put("lastEventText", "五百岁，终极天劫降临。你渡劫失败，身死道消。");
        } else {
            state.put("lastEventText", "你在睡梦中安然离世。");
        }
        state.put("lastChoice", false);
        String deathMsg = formatDeathScreen(state);
        sendText(talker, deathMsg);
        endGame(talker, state);
        return;
    }

    // 自动触发危险事件 + 背景事件
    List autoTexts = autoFireEvents(state);
    if (autoTexts == null) {
        // null 表示玩家在自动事件中死亡
        String deathMsg = formatDeathScreen(state);
        sendText(talker, deathMsg);
        endGame(talker, state);
        return;
    }

    // 为本年选择人生选项
    List choiceIds = selectYearChoices(state, autoTexts);

    // 构建显示
    String lastEvent = (String) state.get("lastEventText");
    if (lastEvent == null) lastEvent = "";

    String display = formatGameDisplay(state, lastEvent, autoTexts, choiceIds);
    sendText(talker, display);

    state.put("waitingForChoice", true);
    state.put("currentOptions", choiceIds);
}

/**
 * 自动触发事件（玩家无法躲避的命运）：
 * 1. 危险事件（分支指向死亡10000，且当前条件匹配）最多2个 - 属性差会死
 * 2. 背景事件（随机安全事件）最多1个 - 生活气息
 * 返回触发的文本列表；若玩家死亡返回null。
 */
List autoFireEvents(Map state) {
    int age = getInt(state.get("age"));
    JSONArray ageEvents = agesData.getJSONArray(String.valueOf(age));
    if (ageEvents == null || ageEvents.isEmpty()) return new ArrayList();

    List dangerous = new ArrayList();  // 分支指向死亡的事件
    List safe = new ArrayList();       // 安全事件

    // 第一步：收集该年龄所有通过条件的事件
    for (int i = 0; i < ageEvents.size(); i++) {
        JSONObject ageEvent = ageEvents.getJSONObject(i);
        String eid = ageEvent.getString("id");
        double prob = ageEvent.getDoubleValue("prob");

        JSONObject eventDef = eventsData.getJSONObject(eid);
        if (eventDef == null) continue;

        if ("1".equals(eventDef.getString("noRandom"))) continue;

        // 出生地里程碑（10009农村/10010城市/10011美国）只能由玩家选择，禁止自动触发
        if ("10009".equals(eid) || "10010".equals(eid) || "10011".equals(eid)) continue;

        if (!checkInclude(eventDef, state)) continue;
        if (checkExclude(eventDef, state)) continue;

        // 深度解析分支
        JSONObject resolved = resolveBranches(eventDef, state);
        if (resolved == null) continue;

        JSONObject item = new JSONObject();
        item.put("id", eid);
        item.put("prob", prob);

        if ("10000".equals(resolved.getString("id"))) {
            // 危险事件：分支最终指向死亡
            item.put("danger", true);
            dangerous.add(item);
        } else {
            item.put("danger", false);
            safe.add(item);
        }
    }

    List texts = new ArrayList();
    Set firedThisYear = new HashSet();

    // 第二步：自动触发危险事件（最多2个，按概率，逐个从池中取出避免重复）
    List dangerPool = new ArrayList(dangerous);
    while (texts.size() < 2 && !dangerPool.isEmpty()) {
        int idx = random.nextInt(dangerPool.size());
        JSONObject item = (JSONObject) dangerPool.get(idx);
        dangerPool.remove(idx);

        String eid = item.getString("id");
        if (firedThisYear.contains(eid)) continue;

        double prob = item.getDoubleValue("prob");
        if (random.nextDouble() > Math.min(prob, 1.0)) continue;

        JSONObject eventDef = eventsData.getJSONObject(eid);
        JSONObject resolved = resolveBranches(eventDef, state);
        if (resolved == null) continue;

        String causeText = getEventPreview(eventDef);
        String appliedText = applyEventSilent(state, resolved);
        firedThisYear.add(eid);

        // 危险事件杀了人：记录死因并返回null
        if ("10000".equals(resolved.getString("id"))) {
            state.put("lastEventText", causeText + " → " + appliedText);
            state.put("lastChoice", false);
            return null;
        }

        texts.add(appliedText);
    }

    // 第三步：自动触发1个背景事件（随机安全事件，按概率）
    List safePool = new ArrayList(safe);
    while (texts.size() < 3 && !safePool.isEmpty()) {
        int idx = random.nextInt(safePool.size());
        JSONObject item = (JSONObject) safePool.get(idx);
        safePool.remove(idx);

        String eid = item.getString("id");
        if (firedThisYear.contains(eid)) continue;

        double prob = item.getDoubleValue("prob");
        if (random.nextDouble() > Math.min(prob, 1.0)) continue;

        JSONObject eventDef = eventsData.getJSONObject(eid);
        JSONObject resolved = resolveBranches(eventDef, state);
        if (resolved == null) continue;

        String appliedText = applyEventSilent(state, resolved);
        firedThisYear.add(eid);
        texts.add(appliedText);
        break;
    }

    state.put("firedThisYear", firedThisYear);
    return texts;
}

/**
 * 为本年选择最多3个人生选项（安全事件）。
 * 优先选择链式事件（include含EVT?[），与之前的选择关联。
 * 返回事件ID列表。
 */
List selectYearChoices(Map state, List autoTexts) {
    int age = getInt(state.get("age"));
    JSONArray ageEvents = agesData.getJSONArray(String.valueOf(age));
    if (ageEvents == null || ageEvents.isEmpty()) return new ArrayList();

    Set firedThisYear = (Set) state.get("firedThisYear");
    if (firedThisYear == null) firedThisYear = new HashSet();

    List chainEvents = new ArrayList();
    List generalEvents = new ArrayList();

    // 收集所有通过条件的安全事件
    for (int i = 0; i < ageEvents.size(); i++) {
        JSONObject ageEvent = ageEvents.getJSONObject(i);
        String eid = ageEvent.getString("id");
        double prob = ageEvent.getDoubleValue("prob");

        JSONObject eventDef = eventsData.getJSONObject(eid);
        if (eventDef == null) continue;

        if ("1".equals(eventDef.getString("noRandom"))) continue;
        if (firedThisYear.contains(eid)) continue;

        if (!checkInclude(eventDef, state)) continue;
        if (checkExclude(eventDef, state)) continue;

        // 深度解析分支
        JSONObject resolved = resolveBranches(eventDef, state);
        if (resolved == null) continue;

        // 危险事件不作为选项（只能自动触发）
        if ("10000".equals(resolved.getString("id"))) continue;

        String include = eventDef.getString("include");
        boolean isChain = (include != null && include.indexOf("EVT?[") >= 0);

        JSONObject item = new JSONObject();
        item.put("id", eid);
        item.put("prob", prob);
        item.put("weight", isChain ? prob * 5.0 : prob);

        if (isChain) {
            chainEvents.add(item);
        } else {
            generalEvents.add(item);
        }
    }

    // 选择最多3个：链式事件优先，一般事件按概率补充
    List selected = new ArrayList();
    Set selectedIds = new HashSet();

    while (selected.size() < 3 && !chainEvents.isEmpty()) {
        int idx = weightedRandomSelect(chainEvents);
        JSONObject item = (JSONObject) chainEvents.get(idx);
        String eid = item.getString("id");
        chainEvents.remove(idx);
        if (selectedIds.contains(eid)) continue;
        selectedIds.add(eid);
        selected.add(eid);
    }

    while (selected.size() < 3 && !generalEvents.isEmpty()) {
        int idx = weightedRandomSelect(generalEvents);
        JSONObject item = (JSONObject) generalEvents.get(idx);
        String eid = item.getString("id");
        generalEvents.remove(idx);
        if (selectedIds.contains(eid)) continue;
        selectedIds.add(eid);
        selected.add(eid);
    }

    return selected;
}

int weightedRandomSelect(List pool) {
    if (pool.isEmpty()) return 0;
    if (pool.size() == 1) return 0;

    double totalWeight = 0;
    for (int i = 0; i < pool.size(); i++) {
        JSONObject item = (JSONObject) pool.get(i);
        totalWeight += item.getDoubleValue("weight");
    }

    if (totalWeight <= 0) {
        return random.nextInt(pool.size());
    }

    double r = random.nextDouble() * totalWeight;
    double cumWeight = 0;
    for (int i = 0; i < pool.size(); i++) {
        JSONObject item = (JSONObject) pool.get(i);
        cumWeight += item.getDoubleValue("weight");
        if (r <= cumWeight) {
            return i;
        }
    }

    return pool.size() - 1;
}

/**
 * 深度解析事件分支（最多5层），返回最终事件。
 * 处理 10012 -> 10013 -> 10000 这类分支链。
 */
JSONObject resolveBranches(JSONObject event, Map state) {
    return resolveBranchesDepth(event, state, 0);
}

JSONObject resolveBranchesDepth(JSONObject event, Map state, int depth) {
    if (event == null || depth > 5) return event;

    JSONArray branches = event.getJSONArray("branch");
    if (branches == null || branches.isEmpty()) return event;

    for (int i = 0; i < branches.size(); i++) {
        String branch = branches.getString(i);
        if (branch == null || branch.length() == 0) continue;

        String condition;
        String targetId = null;

        int colonIdx = branch.indexOf(':');
        if (colonIdx > 0) {
            condition = branch.substring(0, colonIdx);
            targetId = branch.substring(colonIdx + 1);
        } else {
            condition = branch;
        }

        if (evaluateCondition(condition, state)) {
            if (targetId != null && targetId.length() > 0) {
                JSONObject target = eventsData.getJSONObject(targetId);
                if (target != null) {
                    return resolveBranchesDepth(target, state, depth + 1);
                }
            } else {
                return null;
            }
        }
    }

    return event;
}

/**
 * 应用事件效果，返回事件展示文本（不设置lastEventText）。
 */
String applyEventSilent(Map state, JSONObject event) {
    String eid = event.getString("id");

    Set eventHistory = (Set) state.get("eventHistory");
    eventHistory.add(eid);

    int chr = event.getIntValue("CHR");
    int int_ = event.getIntValue("INT");
    int str = event.getIntValue("STR");
    int mny = event.getIntValue("MNY");
    int spr = event.getIntValue("SPR");
    int lif = event.getIntValue("LIF");

    state.put("CHR", Math.max(0, getInt(state.get("CHR")) + chr));
    state.put("INT", Math.max(0, getInt(state.get("INT")) + int_));
    state.put("STR", Math.max(0, getInt(state.get("STR")) + str));
    state.put("MNY", Math.max(0, getInt(state.get("MNY")) + mny));
    state.put("SPR", Math.max(0, getInt(state.get("SPR")) + spr));
    state.put("LIF", getInt(state.get("LIF")) + lif);

    // 修仙入道：寿元提升到400（配合60岁后每年-1，可活到500）
    if ("40001".equals(eid) || "40061".equals(eid)) {
        state.put("LIF", 400);
    }

    return getEventText(event);
}

/**
 * 是否已入道修仙。
 */
boolean isCultivating(Map state) {
    Set eventHistory = (Set) state.get("eventHistory");
    return eventHistory.contains("40001") || eventHistory.contains("40061");
}

boolean checkDeath(Map state) {
    int lif = getInt(state.get("LIF"));
    if (lif <= 0) return true;

    Set eventHistory = (Set) state.get("eventHistory");
    if (eventHistory.contains("10000")) return true;

    return false;
}

void endGame(String talker, Map state) {
    String playerName = (String) state.get("playerName");
    int age = getInt(state.get("age"));

    JSONArray board = (JSONArray) leaderboards.get(talker);
    if (board == null) {
        board = new JSONArray();
        leaderboards.put(talker, board);
    }

    JSONObject entry = new JSONObject();
    entry.put("name", playerName);
    entry.put("age", age);
    entry.put("character", state.get("character"));
    entry.put("CHR", state.get("CHR"));
    entry.put("INT", state.get("INT"));
    entry.put("STR", state.get("STR"));
    entry.put("MNY", state.get("MNY"));
    entry.put("SPR", state.get("SPR"));
    entry.put("time", System.currentTimeMillis());
    board.add(entry);

    // 按年龄降序排序
    for (int i = board.size() - 1; i > 0; i--) {
        JSONObject a = board.getJSONObject(i);
        JSONObject b = board.getJSONObject(i - 1);
        if (a.getIntValue("age") > b.getIntValue("age")) {
            board.set(i, b);
            board.set(i - 1, a);
        }
    }

    while (board.size() > 100) {
        board.remove(board.size() - 1);
    }

    saveLeaderboard(talker);

    String senderWxid = (String) state.get("senderWxid");
    removeActiveGame(talker, senderWxid);
}

// ===== 条件评估 =====

boolean checkInclude(JSONObject event, Map state) {
    String include = event.getString("include");
    if (include == null || include.length() == 0) return true;
    return evaluateCondition(include, state);
}

boolean checkExclude(JSONObject event, Map state) {
    String exclude = event.getString("exclude");
    if (exclude == null || exclude.length() == 0) return false;
    return evaluateCondition(exclude, state);
}

boolean evaluateCondition(String condition, Map state) {
    if (condition == null || condition.length() == 0) return true;

    condition = condition.trim();

    // 处理括号
    if (condition.startsWith("(") && condition.endsWith(")")) {
        int depth = 0;
        boolean outerOnly = true;
        for (int i = 0; i < condition.length(); i++) {
            char c = condition.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            if (depth == 0 && i < condition.length() - 1) {
                outerOnly = false;
                break;
            }
        }
        if (outerOnly) {
            return evaluateCondition(condition.substring(1, condition.length() - 1), state);
        }
    }

    // 处理 OR (|)
    List orParts = splitByOperator(condition, '|');
    if (orParts.size() > 1) {
        for (int i = 0; i < orParts.size(); i++) {
            if (evaluateCondition((String) orParts.get(i), state)) return true;
        }
        return false;
    }

    // 处理 AND (&)
    List andParts = splitByOperator(condition, '&');
    if (andParts.size() > 1) {
        for (int i = 0; i < andParts.size(); i++) {
            if (!evaluateCondition((String) andParts.get(i), state)) return false;
        }
        return true;
    }

    // 原子条件
    return evaluateAtom(condition.trim(), state);
}

List splitByOperator(String expr, char op) {
    List parts = new ArrayList();
    int depth = 0;
    int start = 0;

    for (int i = 0; i < expr.length(); i++) {
        char c = expr.charAt(i);
        if (c == '(') depth++;
        else if (c == ')') depth--;
        else if (c == op && depth == 0) {
            parts.add(expr.substring(start, i));
            start = i + 1;
        }
    }
    parts.add(expr.substring(start));

    return parts;
}

boolean evaluateAtom(String atom, Map state) {
    if (atom == null || atom.length() == 0) return true;

    atom = atom.trim();
    if (atom.startsWith("(") && atom.endsWith(")")) {
        return evaluateCondition(atom.substring(1, atom.length() - 1), state);
    }

    if (atom.startsWith("AGE?[")) {
        String val = extractBracketValue(atom);
        int targetAge = parseIntSafe(val);
        return getInt(state.get("age")) == targetAge;
    }
    if (atom.startsWith("AGE![")) {
        String val = extractBracketValue(atom);
        int targetAge = parseIntSafe(val);
        return getInt(state.get("age")) != targetAge;
    }

    if (atom.startsWith("TLT?[")) {
        String val = extractBracketValue(atom);
        Set talentSet = (Set) state.get("talents");
        String[] ids = val.split(",");
        for (int i = 0; i < ids.length; i++) {
            if (talentSet.contains(ids[i].trim())) return true;
        }
        return false;
    }

    if (atom.startsWith("TLT![")) {
        String val = extractBracketValue(atom);
        Set talentSet = (Set) state.get("talents");
        String[] ids = val.split(",");
        for (int i = 0; i < ids.length; i++) {
            if (talentSet.contains(ids[i].trim())) return false;
        }
        return true;
    }

    if (atom.startsWith("EVT?[")) {
        String val = extractBracketValue(atom);
        Set eventHistory = (Set) state.get("eventHistory");
        String[] ids = val.split(",");
        for (int i = 0; i < ids.length; i++) {
            if (eventHistory.contains(ids[i].trim())) return true;
        }
        return false;
    }

    if (atom.startsWith("EVT![")) {
        String val = extractBracketValue(atom);
        Set eventHistory = (Set) state.get("eventHistory");
        String[] ids = val.split(",");
        for (int i = 0; i < ids.length; i++) {
            if (eventHistory.contains(ids[i].trim())) return false;
        }
        return true;
    }

    // 属性比较: CHR>6, INT<3, STR>5, MNY<8, SPR>2, LIF>0
    String[] props = {"CHR", "INT", "STR", "MNY", "SPR", "LIF"};
    for (int pi = 0; pi < props.length; pi++) {
        String prop = props[pi];
        if (atom.startsWith(prop + ">")) {
            int val = parseIntSafe(atom.substring(prop.length() + 1));
            return getInt(state.get(prop)) > val;
        }
        if (atom.startsWith(prop + "<")) {
            int val = parseIntSafe(atom.substring(prop.length() + 1));
            return getInt(state.get(prop)) < val;
        }
        if (atom.startsWith(prop + "=")) {
            int val = parseIntSafe(atom.substring(prop.length() + 1));
            return getInt(state.get(prop)) == val;
        }
    }

    return false;
}

String extractBracketValue(String s) {
    int start = s.indexOf('[');
    int end = s.indexOf(']');
    if (start >= 0 && end > start) {
        return s.substring(start + 1, end);
    }
    return "";
}

int parseIntSafe(String s) {
    if (s == null) return 0;
    s = s.trim();
    try {
        return Integer.parseInt(s);
    } catch (Exception e) {
        try {
            return (int) Double.parseDouble(s);
        } catch (Exception e2) {
            return 0;
        }
    }
}

// ===== 数据管理 =====

void loadData() {
    try {
        String dataDir = new File(pluginDir, "data").getAbsolutePath();
        File dataDirFile = new File(dataDir);
        if (!dataDirFile.exists()) {
            log("数据目录不存在: " + dataDir);
            return;
        }

        String eventsStr = readFile(dataDir + "/events.json");
        String agesStr = readFile(dataDir + "/ages.json");
        String talentsStr = readFile(dataDir + "/talents.json");
        String charactersStr = readFile(dataDir + "/characters.json");

        if (eventsStr.length() == 0 || agesStr.length() == 0) {
            log("数据文件为空");
            return;
        }

        eventsData = JSON.parseObject(eventsStr);
        agesData = JSON.parseObject(agesStr);
        talentsData = JSON.parseObject(talentsStr);
        charactersData = JSON.parseArray(charactersStr);

        dataLoaded = true;
    } catch (Exception e) {
        log("加载数据失败: " + e.getMessage());
        dataLoaded = false;
    }
}

String readFile(String path) {
    try {
        File file = new File(path);
        if (!file.exists()) return "";
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        byte[] buffer = new byte[(int) file.length()];
        fis.read(buffer);
        fis.close();
        return new String(buffer, "UTF-8");
    } catch (Exception e) {
        log("读取文件失败: " + path + " " + e.getMessage());
        return "";
    }
}

void loadGameEnabled() {
    try {
        String saved = getString("gameEnabledMap", "");
        if (saved.length() > 0) {
            JSONObject json = JSON.parseObject(saved);
            for (String key : json.keySet()) {
                gameEnabled.put(key, json.getBoolean(key));
            }
        }
    } catch (Exception e) {
        log("加载游戏开关状态失败: " + e.getMessage());
    }
}

void saveGameEnabled() {
    try {
        JSONObject json = new JSONObject();
        for (Object key : gameEnabled.keySet()) {
            json.put((String) key, gameEnabled.get(key));
        }
        putString("gameEnabledMap", json.toJSONString());
    } catch (Exception e) {
        log("保存游戏开关状态失败: " + e.getMessage());
    }
}

void loadLeaderboards() {
    try {
        String dataDir = new File(pluginDir, "data").getAbsolutePath();
        File file = new File(dataDir + "/leaderboards.json");
        if (!file.exists()) return;
        String content = readFile(dataDir + "/leaderboards.json");
        if (content.length() > 0) {
            JSONObject json = JSON.parseObject(content);
            for (String key : json.keySet()) {
                leaderboards.put(key, json.getJSONArray(key));
            }
        }
    } catch (Exception e) {
        log("加载排行榜失败: " + e.getMessage());
    }
}

void saveLeaderboard(String talker) {
    try {
        String dataDir = new File(pluginDir, "data").getAbsolutePath();
        File dataDirFile = new File(dataDir);
        if (!dataDirFile.exists()) {
            dataDirFile.mkdirs();
        }

        JSONObject json = new JSONObject();
        for (Object key : leaderboards.keySet()) {
            json.put((String) key, leaderboards.get(key));
        }

        java.io.FileOutputStream fos = new java.io.FileOutputStream(dataDir + "/leaderboards.json");
        fos.write(json.toJSONString().getBytes("UTF-8"));
        fos.close();
    } catch (Exception e) {
        log("保存排行榜失败: " + e.getMessage());
    }
}

void saveAllLeaderboards() {
    try {
        String dataDir = new File(pluginDir, "data").getAbsolutePath();
        File dataDirFile = new File(dataDir);
        if (!dataDirFile.exists()) {
            dataDirFile.mkdirs();
        }

        JSONObject json = new JSONObject();
        for (Object key : leaderboards.keySet()) {
            json.put((String) key, leaderboards.get(key));
        }

        java.io.FileOutputStream fos = new java.io.FileOutputStream(dataDir + "/leaderboards.json");
        fos.write(json.toJSONString().getBytes("UTF-8"));
        fos.close();
    } catch (Exception e) {
        log("保存所有排行榜失败: " + e.getMessage());
    }
}

// ===== 显示 =====

/**
 * 天赋选择界面（紧凑版）。
 */
String formatTalentSelect(Map state, List candidates) {
    StringBuilder sb = new StringBuilder();
    String playerName = (String) state.get("playerName");
    String character = (String) state.get("character");

    sb.append("人生重开模拟器\n");
    sb.append("━━━━━━━━━━━━━━━━\n");
    sb.append(playerName).append(" | ").append(character).append("\n");
    sb.append("颜值").append(getInt(state.get("CHR")))
      .append(" 智力").append(getInt(state.get("INT")))
      .append(" 体质").append(getInt(state.get("STR")))
      .append(" 家境").append(getInt(state.get("MNY")))
      .append(" 快乐").append(getInt(state.get("SPR"))).append("\n");
    sb.append("━━━━━━━━━━━━━━━━\n");
    sb.append("【选天赋】发送3个数字如 135\n");

    for (int i = 0; i < candidates.size(); i++) {
        String tid = (String) candidates.get(i);
        JSONObject talent = talentsData.getJSONObject(tid);
        if (talent == null) continue;

        sb.append(i + 1).append(". ").append(talent.getString("name"));

        String effects = getTalentEffectText(talent);
        if (effects.length() > 0) {
            sb.append(" ").append(effects);
        }
        sb.append("\n");
    }

    sb.append("━━━━━━━━━━━━━━━━\n");
    sb.append("发送 135 选择天赋");
    return sb.toString();
}

/**
 * 天赋效果文本（如 "体质+3 智力+1"）。
 */
String getTalentEffectText(JSONObject talent) {
    List parts = new ArrayList();

    int spr = talent.getIntValue("SPR");
    int mny = talent.getIntValue("MNY");
    int chr = talent.getIntValue("CHR");
    int str = talent.getIntValue("STR");
    int int_ = talent.getIntValue("INT");
    int rdm = talent.getIntValue("RDM");

    if (spr != 0) parts.add("属性" + (spr > 0 ? "+" : "") + spr);
    if (mny != 0) parts.add("快乐" + (mny > 0 ? "+" : "") + mny);
    if (chr != 0) parts.add("家境" + (chr > 0 ? "+" : "") + chr);
    if (str != 0) parts.add("颜值" + (str > 0 ? "+" : "") + str);
    if (int_ != 0) parts.add("体质" + (int_ > 0 ? "+" : "") + int_);
    if (rdm != 0) parts.add("智力" + (rdm > 0 ? "+" : "") + rdm);

    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < parts.size(); i++) {
        if (i > 0) sb.append(" ");
        sb.append(parts.get(i));
    }
    return sb.toString();
}

/**
 * 主游戏界面（紧凑版）：一行属性 + 事件摘要 + 选项。
 */
String formatGameDisplay(Map state, String lastEventText, List autoTexts, List choiceIds) {
    StringBuilder sb = new StringBuilder();
    String playerName = (String) state.get("playerName");
    int age = getInt(state.get("age"));
    String character = (String) state.get("character");
    String gender = (String) state.get("gender");

    sb.append("人生重开模拟器\n");
    sb.append("━━━━━━━━━━━━━━━━\n");
    sb.append(playerName).append("|").append(gender).append("|").append(age)
      .append("岁|").append(character).append("\n");
    sb.append("颜值").append(getInt(state.get("CHR")))
      .append(" 智力").append(getInt(state.get("INT")))
      .append(" 体质").append(getInt(state.get("STR")))
      .append(" 家境").append(getInt(state.get("MNY")))
      .append(" 快乐").append(getInt(state.get("SPR")))
      .append(" 生命").append(getInt(state.get("LIF"))).append("\n");
    sb.append("━━━━━━━━━━━━━━━━\n");

    // 上一年结果（玩家选择 or 出生信息）
    if (lastEventText != null && lastEventText.length() > 0) {
        if (Boolean.TRUE.equals(state.get("lastChoice"))) {
            sb.append("【你的选择】").append(lastEventText).append("\n");
        } else {
            sb.append("【上一年】").append(lastEventText).append("\n");
        }
    }

    // 本年自动事件（命运，非玩家选择）
    if (autoTexts != null && !autoTexts.isEmpty()) {
        sb.append("【命运】");
        for (int i = 0; i < autoTexts.size(); i++) {
            if (i > 0) sb.append("\n      ");
            sb.append("· ").append((String) autoTexts.get(i));
        }
        sb.append("\n");
    }

    // 本年选项
    sb.append("━━━━━━━━━━━━━━━━\n");
    sb.append("【人生选择】\n");

    if (choiceIds == null || choiceIds.isEmpty()) {
        sb.append("这一年风平浪静\n");
        sb.append("发送 1 继续下一年");
    } else {
        for (int i = 0; i < choiceIds.size(); i++) {
            String eid = (String) choiceIds.get(i);
            JSONObject evt = eventsData.getJSONObject(eid);
            if (evt == null) continue;
            String gradeIcon = getGradeIcon(evt.getString("grade"));
            sb.append(i + 1).append(". ").append(getEventPreview(evt));
            if (gradeIcon.length() > 0) {
                sb.append(" ").append(gradeIcon);
            }
            sb.append("\n");
        }
        sb.append("发送 1-").append(choiceIds.size()).append(" 选择");
    }

    return sb.toString();
}

String formatDeathScreen(Map state) {
    StringBuilder sb = new StringBuilder();
    String playerName = (String) state.get("playerName");
    int age = getInt(state.get("age"));
    String character = (String) state.get("character");
    String lastEvent = (String) state.get("lastEventText");

    sb.append("人生重开模拟器\n");
    sb.append("━━━━━━━━━━━━━━━━\n");
    sb.append("人生结束 | 享年").append(age).append("岁\n");
    sb.append(playerName).append(" | ").append(character).append("\n");
    sb.append("颜值").append(getInt(state.get("CHR")))
      .append(" 智力").append(getInt(state.get("INT")))
      .append(" 体质").append(getInt(state.get("STR")))
      .append(" 家境").append(getInt(state.get("MNY")))
      .append(" 快乐").append(getInt(state.get("SPR")))
      .append(" 生命").append(getInt(state.get("LIF"))).append("\n");

    if (lastEvent != null && lastEvent.length() > 0) {
        sb.append("━━━━━━━━━━━━━━━━\n");
        sb.append("最后时刻: ").append(lastEvent).append("\n");
    }

    sb.append("━━━━━━━━━━━━━━━━\n");
    sb.append("已记入人生排行榜\n");
    sb.append("发送 人生排行榜 查看排名\n");
    sb.append("发送 开始模拟 重新开始");

    return sb.toString();
}

/**
 * 事件完整文本（含属性变化），用于显示结果。
 */
String getEventText(JSONObject event) {
    StringBuilder sb = new StringBuilder();
    String eventText = event.getString("event");
    if (eventText != null) {
        sb.append(eventText);
    }

    String postEvent = event.getString("postEvent");
    if (postEvent != null && postEvent.length() > 0) {
        sb.append(" ").append(postEvent);
    }

    List changes = new ArrayList();
    int chr = event.getIntValue("CHR");
    int int_ = event.getIntValue("INT");
    int str = event.getIntValue("STR");
    int mny = event.getIntValue("MNY");
    int spr = event.getIntValue("SPR");
    int lif = event.getIntValue("LIF");

    if (chr != 0) changes.add("颜值" + (chr > 0 ? "+" : "") + chr);
    if (int_ != 0) changes.add("智力" + (int_ > 0 ? "+" : "") + int_);
    if (str != 0) changes.add("体质" + (str > 0 ? "+" : "") + str);
    if (mny != 0) changes.add("家境" + (mny > 0 ? "+" : "") + mny);
    if (spr != 0) changes.add("快乐" + (spr > 0 ? "+" : "") + spr);
    if (lif != 0) changes.add("生命" + (lif > 0 ? "+" : "") + lif);

    if (!changes.isEmpty()) {
        sb.append(" (");
        for (int i = 0; i < changes.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(changes.get(i));
        }
        sb.append(")");
    }

    return sb.toString();
}

/**
 * 事件预览文本（不含属性变化），用于选项展示（悬念）。
 */
String getEventPreview(JSONObject event) {
    StringBuilder sb = new StringBuilder();
    String eventText = event.getString("event");
    if (eventText != null) {
        sb.append(eventText);
    }

    String postEvent = event.getString("postEvent");
    if (postEvent != null && postEvent.length() > 0) {
        sb.append(" ").append(postEvent);
    }

    return sb.toString();
}

/**
 * 事件稀有度图标。
 */
String getGradeIcon(String grade) {
    if (grade == null || grade.length() == 0) return "";
    if ("1".equals(grade)) return "\u2605";
    if ("2".equals(grade)) return "\u2605\u2605";
    if ("3".equals(grade)) return "\u2605\u2605\u2605";
    return "";
}

// ===== 工具方法 =====

String getPlayerName(String senderWxid, String talker) {
    String name = "";
    try {
        if (talker != null && talker.endsWith("@chatroom")) {
            name = getFriendDisplayName(senderWxid, talker);
            if (name == null || name.length() == 0) {
                name = getFriendName(senderWxid);
            }
        } else {
            name = getFriendName(senderWxid);
        }
    } catch (Exception e) {
        // 忽略
    }

    if (name == null || name.length() == 0) {
        if (senderWxid.equals(getLoginWxid())) {
            name = getLoginAlias();
            if (name == null || name.length() == 0) {
                name = "我";
            }
        } else {
            name = senderWxid;
        }
    }

    return name;
}

int getInt(Object obj) {
    if (obj == null) return 0;
    if (obj instanceof Integer) return (Integer) obj;
    if (obj instanceof Number) return ((Number) obj).intValue();
    try {
        return Integer.parseInt(obj.toString());
    } catch (Exception e) {
        return 0;
    }
}

boolean isGameEnabled(String talker) {
    // 设置页勾选的生效群视为已开启，无需再发送「人生开关」
    if (isAllowGroup(talker) && !loadConfigValue(ALLOW_GROUP_KEY).isEmpty()) return true;
    Boolean enabled = (Boolean) gameEnabled.get(talker);
    return enabled != null && enabled;
}

Map getActiveGame(String talker, String senderWxid) {
    Map chatGames = (Map) activeGames.get(talker);
    if (chatGames == null) return null;
    return (Map) chatGames.get(senderWxid);
}

void setActiveGame(String talker, String senderWxid, Map state) {
    Map chatGames = (Map) activeGames.get(talker);
    if (chatGames == null) {
        chatGames = new HashMap();
        activeGames.put(talker, chatGames);
    }
    chatGames.put(senderWxid, state);
}

void removeActiveGame(String talker, String senderWxid) {
    Map chatGames = (Map) activeGames.get(talker);
    if (chatGames != null) {
        chatGames.remove(senderWxid);
    }
}
