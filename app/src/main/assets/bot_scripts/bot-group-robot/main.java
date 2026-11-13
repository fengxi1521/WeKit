import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;

// ============================================================
// 群娱乐机器人 v38.6 (移除自动加群 & 入群验证)
// ============================================================
// 修改：
//   - 删除自动添加群功能（仅齿轮手动设置生效群）
//   - 删除入群验证（onMemberChange 及相关全部移除）
//   - 保留所有其他功能（积分/打劫/婚恋/拍卖/点歌等）
//   - 权限控制仅 ROOT_WXID 可操作群管指令
// ============================================================

// ========== 全局状态 ==========
boolean isLoaded = true;
boolean openSimpleLog = false;
final String ALLOW_GROUP_KEY = "allow_group_list";
final String ROOT_WXID_KEY = "root_wxid";

ConcurrentHashMap<String, ScheduledFuture<?>> taskMap = new ConcurrentHashMap<String, ScheduledFuture<?>>();
ScheduledExecutorService timerPool = Executors.newScheduledThreadPool(3);

// ========== 工具函数 ==========

int randNum(int min, int max) {

return min + (int)(Math.random() * (max - min + 1));

}

String randomTxt(String[] arr) {

if (arr == null || arr.length == 0) return "暂无内容";

return arr[(int)(Math.random() * arr.length)];

}

// ========== 权限检查 ==========

String getRootWxid() {
    String configured = getString(ROOT_WXID_KEY, "");
    if (configured != null && !configured.trim().isEmpty()) return configured.trim();
    String login = getLoginWxid();
    if (login != null && !login.isEmpty()) return login;
    return myWxId;
}

boolean isRoot(String uid) {
    String root = getRootWxid();
    return root != null && !root.isEmpty() && root.equals(uid);
}

// ========== 生效群管理（不再自动添加）==========
String loadAllowGroupList() {
    try {
        File configFile = new File(pluginDir, "config.prop");
        if (configFile.exists()) {
            Properties props = new Properties();
            props.load(new FileInputStream(configFile));
            String list = props.getProperty(ALLOW_GROUP_KEY, "").trim();
            if (!list.isEmpty()) {
                putString(ALLOW_GROUP_KEY, list);
                return list;
            }
        }
    } catch (Throwable ignored) {}
    return getString(ALLOW_GROUP_KEY, "");
}
boolean isDisabledGroup(String roomWxid) {
    String list;
    try {
        File configFile = new File(pluginDir, "config.prop");
        list = "";
        if (configFile.exists()) {
            Properties props = new Properties();
            props.load(new FileInputStream(configFile));
            list = props.getProperty("disabled_group_list", "").trim();
        }
    } catch (Throwable ignored) {
        list = "";
    }
    if (list.isEmpty()) return false;
    for (String g : list.split(",")) {
        if (g.trim().equals(roomWxid)) return true;
    }
    return false;
}
boolean isAllowGroup(String roomWxid) {
    if (isDisabledGroup(roomWxid)) return false;
    String list = loadAllowGroupList();
    // 内置版：未配置生效群时默认所有群生效，配置后只响应勾选的群
    if (list.isEmpty()) return true;
    for (String g : list.split(",")) {
        if (g.trim().equals(roomWxid)) return true;
    }
    return false;
}

// ========== 齿轮设置入口（手动多选群）==========
void openSettings() {
    showModuleInputDialog(
            "生效群配置",
            "输入群ID(xxx@chatroom)或群名检索，空输入加载全部群",
            "",
            "群名/群ID",
            new Consumer() {
                public void accept(Object inputObj) {
                    String search = String.valueOf(inputObj).trim();
                    List<Map> allGroups = (List<Map>) getGroupListInfo();
                    List<String> showText = new ArrayList<>();
                    List<String> roomIds = new ArrayList<>();
                    for (Map g : allGroups) {
                        String rid = String.valueOf(g.get("roomId"));
                        String gname = String.valueOf(g.get("name"));
                        if (search.isEmpty() || gname.toLowerCase().contains(search.toLowerCase()) || rid.contains(search)) {
                            showText.add(gname + " | " + rid);
                            roomIds.add(rid);
                        }
                    }
                    if (showText.isEmpty()) {
                        toast("未匹配到对应群聊");
                        return;
                    }
                    Set<String> saved = new HashSet<>();
                    String listStr = getString(ALLOW_GROUP_KEY, "");
                    if (!listStr.isEmpty()) {
                        Collections.addAll(saved, listStr.split(","));
                    }
                    Set<Integer> selectIdx = new HashSet<>();
                    for (int i = 0; i < roomIds.size(); i++) {
                        if (saved.contains(roomIds.get(i))) selectIdx.add(i);
                    }
                    showModuleMultiChoiceDialog(
                            "勾选插件生效群",
                            "仅勾选的群机器人会响应指令",
                            showText,
                            selectIdx,
                            new Consumer() {
                                public void accept(Object selectObj) {
                                    Set<Integer> selects = (Set<Integer>) selectObj;
                                    Set<String> newWhite = new HashSet<>();
                                    for (int idx : selects) {
                                        newWhite.add(roomIds.get(idx));
                                    }
                                    putString(ALLOW_GROUP_KEY, String.join(",", newWhite));
                                    toast("配置保存成功，生效群数量：" + newWhite.size());
                                }
                            }
                    );
                }
            }
    );
}

// ========== 打劫执行函数 ==========
void runRob(String robber, String target, String room, String robNick) {
    int cost = 15;
    if (getPoint(robber, room) < cost) {
        sendText(room, robNick + " 打劫需要15积分路费，余额不足~");
        return;
    }
    long lastRob = getLastRobTs(robber, room);
    long sinceLast = System.currentTimeMillis() - lastRob;
    if (sinceLast < 540000L) {
        long remain = (540000L - sinceLast) / 1000L;
        sendText(room, robNick + " 打劫太频繁，还需" + (remain / 60) + "分" + (remain % 60) + "秒");
        return;
    }
    addPoint(robber, room, -cost);
    setLastRobTs(robber, room, System.currentTimeMillis());
    String victimUid = "";
    String victimName;
    int victimPoint;
    if (target == null || target.isEmpty()) {
        String userList = getString("userlist_" + room, "");
        String[] allUser = userList.split(",");
        List<String> validUser = new ArrayList<>();
        for (String u : allUser) {
            if (!u.isEmpty() && !u.equals(robber) && !isBlack(u, room)) {
                validUser.add(u);
            }
        }
        if (validUser.size() == 0) {
            addPoint(robber, room, cost);
            sendText(room, "群内无可打劫对象");
            return;
        }
        victimUid = validUser.get(randNum(0, validUser.size() - 1));
        victimName = getFriendDisplayName(victimUid, room);
        victimPoint = getPoint(victimUid, room);
    } else {
        victimUid = target;
        victimName = getFriendDisplayName(target, room);
        victimPoint = getPoint(target, room);
        if (victimPoint <= 0) {
            addPoint(robber, room, cost);
            sendText(room, victimName + " 身无分文，打劫空手而归");
            return;
        }
    }
    int steal = randNum(0, (int)(victimPoint * 0.65));
    if (steal == 0) {
        sendText(room, "[打劫失败] " + robNick + " 打劫" + victimName + "失败\n损失路费" + cost + "积分\n当前积分：" + getPoint(robber, room));
        incJailCount(robber, room);
        int jailCnt = getJailCount(robber, room);
        if (jailCnt >= 3) {
            setJail(robber, room, System.currentTimeMillis() + 1680000L);
            putInt("jail_count_" + room + "_" + robber, 0);
            sendText(room, "[监狱] " + robNick + " 连续打劫失败3次，关押28分钟");
        }
        return;
    }
    addPoint(victimUid, room, -steal);
    addPoint(robber, room, steal);
    int profit = steal - cost;
    sendText(room, "[打劫成功] " + robNick + " 打劫" + victimName + "成功\n净赚" + profit + "积分（抢夺" + steal + "）\n当前积分：" + getPoint(robber, room));
}

// ========== 积分/签到 ==========
int getPoint(String uid, String room) {
    return getInt("point_" + room + "_" + uid, 0);
}
void addPoint(String uid, String room, int num) {
    int val = getPoint(uid, room) + num;
    if (val < 0) val = 0;
    putInt("point_" + room + "_" + uid, val);
}
long getLastSignTs(String uid, String room) {
    return getLong("sign_ts_" + room + "_" + uid, 0);
}
void setLastSignTs(String uid, String room, long ts) {
    putLong("sign_ts_" + room + "_" + uid, ts);
}
int getSignDay(String uid, String room) {
    return getInt("sign_day_" + room + "_" + uid, 0);
}
void setSignDay(String uid, String room, int day) {
    putInt("sign_day_" + room + "_" + uid, day);
}
int getDailyEarned(String uid, String room) {
    long today = System.currentTimeMillis() / 86400000L;
    long recordedDay = getLong("daily_day_" + room + "_" + uid, 0);
    if (recordedDay != today) {
        putLong("daily_day_" + room + "_" + uid, today);
        putInt("daily_earned_" + room + "_" + uid, 0);
        return 0;
    }
    return getInt("daily_earned_" + room + "_" + uid, 0);
}
void addDailyEarned(String uid, String room, int num) {
    int cur = getDailyEarned(uid, room);
    putInt("daily_earned_" + room + "_" + uid, cur + num);
}
boolean checkDailyLimit(String uid, String room, int need, String roomChat) {
    int earned = getDailyEarned(uid, room);
    int LIMIT = 1200;
    if (earned + need > LIMIT) {
        sendText(roomChat, getFriendDisplayName(uid, room) + " 今日积分获取已达上限(" + LIMIT + ")，明天再来吧");
        return false;
    }
    return true;
}

// ========== 打劫/监狱 ==========
long getLastRobTs(String uid, String room) {
    return getLong("rob_ts_" + room + "_" + uid, 0);
}
void setLastRobTs(String uid, String room, long ts) {
    putLong("rob_ts_" + room + "_" + uid, ts);
}
boolean isInJail(String uid, String room) {
    long jailUntil = getLong("jail_until_" + room + "_" + uid, 0);
    if (System.currentTimeMillis() < jailUntil) return true;
    putLong("jail_until_" + room + "_" + uid, 0);
    return false;
}
void setJail(String uid, String room, long untilTs) {
    putLong("jail_until_" + room + "_" + uid, untilTs);
}
int getJailCount(String uid, String room) {
    return getInt("jail_count_" + room + "_" + uid, 0);
}
void incJailCount(String uid, String room) {
    putInt("jail_count_" + room + "_" + uid, getJailCount(uid, room) + 1);
}

// ========== 婚姻/伴侣 ==========
String getMate(String uid, String room) {
    return getString("mate_" + room + "_" + uid, "");
}
void setMate(String u1, String u2, String room) {
    putString("mate_" + room + "_" + u2, u1);
    putString("mate_" + room + "_" + u1, u2);
}
void delMate(String uid, String room) {
    String m = getMate(uid, room);
    if (!m.isEmpty()) {
        putString("mate_" + room + "_" + uid, "");
        putString("mate_" + room + "_" + m, "");
    }
}
int getGiftTotal(String uid, String room) {
    return getInt("gift_total_" + room + "_" + uid, 0);
}
void addGiftTotal(String uid, String room, int num) {
    putInt("gift_total_" + room + "_" + uid, getGiftTotal(uid, room) + num);
}

// ========== 宝宝系统 ==========
int getBabyExp(String uid, String room) {
    return getInt("baby_" + room + "_" + uid, 0);
}
void addBabyExp(String uid, String room, int num) {
    putInt("baby_" + room + "_" + uid, getBabyExp(uid, room) + num);
}
boolean hasBaby(String uid, String room) {
    return "1".equals(getString("babyowned_" + room + "_" + uid, ""));
}
void setBaby(String uid, String room, boolean owned) {
    putString("babyowned_" + room + "_" + uid, owned ? "1" : "");
}
int getBabyLevel(int exp) {
    if (exp >= 800) return 5;
    if (exp >= 400) return 4;
    if (exp >= 170) return 3;
    if (exp >= 70) return 2;
    if (exp >= 22) return 1;
    return 0;
}
String getBabyTitle(int level) {
    String[] titles = {"[蛋]", "[幼崽]", "[萌娃]", "[少年]", "[青年]", "[成年]"};
    if (level < 0 || level >= titles.length) return "?";
    return titles[level];
}

// ========== 发言统计 ==========
int getSpeak(String uid, String room) {
    return getInt("speak_" + room + "_" + uid, 0);
}
void addSpeak(String uid, String room) {
    putInt("speak_" + room + "_" + uid, getSpeak(uid, room) + 1);
}

// ========== 黑名单 ==========
boolean isBlack(String uid, String room) {
    String list = getString("black_" + room, "");
    return ("," + list + ",").contains("," + uid + ",");
}
void addBlack(String uid, String room) {
    String list = getString("black_" + room, "");
    if (!isBlack(uid, room)) {
        putString("black_" + room, list.isEmpty() ? uid : list + "," + uid);
    }
}
void delBlack(String uid, String room) {
    String list = getString("black_" + room, "");
    list = list.replace("," + uid + ",", ",").replace("," + uid, "").replace(uid + ",", "").replace(uid, "");
    putString("black_" + room, list);
}

// ========== 欢迎语 ==========
void setWelcome(String room, String text) {
    putString("welcome_" + room, text);
}
String getWelcome(String room) {
    return getString("welcome_" + room, "欢迎新朋友，发送菜单查看全部功能");
}

// ========== 求婚记录 ==========
String getPropose(String target, String room) {
    return getString("propose_" + room + "_" + target, "");
}
void setPropose(String target, String room, String suitor) {
    putString("propose_" + room + "_" + target, suitor);
}
void clearPropose(String target, String room) {
    putString("propose_" + room + "_" + target, "");
}

// ========== 拍卖系统 ==========
String getAuctionOwner(String room) {
    return getString("auction_owner_" + room, "");
}
int getAuctionPrice(String room) {
    return getInt("auction_price_" + room, 0);
}
long getAuctionEndTs(String room) {
    return getLong("auction_end_" + room, 0);
}
void setAuction(String room, String owner, int price, long endTs) {
    putString("auction_owner_" + room, owner);
    putInt("auction_price_" + room, price);
    putLong("auction_end_" + room, endTs);
}
void clearAuction(String room) {
    putString("auction_owner_" + room, "");
    putInt("auction_price_" + room, 0);
    putLong("auction_end_" + room, 0);
    putString("auction_highest_bidder_" + room, "");
    putInt("auction_highest_price_" + room, 0);
}

// ========== 点歌系统（网易云API）==========
String searchMusic(String keyword) {
    try {
        String encoded = URLEncoder.encode(keyword, "UTF-8");
        String urlStr = "https://music.163.com/api/search/get/web?csrf_token=&s=" + encoded + "&type=1&offset=0&limit=1";
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setRequestProperty("Referer", "https://music.163.com/");
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        JSONObject json = JSON.parseObject(sb.toString());
        if (json.getIntValue("code") != 200) return null;
        JSONObject result = json.getJSONObject("result");
        if (result == null) return null;
        JSONArray songs = result.getJSONArray("songs");
        if (songs == null || songs.size() == 0) return null;
        JSONObject song = songs.getJSONObject(0);
        String name = song.getString("name");
        String id = song.getString("id");
        JSONArray artists = song.getJSONArray("artists");
        String artist = "";
        if (artists != null && artists.size() > 0) {
            artist = artists.getJSONObject(0).getString("name");
        }
        return name + " - " + artist + " | " + id;
    } catch (Exception e) {
        if (openSimpleLog) log("[点歌] 搜索失败: " + e.getMessage());
        return null;
    }
}

// ========== 称号 ==========
String getTitle(String uid, String room) {
    int point = getPoint(uid, room);
    int gift = getGiftTotal(uid, room);
    String title = "";
    if (point >= 8888) title = "[群神]";
    else if (point >= 3888) title = "[一代大佬]";
    else if (point >= 1288) title = "[小富豪]";
    else if (point >= 288) title = "[打工人]";
    if (gift >= 588) title += "[模范情侣]";
    return title;
}

// ========== 用户列表 ==========
void addUserIfNeeded(String uid, String room) {
    String list = getString("userlist_" + room, "");
    if (!("," + list + ",").contains("," + uid + ",")) {
        putString("userlist_" + room, list.isEmpty() ? uid : list + "," + uid);
    }
}

// ========== 提取@目标（辅助）==========
String extractAtTarget(String text, String prefix) {
    String content = text.substring(prefix.length()).trim();
    if (content.startsWith("@")) {
        return content.substring(1).trim();
    }
    String[] parts = content.split("\\s+");
    for (String p : parts) {
        if (p.startsWith("@")) return p.substring(1).trim();
    }
    return content.trim();
}

// ========== 排序工具 ==========
String[] sortUidsByPoint(String room, String[] uids) {
    for (int i = 1; i < uids.length; i++) {
        String key = uids[i];
        int keyVal = getPoint(key, room);
        int j = i - 1;
        while (j >= 0 && getPoint(uids[j], room) < keyVal) {
            uids[j + 1] = uids[j];
            j--;
        }
        uids[j + 1] = key;
    }
    return uids;
}
String[] sortUidsBySpeak(String room, String[] uids) {
    for (int i = 1; i < uids.length; i++) {
        String key = uids[i];
        int keyVal = getSpeak(key, room);
        int j = i - 1;
        while (j >= 0 && getSpeak(uids[j], room) < keyVal) {
            uids[j + 1] = uids[j];
            j--;
        }
        uids[j + 1] = key;
    }
    return uids;
}

// ========== 菜单（移除入群验证相关）==========
void sendMenu(String room) {
    String menu = "";
    menu += "===== 群娱乐机器人 v3.7 =====\n";
    menu += "【日常互动】签到/人品/情话/鸡汤/讲笑话/算命/买运气/打劫\n";
    menu += "【养成系统】领养宝宝/喂食/宝宝状态\n";
    menu += "【婚恋系统】结婚@/同意求婚/离婚/送礼@\n";
    menu += "【积分玩法】开盲盒/积分榜/赎身\n";
    menu += "【拍卖系统】拍卖 底价/出价@ 金额\n";
    menu += "【点歌系统】点歌 歌名\n";
    menu += "【群管指令*仅主人】活跃榜/设置欢迎语/查看欢迎语\n";
    menu += "【群管指令*仅主人】黑名单添加@/黑名单移除@\n";
    menu += "【群管指令*仅主人】添加生效群/移除生效群/查看生效群\n";
    menu += "【群管指令*仅主人】备份群数据/恢复群数据\n";
    menu += "【打劫专用】打劫（随机）、打劫@用户（指定目标）\n";
    menu += "【其他】999查看版本\n";
    menu += "(*) 标注的指令仅文件拥有者可操作";
    sendText(room, menu);
}

// ========== 备份/恢复 ==========
void backupGroupData(String room) {
    try {
        JSONObject backup = new JSONObject();
        String userList = getString("userlist_" + room, "");
        backup.put("room", room);
        backup.put("welcome", getWelcome(room));
        backup.put("blacklist", getString("black_" + room, ""));
        JSONArray users = new JSONArray();
        if (!userList.isEmpty()) {
            String[] uids = userList.split(",");
            for (String uid : uids) {
                if (uid.isEmpty()) continue;
                JSONObject u = new JSONObject();
                u.put("uid", uid);
                u.put("points", getPoint(uid, room));
                u.put("speak", getSpeak(uid, room));
                u.put("babyExp", getBabyExp(uid, room));
                u.put("hasBaby", hasBaby(uid, room));
                u.put("mate", getMate(uid, room));
                u.put("signDay", getSignDay(uid, room));
                u.put("lastSignTs", getLastSignTs(uid, room));
                u.put("giftTotal", getGiftTotal(uid, room));
                users.add(u);
            }
        }
        backup.put("users", users);
        File dir = new File(pluginDir, "backups");
        dir.mkdirs();
        File file = new File(dir, "backup_" + room + ".json");
        FileOutputStream fos = new FileOutputStream(file);
        fos.write(backup.toString().getBytes("UTF-8"));
        fos.close();
        sendText(room, "备份成功：" + file.getAbsolutePath());
    } catch (Exception e) {
        sendText(room, "备份失败：" + e.getMessage());
    }
}
void restoreGroupData(String room) {
    try {
        File dir = new File(pluginDir, "backups");
        File file = new File(dir, "backup_" + room + ".json");
        if (!file.exists()) {
            sendText(room, "备份文件不存在，请先备份。");
            return;
        }
        FileInputStream fis = new FileInputStream(file);
        byte[] data = new byte[(int) file.length()];
        fis.read(data);
        fis.close();
        JSONObject backup = JSON.parseObject(new String(data, "UTF-8"));
        setWelcome(room, backup.getString("welcome"));
        putString("black_" + room, backup.getString("blacklist"));
        JSONArray users = backup.getJSONArray("users");
        String newUserList = "";
        for (int i = 0; i < users.size(); i++) {
            JSONObject u = users.getJSONObject(i);
            String uid = u.getString("uid");
            putInt("point_" + room + "_" + uid, u.getIntValue("points"));
            putInt("speak_" + room + "_" + uid, u.getIntValue("speak"));
            putInt("baby_" + room + "_" + uid, u.getIntValue("babyExp"));
            putString("babyowned_" + room + "_" + uid, u.getBooleanValue("hasBaby") ? "1" : "");
            String mate = u.getString("mate");
            if (!mate.isEmpty()) putString("mate_" + room + "_" + uid, mate);
            else putString("mate_" + room + "_" + uid, "");
            putInt("sign_day_" + room + "_" + uid, u.getIntValue("signDay"));
            putLong("sign_ts_" + room + "_" + uid, u.getLongValue("lastSignTs"));
            putInt("gift_total_" + room + "_" + uid, u.getIntValue("giftTotal"));
            if (newUserList.isEmpty()) newUserList = uid;
            else newUserList += "," + uid;
        }
        putString("userlist_" + room, newUserList);
        sendText(room, "恢复完成！");
    } catch (Exception e) {
        sendText(room, "恢复失败：" + e.getMessage());
    }
}

// ========== 拍卖过期检查 ==========
void checkAuctionExpire(String room) {
    long endTs = getAuctionEndTs(room);
    if (endTs == 0) return;
    if (System.currentTimeMillis() >= endTs) {
        String owner = getAuctionOwner(room);
        String highest = getString("auction_highest_bidder_" + room, "");
        int price = getAuctionPrice(room);
        if (!highest.isEmpty()) {
            int bidPrice = getInt("auction_highest_price_" + room, price);
            if (getPoint(highest, room) >= bidPrice) {
                addPoint(highest, room, -bidPrice);
                addPoint(owner, room, bidPrice);
                sendText(room, "[拍卖结束] " + getFriendDisplayName(highest, room) + " 以 " + bidPrice + " 积分拍下\n" + getFriendDisplayName(owner, room) + " 获得 " + bidPrice + " 积分");
            } else {
                sendText(room, "[拍卖结束] 最高价积分不足，流拍~");
            }
        } else {
            sendText(room, "[拍卖结束] 无人出价，流拍~");
        }
        clearAuction(room);
    }
}

// ========== 拍卖定时检查 ==========
void startAuctionCheck() {
    timerPool.scheduleAtFixedRate(new Runnable() {
        public void run() {
            try {
                String list = getString(ALLOW_GROUP_KEY, "");
                if (list.isEmpty()) return;
                for (String room : list.split(",")) {
                    if (!room.isEmpty()) checkAuctionExpire(room);
                }
            } catch (Exception e) {
                if (openSimpleLog) log("[拍卖检查] 异常: " + e.getMessage());
            }
        }
    }, 10, 10, TimeUnit.SECONDS);
}

// ========== 主消息监听（无自动加群，无入群验证）==========
void onHandleMsg(Object msg) {
    if (!isLoaded) return;
    String room = msg.getTalker();
    if (!msg.isGroupChat() || !msg.isText()) return;
    // 先检查是否在生效群，不在则忽略
    if (!isAllowGroup(room)) return;

    String sender = msg.getSender();
    String nick = getFriendDisplayName(sender, room);
    String text = msg.getContent().trim();
    if (openSimpleLog) log("指令：" + nick + " -> " + text);
    if (isBlack(sender, room)) return;

    // 正常流程
    addSpeak(sender, room);
    addUserIfNeeded(sender, room);
    checkAuctionExpire(room);

    // ---- 菜单 ----
if (text.equals("菜单") || text.equals("帮助")) {
    sendMenu(room);
    return;
}

if (text.equals("999")) {
    sendText(room, "群娱乐机器人 v3.7 (移除入群验证 & 自动加群)");
    return;   // 加 return 避免继续执行
}

// ---- 查看我的 wxid ----
if (text.equals("我的微信 id") || text.equals("我的微信id") || text.equals("我的wxid")) {
    sendText(room, "你的 wxid 是：" + getLoginWxid());
    return;
}

// ========== 以下为群管指令，仅 ROOT 可操作 ==========
// ---- 查看生效群（所有人可看） ----
if (text.equals("查看生效群")) {
    String list = loadAllowGroupList();
    sendText(room, "当前生效群ID：" + (list.isEmpty() ? "暂无" : list));
    return;
}


    // ---- 设置/添加/移除生效群（仅主人）----
    if (text.startsWith("设置生效群 ")) {
        if (!isRoot(sender)) { sendText(room, nick + " 无权限操作"); return; }
        String groups = text.substring("设置生效群 ".length()).trim();
        putString(ALLOW_GROUP_KEY, groups);
        sendText(room, "生效群已更新。");
        return;
    }
    if (text.startsWith("添加生效群 ")) {
        if (!isRoot(sender)) { sendText(room, nick + " 无权限操作"); return; }
        String g = text.substring("添加生效群 ".length()).trim();
        String current = getString(ALLOW_GROUP_KEY, "");
        if (!("," + current + ",").contains("," + g + ",")) {
            putString(ALLOW_GROUP_KEY, current.isEmpty() ? g : current + "," + g);
            sendText(room, "成功添加该群到生效列表");
        } else {
            sendText(room, "该群已在生效列表内");
        }
        return;
    }
    if (text.startsWith("移除生效群 ")) {
        if (!isRoot(sender)) { sendText(room, nick + " 无权限操作"); return; }
        String g = text.substring("移除生效群 ".length()).trim();
        String current = getString(ALLOW_GROUP_KEY, "");
        current = current.replace("," + g + ",", ",").replace("," + g, "").replace(g + ",", "").replace(g, "");
        putString(ALLOW_GROUP_KEY, current);
        sendText(room, "已从生效列表移除该群");
        return;
    }

    // ---- 设置欢迎语 / 查看欢迎语（仅主人可设）----
    if (text.startsWith("设置欢迎语 ")) {
        if (!isRoot(sender)) { sendText(room, nick + " 无权限操作"); return; }
        String welcomeTxt = text.substring("设置欢迎语 ".length()).trim();
        setWelcome(room, welcomeTxt);
        sendText(room, "群欢迎语设置成功！");
        return;
    }
    if (text.equals("查看欢迎语")) {
        sendText(room, "当前欢迎语：" + getWelcome(room));
        return;
    }

    // ---- 黑名单添加/移除（仅主人）----
    if (text.startsWith("黑名单添加@") || text.startsWith("黑名单添加 ")) {
        if (!isRoot(sender)) { sendText(room, nick + " 无权限操作"); return; }
        String target = extractAtTarget(text, "黑名单添加");
        if (target.isEmpty()) {
            sendText(room, "格式：黑名单添加 @用户");
            return;
        }
        addBlack(target, room);
        sendText(room, "已加入黑名单 " + getFriendDisplayName(target, room));
        return;
    }
    if (text.startsWith("黑名单移除@") || text.startsWith("黑名单移除 ")) {
        if (!isRoot(sender)) { sendText(room, nick + " 无权限操作"); return; }
        String target = extractAtTarget(text, "黑名单移除");
        if (target.isEmpty()) {
            sendText(room, "格式：黑名单移除 @用户");
            return;
        }
        delBlack(target, room);
        sendText(room, "已移出黑名单 " + getFriendDisplayName(target, room));
        return;
    }

    // ---- 活跃榜（所有人可看）----
    if (text.equals("活跃榜")) {
        String list = getString("userlist_" + room, "");
        if (list.isEmpty()) { sendText(room, "暂无数据"); return; }
        String[] users = list.split(",");
        List<String> valid = new ArrayList<String>();
        for (String u : users) { if (!u.isEmpty() && !isBlack(u, room)) valid.add(u); }
        String[] sorted = sortUidsBySpeak(room, valid.toArray(new String[0]));
        StringBuilder sb = new StringBuilder("发言榜 TOP" + Math.min(10, sorted.length) + "\n");
        int show = Math.min(10, sorted.length);
        for (int i = 0; i < show; i++) {
            String u = sorted[i];
            String medal;
            if (i == 0) medal = "[1st]";
            else if (i == 1) medal = "[2nd]";
            else if (i == 2) medal = "[3rd]";
            else medal = (i + 1) + ".";
            sb.append(medal).append(" ").append(getFriendDisplayName(u, room));
            sb.append("：发言").append(getSpeak(u, room)).append("\n");
        }
        sendText(room, sb.toString());
        return;
    }

    // ---- 备份/恢复（仅主人）----
    if (text.equals("备份群数据")) {
        if (!isRoot(sender)) { sendText(room, nick + " 无权限操作"); return; }
        backupGroupData(room);
        return;
    }
    if (text.equals("恢复群数据")) {
        if (!isRoot(sender)) { sendText(room, nick + " 无权限操作"); return; }
        restoreGroupData(room);
        return;
    }

    // ---- 监狱检查 ----
    if (isInJail(sender, room)) {
        long remain = (getLong("jail_until_" + room + "_" + sender, 0) - System.currentTimeMillis()) / 60000L;
        sendText(room, nick + " 还在反省监狱（剩余" + remain + "分钟），不能操作~");
        return;
    }

    // ========== 打劫逻辑 ==========
    if (text.equals("打劫")) {
        runRob(sender, "", room, nick);
        return;
    }
    if (text.startsWith("打劫@") || text.startsWith("打劫 ")) {
        String target = extractAtTarget(text, "打劫");
        if (target.isEmpty()) {
            sendText(room, "用法：打劫（随机） / 打劫@群成员");
            return;
        }
        if (target.equals(sender)) {
            sendText(room, "不能打劫自己！");
            return;
        }
        runRob(sender, target, room, nick);
        return;
    }

    // ========== 签到 ==========
    if (text.equals("签到")) {
        long now = System.currentTimeMillis();
        long lastTs = getLastSignTs(sender, room);
        long dayGap = now - lastTs;
        int continuousDay = getSignDay(sender, room);
        if (dayGap < 86400000L && continuousDay > 0) {
            sendText(room, nick + " 今天已经签到\n连续签到：" + continuousDay + "天\n当前积分：" + getPoint(sender, room));
            return;
        }
        if (dayGap > 172800000L) {
            continuousDay = 0;
        }
        continuousDay += 1;
        setSignDay(sender, room, continuousDay);
        setLastSignTs(sender, room, now);
        int base = randNum(100, 150);
        int bonus = continuousDay * 2;
        int totalPoint = base + bonus;
        if (!checkDailyLimit(sender, room, totalPoint, room)) return;
        addPoint(sender, room, totalPoint);
        addDailyEarned(sender, room, totalPoint);
        String streakMsg = continuousDay > 1 ? "\n连续签到：" + continuousDay + "天（+" + bonus + "连签奖励）" : "";
        sendText(room, nick + " 签到成功\n基础+" + base + streakMsg + "\n本次合计+" + totalPoint + "\n当前积分：" + getPoint(sender, room) + getTitle(sender, room));
        return;
    }

    // ========== 互动词库 ==========
    String[] loveArr = {
        "满目星河，唯有你是归宿",
        "岁岁年年，伴你左右",
        "山河远阔，人间烟火，无一是你，无一不是你",
        "你是我藏在微风里的欢喜",
        "世间万物，不及你一回眸",
        "我想变成风，这样就能每天拥抱你",
        "你的名字，是我见过最短的情诗",
        "遇见你之后，我再没羡慕过任何人",
        "你是我的今天，以及所有的明天",
        "星河滚烫，你是人间理想",
        "我想和你，从新鲜感到归属感",
        "世界很大，但我的视线只装得下一个你",
        "你是我绕遍世界后，唯一的归途",
        "春风十里，不如你笑靥如花",
        "你是早安，也是晚安，更是每一天",
        "我看过万千风景，你是最美那一道",
        "喜欢你，是我做过最久的计划",
        "你是我写不出结尾的那首诗",
        "如果爱有重量，那一定是你的名字",
        "我的心跳频率，永远为你加速",
        "你是我平淡生活里的高光时刻",
        "不求与你同日生，但求与你共余生",
        "全世界都在催我长大，只有你护我童心",
        "你是我最想留住的幸运"
    };
    String[] luckArr = {"欧皇附体", "普通运势", "今日水逆"};
    if (text.equals("人品")) {
        sendText(room, nick + " 今日运势：" + randomTxt(luckArr));
        return;
    }
    if (text.equals("情话")) {
        sendText(room, randomTxt(loveArr));
        return;
    }
    String[] jokeArr = {
        "早睡早起，不如通宵狂喜",
        "我的钱虽然不是大风刮来的，但像被大风刮走的",
        "只要我够穷，骗子就骗不到我",
        "努力不一定成功，但不努力一定很舒服",
        "钱包和脸一样，看着挺大，用着用着就空了",
        "别人的目标是财务自由，我的目标是早点下班",
        "我是个现实主义者，我的梦想是发财",
        "人生就像巧克力，你永远不知道下一颗有多甜，反正我吃不起",
        "世界上最远的距离不是天涯海角，而是我在减肥你在吃火锅",
        "别问我工资多少，我怕你听了想借钱"
    };
    if (text.equals("讲笑话") || text.equals("毒鸡汤")) {
        sendText(room, randomTxt(jokeArr));
        return;
    }
    String[] dogLickArr = {
        "今天你回了我一个句号，我反复看了20遍，你是不是暗示我们圆满了？",
        "宝，下雨了，我第一个想到你，怕你淋雨，可我连送伞的身份都没有",
        "你半天不回消息，我疯狂检讨自己，是不是表情包不合你心意",
        "你随口说想吃奶茶，我攒了三天零花钱，却不好意思开口送给你",
        "就算你一整天不理我，我还是提前把晚安编辑好，等到零点准时发送",
        "朋友都劝我别当舔狗，可只要你多看我一眼，我就觉得全世界都值得",
        "你和别人出去玩，我只能默默刷朋友圈，不敢点赞，怕打扰你的快乐",
        "我主动找你100次，你敷衍一次，我依旧开心一整天，没救了",
        "宝，今天我连晚饭都没吃，就为了等你一句闲聊，结果等到了晚安",
        "你说我们只适合做朋友，我连夜改写简介：专属好朋友，心甘情愿"
    };
    if (text.equals("舔狗日记")) {
        sendText(room, randomTxt(dogLickArr));
        return;
    }
    if (text.equals("算命")) {
        sendText(room, nick + " 总资产：" + (getPoint(sender, room) * 1088) + " 元" + getTitle(sender, room));
        return;
    }

    // ---- 买运气 ----
    if (text.equals("买运气")) {
        int cost = 66;
        if (getPoint(sender, room) < cost) {
            sendText(room, nick + " 买运气需要66积分，余额不足~");
            return;
        }
        addPoint(sender, room, -cost);
        String[] buyLuckResult = {
            "今日欧皇附体，万事顺意！",
            "锦鲤附身，好运爆棚！",
            "平平淡淡，安稳度日~",
            "生活如水，波澜不惊"
        };
        sendText(room, nick + " 花费66积分！\n" + randomTxt(buyLuckResult) + "\n剩余积分：" + getPoint(sender, room));
        return;
    }

    // ---- 开盲盒 ----
    if (text.equals("开盲盒")) {
        int cost = 38;
        if (getPoint(sender, room) < cost) {
            sendText(room, nick + " 开盲盒需要38积分，余额不足~");
            return;
        }
        addPoint(sender, room, -cost);
        int r = randNum(1, 110);
        int reward;
        String rank;
        if (r <= 42) { reward = 18; rank = "普通"; }
        else if (r <= 72) { reward = 58; rank = "稀有"; }
        else if (r <= 90) { reward = 168; rank = "史诗"; }
        else { reward = 520; rank = "传说"; }
        if (!checkDailyLimit(sender, room, reward, room)) {
            addPoint(sender, room, cost);
            return;
        }
        addPoint(sender, room, reward);
        addDailyEarned(sender, room, reward);
        sendText(room, nick + " 开出[" + rank + "]\n获得 " + reward + " 积分！\n剩余积分：" + getPoint(sender, room));
        return;
    }

        // ---- 赎身 ----
    if (text.equals("赎身")) {
        if (!isBlack(sender, room)) {
            sendText(room, nick + " 你没有被拉黑，无需赎身~");
            return;
        }
        int cost = 280;
        if (getPoint(sender, room) < cost) {
            sendText(room, nick + " 赎身需280积分，还差" + (cost - getPoint(sender, room)));
            return;
        }
        addPoint(sender, room, -cost);
        delBlack(sender, room);
        sendText(room, nick + " 花费280积分成功赎身！");
        return;
    }

    // ---- 积分榜 ----
    if (text.equals("积分榜")) {
        String list = getString("userlist_" + room, "");
        if (list.isEmpty()) { sendText(room, "暂无数据"); return; }
        String[] users = list.split(",");
        List<String> valid = new ArrayList<String>();
        for (String u : users) { if (!u.isEmpty() && !isBlack(u, room)) valid.add(u); }
        String[] sorted = sortUidsByPoint(room, valid.toArray(new String[0]));
        StringBuilder sb = new StringBuilder("积分榜 TOP" + Math.min(10, sorted.length) + "\n");
        int show = Math.min(10, sorted.length);
        for (int i = 0; i < show; i++) {
            String u = sorted[i];
            String medal;
            if (i == 0) medal = "[1st]";
            else if (i == 1) medal = "[2nd]";
            else if (i == 2) medal = "[3rd]";
            else medal = (i + 1) + ".";
            sb.append(medal).append(" ").append(getFriendDisplayName(u, room));
            sb.append("：").append(getPoint(u, room)).append(getTitle(u, room)).append("\n");
        }
        sendText(room, sb.toString());
        return;
    }

    // ---- 宝宝 ----
    if (text.equals("领养宝宝")) {
        if (hasBaby(sender, room)) {
            sendText(room, nick + " 你已经领养过宝宝了，快去喂食吧！");
        } else {
            setBaby(sender, room, true);
            sendText(room, nick + " 领养宝宝成功！\n喂食消耗11积分，发送【喂食宝宝】增加经验");
        }
        return;
    }
    if (text.equals("喂食宝宝")) {
        if (!hasBaby(sender, room)) {
            sendText(room, nick + " 你还没有宝宝，请先【领养宝宝】");
            return;
        }
        int cost = 11;
        if (getPoint(sender, room) < cost) {
            sendText(room, nick + " 喂食需要11积分，余额不足");
            return;
        }
        addPoint(sender, room, -cost);
        int expGain = randNum(6, 21) + cost / 2;
        addBabyExp(sender, room, expGain);
        int lvl = getBabyLevel(getBabyExp(sender, room));
        sendText(room, nick + " 喂食成功\n消耗" + cost + "积分，宝宝经验+" + expGain + "\n等级：" + getBabyTitle(lvl) + "\n总经验：" + getBabyExp(sender, room) + "\n剩余积分：" + getPoint(sender, room));
        return;
    }
    if (text.equals("宝宝状态")) {
        if (!hasBaby(sender, room)) {
            sendText(room, nick + " 你还没有宝宝，请先【领养宝宝】");
            return;
        }
        int exp = getBabyExp(sender, room);
        int lvl = getBabyLevel(exp);
        int[] thresholds = {22, 71, 171, 401, 801};
        int next = lvl >= 5 ? -1 : thresholds[lvl];
        String tip = next > 0 ? ("距下一级还需" + (next - exp) + "经验") : "已满级";
        sendText(room, nick + " 的宝宝状态\n等级：" + getBabyTitle(lvl) + "\n总经验：" + exp + " " + tip);
        return;
    }

    // ========== 婚恋系统（使用 getAtUserList）==========
    if (text.startsWith("结婚 @") || text.startsWith("结婚@")) {
        String targetUid = "";
        List atList = msg.getAtUserList();
        if (atList != null && atList.size() > 0) {
            targetUid = (String) atList.get(0);
        } else {
            targetUid = extractAtTarget(text, "结婚");
        }
        if (targetUid.isEmpty()) {
            sendText(room, "格式：结婚 @群成员");
            return;
        }
        if (targetUid.equals(sender)) {
            sendText(room, "不能和自己结婚！");
            return;
        }
        String curMate = getMate(sender, room);
        if (!curMate.isEmpty()) {
            sendText(room, nick + " 你已有伴侣（" + getFriendDisplayName(curMate, room) + "），请先发送【离婚】");
            return;
        }
        setPropose(targetUid, room, sender);
        sendText(room, nick + " 向 " + getFriendDisplayName(targetUid, room) + " 求婚！对方发送【同意求婚】即可结为伴侣");
        return;
    }
    if (text.equals("同意求婚") || text.equals("同意结婚")) {
        String suitor = getPropose(sender, room);
        if (suitor.isEmpty()) {
            sendText(room, "目前没有人对你发起求婚~ 让对方发送：结婚 @你");
            return;
        }
        String old1 = getMate(suitor, room);
        String old2 = getMate(sender, room);
        if (!old1.isEmpty()) delMate(suitor, room);
        if (!old2.isEmpty()) delMate(sender, room);
        setMate(suitor, sender, room);
        clearPropose(sender, room);
        sendText(room, getFriendDisplayName(suitor, room) + " 与 " + nick + " 结为伴侣！祝福他们！");
        return;
    }
    if (text.equals("离婚")) {
        String mate = getMate(sender, room);
        if (mate.isEmpty()) {
            sendText(room, nick + " 你目前没有伴侣，无需离婚~");
            return;
        }
        int fee = 50;
        if (getPoint(sender, room) < fee) {
            sendText(room, nick + " 离婚需要50积分手续费，余额不足");
            return;
        }
        addPoint(sender, room, -fee);
        addPoint(mate, room, fee / 2);
        delMate(sender, room);
        sendText(room, nick + " 支付50积分解除伴侣关系，" + getFriendDisplayName(mate, room) + " 获得25积分补偿");
        return;
    }
    if (text.startsWith("送礼 @") || text.startsWith("送礼@")) {
        String target = extractAtTarget(text, "送礼");
        if (target.isEmpty()) {
            sendText(room, "格式：送礼 @伴侣 金额");
            return;
        }
        String after = text.substring(text.indexOf(target) + target.length()).trim();
        int amount = 20;
        try { amount = Integer.parseInt(after); } catch (Exception e) {}
        if (amount <= 0) {
            sendText(room, "赠送积分必须大于0");
            return;
        }
        if (!target.equals(getMate(sender, room))) {
            sendText(room, "只能送给自己的伴侣哦~（先结婚吧）");
            return;
        }
        if (getPoint(sender, room) < amount) {
            sendText(room, "积分不足");
            return;
        }
        addPoint(sender, room, -amount);
        addPoint(target, room, amount);
        addGiftTotal(sender, room, amount);
        sendText(room, nick + " 赠送伴侣 " + amount + " 积分！剩余：" + getPoint(sender, room));
        return;
    }

    // ---- 拍卖系统 ----
    if (text.startsWith("拍卖 ")) {
        String s = text.substring("拍卖 ".length()).trim();
        int price;
        try { price = Integer.parseInt(s); }
        catch (Exception e) {
            sendText(room, "格式：拍卖 底价（例：拍卖 200）");
            return;
        }
        if (price <= 0) {
            sendText(room, "底价必须大于0");
            return;
        }
        if (getAuctionEndTs(room) > System.currentTimeMillis()) {
            sendText(room, "已有拍卖进行中，无法新建");
            return;
        }
        setAuction(room, sender, price, System.currentTimeMillis() + 300000L);
        sendText(room, nick + " 开启拍卖，底价" + price + "积分，5分钟内出价\n示例：出价@" + nick + " 500");
        return;
    }
    if (text.startsWith("出价@") || text.startsWith("出价 ")) {
        String content = text.startsWith("出价@")
                ? text.substring("出价@".length()).trim()
                : text.substring("出价 ".length()).trim();
        String target = "";
        String numStr = "";
        String[] parts = content.split("\\s+");
        if (parts.length >= 2) {
            boolean firstIsNum = true;
            try { Integer.parseInt(parts[0]); }
            catch (Exception e) { firstIsNum = false; }
            if (firstIsNum) {
                numStr = parts[0];
                target = parts[1].startsWith("@") ? parts[1].substring(1) : parts[1];
            } else {
                target = parts[0].startsWith("@") ? parts[0].substring(1) : parts[0];
                numStr = parts[1];
            }
        } else if (parts.length == 1) {
            numStr = parts[0];
        }
        int bidNum;
        try { bidNum = Integer.parseInt(numStr); }
        catch (Exception e) {
            sendText(room, "格式：出价@拍卖发起人 金额（例：出价@wxid_xxx 500）");
            return;
        }
        long auctionEnd = getAuctionEndTs(room);
        if (auctionEnd == 0 || System.currentTimeMillis() >= auctionEnd) {
            sendText(room, "当前没有进行中的拍卖");
            return;
        }
        String auctionOwner = getAuctionOwner(room);
        if (!auctionOwner.equals(target)) {
            sendText(room, "出价目标必须是当前拍卖发起人");
            return;
        }
        int currentHigh = getInt("auction_highest_price_" + room, getAuctionPrice(room));
        if (bidNum <= currentHigh) {
            sendText(room, "出价必须高于当前最高价：" + currentHigh);
            return;
        }
        if (getPoint(sender, room) < bidNum) {
            sendText(room, "积分不足，无法出价");
            return;
        }
        String lastBidder = getString("auction_highest_bidder_" + room, "");
        if (!lastBidder.isEmpty() && !lastBidder.equals(sender)) {
            addPoint(lastBidder, room, currentHigh);
        }
        addPoint(sender, room, -bidNum);
        putString("auction_highest_bidder_" + room, sender);
        putInt("auction_highest_price_" + room, bidNum);
        putLong("auction_end_" + room, System.currentTimeMillis() + 60000L);
        sendText(room, nick + " 出价 " + bidNum + " 积分（拍卖延长1分钟）");
        return;
    }

    // ---- 点歌 ----
    if (text.startsWith("点歌 ")) {
        String keyword = text.substring("点歌 ".length()).trim();
        if (keyword.isEmpty()) {
            sendText(room, "格式：点歌 歌名或歌手");
            return;
        }
        sendText(room, "搜索【" + keyword + "】中...");
        String result = searchMusic(keyword);
        if (result == null || result.isEmpty()) {
            sendText(room, "未找到相关歌曲，换个关键词试试~");
            return;
        }
        String[] parts2 = result.split("\\|");
        String songInfo = parts2[0].trim();
        String songId = parts2.length > 1 ? parts2[1].trim() : "";
        String shareUrl = "https://music.163.com/song/" + songId + "/";
        sendText(room, "点歌成功！\n《" + songInfo + "》\n播放链接：" + shareUrl + "\n点歌人：" + nick);
        return;
    }
}

// ========== 生命周期 ==========
void onLoad() {
    isLoaded = true;
    log("群娱乐机器人 v3.7 加载完成 (已移除入群验证 & 自动加群)");
    startAuctionCheck();
}
void onUnload() {
    isLoaded = false;
    if (timerPool != null) timerPool.shutdownNow();
    taskMap.clear();
    log("插件已卸载");
}
