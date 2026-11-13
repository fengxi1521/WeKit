import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Calendar;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.time.Duration;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.tencent.mm.opensdk.modelmsg.WXMediaMessage;
import com.tencent.mm.opensdk.modelmsg.WXMusicObject;
import com.tencent.mm.opensdk.modelmsg.WXImageObject;
import java.util.Comparator;
import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.BufferedWriter;
import java.io.FileWriter;

// ==================== 全局数据 ====================
private Map<String,String> USER_DATA = new HashMap<String,String>();
private Map<String,Boolean> ADMIN_SESSION = new HashMap<>();
private Map<String,String> GROUP_CONFIG = new HashMap<>();
private String GLOBAL_ANNOUNCEMENT = "";
private Map<String,String> BOSS_STATE = new HashMap<>();
private final String GLOBAL_ADMIN_CFG_KEY = "__global_admin_cfg__";
private final String DATA_DIR = pluginPath + "/data/";
private boolean dataLoaded = false;
private boolean groupConfigLoaded = false;
private boolean announcementLoaded = false;
private boolean bossStateLoaded = false;

// ==================== ID系统（5位唯一ID，替代@方式） ====================
private Map<String,String> USER_ID_MAP = new HashMap<>();
private int ID_COUNTER = 0;
private boolean idMapLoaded = false;

// ==================== 定时任务 ====================
private ArrayList<String[]> SCHEDULED_TASKS = new ArrayList<>();
private boolean schedulerLoaded = false;
private long lastScheduleCheck = 0;
private String lastScheduleMinute = "";

// ==================== 授权用户 ====================
private Map<String,String> AUTHORIZED_USERS = new HashMap<>();
private boolean authLoaded = false;

// ==================== 聊天功能开关 ====================
private boolean CHAT_ENABLED = false;
private boolean chatConfigLoaded = false;

// ==================== 天气一言列表 ====================
private ArrayList<String> quoteList = new ArrayList<>();
private boolean quotesLoaded = false;

private final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(30))
        .callTimeout(Duration.ofSeconds(30))
        .readTimeout(Duration.ofSeconds(30))
        .build();

// ==================== 文件读写工具 ====================
private String readFile(String fileName){
    try{
        File dir = new File(DATA_DIR);
        if(!dir.exists()) dir.mkdirs();
        File f = new File(DATA_DIR + fileName);
        if(!f.exists()) return null;
        BufferedReader br = new BufferedReader(new FileReader(f));
        StringBuilder sb = new StringBuilder();
        String line;
        while((line = br.readLine()) != null){ sb.append(line); }
        br.close();
        return sb.toString();
    }catch(Throwable t){ return null; }
}
private void writeFile(String fileName, String content){
    try{
        File dir = new File(DATA_DIR);
        if(!dir.exists()) dir.mkdirs();
        BufferedWriter bw = new BufferedWriter(new FileWriter(DATA_DIR + fileName));
        bw.write(content);
        bw.close();
    }catch(Throwable t){}
}

// ==================== 图片发送 ====================
private void sendImage(final String talker, final String imageUrl, final String fallback){
    new Thread(new Runnable(){
        public void run(){
            String path = DATA_DIR + "cache_img_" + System.currentTimeMillis() + "_" + (int)(Math.random()*10000) + ".jpg";
            try{
                Request req = new Request.Builder().url(imageUrl).build();
                Response resp = client.newCall(req).execute();
                if(resp == null || !resp.isSuccessful()){
                    if(fallback != null) sendText(talker, fallback);
                    return;
                }
                InputStream is = resp.body().byteStream();
                FileOutputStream fos = new FileOutputStream(path);
                byte[] buf = new byte[8192]; int len;
                while((len = is.read(buf)) != -1){ fos.write(buf, 0, len); }
                fos.close(); is.close(); resp.close();
                FileInputStream fis = new FileInputStream(path);
                byte[] imgData = new byte[fis.available()];
                fis.read(imgData);
                fis.close();
                if(imgData.length == 0){
                    if(fallback != null) sendText(talker, fallback);
                    return;
                }
                WXImageObject imgObj = new WXImageObject();
                imgObj.setImageData(imgData);
                WXMediaMessage msg = new WXMediaMessage(imgObj);
                try{
                    Bitmap bmp = BitmapFactory.decodeByteArray(imgData, 0, imgData.length);
                    if(bmp != null){
                        int w = bmp.getWidth(), h = bmp.getHeight();
                        int tw, th;
                        if(w > h){ tw = 100; th = (h > 0) ? (100 * h / w) : 100; }
                        else{ th = 100; tw = (w > 0) ? (100 * w / h) : 100; }
                        if(tw < 1) tw = 1; if(th < 1) th = 1;
                        Bitmap thumb = Bitmap.createScaledBitmap(bmp, tw, th, true);
                        bmp.recycle();
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        thumb.compress(Bitmap.CompressFormat.JPEG, 75, baos);
                        thumb.recycle();
                        msg.thumbData = baos.toByteArray();
                        baos.close();
                    }
                }catch(Throwable tb){}
                sendMediaMsg(talker, msg, "wx485a97c844086dc9");
            }catch(Throwable t){
                if(fallback != null) sendText(talker, fallback);
            }finally{
                try{ new File(path).delete(); }catch(Throwable t){}
            }
        }
    }).start();
}

// ==================== 管理员配置 ====================
private String getGlobalAdminRaw(){
    if(USER_DATA.containsKey(GLOBAL_ADMIN_CFG_KEY)) return USER_DATA.get(GLOBAL_ADMIN_CFG_KEY);
    String raw = readFile("admin.cfg");
    if(raw != null && !raw.isEmpty()){ USER_DATA.put(GLOBAL_ADMIN_CFG_KEY, raw); return raw; }
    return null;
}
private void saveGlobalAdmin(String acc,String pwd,String ownerWxid){
    String val = "acc="+acc+"|pwd="+pwd+"|ownerWxid="+ownerWxid;
    USER_DATA.put(GLOBAL_ADMIN_CFG_KEY,val);
    writeFile("admin.cfg", val);
}
private Map<String,String> parseGlobalAdmin(){
    Map<String,String> ret = new HashMap<>();
    String raw = getGlobalAdminRaw();
    if(raw == null) return ret;
    String[] sp = raw.split("\\|");
    for(String s : sp){ String[] kv = s.split("=",2); if(kv.length ==2) ret.put(kv[0],kv[1]); }
    return ret;
}

// ==================== 授权用户系统 ====================
private void loadAuthorizedUsers(){
    if(authLoaded) return;
    AUTHORIZED_USERS.clear();
    String raw = readFile("authorized_users.dat");
    if(raw != null && !raw.isEmpty()){
        String[] pairs = raw.split("\\|");
        for(String p : pairs){
            String[] kv = p.split("=",2);
            if(kv.length == 2) AUTHORIZED_USERS.put(kv[0], kv[1]);
        }
    }
    authLoaded = true;
}
private void saveAuthorizedUsers(){
    StringBuilder sb = new StringBuilder();
    for(Map.Entry<String,String> e : AUTHORIZED_USERS.entrySet()){
        sb.append(e.getKey()).append("=").append(e.getValue()).append("|");
    }
    writeFile("authorized_users.dat", sb.toString());
}
private boolean isAuthorized(String wxid){
    loadAuthorizedUsers();
    return AUTHORIZED_USERS.containsKey(wxid);
}
private void addAuthorized(String wxid){
    loadAuthorizedUsers();
    AUTHORIZED_USERS.put(wxid, String.valueOf(System.currentTimeMillis()));
    saveAuthorizedUsers();
}
private void removeAuthorized(String wxid){
    loadAuthorizedUsers();
    AUTHORIZED_USERS.remove(wxid);
    saveAuthorizedUsers();
}
// 检查是否有管理员权限（已登录管理员 或 已授权用户）
private boolean hasAdminPermission(String wxid){
    return ADMIN_SESSION.containsKey(wxid) || isAuthorized(wxid);
}

// ==================== 聊天功能配置 ====================
private void loadChatConfig(){
    String raw = readFile("chat_config.dat");
    CHAT_ENABLED = "1".equals(raw);
    chatConfigLoaded = true;
}
private void saveChatConfig(boolean enabled){
    CHAT_ENABLED = enabled;
    writeFile("chat_config.dat", enabled ? "1" : "0");
    chatConfigLoaded = true;
}

// ==================== 群聊开关配置 ====================
private void loadGroupConfig(){
    String raw = readFile("group_config.dat");
    GROUP_CONFIG.clear();
    if(raw != null && !raw.isEmpty()){
        String[] pairs = raw.split("\\|");
        for(String p : pairs){
            String[] kv = p.split("=",2);
            if(kv.length == 2) GROUP_CONFIG.put(kv[0], kv[1]);
        }
    }
    groupConfigLoaded = true;
}
private void saveGroupConfig(){
    StringBuilder sb = new StringBuilder();
    for(Map.Entry<String,String> e : GROUP_CONFIG.entrySet()){
        sb.append(e.getKey()).append("=").append(e.getValue()).append("|");
    }
    writeFile("group_config.dat", sb.toString());
}
private boolean isGroupEnabled(String groupWxid){
    loadGroupConfig();
    return "1".equals(GROUP_CONFIG.get(groupWxid));
}
private void setGroupEnabled(String groupWxid, boolean enabled){
    loadGroupConfig();
    GROUP_CONFIG.put(groupWxid, enabled ? "1" : "0");
    saveGroupConfig();
}

// ==================== 全局公告 ====================
private void loadAnnouncement(){
    if(announcementLoaded) return;
    String raw = readFile("announcement.dat");
    GLOBAL_ANNOUNCEMENT = (raw != null) ? raw : "";
    announcementLoaded = true;
}
private void saveAnnouncement(String text){
    GLOBAL_ANNOUNCEMENT = text;
    writeFile("announcement.dat", text);
    announcementLoaded = true;
}

// ==================== 世界Boss状态 ====================
private void loadBossState(){
    if(bossStateLoaded) return;
    String raw = readFile("boss_state.dat");
    BOSS_STATE.clear();
    if(raw != null && !raw.isEmpty()){
        String[] pairs = raw.split("\\|");
        for(String p : pairs){
            String[] kv = p.split("=",2);
            if(kv.length == 2) BOSS_STATE.put(kv[0], kv[1]);
        }
    }
    bossStateLoaded = true;
}
private void saveBossState(){
    StringBuilder sb = new StringBuilder();
    for(Map.Entry<String,String> e : BOSS_STATE.entrySet()){
        sb.append(e.getKey()).append("=").append(e.getValue()).append("|");
    }
    writeFile("boss_state.dat", sb.toString());
}

// ==================== ID系统（5位唯一ID） ====================
private void loadIdMap(){
    if(idMapLoaded) return;
    String raw = readFile("id_map.dat");
    USER_ID_MAP.clear();
    if(raw != null && !raw.isEmpty()){
        String[] pairs = raw.split("\\|");
        for(String p : pairs){
            String[] kv = p.split("=",2);
            if(kv.length == 2) USER_ID_MAP.put(kv[0], kv[1]);
        }
    }
    String counterRaw = readFile("id_counter.dat");
    try{ ID_COUNTER = Integer.parseInt(counterRaw); }catch(Throwable t){ ID_COUNTER = 0; }
    idMapLoaded = true;
}
private void saveIdMap(){
    StringBuilder sb = new StringBuilder();
    for(Map.Entry<String,String> e : USER_ID_MAP.entrySet()){
        sb.append(e.getKey()).append("=").append(e.getValue()).append("|");
    }
    writeFile("id_map.dat", sb.toString());
    writeFile("id_counter.dat", String.valueOf(ID_COUNTER));
}
private String allocateId(String wxid){
    loadIdMap();
    for(Map.Entry<String,String> e : USER_ID_MAP.entrySet()){
        if(e.getValue().equals(wxid)) return e.getKey();
    }
    ID_COUNTER++;
    String newId = String.format("%05d", ID_COUNTER);
    USER_ID_MAP.put(newId, wxid);
    saveIdMap();
    return newId;
}
private String resolveTarget(String input){
    if(input == null || input.isEmpty()) return null;
    loadIdMap();
    input = input.trim();
    if(input.startsWith("wxid:")) input = input.substring(5).trim();
    if(input.matches("\\d{5}")){
        String wxid = USER_ID_MAP.get(input);
        if(wxid != null) return wxid;
        // 修复1：ID映射表可能未正确加载，遍历USER_DATA按userId字段查找
        for(Map.Entry<String,String> e : USER_DATA.entrySet()){
            if(GLOBAL_ADMIN_CFG_KEY.equals(e.getKey())) continue;
            Map<String,String> ud = parseUserData(e.getValue());
            if(input.equals(ud.get("userId"))) return e.getKey();
        }
        // 修复2：内存中也没有时，遍历用户数据文件查找（彻底解决找不到用户bug）
        try{
            File dir = new File(DATA_DIR);
            if(dir.exists()){
                File[] files = dir.listFiles();
                if(files != null){
                    for(File f : files){
                        String name = f.getName();
                        if(name.startsWith("user_") && name.endsWith(".dat")){
                            String fileWxid = name.substring(5, name.length()-4);
                            String raw = readFile(name);
                            if(raw != null && !raw.isEmpty()){
                                Map<String,String> ud = parseUserData(raw);
                                if(input.equals(ud.get("userId"))){
                                    // 找到后加载到内存缓存
                                    USER_DATA.put(fileWxid, raw);
                                    return fileWxid;
                                }
                            }
                        }
                    }
                }
            }
        }catch(Throwable t){}
    }
    if(input.startsWith("wxid_")) return input;
    for(Map.Entry<String,String> e : USER_DATA.entrySet()){
        if(GLOBAL_ADMIN_CFG_KEY.equals(e.getKey())) continue;
        Map<String,String> ud = parseUserData(e.getValue());
        if(input.equals(ud.get("nick"))) return e.getKey();
    }
    // 按昵称查找时也遍历文件
    try{
        File dir = new File(DATA_DIR);
        if(dir.exists()){
            File[] files = dir.listFiles();
            if(files != null){
                for(File f : files){
                    String name = f.getName();
                    if(name.startsWith("user_") && name.endsWith(".dat")){
                        String fileWxid = name.substring(5, name.length()-4);
                        String raw = readFile(name);
                        if(raw != null && !raw.isEmpty()){
                            Map<String,String> ud = parseUserData(raw);
                            if(input.equals(ud.get("nick"))){
                                USER_DATA.put(fileWxid, raw);
                                return fileWxid;
                            }
                        }
                    }
                }
            }
        }
    }catch(Throwable t){}
    return null;
}

// ==================== 北京时间日期工具（修复UTC时区导致凌晨不刷新bug） ====================
private static final java.util.TimeZone BEIJING_TZ = java.util.TimeZone.getTimeZone("Asia/Shanghai");
private String getBeijingDateStr(long ts){
    java.util.Calendar cal = java.util.Calendar.getInstance(BEIJING_TZ);
    cal.setTimeInMillis(ts);
    return String.format("%04d%02d%02d", cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH)+1, cal.get(java.util.Calendar.DAY_OF_MONTH));
}
private String getBeijingDateStr(){
    return getBeijingDateStr(System.currentTimeMillis());
}
// 计算两个时间戳在北京时间下的天数差（ts2 - ts1，按天归零后计算）
private int getBeijingDayDiff(long ts1, long ts2){
    java.util.Calendar c1 = java.util.Calendar.getInstance(BEIJING_TZ);
    c1.setTimeInMillis(ts1);
    c1.set(java.util.Calendar.HOUR_OF_DAY, 0);
    c1.set(java.util.Calendar.MINUTE, 0);
    c1.set(java.util.Calendar.SECOND, 0);
    c1.set(java.util.Calendar.MILLISECOND, 0);
    java.util.Calendar c2 = java.util.Calendar.getInstance(BEIJING_TZ);
    c2.setTimeInMillis(ts2);
    c2.set(java.util.Calendar.HOUR_OF_DAY, 0);
    c2.set(java.util.Calendar.MINUTE, 0);
    c2.set(java.util.Calendar.SECOND, 0);
    c2.set(java.util.Calendar.MILLISECOND, 0);
    return (int)((c2.getTimeInMillis() - c1.getTimeInMillis()) / (24L*60*60*1000));
}
// 获取北京时间当天的"天数编号"（用于替代 now/dMs 的UTC天数）
private int getBeijingDayNumber(long ts){
    java.util.Calendar cal = java.util.Calendar.getInstance(BEIJING_TZ);
    cal.setTimeInMillis(ts);
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
    cal.set(java.util.Calendar.MINUTE, 0);
    cal.set(java.util.Calendar.SECOND, 0);
    cal.set(java.util.Calendar.MILLISECOND, 0);
    return (int)(cal.getTimeInMillis() / (24L*60*60*1000));
}
private int getBeijingDayNumber(){
    return getBeijingDayNumber(System.currentTimeMillis());
}

// ==================== 定时任务 ====================
private void loadScheduledTasks(){
    if(schedulerLoaded) return;
    SCHEDULED_TASKS.clear();
    String raw = readFile("scheduled_tasks.dat");
    if(raw != null && !raw.isEmpty()){
        String[] lines = raw.split("\\n");
        for(String line : lines){
            if(line == null || line.isEmpty()) continue;
            String[] parts = line.split("\\|", 5);
            if(parts.length >= 5){
                try{ parts[2] = java.net.URLDecoder.decode(parts[2], "UTF-8"); }catch(Throwable t){}
                SCHEDULED_TASKS.add(parts);
            }
        }
    }
    schedulerLoaded = true;
}
private void saveScheduledTasks(){
    StringBuilder sb = new StringBuilder();
    for(String[] task : SCHEDULED_TASKS){
        String encodedContent = task[2];
        try{ encodedContent = URLEncoder.encode(task[2], "UTF-8"); }catch(Throwable t){}
        sb.append(task[0]).append("|").append(task[1]).append("|").append(encodedContent).append("|").append(task[3]).append("|").append(task[4]).append("\n");
    }
    writeFile("scheduled_tasks.dat", sb.toString());
}
private void checkScheduledTasks(){
    loadScheduledTasks();
    if(SCHEDULED_TASKS.isEmpty()) return;
    long now = System.currentTimeMillis();
    if(now - lastScheduleCheck < 20*1000) return;
    lastScheduleCheck = now;
    Calendar cal = Calendar.getInstance(BEIJING_TZ);
    String currentTime = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
    if(currentTime.equals(lastScheduleMinute)) return;
    lastScheduleMinute = currentTime;
    for(String[] task : SCHEDULED_TASKS){
        if(task[1].equals(currentTime)){
            try{ asyncSend(task[4], "⏰定时提醒\n\n" + task[2]); }catch(Throwable t){}
        }
    }
}

// ==================== 一键清除所有数据 ====================
private void clearAllGameData(){
    try{
        File dir = new File(DATA_DIR);
        if(dir.exists()){
            File[] files = dir.listFiles();
            if(files != null){
                for(File f : files){
                    try{ f.delete(); }catch(Throwable t){}
                }
            }
        }
    }catch(Throwable t){}
    USER_DATA.clear();
    ADMIN_SESSION.clear();
    GROUP_CONFIG.clear();
    GLOBAL_ANNOUNCEMENT = "";
    BOSS_STATE.clear();
    USER_ID_MAP.clear();
    ID_COUNTER = 0;
    SCHEDULED_TASKS.clear();
    AUTHORIZED_USERS.clear();
    CHAT_ENABLED = false;
    dataLoaded = false;
    groupConfigLoaded = false;
    announcementLoaded = false;
    bossStateLoaded = false;
    idMapLoaded = false;
    schedulerLoaded = false;
    authLoaded = false;
    chatConfigLoaded = false;
    lastScheduleCheck = 0;
    lastScheduleMinute = "";
}

// ==================== 节日活动 ====================
private String getFestivalName(int month, int day){
    if(month==1 && day==1) return "元旦节";
    if(month==2 && day==14) return "情人节";
    if(month==3 && day==8) return "妇女节";
    if(month==5 && day==1) return "劳动节";
    if(month==6 && day==1) return "儿童节";
    if(month==7 && day==1) return "建党节";
    if(month==8 && day==1) return "建军节";
    if(month==9 && day==10) return "教师节";
    if(month==10 && day==1) return "国庆节";
    if(month==10 && day==31) return "万圣节";
    if(month==11 && day==11) return "光棍节";
    if(month==12 && day==24) return "平安夜";
    if(month==12 && day==25) return "圣诞节";
    return null;
}
private boolean isFestivalDoubleExp(){
    Calendar cal = Calendar.getInstance(BEIJING_TZ);
    String festival = getFestivalName(cal.get(Calendar.MONTH)+1, cal.get(Calendar.DAY_OF_MONTH));
    return festival != null;
}
private String getFestivalAnnouncement(){
    Calendar cal = Calendar.getInstance(BEIJING_TZ);
    int month = cal.get(Calendar.MONTH)+1;
    int day = cal.get(Calendar.DAY_OF_MONTH);
    int year = cal.get(Calendar.YEAR);
    String festival = getFestivalName(month, day);
    if(festival != null){
        return "📢公告：今日为" + year + "年" + month + "月" + day + "日 " + festival + " 活动期间打怪增加双倍经验！";
    }
    return null;
}

// ==================== 用户数据 ====================
private Map<String,String> parseUserData(String raw){
    Map<String,String> map = new HashMap<>();
    if(raw == null || raw.isEmpty()) return map;
    String[] kv = raw.split("\\|");
    for(String item : kv){ String[] pair = item.split("=",2); if(pair.length ==2) map.put(pair[0],pair[1]); }
    return map;
}
private String serializeUserData(Map<String,String> map){
    StringBuilder sb = new StringBuilder();
    for(Map.Entry<String,String> e : map.entrySet()){ sb.append(e.getKey()).append("=").append(e.getValue()).append("|"); }
    return sb.toString();
}
private String[] upgradePetData(String petStr){
    String[] p = petStr.split(",");
    if(p.length >= 8) return p;
    String[] result = new String[8];
    for(int i=0;i<8;i++){
        if(i < p.length) result[i] = p[i];
        else if(i==5) result[i] = String.valueOf(System.currentTimeMillis());
        else if(i==6 || i==7) result[i] = "0";
        else result[i] = "0";
    }
    return result;
}
private Map<String,String> getDefaultUser(){
    Map<String,String> res = new HashMap<>();
    res.put("nick","未设置昵称");
    res.put("gender","保密");
    res.put("age","0");
    res.put("sign","这个人很神秘，什么都没有写");
    res.put("gold","100");
    res.put("exp","0");
    res.put("level","1");
    res.put("lastSign","0");
    res.put("signDays","0");
    res.put("fishCount","0");
    res.put("fishDay","0");
    res.put("battleTimes","0");
    res.put("battleTs","0");
    res.put("petBag","");
    res.put("battlePetIdx","-1");
    res.put("items","ball1=2,ball2=0,ball3=0,flower=0,expPotion=0,goldBag=0,revive=0,lucky=0,doubleGoldCard=0,doubleExpCard=0,");
    res.put("pendingPet","");
    res.put("catchCtx","");
    res.put("dailyTaskTs","0");
    res.put("dailyTaskDone","fish=0,battle=0,sign=0,work=0,rich=0,box=0,feed=0,");
    res.put("dailyRewardGet","0");
    res.put("workTs","0");
    res.put("pkTs","0");
    res.put("luckyBuff","0");
    res.put("intimacy","");
    res.put("musicEnable","1");
    res.put("dafuPos","0");
    res.put("dafuTs","0");
    res.put("dafuCount","0");
    res.put("mysteryBoxTs","0");
    res.put("mysteryBoxCount","0");
    res.put("fortuneDay","");
    res.put("fortuneCount","0");
    res.put("boxDay","");
    res.put("boxCount","0");
    res.put("doubleGoldTs","0");
    res.put("doubleExpTs","0");
    res.put("friends","");
    res.put("banned","0");
    res.put("pkTargetTs","{}");
    res.put("userId","");
    return res;
}
private Map<String,String> getUser(String wxid){
    try{
    Map<String,String> res;
    if(USER_DATA.containsKey(wxid)){
        res = parseUserData(USER_DATA.get(wxid));
    }else{
        String fileRaw = readFile("user_" + wxid + ".dat");
        if(fileRaw != null && !fileRaw.isEmpty()){
            USER_DATA.put(wxid, fileRaw);
            res = parseUserData(fileRaw);
        }else{
            res = getDefaultUser();
        }
    }
    if(res.get("nick")==null) res.put("nick","未设置昵称");
    if(res.get("gender")==null) res.put("gender","保密");
    if(res.get("age")==null) res.put("age","0");
    if(res.get("sign")==null) res.put("sign","这个人很神秘，什么都没有写");
    if(res.get("gold")==null) res.put("gold","100");
    if(res.get("exp")==null) res.put("exp","0");
    if(res.get("level")==null) res.put("level","1");
    if(res.get("lastSign")==null) res.put("lastSign","0");
    if(res.get("petBag")==null) res.put("petBag","");
    if(res.get("battlePetIdx")==null) res.put("battlePetIdx","-1");
    if(res.get("pendingPet")==null) res.put("pendingPet","");
    if(res.get("catchCtx")==null) res.put("catchCtx","");
    if(res.get("dailyTaskTs")==null) res.put("dailyTaskTs","0");
    if(res.get("dailyRewardGet")==null) res.put("dailyRewardGet","0");
    if(res.get("intimacy")==null) res.put("intimacy","");
    if(res.get("musicEnable")==null) res.put("musicEnable","1");
    if(res.get("dafuPos")==null) res.put("dafuPos","0");
    if(res.get("dafuTs")==null) res.put("dafuTs","0");
    if(res.get("dafuCount")==null) res.put("dafuCount","0");
    if(res.get("signDays")==null) res.put("signDays","0");
    if(res.get("workTs")==null) res.put("workTs","0");
    if(res.get("pkTs")==null) res.put("pkTs","0");
    if(res.get("luckyBuff")==null) res.put("luckyBuff","0");
    if(res.get("mysteryBoxTs")==null) res.put("mysteryBoxTs","0");
    if(res.get("mysteryBoxCount")==null) res.put("mysteryBoxCount","0");
    if(res.get("fortuneDay")==null) res.put("fortuneDay","");
    if(res.get("fortuneCount")==null) res.put("fortuneCount","0");
    if(res.get("boxDay")==null) res.put("boxDay","");
    if(res.get("boxCount")==null) res.put("boxCount","0");
    if(res.get("doubleGoldTs")==null) res.put("doubleGoldTs","0");
    if(res.get("doubleExpTs")==null) res.put("doubleExpTs","0");
    if(res.get("friends")==null) res.put("friends","");
    if(res.get("banned")==null) res.put("banned","0");
    if(res.get("pkTargetTs")==null) res.put("pkTargetTs","{}");
    if(res.get("fishCount")==null) res.put("fishCount","0");
    if(res.get("fishDay")==null) res.put("fishDay","0");
    if(res.get("battleTimes")==null) res.put("battleTimes","0");
    if(res.get("battleTs")==null) res.put("battleTs","0");
    String items = res.get("items");
    if(items == null) items = "";
    if(!items.contains("doubleGoldCard")){
        if(!items.endsWith(",") && !items.isEmpty()) items = items + ",";
        res.put("items", items + "doubleGoldCard=0,doubleExpCard=0,");
    }
    if(res.get("dailyTaskDone")==null) res.put("dailyTaskDone","fish=0,battle=0,sign=0,work=0,rich=0,box=0,feed=0,");
    else if(!res.get("dailyTaskDone").contains("feed")){
        String d = res.get("dailyTaskDone");
        if(!d.endsWith(",")) d = d + ",";
        res.put("dailyTaskDone", d + "feed=0,");
    }
    String[] numericFields = {"gold","exp","level","age","lastSign","signDays","fishCount","fishDay",
        "battleTimes","battleTs","battlePetIdx","dailyTaskTs","dailyRewardGet","workTs","pkTs",
        "luckyBuff","dafuPos","dafuTs","dafuCount","mysteryBoxTs","mysteryBoxCount","doubleGoldTs","doubleExpTs","banned"};
    for(String field : numericFields){
        String val = res.get(field);
        if(val == null || val.isEmpty()){
            res.put(field, "0");
        }
    }
    if(res.get("userId") == null || res.get("userId").isEmpty()){
        try{
            String uid = allocateId(wxid);
            res.put("userId", uid);
            String dataStr = serializeUserData(res);
            USER_DATA.put(wxid, dataStr);
            writeFile("user_" + wxid + ".dat", dataStr);
        }catch(Throwable t){}
    }
    return res;
    }catch(Throwable t){
        return getDefaultUser();
    }
}
private void saveUser(String wxid, Map<String,String> u){
    String dataStr = serializeUserData(u);
    USER_DATA.put(wxid, dataStr);
    writeFile("user_" + wxid + ".dat", dataStr);
}
private void asyncSend(final String talker, final String text){
    new Thread(new Runnable(){ public void run(){ sendText(talker, text); } }).start();
}
private boolean isDoubleGoldActive(Map<String,String> user){
    long ts = Long.parseLong(user.get("doubleGoldTs"));
    return ts > System.currentTimeMillis();
}
private boolean isDoubleExpActive(Map<String,String> user){
    long ts = Long.parseLong(user.get("doubleExpTs"));
    return ts > System.currentTimeMillis() || isFestivalDoubleExp();
}
private boolean isBankruptProtected(Map<String,String> user){
    int gold = Integer.parseInt(user.get("gold"));
    return gold <= 0;
}

// ==================== 头衔系统 ====================
private String getTitleByLevel(int level){
    if(level >= 200) return "[创世神]";
    if(level >= 151) return "[真神降世]";
    if(level >= 121) return "[半神之躯]";
    if(level >= 91) return "[宗师传说]";
    if(level >= 71) return "[大师王者]";
    if(level >= 51) return "[钻石领主]";
    if(level >= 36) return "[铂金骑士]";
    if(level >= 21) return "[黄金战士]";
    if(level >= 11) return "[白银猎手]";
    return "[青铜小手]";
}

// ==================== @用户解析 ====================
private String getAtWxid(Object msgInfo, String content){
    try{
        String[] mns = {"getAtList","atList","getAtTalker","atTalker"};
        for(String mn : mns){
            try{
                Method m = msgInfo.getClass().getMethod(mn);
                Object result = m.invoke(msgInfo);
                if(result != null){
                    String s = result.toString().replace("[","").replace("]","").trim();
                    if(!s.isEmpty() && !"null".equals(s)){
                        String[] parts = s.split(",");
                        for(String p : parts){ p = p.trim(); if(!p.isEmpty()) return p; }
                    }
                }
            }catch(Throwable t2){}
        }
    }catch(Throwable t){}
    int atIdx = content.indexOf("@");
    if(atIdx >= 0){
        String after = content.substring(atIdx+1).trim();
        String nick = after.split(" ")[0].trim();
        if(!nick.isEmpty()){
            for(Map.Entry<String,String> e : USER_DATA.entrySet()){
                if(GLOBAL_ADMIN_CFG_KEY.equals(e.getKey())) continue;
                Map<String,String> ud = parseUserData(e.getValue());
                if(nick.equals(ud.get("nick"))) return e.getKey();
            }
        }
    }
    return null;
}

// ==================== 每日任务 ====================
private void incTask(Map<String,String> user, String key){
    try{
        String done = user.get("dailyTaskDone");
        if(done == null || done.isEmpty()) done = "fish=0,battle=0,sign=0,work=0,rich=0,box=0,feed=0,";
        String[] pairs = done.split(",");
        StringBuilder sb = new StringBuilder();
        for(String p : pairs){
            if(p.isEmpty()) continue;
            String[] kv = p.split("=");
            if(kv.length==2 && kv[0].equals(key)){ sb.append(kv[0]).append("=").append(Integer.parseInt(kv[1])+1); }
            else{ sb.append(p); }
            sb.append(",");
        }
        user.put("dailyTaskDone", sb.toString());
    }catch(Throwable t){}
}
private String[] getTodayTasks(long nowMs){
    long daySeed = getBeijingDayNumber(nowMs);
    String[] pool = {"fish","battle","sign","work","rich","box","feed"};
    int i1 = (int)(daySeed % 7);
    int i2 = (int)((daySeed / 7 + i1 + 1) % 7);
    if(i2 == i1) i2 = (i2+1)%7;
    int i3 = (int)((daySeed / 49 + i2 + 2) % 7);
    while(i3 == i1 || i3 == i2) i3 = (i3+1)%7;
    return new String[]{pool[i1], pool[i2], pool[i3]};
}
private String taskName(String key){
    if("fish".equals(key)) return "钓鱼2次";
    if("battle".equals(key)) return "打怪2次";
    if("sign".equals(key)) return "签到1次";
    if("work".equals(key)) return "打工1次";
    if("rich".equals(key)) return "大富翁1次";
    if("box".equals(key)) return "开宝箱1次";
    if("feed".equals(key)) return "喂食宠物1次";
    return key;
}
private int taskTarget(String key){
    if("fish".equals(key)) return 2;
    if("battle".equals(key)) return 2;
    return 1;
}
private int getTaskCount(Map<String,String> user, String key){
    try{
        String done = user.get("dailyTaskDone");
        if(done == null) return 0;
        String[] pairs = done.split(",");
        for(String p : pairs){ String[] kv = p.split("="); if(kv.length==2 && kv[0].equals(key)) return Integer.parseInt(kv[1]); }
    }catch(Throwable t){}
    return 0;
}

// ==================== 排行榜 ====================
private boolean isZombieUser(Map<String,String> ud){
    String nick = ud.get("nick");
    String gold = ud.get("gold");
    String level = ud.get("level");
    String petBag = ud.get("petBag");
    if(nick == null || nick.equals("未设置昵称")){
        if((gold == null || gold.equals("100")) && (level == null || level.equals("1")) && (petBag == null || petBag.isEmpty())){
            return true;
        }
    }
    return false;
}
private ArrayList<String> buildRankList(){
    ArrayList<String> list = new ArrayList<>();
    for(Map.Entry<String,String> entry : USER_DATA.entrySet()){
        if(GLOBAL_ADMIN_CFG_KEY.equals(entry.getKey())) continue;
        Map<String,String> ud = parseUserData(entry.getValue());
        if(isZombieUser(ud)) continue;
        list.add(entry.getKey()+"|"+ud.get("nick")+"|"+ud.get("gold")+"|"+ud.get("level"));
    }
    return list;
}

// ==================== 野外随机事件 ====================
private String triggerRandomEvent(String talker, Map<String,String> user, String senderWxid){
    double r = Math.random();
    if(r < 0.15){
        String[] rareItems = {"高级球","双倍金币卡","双倍经验卡","复活药水"};
        String item = rareItems[(int)(Math.random()*rareItems.length)];
        int price = 0;
        if("高级球".equals(item)) price = 120;
        else if("双倍金币卡".equals(item)) price = 100;
        else if("双倍经验卡".equals(item)) price = 100;
        else if("复活药水".equals(item)) price = 30;
        int gold = Integer.parseInt(user.get("gold"));
        if(gold >= price){
            gold -= price;
            user.put("gold", String.valueOf(gold));
            String key = "";
            if("高级球".equals(item)) key = "ball3";
            else if("双倍金币卡".equals(item)) key = "doubleGoldCard";
            else if("双倍经验卡".equals(item)) key = "doubleExpCard";
            else if("复活药水".equals(item)) key = "revive";
            String[] its = user.get("items").split(",");
            StringBuilder sb = new StringBuilder();
            for(String it : its){
                if(it.isEmpty()) continue;
                String[] kv = it.split("=");
                if(kv.length==2 && kv[0].equals(key)){ sb.append(kv[0]).append("=").append(Integer.parseInt(kv[1])+1).append(","); }
                else{ sb.append(it).append(","); }
            }
            user.put("items", sb.toString());
            saveUser(senderWxid, user);
            return "\n\n🛒偶遇神秘商人！低价抢购了【"+item+"】（原价"+(price*2)+"金，现价"+price+"金）";
        }
        return "\n\n🛒偶遇神秘商人，但金币不足，错过了【"+item+"】（特价"+price+"金）";
    }else if(r < 0.30){
        int gain = 30 + (int)(Math.random()*70);
        int gold = Integer.parseInt(user.get("gold")) + gain;
        user.put("gold", String.valueOf(gold));
        saveUser(senderWxid, user);
        return "\n\n🎁路边捡到一个宝箱！获得"+gain+"金币";
    }else if(r < 0.40){
        int gold = Integer.parseInt(user.get("gold"));
        if(gold > 0){
            int lose = 5 + (int)(Math.random()*15);
            if(lose > gold) lose = gold;
            gold -= lose;
            user.put("gold", String.valueOf(gold));
            saveUser(senderWxid, user);
            return "\n\n🦹遭遇小偷！被偷走"+lose+"金币";
        }
    }
    return "";
}

// ==================== HTTP工具 ====================
private void addHeaders(Request.Builder builder, Map header){
    if(header != null){ for(Object key : header.keySet()){ builder.addHeader((String)key,(String)header.get(key)); } }
}
private String executeRequest(Request.Builder builder){
    try{ Response response = client.newCall(builder.build()).execute(); return response.body().string(); }
    catch(IOException e){ return null; }
}
public String get(String url, Map header){
    Request.Builder builder = new Request.Builder().url(url).get();
    addHeaders(builder, header);
    return executeRequest(builder);
}

// ==================== 音乐 ====================
private void sendProcessedMusicMsg(String talker, String title, String singer, String url, String lyric, String pic){
    WXMusicObject music = new WXMusicObject();
    music.musicDataUrl = url;
    if(lyric == null || lyric.isEmpty()) lyric = "[99:99.99]暂无歌词";
    music.songLyric = lyric;
    music.songAlbumUrl = pic;
    WXMediaMessage media = new WXMediaMessage(music);
    media.title = title;
    media.description = singer;
    sendMediaMsg(talker, media, "wx485a97c844086dc9");
}
public void GetNeteaseMusic(final String talker, final String song){
    try{
        String searchUrl = "https://api.qijieya.cn/meting/?type=search&server=netease&limit=1&id=" + URLEncoder.encode(song, "UTF-8");
        String searchResponse = get(searchUrl, null);
        if(searchResponse == null || searchResponse.isEmpty()){ sendText(talker, "网易云接口请求失败"); return; }
        JSONArray searchResult = JSON.parseArray(searchResponse);
        if(searchResult == null || searchResult.size() == 0){ sendText(talker, "未搜到歌曲: " + song); return; }
        JSONObject musicDetail = searchResult.getJSONObject(0);
        String name = musicDetail.getString("name"), singer = musicDetail.getString("artist");
        String pic = musicDetail.getString("pic"), url = musicDetail.getString("url"), lrcUrl = musicDetail.getString("lrc");
        String lyric = "";
        if(lrcUrl != null && !lrcUrl.isEmpty()) lyric = get(lrcUrl, null);
        sendProcessedMusicMsg(talker, name, singer, url, lyric, pic);
    }catch(Exception e){ sendText(talker, "点歌出错"); }
}
public void GetHitokoto(final String talker){
    try{
        String resp = get("https://v1.hitokoto.cn/", null);
        if(resp == null || resp.isEmpty()){ sendText(talker, "📜语录接口请求失败，请稍后再试"); return; }
        JSONObject obj = JSON.parseObject(resp);
        if(obj == null){ sendText(talker, "📜语录接口返回异常"); return; }
        String text = obj.getString("hitokoto"), from = obj.getString("from");
        if(text == null || text.isEmpty()){ sendText(talker, "📜暂无语录"); return; }
        String result = "📜热门语录\n\n" + text;
        if(from != null && !from.isEmpty()) result += "\n\n——《" + from + "》";
        sendText(talker, result);
    }catch(Exception e){ sendText(talker, "📜获取语录出错"); }
}

// ==================== 60秒新闻（对接能用的每日60秒新闻脚本，用框架原生sendImage发图） ====================
public void sendToday(final String talker){
    try{
        String[] apis = {
            "https://60s.viki.moe/v2/60s?encoding=json",
            "https://api.cczo.cc/60s/v2/60s?encoding=json",
            "https://60s.zellon.top/v2/60s?encoding=json",
            "https://60s.superjeason.qzz.io/v2/60s?encoding=json"
        };
        String imageUrl = null;
        for(String api : apis){
            try{
                String resp = get(api, null);
                if(resp == null || resp.isEmpty()) continue;
                JSONObject obj = JSON.parseObject(resp);
                if(obj == null) continue;
                if(obj.containsKey("code") && obj.getIntValue("code") != 200) continue;
                JSONObject data = obj.containsKey("data") ? obj.getJSONObject("data") : obj;
                if(data == null) continue;
                imageUrl = data.getString("image");
                if(imageUrl != null && !imageUrl.isEmpty()) break;
            }catch(Throwable t){}
        }
        if(imageUrl == null || imageUrl.isEmpty()){ sendText(talker, "📰新闻接口请求失败，请稍后再试"); return; }
        // 下载图片到本地
        final String cachePath = DATA_DIR + "hot60s-" + Math.abs(imageUrl.hashCode()) + ".png";
        Request req = new Request.Builder().url(imageUrl).build();
        Response resp = client.newCall(req).execute();
        if(resp == null || !resp.isSuccessful()){ sendText(talker, "📰新闻图片下载失败"); return; }
        InputStream is = resp.body().byteStream();
        FileOutputStream fos = new FileOutputStream(cachePath);
        byte[] buf = new byte[8192]; int len;
        while((len = is.read(buf)) != -1){ fos.write(buf, 0, len); }
        fos.close(); is.close(); resp.close();
        // 用框架原生的两参数 sendImage(talker, 本地路径) 发送图片（和能用的每日60秒新闻脚本完全一样）
        sendImage(talker, cachePath);
        // 延迟3秒后删除临时文件
        new Thread(new Runnable(){ public void run(){
            try{ Thread.sleep(3000); }catch(InterruptedException e){}
            try{ new File(cachePath).delete(); }catch(Throwable t){}
        }}).start();
    }catch(Throwable t){ sendText(talker, "📰获取新闻出错"); }
}

// ==================== 天气查询系统 ====================
private void loadQuotesFromFile(){
    if(quotesLoaded) return;
    try{
        String filePath = DATA_DIR + "quotes.txt";
        java.io.File file = new java.io.File(filePath);
        if(file.exists()){
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(file), "UTF-8")
            );
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) quoteList.add(line);
            }
            reader.close();
        }
    } catch (Exception e) {}
    quotesLoaded = true;
}
private String getRandomQuote(){
    loadQuotesFromFile();
    if(quoteList.isEmpty()) return "";
    int index = (int)(Math.random() * quoteList.size());
    return quoteList.get(index);
}
private static final Map<String, String> WEATHER_CITY_CN_MAP = new HashMap<>();
static {
    WEATHER_CITY_CN_MAP.put("beijing", "北京"); WEATHER_CITY_CN_MAP.put("shanghai", "上海");
    WEATHER_CITY_CN_MAP.put("guangzhou", "广州"); WEATHER_CITY_CN_MAP.put("shenzhen", "深圳");
    WEATHER_CITY_CN_MAP.put("chengdu", "成都"); WEATHER_CITY_CN_MAP.put("hangzhou", "杭州");
    WEATHER_CITY_CN_MAP.put("nanjing", "南京"); WEATHER_CITY_CN_MAP.put("wuhan", "武汉");
    WEATHER_CITY_CN_MAP.put("chongqing", "重庆"); WEATHER_CITY_CN_MAP.put("tianjin", "天津");
    WEATHER_CITY_CN_MAP.put("suzhou", "苏州"); WEATHER_CITY_CN_MAP.put("xian", "西安");
    WEATHER_CITY_CN_MAP.put("changsha", "长沙"); WEATHER_CITY_CN_MAP.put("zhengzhou", "郑州");
    WEATHER_CITY_CN_MAP.put("qingdao", "青岛"); WEATHER_CITY_CN_MAP.put("dalian", "大连");
    WEATHER_CITY_CN_MAP.put("kunming", "昆明"); WEATHER_CITY_CN_MAP.put("xiamen", "厦门");
    WEATHER_CITY_CN_MAP.put("fuzhou", "福州"); WEATHER_CITY_CN_MAP.put("hefei", "合肥");
    WEATHER_CITY_CN_MAP.put("nanchang", "南昌"); WEATHER_CITY_CN_MAP.put("guiyang", "贵阳");
    WEATHER_CITY_CN_MAP.put("lanzhou", "兰州"); WEATHER_CITY_CN_MAP.put("harbin", "哈尔滨");
    WEATHER_CITY_CN_MAP.put("changchun", "长春"); WEATHER_CITY_CN_MAP.put("shenyang", "沈阳");
    WEATHER_CITY_CN_MAP.put("jinan", "济南"); WEATHER_CITY_CN_MAP.put("taipei", "台北");
    WEATHER_CITY_CN_MAP.put("hongkong", "香港"); WEATHER_CITY_CN_MAP.put("macau", "澳门");
    WEATHER_CITY_CN_MAP.put("tokyo", "东京"); WEATHER_CITY_CN_MAP.put("seoul", "首尔");
    WEATHER_CITY_CN_MAP.put("london", "伦敦"); WEATHER_CITY_CN_MAP.put("paris", "巴黎");
    WEATHER_CITY_CN_MAP.put("newyork", "纽约"); WEATHER_CITY_CN_MAP.put("losangeles", "洛杉矶");
    WEATHER_CITY_CN_MAP.put("sydney", "悉尼"); WEATHER_CITY_CN_MAP.put("singapore", "新加坡");
    WEATHER_CITY_CN_MAP.put("bangkok", "曼谷"); WEATHER_CITY_CN_MAP.put("moscow", "莫斯科");
}
private static final Map<Integer, String[]> WEATHER_WMO_INFO = new HashMap<>();
static {
    WEATHER_WMO_INFO.put(0,  new String[]{"晴", "☀️"});
    WEATHER_WMO_INFO.put(1,  new String[]{"大部晴", "🌤️"});
    WEATHER_WMO_INFO.put(2,  new String[]{"多云", "⛅"});
    WEATHER_WMO_INFO.put(3,  new String[]{"阴", "☁️"});
    WEATHER_WMO_INFO.put(45, new String[]{"雾", "🌫️"});
    WEATHER_WMO_INFO.put(48, new String[]{"雾凇", "🌫️"});
    WEATHER_WMO_INFO.put(51, new String[]{"小毛毛雨", "🌦️"});
    WEATHER_WMO_INFO.put(53, new String[]{"中毛毛雨", "🌦️"});
    WEATHER_WMO_INFO.put(55, new String[]{"大毛毛雨", "🌧️"});
    WEATHER_WMO_INFO.put(56, new String[]{"小冻毛毛雨", "🌧️"});
    WEATHER_WMO_INFO.put(57, new String[]{"大冻毛毛雨", "🌧️"});
    WEATHER_WMO_INFO.put(61, new String[]{"小雨", "🌦️"});
    WEATHER_WMO_INFO.put(63, new String[]{"中雨", "🌧️"});
    WEATHER_WMO_INFO.put(65, new String[]{"大雨", "🌧️"});
    WEATHER_WMO_INFO.put(66, new String[]{"小冻雨", "🌧️"});
    WEATHER_WMO_INFO.put(67, new String[]{"大冻雨", "🌧️"});
    WEATHER_WMO_INFO.put(71, new String[]{"小雪", "❄️"});
    WEATHER_WMO_INFO.put(73, new String[]{"中雪", "❄️"});
    WEATHER_WMO_INFO.put(75, new String[]{"大雪", "❄️"});
    WEATHER_WMO_INFO.put(77, new String[]{"雪粒", "❄️"});
    WEATHER_WMO_INFO.put(80, new String[]{"小阵雨", "🌦️"});
    WEATHER_WMO_INFO.put(81, new String[]{"中阵雨", "🌧️"});
    WEATHER_WMO_INFO.put(82, new String[]{"大阵雨", "🌧️"});
    WEATHER_WMO_INFO.put(85, new String[]{"小阵雪", "❄️"});
    WEATHER_WMO_INFO.put(86, new String[]{"大阵雪", "❄️"});
    WEATHER_WMO_INFO.put(95, new String[]{"雷暴", "⛈️"});
    WEATHER_WMO_INFO.put(96, new String[]{"雷暴伴小冰雹", "⛈️"});
    WEATHER_WMO_INFO.put(99, new String[]{"雷暴伴大冰雹", "⛈️"});
}
private static final String[] WEATHER_WIND_NAMES = {"北风","北东北风","东北风","东东北风","东风","东东南风","东南风","南东南风",
                                     "南风","南西南风","西南风","西西南风","西风","西西北风","西北风","北西北风"};
private static final String[] WEATHER_WIND_ARROWS = {
    "↑", "↗", "↗", "→", "→", "↘", "↘", "↓",
    "↓", "↙", "↙", "←", "←", "↖", "↖", "↑"
};
private static String weatherWindDirWithArrow(double degrees) {
    int index = (int) Math.round(degrees / 22.5) % 16;
    return WEATHER_WIND_NAMES[index] + WEATHER_WIND_ARROWS[index];
}
private static final String[] WEATHER_WEEK_NAMES = {"星期日","星期一","星期二","星期三","星期四","星期五","星期六"};
private String fetchWeatherByCity(String city) {
    try {
        double[] coords = weatherGeocode(city);
        if (coords == null) return null;
        double lat = coords[0], lon = coords[1];
        String apiUrl = "https://api.open-meteo.com/v1/forecast?" +
                        "latitude=" + lat + "&longitude=" + lon +
                        "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m" +
                        "&daily=temperature_2m_max,temperature_2m_min" +
                        "&timezone=Asia/Shanghai&forecast_days=1";
        java.net.URL url = new java.net.URL(apiUrl);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        int code = conn.getResponseCode();
        if (code != 200) return null;
        java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(conn.getInputStream(), "UTF-8")
        );
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        conn.disconnect();
        String json = sb.toString();
        org.json.JSONObject root = new org.json.JSONObject(json);
        org.json.JSONObject current = root.getJSONObject("current");
        org.json.JSONObject daily = root.getJSONObject("daily");
        double temp = current.getDouble("temperature_2m");
        double feels = current.getDouble("apparent_temperature");
        int humidity = current.getInt("relative_humidity_2m");
        int weatherCode = current.getInt("weather_code");
        double windSpeed = current.getDouble("wind_speed_10m");
        double windDeg = current.getDouble("wind_direction_10m");
        String dateStr = daily.getJSONArray("time").getString(0);
        double maxTemp = daily.getJSONArray("temperature_2m_max").getDouble(0);
        double minTemp = daily.getJSONArray("temperature_2m_min").getDouble(0);
        String[] weatherInfo = WEATHER_WMO_INFO.getOrDefault(weatherCode, new String[]{"未知", "❓"});
        String weatherCn = weatherInfo[0];
        String weatherEmoji = weatherInfo[1];
        String windDisplay = weatherWindDirWithArrow(windDeg);
        int beaufort = weatherWindLevel((int)Math.round(windSpeed));
        String cityKey = city.toLowerCase().replaceAll("[^a-z]", "");
        String displayCity = WEATHER_CITY_CN_MAP.getOrDefault(cityKey, city);
        String dateWeek = weatherFormatDateAndWeek(dateStr);
        String quote = getRandomQuote();
        StringBuilder result = new StringBuilder();
        result.append("\u3000\u3000\u3000\u3000\u3000").append("🗺️").append(displayCity).append("\n");
        result.append(weatherEmoji).append(weatherCn).append("，气温").append((int)Math.round(temp)).append("°C（体感").append((int)Math.round(feels)).append("°C）\n");
        result.append("🌡️今日最高 ").append((int)Math.round(maxTemp)).append("°C / 最低 ").append((int)Math.round(minTemp)).append("°C\n");
        result.append("🌪️风力：").append(windDisplay).append(" ").append(beaufort).append("级（").append((int)Math.round(windSpeed)).append(" km/h）\n");
        result.append("💧湿度：").append(humidity).append("%\n");
        result.append("📆今天是").append(dateWeek);
        if (!quote.isEmpty()) {
            result.append("\n📖").append(quote);
        }
        return result.toString();
    } catch (org.json.JSONException je) {
        return null;
    } catch (Exception e) {
        return null;
    }
}
private double[] weatherGeocode(String city) throws Exception {
    String encoded = java.net.URLEncoder.encode(city, "UTF-8");
    String urlStr = "https://geocoding-api.open-meteo.com/v1/search?name=" + encoded + "&count=1&language=zh&format=json";
    java.net.URL url = new java.net.URL(urlStr);
    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
    conn.setRequestMethod("GET");
    conn.setConnectTimeout(5000);
    conn.setReadTimeout(5000);
    conn.setRequestProperty("User-Agent", "Mozilla/5.0");
    int code = conn.getResponseCode();
    if (code != 200) return null;
    java.io.BufferedReader reader = new java.io.BufferedReader(
        new java.io.InputStreamReader(conn.getInputStream(), "UTF-8")
    );
    StringBuilder sb = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) sb.append(line);
    reader.close();
    conn.disconnect();
    String json = sb.toString();
    if (json.contains("\"results\":[")) {
        org.json.JSONObject obj = new org.json.JSONObject(json);
        org.json.JSONObject result = obj.getJSONArray("results").getJSONObject(0);
        double lat = result.getDouble("latitude");
        double lon = result.getDouble("longitude");
        return new double[]{lat, lon};
    }
    return null;
}
private int weatherWindLevel(int kmh) {
    if (kmh < 1) return 0;
    if (kmh <= 5) return 1;
    if (kmh <= 11) return 2;
    if (kmh <= 19) return 3;
    if (kmh <= 28) return 4;
    if (kmh <= 38) return 5;
    if (kmh <= 49) return 6;
    if (kmh <= 61) return 7;
    if (kmh <= 74) return 8;
    if (kmh <= 88) return 9;
    if (kmh <= 102) return 10;
    if (kmh <= 117) return 11;
    return 12;
}
private String weatherFormatDateAndWeek(String ymd) {
    try {
        java.text.SimpleDateFormat sdfIn = new java.text.SimpleDateFormat("yyyy-MM-dd");
        java.util.Date d = sdfIn.parse(ymd);
        java.text.SimpleDateFormat sdfOut = new java.text.SimpleDateFormat("M月d日");
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTime(d);
        int dow = cal.get(java.util.Calendar.DAY_OF_WEEK);
        String week = WEATHER_WEEK_NAMES[(dow == 1 ? 0 : dow - 1)];
        return sdfOut.format(d) + " " + week;
    } catch (Exception e) {
        return ymd;
    }
}

// ==================== 聊天对话系统 ====================
private static final String[] CHAT_RANDOM_REPLIES = {
    "嗯嗯，我在听呢~",
    "哈哈，有意思！",
    "是吗？然后呢？",
    "我觉得你说得对！",
    "哇，真的吗？",
    "嗯嗯嗯，继续说~",
    "这个我也想知道！",
    "你今天看起来心情不错呀~",
    "嘿嘿，被你发现了",
    "让我想想...嗯...",
    "有道理！",
    "我也是这么想的！",
    "真的假的？",
    "好的好的，我记住了",
    "你说的都对~",
    "嗯嗯，我懂你的意思",
    "哇塞，厉害了！",
    "哈哈哈，笑死我了",
    "嗯...这个有点难回答",
    "你猜我在想什么？"
};
private String callQingyunkeAPI(String msg){
    try{
        String encoded = URLEncoder.encode(msg, "UTF-8");
        String url = "http://api.qingyunke.com/api.php?key=free&appid=0&msg=" + encoded;
        String resp = get(url, null);
        if(resp == null || resp.isEmpty()) return null;
        JSONObject obj = JSON.parseObject(resp);
        if(obj == null) return null;
        int result = obj.getIntValue("result");
        if(result != 0) return null;
        String content = obj.getString("content");
        if(content == null || content.isEmpty()) return null;
        // 青云客返回的内容可能包含{br}换行符，替换为换行
        content = content.replace("{br}", "\n");
        return content;
    }catch(Throwable t){
        return null;
    }
}
private String getChatReply(String userMsg){
    // 优先调用青云客API
    String apiReply = callQingyunkeAPI(userMsg);
    if(apiReply != null && !apiReply.isEmpty()){
        return apiReply;
    }
    // API失败时使用本地随机回复
    return CHAT_RANDOM_REPLIES[(int)(Math.random() * CHAT_RANDOM_REPLIES.length)];
}

// ==================== 帮助文本 ====================
private String getHelpText(){
    StringBuilder sb = new StringBuilder();
    sb.append("❓===== 使用帮助 =====\n\n");
    sb.append("【基础指令】\n");
    sb.append("菜单 - 显示游戏菜单\n");
    sb.append("我的资料 - 查看个人资料（含头衔、ID）\n");
    sb.append("排行榜 - 查看金币/等级排行榜\n");
    sb.append("签到 - 每日签到领取金币\n");
    sb.append("❓帮助 - 显示本帮助\n\n");
    sb.append("【宠物系统】\n");
    sb.append("我的宠物 - 查看宠物背包\n");
    sb.append("宠物商店 - 查看可领养宠物\n");
    sb.append("领养 宠物名 - 领养宠物（猫咪/小狗/兔子/乌龟/狐狸/熊猫/龙）\n");
    sb.append("取名 名字 - 给新领养的宠物取名\n");
    sb.append("设置出战1 - 设置第1只宠物出战\n");
    sb.append("放生宠物1 - 放生第1只宠物\n");
    sb.append("喂食1 - 喂食第1只宠物获得经验\n");
    sb.append("改名1 新名 - 给第1只宠物改名\n");
    sb.append("宠物战绩1 - 查看第1只宠物战绩\n\n");
    sb.append("【道具系统】\n");
    sb.append("道具商城 - 查看可购买道具\n");
    sb.append("我的道具 - 查看已有道具\n");
    sb.append("购买 道具名 - 购买道具\n");
    sb.append("使用 道具名 - 使用道具\n\n");
    sb.append("【游戏玩法】\n");
    sb.append("钓鱼 - 钓鱼获得金币（每日8次）\n");
    sb.append("打怪 - 带宠物打怪获得金币和经验\n");
    sb.append("回血 - 给出战宠物回血\n");
    sb.append("打工 - 打工获得金币（5分钟冷却）\n");
    sb.append("PK ID/昵称 - 与其他玩家宠物PK\n");
    sb.append("大富翁 - 掷骰子前进，到达终点得奖励\n");
    sb.append("盲盒 - 30金币开盲盒，可能赚可能亏\n");
    sb.append("开宝箱 - 50金币开宝箱\n\n");
    sb.append("【娱乐功能】\n");
    sb.append("人品 - 测试今日人品\n");
    sb.append("抽签 - 抽签占卜\n");
    sb.append("讲笑话 - 听一个笑话\n");
    sb.append("求财神 - 求财神赐金币（每日3次）\n");
    sb.append("热门语录 - 获取一句热门语录\n");
    sb.append("今日新闻 - 查看60秒新闻\n");
    sb.append("点歌 歌名 - 点一首歌\n");
    sb.append("配对 昵称 - 测试与某人的匹配度\n");
    sb.append("天气 城市 - 查询城市天气\n\n");
    sb.append("【社交系统】\n");
    sb.append("好友列表 - 查看好友列表\n");
    sb.append("添加好友 ID/昵称 - 添加好友\n");
    sb.append("删除好友 ID/昵称 - 删除好友\n");
    sb.append("好友PK ID/昵称 - 与好友切磋（无奖惩）\n\n");
    sb.append("【任务与提醒】\n");
    sb.append("每日任务 - 查看今日任务，完成得100金币\n");
    sb.append("定时 HH:MM 内容 - 设置每日定时提醒\n");
    sb.append("查看定时任务 - 查看所有定时任务\n");
    sb.append("取消定时任务序号 - 取消指定定时任务\n\n");
    sb.append("【个人设置】（在「我的资料」中查看）\n");
    sb.append("设置昵称 xxx - 设置昵称\n");
    sb.append("设置性别 男/女/保密 - 设置性别\n");
    sb.append("设置年龄 xx - 设置年龄\n");
    sb.append("设置签名 文本 - 设置个性签名\n\n");
    sb.append("💡提示：头衔随等级自动升级，等级越高头衔越霸气！");
    return sb.toString();
}

// ==================== 管理员指令列表（登录后显示） ====================
private String getAdminCommandList(boolean isOwner){
    StringBuilder sb = new StringBuilder();
    sb.append("✅管理员已登录！\n");
    sb.append("===== 🛠管理员指令 =====\n\n");
    sb.append("💰玩家管理（管理员/授权者可用）\n");
    sb.append("#加金币 ID 数量　　#减金币 ID 数量\n");
    sb.append("#封禁 ID　　　　　#解封 ID\n\n");
    sb.append("🐾宠物道具（管理员/授权者可用）\n");
    sb.append("#刷道具 ID 道具名 数量\n");
    sb.append("#减道具 ID 道具名 数量\n\n");
    sb.append("⚙️仅管理员可用\n");
    sb.append("#给宠物 ID 宠物名　#改等级 ID 等级\n");
    sb.append("#设置公告 内容　　　#清除公告\n");
    sb.append("#刷新Boss　　　　　#玩家数据\n");
    sb.append("#开启点歌　　　　　#关闭点歌\n");
    sb.append("#开启聊天　　　　　#关闭聊天\n\n");
    sb.append("🆔ID系统\n");
    sb.append("#我的id - 查看wxid和快捷ID\n");
    sb.append("所有管理员操作可用5位ID代替wxid\n");
    sb.append("例：#加金币 00001 999\n\n");
    if(isOwner){
        sb.append("🔐仅管理员本人可用\n");
        sb.append("#授权 ID　　　　　#取消授权 ID\n");
        sb.append("#修改密码 新密码　#清除所有数据\n\n");
    }
    sb.append("🚪退出登录：#退出管理员\n\n");
    sb.append("💡提示：授权者可使用加/减金币、刷/减道具、封禁/解封、开关点歌功能");
    return sb.toString();
}

// ==================== 菜单文本（两列布局） ====================
private String getMenuText(boolean isFirstSetup){
    loadAnnouncement();
    String festivalAnn = getFestivalAnnouncement();
    StringBuilder sb = new StringBuilder();
    if(isFirstSetup){
        return "🎉检测尚未初始化管理员\n你是第一位使用者！\n请发送：#设置管理员 账号 密码\n例：#设置管理员 admin 123456";
    }
    sb.append("=== 🤖游戏菜单_MOin 🤖 ===\n");
    if(GLOBAL_ANNOUNCEMENT != null && !GLOBAL_ANNOUNCEMENT.isEmpty()){
        sb.append("📢【公告】").append(GLOBAL_ANNOUNCEMENT).append("\n");
    }
    sb.append("📋菜单　　　　✅签到\n");
    sb.append("👤我的资料　　🏆排行榜\n");
    sb.append("❓帮助　　　　📜每日任务\n");
    sb.append("🐱我的宠物　　🏪宠物商店\n");
    sb.append("🎒道具商城　　📦我的道具\n");
    sb.append("🎣钓鱼　　　　⚔️打怪\n");
    sb.append("❤️回血　　　　💼打工\n");
    sb.append("⚔️PK ID/昵称　🎲大富翁\n");
    sb.append("🎁盲盒　　　　📦开宝箱\n");
    sb.append("📜热门语录　　📰今日新闻\n");
    sb.append("🌤天气 城市　　🎵点歌 歌名\n");
    sb.append("🎲人品　　　　🎋抽签\n");
    sb.append("💞配对 昵称　　🙏求财神\n");
    sb.append("👥好友列表　　😂讲笑话\n");
    sb.append("⏰定时 HH:MM　🎮好友PK\n");
    sb.append("❌取消定时任务　📋查看定时任务\n");
    sb.append("🛠管理员指令\n");
    sb.append("发送 #管理员 账号 密码 登录\n");
    sb.append("登录后查看完整管理员指令\n");
    if(festivalAnn != null){
        sb.append("\n").append(festivalAnn);
    }
    return sb.toString();
}

// ==================== 主消息处理 ====================
public void onHandleMsg(Object msgInfo){
    String talker = null;
    try{
        if(!dataLoaded){
            try{
                File dir = new File(DATA_DIR);
                if(dir.exists()){
                    File[] files = dir.listFiles();
                    if(files != null){
                        for(File f : files){
                            String name = f.getName();
                            if(name.startsWith("user_") && name.endsWith(".dat")){
                                String wxid = name.substring(5, name.length()-4);
                                if(!USER_DATA.containsKey(wxid)){
                                    String content = readFile(name);
                                    if(content != null) USER_DATA.put(wxid, content);
                                }
                            }
                        }
                    }
                }
            }catch(Throwable t){}
            dataLoaded = true;
        }
        loadGroupConfig();
        loadAnnouncement();
        loadBossState();
        loadChatConfig();
        try{ checkScheduledTasks(); }catch(Throwable t){}
        Class<?> clazz = msgInfo.getClass();
        boolean isSystem = (Boolean) clazz.getMethod("isSystem").invoke(msgInfo);
        if(isSystem) return;
        boolean isText = (Boolean) clazz.getMethod("isText").invoke(msgInfo);
        if(!isText) return;
        String content = (String) clazz.getMethod("getContent").invoke(msgInfo).trim();
        talker = (String) clazz.getMethod("getTalker").invoke(msgInfo);
        String senderWxid = (String) clazz.getMethod("getSendTalker").invoke(msgInfo);
        if(talker == null || talker.isEmpty()) return;
        if(senderWxid == null || senderWxid.isEmpty()) return;
        if(content == null || content.isEmpty()) return;
        Map<String,String> globalAdminCfg = parseGlobalAdmin();
        boolean noAdminYet = globalAdminCfg.isEmpty();
        String ownerWxid = globalAdminCfg.get("ownerWxid");
        boolean isOwner = ownerWxid != null && ownerWxid.equals(senderWxid);
        Map<String,String> user = getUser(senderWxid);
        long nowMs = System.currentTimeMillis();
        try{
            long taskTs = Long.parseLong(user.get("dailyTaskTs"));
            // 使用北京时间判断是否同一天（修复UTC时区导致凌晨0-8点不刷新bug）
            if(getBeijingDayNumber(taskTs) != getBeijingDayNumber(nowMs)){
                user.put("dailyTaskDone","fish=0,battle=0,sign=0,work=0,rich=0,box=0,feed=0,");
                user.put("dailyRewardGet","0");
                user.put("dailyTaskTs",String.valueOf(nowMs));
                saveUser(senderWxid,user);
            }
        }catch(Throwable t){
            user.put("dailyTaskTs",String.valueOf(nowMs));
            user.put("dailyTaskDone","fish=0,battle=0,sign=0,work=0,rich=0,box=0,feed=0,");
            user.put("dailyRewardGet","0");
            saveUser(senderWxid,user);
        }
        boolean isGroupChat = talker.endsWith("@chatroom");

        // ========== 管理员初始化 ==========
        if(content.startsWith("#设置管理员")){
            if(!noAdminYet){ asyncSend(talker,"❌管理员已经被初始化，不可重复设置"); return; }
            String[] sp = content.split(" ");
            if(sp.length <3){ asyncSend(talker,"格式错误！例 #设置管理员 admin 123456"); return; }
            if(sp[1].length()<1 || sp[2].length()<1){ asyncSend(talker,"账号密码不能为空"); return; }
            saveGlobalAdmin(sp[1],sp[2],senderWxid);
            asyncSend(talker,"✅管理员初始化完成！账号："+sp[1]+"\n你已被绑定为管理员wxid，可使用 #管理员 开机/关机");
            return;
        }

        // ========== #管理员指令（登录/开关机） ==========
        if(content.startsWith("#管理员")){
            String[] sp = content.split(" ");
            if(sp.length >= 2 && ("开机".equals(sp[1]) || "关机".equals(sp[1]))){
                if(noAdminYet){ asyncSend(talker,"❌还没有设置管理员"); return; }
                if(!isOwner){ asyncSend(talker,"❌只有管理员本人才能执行开关机"); return; }
                if(!isGroupChat){ asyncSend(talker,"❌开关机指令只能在群聊中使用"); return; }
                if("开机".equals(sp[1])){
                    setGroupEnabled(talker, true);
                    asyncSend(talker,"✅本群游戏菜单已开机！发送【菜单】开始使用");
                }else{
                    setGroupEnabled(talker, false);
                    asyncSend(talker,"❌本群游戏菜单已关机！仅管理员可使用 #管理员 开机");
                }
                return;
            }
            if(sp.length>=3){
                if(noAdminYet){ asyncSend(talker,"❌还没有设置管理员，先发送【菜单】进行初始化"); return; }
                if(sp[1].equals(globalAdminCfg.get("acc")) && sp[2].equals(globalAdminCfg.get("pwd"))){
                    // 修复：只有初始化时绑定的owner wxid才能登录
                    if(ownerWxid == null || !ownerWxid.equals(senderWxid)){
                        asyncSend(talker,"❌账号密码正确，但你的wxid未被绑定为管理员\n只有首次初始化管理员的账号才能登录");
                        return;
                    }
                    ADMIN_SESSION.put(senderWxid,true);
                    asyncSend(talker, getAdminCommandList(isOwner));
                }else{ asyncSend(talker,"❌账号密码错误"); }
                return;
            }
            return;
        }

        // ========== 群聊关闭状态检查 ==========
        if(isGroupChat && !isGroupEnabled(talker)){
            return;
        }

        // ========== 封禁检查 ==========
        if("1".equals(user.get("banned")) && !ADMIN_SESSION.containsKey(senderWxid)){
            if("菜单".equals(content) || "#我的id".equals(content) || "帮助".equals(content) || "/帮助".equals(content)){
                // 允许查看
            }else{
                asyncSend(talker,"❌你已被管理员封禁，无法使用游戏功能");
                return;
            }
        }

        // ========== 基础指令 ==========
        if("菜单".equals(content)){ asyncSend(talker, getMenuText(noAdminYet)); return; }
        if("帮助".equals(content) || "/帮助".equals(content)){ asyncSend(talker, getHelpText()); return; }
        if("#我的id".equals(content)){
            String uid = user.get("userId");
            if(uid == null || uid.isEmpty()){ uid = allocateId(senderWxid); user.put("userId", uid); saveUser(senderWxid, user); }
            asyncSend(talker,"你的wxid："+senderWxid+"\n快捷ID："+uid+"\n（管理员操作可用ID代替wxid，例：#加金币 "+uid+" 999）");
            return;
        }
        if("#退出管理员".equals(content)){ ADMIN_SESSION.remove(senderWxid); asyncSend(talker,"✅已退出管理员模式"); return; }

        // ========== 天气查询 ==========
        if(content.startsWith("天气 ")){
            final String city = content.substring(3).trim();
            if(city.isEmpty()){ asyncSend(talker,"🌤请发送：天气 城市名\n例：天气 北京"); return; }
            final String ftalker = talker;
            new Thread(new Runnable(){ public void run(){
                String weatherInfo = fetchWeatherByCity(city);
                if(weatherInfo == null){ sendText(ftalker,"❌天气查询失败，请检查城市名是否正确"); return; }
                sendText(ftalker, weatherInfo);
            }}).start();
            return;
        }
        // 支持"城市天气"格式
        if(content.endsWith("天气") && content.length() > 2){
            String city = content.substring(0, content.length()-2).trim();
            if(!city.isEmpty() && !city.contains(" ")){
                final String fcity = city;
                final String ftalker = talker;
                new Thread(new Runnable(){ public void run(){
                    String weatherInfo = fetchWeatherByCity(fcity);
                    if(weatherInfo == null){ sendText(ftalker,"❌天气查询失败，请检查城市名是否正确"); return; }
                    sendText(ftalker, weatherInfo);
                }}).start();
                return;
            }
        }

        // ========== /小o AI对话 ==========
        if(content.startsWith("/小o")){
            String userMsg = content.substring(3).trim();
            if(userMsg.isEmpty()){ asyncSend(talker,"🤖使用方法：/小o 你想说的话\n例：/小o 你好呀"); return; }
            final String fmsg = userMsg;
            final String ftalker = talker;
            new Thread(new Runnable(){ public void run(){
                String reply = getChatReply(fmsg);
                sendText(ftalker, "🤖小o：" + reply);
            }}).start();
            return;
        }

        // ========== 聊天功能：70%随机接话 ==========
        if(CHAT_ENABLED && isGroupChat){
            // 排除所有指令和以特殊字符开头的消息
            boolean isCommand = content.startsWith("#") || content.startsWith("/") ||
                content.startsWith("设置") || content.startsWith("领养") ||
                content.startsWith("取名") || content.startsWith("购买") ||
                content.startsWith("使用") || content.startsWith("喂食") ||
                content.startsWith("改名") || content.startsWith("放生") ||
                content.startsWith("添加") || content.startsWith("删除") ||
                content.startsWith("取消") || content.startsWith("定时") ||
                content.startsWith("PK") || content.startsWith("好友PK") ||
                content.startsWith("配对") || content.startsWith("点歌") ||
                content.startsWith("天气") || content.endsWith("天气") ||
                content.equals("是") || content.equals("否");
            if(!isCommand && Math.random() < 0.7){
                final String fmsg = content;
                final String ftalker = talker;
                new Thread(new Runnable(){ public void run(){
                    String reply = getChatReply(fmsg);
                    sendText(ftalker, reply);
                }}).start();
                return;
            }
        }

        // ========== 定时任务 ==========
        if(content.startsWith("定时 ")){
            String rest = content.substring(3).trim();
            String[] parts = rest.split(" ", 2);
            if(parts.length < 2 || parts[1].isEmpty()){ asyncSend(talker,"❌格式：定时 HH:MM 内容\n例：定时 19:19 晚上好"); return; }
            String timeStr = parts[0];
            String msgContent = parts[1];
            if(!timeStr.matches("\\d{2}:\\d{2}")){ asyncSend(talker,"❌时间格式错误，例：19:19"); return; }
            String[] hm = timeStr.split(":");
            int h = Integer.parseInt(hm[0]), m = Integer.parseInt(hm[1]);
            if(h < 0 || h > 23 || m < 0 || m > 59){ asyncSend(talker,"❌时间范围错误，小时0-23，分钟0-59"); return; }
            loadScheduledTasks();
            SCHEDULED_TASKS.add(new String[]{"0", timeStr, msgContent, senderWxid, talker});
            Collections.sort(SCHEDULED_TASKS, new Comparator<String[]>(){
                public int compare(String[] a, String[] b){ return a[1].compareTo(b[1]); }
            });
            for(int i=0; i<SCHEDULED_TASKS.size(); i++){ SCHEDULED_TASKS.get(i)[0] = String.valueOf(i+1); }
            saveScheduledTasks();
            asyncSend(talker,"✅定时任务已设置！\n时间：每天 "+timeStr+"\n内容："+msgContent+"\n发送【查看定时任务】查看所有定时任务");
            return;
        }
        if("查看定时任务".equals(content)){
            loadScheduledTasks();
            if(SCHEDULED_TASKS.isEmpty()){ asyncSend(talker,"📋当前没有定时任务\n设置：定时 HH:MM 内容\n例：定时 19:19 晚上好"); return; }
            StringBuilder sb = new StringBuilder("📋====定时任务列表====\n");
            for(String[] task : SCHEDULED_TASKS){
                sb.append(task[0]).append(". 每天").append(task[1]).append(" 发送：").append(task[2]).append("\n");
            }
            sb.append("\n取消：取消定时任务序号\n例：取消定时任务1");
            asyncSend(talker, sb.toString());
            return;
        }
        if(content.startsWith("取消定时任务")){
            String numStr = content.substring("取消定时任务".length()).trim();
            int taskId;
            try{ taskId = Integer.parseInt(numStr); }catch(Exception e){ asyncSend(talker,"❌格式：取消定时任务序号\n例：取消定时任务1"); return; }
            loadScheduledTasks();
            if(taskId < 1 || taskId > SCHEDULED_TASKS.size()){ asyncSend(talker,"❌没有这个序号的定时任务，发送【查看定时任务】查看列表"); return; }
            String[] removed = SCHEDULED_TASKS.remove(taskId-1);
            for(int i=0; i<SCHEDULED_TASKS.size(); i++){ SCHEDULED_TASKS.get(i)[0] = String.valueOf(i+1); }
            saveScheduledTasks();
            asyncSend(talker,"✅已取消定时任务"+taskId+"\n（每天"+removed[1]+" 发送："+removed[2]+"）");
            return;
        }

        // ========== 管理员增强指令 ==========
        // #加金币（管理员/授权者可用）
        if(content.startsWith("#加金币")){
            if(noAdminYet){ asyncSend(talker,"❌尚未设置管理员"); return; }
            if(!hasAdminPermission(senderWxid)){ asyncSend(talker,"❌请先 #管理员 账号 密码 登录，或联系管理员授权"); return; }
            String[] sp = content.split(" ");
            if(sp.length < 3){ asyncSend(talker,"❌格式：#加金币 ID/wxid 数量\n例：#加金币 00001 999"); return; }
            int addGold;
            try{ addGold = Integer.parseInt(sp[sp.length-1]); }catch(Exception e){ asyncSend(talker,"❌数量必须是数字"); return; }
            StringBuilder targetSb = new StringBuilder();
            for(int i=1; i<sp.length-1; i++){ if(targetSb.length()>0) targetSb.append(" "); targetSb.append(sp[i]); }
            String targetWx = resolveTarget(targetSb.toString());
            if(targetWx == null){ asyncSend(talker,"❌找不到该用户，请确认ID/wxid正确\n例：#加金币 00001 999"); return; }
            Map<String,String> targetUser = getUser(targetWx);
            int g = Integer.parseInt(targetUser.get("gold")) + addGold;
            if(g < 0) g = 0;
            targetUser.put("gold",String.valueOf(g));
            saveUser(targetWx,targetUser);
            asyncSend(talker,"✅给"+targetUser.get("nick")+"（ID:"+targetUser.get("userId")+"）增加"+addGold+"金币，当前："+g);
            return;
        }
        // #减金币（管理员/授权者可用）
        if(content.startsWith("#减金币")){
            if(noAdminYet){ asyncSend(talker,"❌尚未设置管理员"); return; }
            if(!hasAdminPermission(senderWxid)){ asyncSend(talker,"❌请先登录管理员或联系管理员授权"); return; }
            String[] sp = content.split(" ");
            if(sp.length < 3){ asyncSend(talker,"❌格式：#减金币 ID/wxid 数量\n例：#减金币 00001 100"); return; }
            int subGold;
            try{ subGold = Integer.parseInt(sp[sp.length-1]); }catch(Exception e){ asyncSend(talker,"❌数量必须是数字"); return; }
            StringBuilder targetSb = new StringBuilder();
            for(int i=1; i<sp.length-1; i++){ if(targetSb.length()>0) targetSb.append(" "); targetSb.append(sp[i]); }
            String targetWx = resolveTarget(targetSb.toString());
            if(targetWx == null){ asyncSend(talker,"❌找不到该用户，请确认ID/wxid正确"); return; }
            Map<String,String> targetUser = getUser(targetWx);
            int g = Integer.parseInt(targetUser.get("gold")) - subGold;
            if(g < 0) g = 0;
            targetUser.put("gold",String.valueOf(g));
            saveUser(targetWx,targetUser);
            asyncSend(talker,"✅从"+targetUser.get("nick")+"（ID:"+targetUser.get("userId")+"）扣除"+subGold+"金币，当前："+g);
            return;
        }
        // #给宠物（仅管理员）
        if(content.startsWith("#给宠物")){
            if(!ADMIN_SESSION.containsKey(senderWxid)){ asyncSend(talker,"❌请先登录管理员（授权者不可用）"); return; }
            String[] sp = content.split(" ");
            if(sp.length < 3){ asyncSend(talker,"❌格式：#给宠物 ID/wxid 宠物名\n例：#给宠物 00001 猫咪"); return; }
            String petType = sp[sp.length-1];
            StringBuilder targetSb = new StringBuilder();
            for(int i=1; i<sp.length-1; i++){ if(targetSb.length()>0) targetSb.append(" "); targetSb.append(sp[i]); }
            String targetWx = resolveTarget(targetSb.toString());
            if(targetWx == null){ asyncSend(talker,"❌找不到该用户，请确认ID/wxid正确"); return; }
            int hp=0,atk=0; String showName="";
            if("猫咪".equals(petType)){hp=100;atk=12;showName="小猫咪";}
            else if("小狗".equals(petType)){hp=120;atk=16;showName="小狗崽";}
            else if("兔子".equals(petType)){hp=80;atk=20;showName="小兔子";}
            else if("乌龟".equals(petType)){hp=180;atk=8;showName="小乌龟";}
            else if("狐狸".equals(petType)){hp=110;atk=18;showName="小狐狸";}
            else if("熊猫".equals(petType)){hp=200;atk=22;showName="大熊猫";}
            else if("龙".equals(petType)){hp=250;atk=30;showName="幼龙";}
            else{ asyncSend(talker,"❌宠物名：猫咪/小狗/兔子/乌龟/狐狸/熊猫/龙"); return; }
            Map<String,String> targetUser = getUser(targetWx);
            targetUser.put("petBag", targetUser.get("petBag") + showName + ",1," + hp + "," + hp + "," + atk + "," + nowMs + ",0,0;");
            saveUser(targetWx, targetUser);
            asyncSend(talker,"✅管理员直接给"+targetUser.get("nick")+"（ID:"+targetUser.get("userId")+"）增加了一只【"+showName+"】");
            return;
        }
        // #刷道具（管理员/授权者可用）
        if(content.startsWith("#刷道具")){
            if(!hasAdminPermission(senderWxid)){ asyncSend(talker,"❌请先登录管理员或联系管理员授权"); return; }
            String[] sp = content.split(" ");
            if(sp.length < 4){ asyncSend(talker,"❌格式：#刷道具 ID/wxid 道具名 数量\n例：#刷道具 00001 高级球 10"); return; }
            String itemName = sp[sp.length-2];
            int count;
            try{ count = Integer.parseInt(sp[sp.length-1]); }catch(Exception e){ asyncSend(talker,"❌数量必须是数字"); return; }
            StringBuilder targetSb = new StringBuilder();
            for(int i=1; i<sp.length-2; i++){ if(targetSb.length()>0) targetSb.append(" "); targetSb.append(sp[i]); }
            String targetWx = resolveTarget(targetSb.toString());
            if(targetWx == null){ asyncSend(talker,"❌找不到该用户，请确认ID/wxid正确"); return; }
            String key = "";
            if("初级球".equals(itemName)) key="ball1";
            else if("中级球".equals(itemName)) key="ball2";
            else if("高级球".equals(itemName)) key="ball3";
            else if("鲜花".equals(itemName)) key="flower";
            else if("经验药水".equals(itemName)) key="expPotion";
            else if("金币袋".equals(itemName)) key="goldBag";
            else if("复活药水".equals(itemName)) key="revive";
            else if("幸运符".equals(itemName)) key="lucky";
            else if("双倍金币卡".equals(itemName)) key="doubleGoldCard";
            else if("双倍经验卡".equals(itemName)) key="doubleExpCard";
            else{ asyncSend(talker,"❌道具名：初级球/中级球/高级球/鲜花/经验药水/金币袋/复活药水/幸运符/双倍金币卡/双倍经验卡"); return; }
            Map<String,String> targetUser = getUser(targetWx);
            String[] its = targetUser.get("items").split(",");
            StringBuilder sb = new StringBuilder();
            for(String it : its){
                if(it.isEmpty()) continue;
                String[] kv = it.split("=");
                if(kv.length==2 && kv[0].equals(key)){ sb.append(kv[0]).append("=").append(Integer.parseInt(kv[1])+count).append(","); }
                else{ sb.append(it).append(","); }
            }
            targetUser.put("items", sb.toString());
            saveUser(targetWx, targetUser);
            asyncSend(talker,"✅给"+targetUser.get("nick")+"（ID:"+targetUser.get("userId")+"）刷了"+count+"个【"+itemName+"】");
            return;
        }
        // #减道具（管理员/授权者可用）
        if(content.startsWith("#减道具")){
            if(!hasAdminPermission(senderWxid)){ asyncSend(talker,"❌请先登录管理员或联系管理员授权"); return; }
            String[] sp = content.split(" ");
            if(sp.length < 4){ asyncSend(talker,"❌格式：#减道具 ID/wxid 道具名 数量\n例：#减道具 00001 高级球 5"); return; }
            String itemName = sp[sp.length-2];
            int count;
            try{ count = Integer.parseInt(sp[sp.length-1]); }catch(Exception e){ asyncSend(talker,"❌数量必须是数字"); return; }
            StringBuilder targetSb = new StringBuilder();
            for(int i=1; i<sp.length-2; i++){ if(targetSb.length()>0) targetSb.append(" "); targetSb.append(sp[i]); }
            String targetWx = resolveTarget(targetSb.toString());
            if(targetWx == null){ asyncSend(talker,"❌找不到该用户，请确认ID/wxid正确"); return; }
            String key = "";
            if("初级球".equals(itemName)) key="ball1";
            else if("中级球".equals(itemName)) key="ball2";
            else if("高级球".equals(itemName)) key="ball3";
            else if("鲜花".equals(itemName)) key="flower";
            else if("经验药水".equals(itemName)) key="expPotion";
            else if("金币袋".equals(itemName)) key="goldBag";
            else if("复活药水".equals(itemName)) key="revive";
            else if("幸运符".equals(itemName)) key="lucky";
            else if("双倍金币卡".equals(itemName)) key="doubleGoldCard";
            else if("双倍经验卡".equals(itemName)) key="doubleExpCard";
            else{ asyncSend(talker,"❌道具名：初级球/中级球/高级球/鲜花/经验药水/金币袋/复活药水/幸运符/双倍金币卡/双倍经验卡"); return; }
            Map<String,String> targetUser = getUser(targetWx);
            String[] its = targetUser.get("items").split(",");
            StringBuilder sb = new StringBuilder();
            for(String it : its){
                if(it.isEmpty()) continue;
                String[] kv = it.split("=");
                if(kv.length==2 && kv[0].equals(key)){ int v = Integer.parseInt(kv[1])-count; if(v<0)v=0; sb.append(kv[0]).append("=").append(v).append(","); }
                else{ sb.append(it).append(","); }
            }
            targetUser.put("items", sb.toString());
            saveUser(targetWx, targetUser);
            asyncSend(talker,"✅从"+targetUser.get("nick")+"（ID:"+targetUser.get("userId")+"）扣除"+count+"个【"+itemName+"】");
            return;
        }
        // #改等级（仅管理员）
        if(content.startsWith("#改等级")){
            if(!ADMIN_SESSION.containsKey(senderWxid)){ asyncSend(talker,"❌请先登录管理员（授权者不可用）"); return; }
            String[] sp = content.split(" ");
            if(sp.length < 3){ asyncSend(talker,"❌格式：#改等级 ID/wxid 等级\n例：#改等级 00001 10"); return; }
            int newLevel;
            try{ newLevel = Integer.parseInt(sp[sp.length-1]); }catch(Exception e){ asyncSend(talker,"❌等级必须是数字"); return; }
            if(newLevel < 1 || newLevel > 999){ asyncSend(talker,"❌等级范围1-999"); return; }
            StringBuilder targetSb = new StringBuilder();
            for(int i=1; i<sp.length-1; i++){ if(targetSb.length()>0) targetSb.append(" "); targetSb.append(sp[i]); }
            String targetWx = resolveTarget(targetSb.toString());
            if(targetWx == null){ asyncSend(talker,"❌找不到该用户，请确认ID/wxid正确"); return; }
            Map<String,String> targetUser = getUser(targetWx);
            targetUser.put("level", String.valueOf(newLevel));
            targetUser.put("exp", "0");
            saveUser(targetWx, targetUser);
            asyncSend(talker,"✅管理员将"+targetUser.get("nick")+"（ID:"+targetUser.get("userId")+"）的等级改为"+newLevel);
            return;
        }
        // #封禁（管理员/授权者可用）
        if(content.startsWith("#封禁")){
            if(!hasAdminPermission(senderWxid)){ asyncSend(talker,"❌请先登录管理员或联系管理员授权"); return; }
            String[] sp = content.split(" ");
            if(sp.length < 2){ asyncSend(talker,"❌格式：#封禁 ID/wxid\n例：#封禁 00001"); return; }
            StringBuilder targetSb = new StringBuilder();
            for(int i=1; i<sp.length; i++){ if(targetSb.length()>0) targetSb.append(" "); targetSb.append(sp[i]); }
            String targetWx = resolveTarget(targetSb.toString());
            if(targetWx == null){ asyncSend(talker,"❌找不到该用户，请确认ID/wxid正确"); return; }
            Map<String,String> targetUser = getUser(targetWx);
            targetUser.put("banned", "1");
            saveUser(targetWx, targetUser);
            asyncSend(talker,"✅已封禁玩家："+targetUser.get("nick")+"（ID:"+targetUser.get("userId")+"）");
            return;
        }
        // #解封（管理员/授权者可用）
        if(content.startsWith("#解封")){
            if(!hasAdminPermission(senderWxid)){ asyncSend(talker,"❌请先登录管理员或联系管理员授权"); return; }
            String[] sp = content.split(" ");
            if(sp.length < 2){ asyncSend(talker,"❌格式：#解封 ID/wxid\n例：#解封 00001"); return; }
            StringBuilder targetSb = new StringBuilder();
            for(int i=1; i<sp.length; i++){ if(targetSb.length()>0) targetSb.append(" "); targetSb.append(sp[i]); }
            String targetWx = resolveTarget(targetSb.toString());
            if(targetWx == null){ asyncSend(talker,"❌找不到该用户，请确认ID/wxid正确"); return; }
            Map<String,String> targetUser = getUser(targetWx);
            targetUser.put("banned", "0");
            saveUser(targetWx, targetUser);
            asyncSend(talker,"✅已解封玩家："+targetUser.get("nick")+"（ID:"+targetUser.get("userId")+"）");
            return;
        }
        // #设置公告（仅管理员）
        if(content.startsWith("#设置公告")){
            if(!ADMIN_SESSION.containsKey(senderWxid)){ asyncSend(talker,"❌请先登录管理员（授权者不可用）"); return; }
            String ann = content.substring("#设置公告".length()).trim();
            if(ann.isEmpty()){ asyncSend(talker,"❌公告内容不能为空"); return; }
            saveAnnouncement(ann);
            asyncSend(talker,"✅全局公告已设置："+ann);
            return;
        }
        if("#清除公告".equals(content)){
            if(!ADMIN_SESSION.containsKey(senderWxid)){ asyncSend(talker,"❌请先登录管理员（授权者不可用）"); return; }
            saveAnnouncement("");
            asyncSend(talker,"✅全局公告已清除");
            return;
        }
        // #清除所有数据（仅管理员本人）
        if("#清除所有数据".equals(content)){
            if(!ADMIN_SESSION.containsKey(senderWxid)){ asyncSend(talker,"❌请先登录管理员"); return; }
            if(!isOwner){ asyncSend(talker,"❌只有管理员本人才能清除所有数据（授权者也不行）"); return; }
            clearAllGameData();
            asyncSend(talker,"🗑️所有游戏数据已全部清除！\n包括：所有玩家数据、管理员账号密码、群配置、公告、Boss状态、ID映射、定时任务、授权用户、聊天配置\n\n请重新发送【菜单】开始初始化");
            return;
        }
        // #玩家数据（仅管理员）
        if("#玩家数据".equals(content)){
            if(!ADMIN_SESSION.containsKey(senderWxid)){ asyncSend(talker,"❌请先登录管理员（授权者不可用）"); return; }
            StringBuilder sb = new StringBuilder("📊全部玩家数据（共");
            int count = 0;
            ArrayList<String> players = new ArrayList<>();
            for(Map.Entry<String,String> entry : USER_DATA.entrySet()){
                if(GLOBAL_ADMIN_CFG_KEY.equals(entry.getKey())) continue;
                Map<String,String> ud = parseUserData(entry.getValue());
                if(isZombieUser(ud)) continue;
                int petCount = 0;
                String petBag = ud.get("petBag");
                if(petBag != null && !petBag.isEmpty()){
                    petCount = petBag.split(";").length;
                }
                players.add(ud.get("nick")+"|金:"+ud.get("gold")+"|Lv."+ud.get("level")+"|宠:"+petCount);
                count++;
            }
            sb.append(count).append("人）\n");
            Collections.sort(players, new Comparator<String>(){
                public int compare(String a,String b){
                    int ga = Integer.parseInt(a.split("\\|")[1].replace("金:",""));
                    int gb = Integer.parseInt(b.split("\\|")[1].replace("金:",""));
                    return Integer.compare(gb, ga);
                }
            });
            for(int i=0;i<players.size()&&i<20;i++){
                String[] p = players.get(i).split("\\|");
                sb.append(i+1).append(". ").append(p[0]).append("｜").append(p[1]).append("｜").append(p[2]).append("｜").append(p[3]).append("\n");
            }
            if(players.size() > 20) sb.append("...仅显示前20名");
            asyncSend(talker, sb.toString());
            return;
        }

        // ========== 管理员专属：授权/取消授权 ==========
        if(content.startsWith("#授权")){
            if(!ADMIN_SESSION.containsKey(senderWxid)){ asyncSend(talker,"❌请先登录管理员"); return; }
            if(!isOwner){ asyncSend(talker,"❌只有管理员本人才能授权用户"); return; }
            String targetInput = content.substring(3).trim();
            if(targetInput.isEmpty()){ asyncSend(talker,"❌格式：#授权 ID/wxid/昵称\n例：#授权 00001"); return; }
            String targetWx = resolveTarget(targetInput);
            if(targetWx == null){ asyncSend(talker,"❌找不到该用户，请确认ID/wxid正确"); return; }
            if(isOwner && targetWx.equals(ownerWxid)){ asyncSend(talker,"❌管理员本人无需授权"); return; }
            addAuthorized(targetWx);
            Map<String,String> targetUser = getUser(targetWx);
            asyncSend(talker,"✅已授权用户："+targetUser.get("nick")+"（ID:"+targetUser.get("userId")+"）\n授权后可使用：加/减金币、刷/减道具、封禁/解封、开关点歌");
            return;
        }
        if(content.startsWith("#取消授权")){
            if(!ADMIN_SESSION.containsKey(senderWxid)){ asyncSend(talker,"❌请先登录管理员"); return; }
            if(!isOwner){ asyncSend(talker,"❌只有管理员本人才能取消授权"); return; }
            String targetInput = content.substring(5).trim();
            if(targetInput.isEmpty()){ asyncSend(talker,"❌格式：#取消授权 ID/wxid/昵称\n例：#取消授权 00001"); return; }
            String targetWx = resolveTarget(targetInput);
            if(targetWx == null){ asyncSend(talker,"❌找不到该用户，请确认ID/wxid正确"); return; }
            if(!isAuthorized(targetWx)){ asyncSend(talker,"❌该用户没有被授权"); return; }
            removeAuthorized(targetWx);
            Map<String,String> targetUser = getUser(targetWx);
            asyncSend(talker,"✅已取消授权："+targetUser.get("nick")+"（ID:"+targetUser.get("userId")+"）\n该用户权限已恢复为普通用户");
            return;
        }

        // ========== 管理员专属：修改密码 ==========
        if(content.startsWith("#修改密码")){
            if(!ADMIN_SESSION.containsKey(senderWxid)){ asyncSend(talker,"❌请先登录管理员"); return; }
            if(!isOwner){ asyncSend(talker,"❌只有管理员本人才能修改密码"); return; }
            String newPwd = content.substring(5).trim();
            if(newPwd.isEmpty() || newPwd.length() < 1){ asyncSend(talker,"❌格式：#修改密码 新密码\n例：#修改密码 654321"); return; }
            Map<String,String> cfg = parseGlobalAdmin();
            String acc = cfg.get("acc");
            if(acc == null) acc = "admin";
            saveGlobalAdmin(acc, newPwd, ownerWxid);
            asyncSend(talker,"✅管理员密码已修改为："+newPwd+"\n请使用新密码登录");
            return;
        }

        // ========== 管理员专属：聊天开关 ==========
        if("#开启聊天".equals(content)){
            if(!ADMIN_SESSION.containsKey(senderWxid)){ asyncSend(talker,"❌请先登录管理员（授权者不可用）"); return; }
            saveChatConfig(true);
            asyncSend(talker,"🤖聊天对话功能已开启！\n\n使用方法：\n1. 群内普通消息约70%概率自动接话\n2. 发送 /小o 内容 可100%调用AI回复\n例：/小o 你好呀\n\nAI接口：青云客免费聊天机器人（无需密钥）");
            return;
        }
        if("#关闭聊天".equals(content)){
            if(!ADMIN_SESSION.containsKey(senderWxid)){ asyncSend(talker,"❌请先登录管理员（授权者不可用）"); return; }
            saveChatConfig(false);
            asyncSend(talker,"🤖聊天对话功能已关闭");
            return;
        }

        // ========== 管理员/授权者：点歌开关 ==========
        if("#开启点歌".equals(content)){
            if(!hasAdminPermission(senderWxid)){ asyncSend(talker,"❌请先登录管理员或联系管理员授权"); return; }
            user.put("musicEnable","1");saveUser(senderWxid,user);
            asyncSend(talker,"🔔点歌功能已开启");
            return;
        }
        if("#关闭点歌".equals(content)){
            if(!hasAdminPermission(senderWxid)){ asyncSend(talker,"❌请先登录管理员或联系管理员授权"); return; }
            user.put("musicEnable","0");saveUser(senderWxid,user);
            asyncSend(talker,"🔔点歌功能已关闭");
            return;
        }

        // ========== 个人资料设置 ==========
        if(content.startsWith("设置昵称")){
            String nick = content.substring("设置昵称".length()).trim();
            if(nick.length()<1||nick.length()>16){ asyncSend(talker,"❌昵称1‑16字符"); return; }
            user.put("nick",nick);saveUser(senderWxid,user); asyncSend(talker,"✅昵称："+nick); return;
        }
        if(content.startsWith("设置性别")){
            String g = content.substring("设置性别".length()).trim();
            if(!("男".equals(g)||"女".equals(g)||"保密".equals(g))){ asyncSend(talker,"❌只能：男/女/保密"); return; }
            user.put("gender",g);saveUser(senderWxid,user); asyncSend(talker,"✅性别已设置"); return;
        }
        if(content.startsWith("设置年龄")){
            String numStr = content.substring("设置年龄".length()).trim();
            int age; try{age=Integer.parseInt(numStr);}catch(Exception e){ asyncSend(talker,"❌输入数字");return; }
            if(age<0||age>120){asyncSend(talker,"❌0‑120");return;}
            user.put("age",String.valueOf(age));saveUser(senderWxid,user); asyncSend(talker,"✅年龄："+age); return;
        }
        if(content.startsWith("设置签名")){
            String sign = content.substring("设置签名".length()).trim();
            if(sign.length()>40){asyncSend(talker,"❌最多40字");return;}
            user.put("sign",sign);saveUser(senderWxid,user); asyncSend(talker,"✅签名更新"); return;
        }

        // ========== 我的资料（含头衔、个人设置入口） ==========
        if("我的资料".equals(content)){
            String nick = user.get("nick"), gender = user.get("gender"), ageStr = user.get("age"), sign = user.get("sign");
            int gold = Integer.parseInt(user.get("gold")), lv = Integer.parseInt(user.get("level")), exp = Integer.parseInt(user.get("exp"));
            String title = getTitleByLevel(lv);
            String battlePetShow = "无出战宠物";
            int bidx = Integer.parseInt(user.get("battlePetIdx"));
            String petBagRaw = user.get("petBag");
            if(bidx !=-1 && petBagRaw!=null&&!petBagRaw.isEmpty()){
                String[] pets = petBagRaw.split(";");
                if(bidx < pets.length && pets[bidx]!=null&&!pets[bidx].isEmpty()){
                    String[] p = upgradePetData(pets[bidx]);
                    battlePetShow = p[0]+" Lv."+p[1];
                }
            }
            String buffShow = "";
            if(isDoubleGoldActive(user)) buffShow += "💰双倍金币生效中\n";
            if(isDoubleExpActive(user)) buffShow += "⭐双倍经验生效中\n";
            int friendCount = 0;
            String friends = user.get("friends");
            if(friends != null && !friends.isEmpty()) friendCount = friends.split(",").length;
            ArrayList<String> rankAll = buildRankList();
            ArrayList<String> goldList = new ArrayList<>(rankAll);
            Collections.sort(goldList,new Comparator<String>(){ public int compare(String a,String b){ String[] sa=a.split("\\|"),sb=b.split("\\|"); return Integer.compare(Integer.parseInt(sb[2]),Integer.parseInt(sa[2])); } });
            ArrayList<String> lvList = new ArrayList<>(rankAll);
            Collections.sort(lvList,new Comparator<String>(){ public int compare(String a,String b){ String[] sa=a.split("\\|"),sb=b.split("\\|"); int c=Integer.compare(Integer.parseInt(sb[3]),Integer.parseInt(sa[3])); return c!=0?c:Integer.compare(Integer.parseInt(sb[2]),Integer.parseInt(sa[2])); } });
            Integer goldRank=null,lvRank=null;
            for(int i=0;i<goldList.size();i++){ if(goldList.get(i).split("\\|")[0].equals(senderWxid)){goldRank=i+1;break;} }
            for(int i=0;i<lvList.size();i++){ if(lvList.get(i).split("\\|")[0].equals(senderWxid)){lvRank=i+1;break;} }
            StringBuilder sb = new StringBuilder();
            sb.append("👤====个人资料====\n");
            sb.append("昵称：").append(nick).append("\n");
            sb.append("头衔：").append(title).append("\n");
            sb.append("🆔我的ID：").append(user.get("userId")).append("\n");
            sb.append("性别：").append(gender).append("\n");
            sb.append("年龄：").append(ageStr).append("\n");
            sb.append("签名：").append(sign).append("\n");
            sb.append("——————————\n");
            sb.append("💰金币：").append(gold).append("\n");
            sb.append("⭐等级：").append(lv).append("\n");
            sb.append("📊经验：").append(exp).append("/").append(lv*120).append("\n");
            sb.append("🐾出战宠物：").append(battlePetShow).append("\n");
            sb.append("👥好友数：").append(friendCount).append("\n");
            if(buffShow.length()>0) sb.append(buffShow);
            if("1".equals(user.get("banned"))) sb.append("⚠️状态：已被封禁\n");
            if(goldRank!=null && goldRank<=8) sb.append("🏆金币榜：第").append(goldRank).append("名\n");
            if(lvRank!=null && lvRank<=8) sb.append("🏆等级榜：第").append(lvRank).append("名\n");
            sb.append("——————————\n");
            sb.append("✏️个人设置（直接发送）：\n");
            sb.append("  设置昵称 xxx\n");
            sb.append("  设置性别 男/女/保密\n");
            sb.append("  设置年龄 xx\n");
            sb.append("  设置签名 文本\n");
            sb.append("💡头衔随等级自动升级，等级越高越霸气！");
            asyncSend(talker,sb.toString()); return;
        }

        // ========== 排行榜 ==========
        if("排行榜".equals(content)){
            ArrayList<String> rankAll = buildRankList();
            ArrayList<String> goldList = new ArrayList<>(rankAll);
            Collections.sort(goldList,new Comparator<String>(){ public int compare(String a,String b){ String[] sa=a.split("\\|"),sb=b.split("\\|"); return Integer.compare(Integer.parseInt(sb[2]),Integer.parseInt(sa[2])); } });
            ArrayList<String> lvList = new ArrayList<>(rankAll);
            Collections.sort(lvList,new Comparator<String>(){ public int compare(String a,String b){ String[] sa=a.split("\\|"),sb=b.split("\\|"); int c=Integer.compare(Integer.parseInt(sb[3]),Integer.parseInt(sa[3])); return c!=0?c:Integer.compare(Integer.parseInt(sb[2]),Integer.parseInt(sa[2])); } });
            StringBuilder sb=new StringBuilder();
            sb.append("🏆====游戏排行榜（实时）====\n【💰金币榜前8】\n");
            for(int i=0;i<goldList.size()&&i<8;i++){ String[] s=goldList.get(i).split("\\|"); sb.append(i+1).append(". ").append(s[1]).append("｜金币:").append(s[2]).append("\n"); }
            sb.append("\n【⭐等级榜前8】\n");
            for(int i=0;i<lvList.size()&&i<8;i++){ String[] s=lvList.get(i).split("\\|"); sb.append(i+1).append(". ").append(s[1]).append("｜Lv.").append(s[3]).append("\n"); }
            asyncSend(talker,sb.toString()); return;
        }

        // ========== 签到 ==========
        if("签到".equals(content)){
            long now = System.currentTimeMillis();
            long lastSignTs = Long.parseLong(user.get("lastSign"));
            // 使用北京时间判断是否同一天（修复UTC时区导致凌晨0-8点不刷新bug）
            if(getBeijingDayNumber(now) == getBeijingDayNumber(lastSignTs)){ asyncSend(talker,"❌今日已签到"); return; }
            int gold = Integer.parseInt(user.get("gold"));
            int signDays = Integer.parseInt(user.get("signDays"));
            // 连续签到判断：北京时间下相差1天
            if(getBeijingDayDiff(lastSignTs, now) == 1) signDays++; else signDays=1;
            int bonus=0; if(signDays>=7){bonus=50;signDays=0;} else if(signDays>=3) bonus=20;
            int gain = 30+bonus;
            if(isDoubleGoldActive(user)) gain *= 2;
            gold += gain;
            user.put("gold",String.valueOf(gold)); user.put("lastSign",String.valueOf(now)); user.put("signDays",String.valueOf(signDays));
            incTask(user,"sign");
            saveUser(senderWxid,user);
            String msg="✅签到成功！+"+gain+"金币";
            if(isDoubleGoldActive(user)) msg += "（双倍金币卡生效）";
            if(bonus>0) msg+="\n🔥连续签到"+signDays+"天奖励 +"+bonus+"金币";
            if(signDays==0) msg+="\n🎉7天连击完成！";
            msg+="\n当前金币："+gold;
            asyncSend(talker,msg); return;
        }

        // ========== 我的宠物 ==========
        if("我的宠物".equals(content)){
            String petBagRaw = user.get("petBag"), pending = user.get("pendingPet");
            int battleIdx = Integer.parseInt(user.get("battlePetIdx"));
            if(pending != null && !pending.isEmpty()){ String[] pinfo=pending.split(","); asyncSend(talker,"⚠️你有一只待取名的"+pinfo[0]+"！\n请发送：取名 宠物名字"); return; }
            if(petBagRaw == null || petBagRaw.isEmpty()){ asyncSend(talker,"📦背包为空，请去宠物商店领养"); return; }
            String[] pets = petBagRaw.split(";");
            StringBuilder sb=new StringBuilder();
            sb.append("====我的宠物背包====\n");
            for(int i=0;i<pets.length;i++){
                if(pets[i]==null||pets[i].isEmpty())continue;
                String[] p=upgradePetData(pets[i]);
                if(p.length<5)continue;
                String tag=(i==battleIdx)?"【出战】":"";
                sb.append(i+1).append(".").append(tag).append(p[0]).append(" Lv.").append(p[1]).append(" HP:").append(p[2]).append("/").append(p[3]).append(" 攻:").append(p[4]).append("\n");
            }
            sb.append("\n指令：设置出战1｜放生宠物1｜喂食1｜改名1 新名｜宠物战绩1");
            asyncSend(talker,sb.toString()); return;
        }
        if(content.startsWith("宠物战绩")){
            String numStr=content.substring("宠物战绩".length()).trim(); int idx;
            try{idx=Integer.parseInt(numStr)-1;}catch(Exception e){asyncSend(talker,"❌格式：宠物战绩1");return;}
            String petBagRaw=user.get("petBag"); if(petBagRaw==null||petBagRaw.isEmpty()){asyncSend(talker,"❌背包为空");return;}
            String[] pets=petBagRaw.split(";");
            if(idx<0||idx>=pets.length||pets[idx]==null||pets[idx].isEmpty()){asyncSend(talker,"❌序号错误");return;}
            String[] p=upgradePetData(pets[idx]);
            String catchTime = "未知";
            try{
                long ts = Long.parseLong(p[5]);
                if(ts > 0){
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
                    catchTime = sdf.format(new java.util.Date(ts));
                }
            }catch(Throwable t){}
            int battles = Integer.parseInt(p[6]);
            int wins = Integer.parseInt(p[7]);
            double winRate = battles > 0 ? (wins*100.0/battles) : 0;
            asyncSend(talker,"📜【"+p[0]+"】宠物战绩\n━━━━━━━━━━━━\n捕捉时间："+catchTime+"\n等级：Lv."+p[1]+"\n血量："+p[2]+"/"+p[3]+"\n攻击："+p[4]+"\n━━━━━━━━━━━━\n战斗场次："+battles+"场\n胜利次数："+wins+"场\n胜率："+String.format("%.1f",winRate)+"%");
            return;
        }
        if(content.startsWith("设置出战")){
            String numStr=content.substring("设置出战".length()).trim(); int idx;
            try{idx=Integer.parseInt(numStr)-1;}catch(Exception e){asyncSend(talker,"❌格式：设置出战1");return;}
            String petBagRaw=user.get("petBag"); if(petBagRaw==null||petBagRaw.isEmpty()){asyncSend(talker,"❌背包为空");return;}
            String[] pets=petBagRaw.split(";");
            if(idx<0||idx>=pets.length||pets[idx]==null||pets[idx].isEmpty()){asyncSend(talker,"❌序号错误");return;}
            user.put("battlePetIdx",String.valueOf(idx)); saveUser(senderWxid,user);
            asyncSend(talker,"✅设置【"+upgradePetData(pets[idx])[0]+"】出战"); return;
        }
        if(content.startsWith("放生宠物")){
            String numStr=content.substring("放生宠物".length()).trim(); int idx;
            try{idx=Integer.parseInt(numStr)-1;}catch(Exception e){asyncSend(talker,"❌格式：放生宠物1");return;}
            String petBagRaw=user.get("petBag"); if(petBagRaw==null||petBagRaw.isEmpty()){asyncSend(talker,"❌背包为空");return;}
            String[] pets=petBagRaw.split(";");
            if(idx<0||idx>=pets.length||pets[idx]==null||pets[idx].isEmpty()){asyncSend(talker,"❌序号错误");return;}
            String delName=upgradePetData(pets[idx])[0];
            StringBuilder newBag=new StringBuilder();
            for(int i=0;i<pets.length;i++){ if(i!=idx&&pets[i]!=null&&!pets[i].isEmpty()) newBag.append(pets[i]).append(";"); }
            user.put("petBag",newBag.toString());
            int bidx=Integer.parseInt(user.get("battlePetIdx"));
            if(bidx==idx) user.put("battlePetIdx","-1"); else if(bidx>idx) user.put("battlePetIdx",String.valueOf(bidx-1));
            saveUser(senderWxid,user); asyncSend(talker,"✅放生："+delName); return;
        }
        if(content.startsWith("喂食")){
            String numStr=content.substring("喂食".length()).trim(); int idx;
            try{idx=Integer.parseInt(numStr)-1;}catch(Exception e){asyncSend(talker,"❌格式：喂食1");return;}
            String petBagRaw=user.get("petBag"); if(petBagRaw==null||petBagRaw.isEmpty()){asyncSend(talker,"❌背包为空");return;}
            String[] pets=petBagRaw.split(";");
            if(idx<0||idx>=pets.length||pets[idx]==null||pets[idx].isEmpty()){asyncSend(talker,"❌序号错误");return;}
            int gold=Integer.parseInt(user.get("gold"));
            if(gold<20){asyncSend(talker,"❌喂食需要20金币，当前："+gold);return;}
            gold-=20; String[] pet=upgradePetData(pets[idx]);
            int petLv=Integer.parseInt(pet[1]),petMaxHp=Integer.parseInt(pet[3]),exp=Integer.parseInt(user.get("exp"));
            int gainExp=15+(int)(Math.random()*10);
            if(isDoubleExpActive(user)) gainExp *= 2;
            exp+=gainExp;
            user.put("gold",String.valueOf(gold)); user.put("exp",String.valueOf(exp));
            String upgradeMsg="";
            if(exp>=petLv*120){ exp-=petLv*120; petLv++; petMaxHp+=25; pet[1]=String.valueOf(petLv); pet[2]=String.valueOf(petMaxHp); pet[3]=String.valueOf(petMaxHp); user.put("exp",String.valueOf(exp)); upgradeMsg="\n🎉宠物升级至Lv."+petLv+"！血量回满"; pets[idx]=pet[0]+","+pet[1]+","+pet[2]+","+pet[3]+","+pet[4]+","+pet[5]+","+pet[6]+","+pet[7]; StringBuilder nb=new StringBuilder();for(String ps:pets){if(ps!=null&&!ps.isEmpty())nb.append(ps).append(";");} user.put("petBag",nb.toString()); }
            incTask(user,"feed");
            saveUser(senderWxid,user);
            asyncSend(talker,"🍖喂食【"+pet[0]+"】成功！\n消耗20金币，获得"+gainExp+"经验"+(isDoubleExpActive(user)?"（双倍经验生效）":"")+upgradeMsg+"\n剩余金币："+gold); return;
        }
        if(content.startsWith("改名")){
            String rest=content.substring("改名".length()).trim(); String[] parts=rest.split(" ",2);
            if(parts.length<2){asyncSend(talker,"❌格式：改名1 新名字");return;}
            int idx; try{idx=Integer.parseInt(parts[0].trim())-1;}catch(Exception e){asyncSend(talker,"❌格式：改名1 新名字");return;}
            String newName=parts[1].trim().replace(",","").replace(";","").replace("|","").replace("=","");
            if(newName.isEmpty()||newName.length()>12){asyncSend(talker,"❌名字1‑12字，不能含特殊字符");return;}
            String petBagRaw=user.get("petBag"); if(petBagRaw==null||petBagRaw.isEmpty()){asyncSend(talker,"❌背包为空");return;}
            String[] pets=petBagRaw.split(";");
            if(idx<0||idx>=pets.length||pets[idx]==null||pets[idx].isEmpty()){asyncSend(talker,"❌序号错误");return;}
            int gold=Integer.parseInt(user.get("gold"));
            if(gold<50){asyncSend(talker,"❌改名需要50金币，当前："+gold);return;}
            gold-=50; String[] pet=upgradePetData(pets[idx]); String oldName=pet[0]; pet[0]=newName;
            pets[idx]=pet[0]+","+pet[1]+","+pet[2]+","+pet[3]+","+pet[4]+","+pet[5]+","+pet[6]+","+pet[7];
            StringBuilder nb=new StringBuilder();for(String ps:pets){if(ps!=null&&!ps.isEmpty())nb.append(ps).append(";");}
            user.put("petBag",nb.toString()); user.put("gold",String.valueOf(gold));
            saveUser(senderWxid,user); asyncSend(talker,"✏️【"+oldName+"】改名为【"+newName+"】\n消耗50金币，剩余："+gold); return;
        }
        if("宠物商店".equals(content)){
            asyncSend(talker,"====宠物商店====\n1.小猫咪 80金【领养猫咪】HP100 攻12\n2.小狗崽 120金【领养小狗】HP120 攻16\n3.小兔子 100金【领养兔子】HP80 攻20\n4.小乌龟 100金【领养乌龟】HP180 攻8\n5.小狐狸 160金【领养狐狸】HP110 攻18\n6.大熊猫 250金【领养熊猫】HP200 攻22\n7.幼龙 500金【领养龙】HP250 攻30\n领养后发送：取名 宠物名"); return;
        }
        if(content.startsWith("领养")){
            String type=content.substring(2).trim();
            String pending=user.get("pendingPet");
            if(pending!=null&&!pending.isEmpty()){asyncSend(talker,"❌你已有一只待取名的宠物，请先发送：取名 名字");return;}
            int price=0,hp=0,atk=0; String showName="";
            if("猫咪".equals(type)){price=80;hp=100;atk=12;showName="小猫咪";}
            else if("小狗".equals(type)){price=120;hp=120;atk=16;showName="小狗崽";}
            else if("兔子".equals(type)){price=100;hp=80;atk=20;showName="小兔子";}
            else if("乌龟".equals(type)){price=100;hp=180;atk=8;showName="小乌龟";}
            else if("狐狸".equals(type)){price=160;hp=110;atk=18;showName="小狐狸";}
            else if("熊猫".equals(type)){price=250;hp=200;atk=22;showName="大熊猫";}
            else if("龙".equals(type)){price=500;hp=250;atk=30;showName="幼龙";}
            else{asyncSend(talker,"❌没有这种宠物，发送【宠物商店】查看");return;}
            int gold=Integer.parseInt(user.get("gold"));
            if(gold<price){asyncSend(talker,"❌金币不足需要"+price+"，当前："+gold);return;}
            gold-=price; user.put("gold",String.valueOf(gold));
            user.put("pendingPet",showName+",1,"+hp+","+hp+","+atk+","+nowMs+",0,0");
            saveUser(senderWxid,user);
            asyncSend(talker,"✅购买成功！请发送：取名 宠物名字\n（不取名字宠物不会进入背包）"); return;
        }
        if(content.startsWith("取名")){
            String pending=user.get("pendingPet");
            if(pending==null||pending.isEmpty()){asyncSend(talker,"❌先领养宠物");return;}
            String petName=content.substring("取名".length()).trim();
            if(petName.isEmpty()){asyncSend(talker,"❌名字不能为空");return;}
            petName=petName.replace(",","").replace(";","").replace("|","").replace("=","");
            if(petName.isEmpty()||petName.length()>12){asyncSend(talker,"❌名字1‑12字，不能含特殊字符");return;}
            String[] base=upgradePetData(pending);
            user.put("petBag",user.get("petBag")+petName+","+base[1]+","+base[2]+","+base[3]+","+base[4]+","+base[5]+","+base[6]+","+base[7]+";");
            user.put("pendingPet","");
            saveUser(senderWxid,user);
            asyncSend(talker,"🎉宠物【"+petName+"】完成！发送【我的宠物】查看"); return;
        }
        if("道具商城".equals(content)){
            asyncSend(talker,"====道具商城====\n1.初级精灵球 60金【购买初级球】捕捉率25%\n2.中级精灵球 130金【购买中级球】捕捉率45%\n3.高级精灵球 240金【购买高级球】捕捉率70%\n4.鲜花 40金【购买鲜花】\n5.经验药水 100金【购买经验药水】出战宠物+50经验\n6.金币袋 80金【购买金币袋】开出50‑150金币\n7.复活药水 60金【购买复活药水】出战宠物HP回满\n8.幸运符 150金【购买幸运符】下次打怪必赢\n9.双倍金币卡 200金【购买双倍金币卡】10分钟内金币翻倍\n10.双倍经验卡 200金【购买双倍经验卡】10分钟内经验翻倍\n\n使用：使用 道具名"); return;
        }
        if("我的道具".equals(content)){
            String itemRaw=user.get("items");
            String[] its=itemRaw.split(",");
            StringBuilder sb=new StringBuilder("====我的道具====\n");
            for(String it:its){ if(it.isEmpty())continue; String[] kv=it.split("="); if(kv.length==2){ String name=kv[0]; if("ball1".equals(name))name="初级精灵球"; else if("ball2".equals(name))name="中级精灵球"; else if("ball3".equals(name))name="高级精灵球"; else if("flower".equals(name))name="鲜花"; else if("expPotion".equals(name))name="经验药水"; else if("goldBag".equals(name))name="金币袋"; else if("revive".equals(name))name="复活药水"; else if("lucky".equals(name))name="幸运符"; else if("doubleGoldCard".equals(name))name="双倍金币卡"; else if("doubleExpCard".equals(name))name="双倍经验卡"; sb.append(name).append("：").append(kv[1]).append("\n"); } }
            if(isDoubleGoldActive(user)){
                long remain = (Long.parseLong(user.get("doubleGoldTs")) - nowMs)/1000;
                sb.append("\n💰双倍金币卡生效中，剩余").append(remain/60).append("分").append(remain%60).append("秒");
            }
            if(isDoubleExpActive(user) && Long.parseLong(user.get("doubleExpTs")) > nowMs){
                long remain = (Long.parseLong(user.get("doubleExpTs")) - nowMs)/1000;
                sb.append("\n⭐双倍经验卡生效中，剩余").append(remain/60).append("分").append(remain%60).append("秒");
            }
            asyncSend(talker,sb.toString()); return;
        }
        if(content.startsWith("购买")){
            String item=content.substring("购买".length()).trim();
            String key=""; int price=0;
            if("初级球".equals(item)){key="ball1";price=60;}
            else if("中级球".equals(item)){key="ball2";price=130;}
            else if("高级球".equals(item)){key="ball3";price=240;}
            else if("鲜花".equals(item)){key="flower";price=40;}
            else if("经验药水".equals(item)){key="expPotion";price=100;}
            else if("金币袋".equals(item)){key="goldBag";price=80;}
            else if("复活药水".equals(item)){key="revive";price=60;}
            else if("幸运符".equals(item)){key="lucky";price=150;}
            else if("双倍金币卡".equals(item)){key="doubleGoldCard";price=200;}
            else if("双倍经验卡".equals(item)){key="doubleExpCard";price=200;}
            else{asyncSend(talker,"❌没有这种道具，发送【道具商城】查看");return;}
            int gold=Integer.parseInt(user.get("gold"));
            if(gold<price){asyncSend(talker,"❌金币不足需要"+price+"，当前："+gold);return;}
            gold-=price; user.put("gold",String.valueOf(gold));
            String[] its=user.get("items").split(",");
            StringBuilder sb=new StringBuilder();
            for(String it:its){ if(it.isEmpty())continue; String[] kv=it.split("="); if(kv.length==2&&kv[0].equals(key)){ sb.append(kv[0]).append("=").append(Integer.parseInt(kv[1])+1).append(","); } else { sb.append(it).append(","); } }
            user.put("items",sb.toString());
            saveUser(senderWxid,user);
            asyncSend(talker,"✅购买"+item+"成功，消耗"+price+"金币，剩余："+gold); return;
        }
        if(content.startsWith("使用")){
            String item=content.substring("使用".length()).trim();
            String key="";
            if("经验药水".equals(item)) key="expPotion";
            else if("金币袋".equals(item)) key="goldBag";
            else if("复活药水".equals(item)) key="revive";
            else if("幸运符".equals(item)) key="lucky";
            else if("双倍金币卡".equals(item)) key="doubleGoldCard";
            else if("双倍经验卡".equals(item)) key="doubleExpCard";
            else{asyncSend(talker,"❌没有这种道具，可用：经验药水/金币袋/复活药水/幸运符/双倍金币卡/双倍经验卡");return;}
            String[] its=user.get("items").split(",");
            int count=0;
            for(String it:its){ String[] kv=it.split("="); if(kv.length==2&&kv[0].equals(key)){ count=Integer.parseInt(kv[1]); break; } }
            if(count<=0){asyncSend(talker,"❌你没有"+item);return;}
            if("expPotion".equals(key) || "revive".equals(key)){
                int battleIdx=Integer.parseInt(user.get("battlePetIdx"));
                String petBag=user.get("petBag");
                if(battleIdx==-1||petBag==null||petBag.isEmpty()){asyncSend(talker,"❌请先设置出战宠物");return;}
                String[] pets=petBag.split(";");
                if(battleIdx>=pets.length||pets[battleIdx]==null||pets[battleIdx].isEmpty()){asyncSend(talker,"❌出战宠物异常");return;}
            }
            StringBuilder sb=new StringBuilder();
            for(String it:its){ if(it.isEmpty())continue; String[] kv=it.split("="); if(kv.length==2&&kv[0].equals(key)){ sb.append(kv[0]).append("=").append(count-1).append(","); } else { sb.append(it).append(","); } }
            user.put("items",sb.toString());
            if("expPotion".equals(key)){
                int battleIdx=Integer.parseInt(user.get("battlePetIdx"));
                String[] pets=user.get("petBag").split(";");
                String[] pet=upgradePetData(pets[battleIdx]);
                int petLv=Integer.parseInt(pet[1]),petMaxHp=Integer.parseInt(pet[3]),exp=Integer.parseInt(user.get("exp"))+50;
                if(isDoubleExpActive(user)) exp += 50;
                user.put("exp",String.valueOf(exp));
                String upg="";
                if(exp>=petLv*120){ exp-=petLv*120; petLv++; petMaxHp+=25; pet[1]=String.valueOf(petLv); pet[2]=String.valueOf(petMaxHp); pet[3]=String.valueOf(petMaxHp); user.put("exp",String.valueOf(exp)); upg="\n🎉升级至Lv."+petLv+"！"; pets[battleIdx]=pet[0]+","+pet[1]+","+pet[2]+","+pet[3]+","+pet[4]+","+pet[5]+","+pet[6]+","+pet[7]; StringBuilder nb=new StringBuilder();for(String ps:pets){if(ps!=null&&!ps.isEmpty())nb.append(ps).append(";");} user.put("petBag",nb.toString()); }
                saveUser(senderWxid,user);
                asyncSend(talker,"🧪使用经验药水！出战宠物+"+(isDoubleExpActive(user)?"100":"50")+"经验"+(isDoubleExpActive(user)?"（双倍经验生效）":"")+upg); return;
            }
            if("goldBag".equals(key)){
                int gain=50+(int)(Math.random()*101);
                if(isDoubleGoldActive(user)) gain *= 2;
                int gold=Integer.parseInt(user.get("gold"))+gain;
                user.put("gold",String.valueOf(gold));
                saveUser(senderWxid,user);
                asyncSend(talker,"💰打开金币袋！获得"+gain+"金币"+(isDoubleGoldActive(user)?"（双倍金币生效）":"")+"，当前："+gold); return;
            }
            if("revive".equals(key)){
                int battleIdx=Integer.parseInt(user.get("battlePetIdx"));
                String[] pets=user.get("petBag").split(";");
                String[] pet=upgradePetData(pets[battleIdx]);
                pet[2]=pet[3];
                pets[battleIdx]=pet[0]+","+pet[1]+","+pet[2]+","+pet[3]+","+pet[4]+","+pet[5]+","+pet[6]+","+pet[7];
                StringBuilder nb=new StringBuilder();for(String ps:pets){if(ps!=null&&!ps.isEmpty())nb.append(ps).append(";");}
                user.put("petBag",nb.toString());
                saveUser(senderWxid,user);
                asyncSend(talker,"💉使用复活药水！【"+pet[0]+"】HP已回满"); return;
            }
            if("lucky".equals(key)){
                user.put("luckyBuff","1");
                saveUser(senderWxid,user);
                asyncSend(talker,"🍀使用幸运符！下次打怪必赢！"); return;
            }
            if("doubleGoldCard".equals(key)){
                user.put("doubleGoldTs", String.valueOf(nowMs + 10*60*1000));
                saveUser(senderWxid,user);
                asyncSend(talker,"💰使用双倍金币卡！10分钟内所有金币收益翻倍"); return;
            }
            if("doubleExpCard".equals(key)){
                user.put("doubleExpTs", String.valueOf(nowMs + 10*60*1000));
                saveUser(senderWxid,user);
                asyncSend(talker,"⭐使用双倍经验卡！10分钟内所有经验收益翻倍"); return;
            }
        }

        // ========== 钓鱼 ==========
        if("钓鱼".equals(content)){
            if(isBankruptProtected(user)){ asyncSend(talker,"❌破产保护中，无法钓鱼（金币为0）"); return; }
            // 使用北京时间天数判断（修复UTC时区导致凌晨0-8点不刷新bug）
            int fishDay=Integer.parseInt(user.get("fishDay")),fishCount=Integer.parseInt(user.get("fishCount")),currDay=getBeijingDayNumber();
            if(fishDay!=currDay){ user.put("fishDay",String.valueOf(currDay)); user.put("fishCount","0"); fishCount=0; }
            if(fishCount>=8){asyncSend(talker,"❌今日钓鱼次数用尽");return;}
            fishCount++; user.put("fishCount",String.valueOf(fishCount));
            int gold=Integer.parseInt(user.get("gold"));
            String result = "";
            if(Math.random()<0.35){ result = "🎣鱼跑掉一无所获"; }
            else{ int gain=(int)(Math.random()*40)+15; if(isDoubleGoldActive(user)) gain*=2; gold+=gain; user.put("gold",String.valueOf(gold)); result = "🎣钓鱼成功！获得"+gain+"金币"+(isDoubleGoldActive(user)?"（双倍金币生效）":"")+"，当前："+gold; }
            incTask(user,"fish");
            String randomEvent = triggerRandomEvent(talker, user, senderWxid);
            saveUser(senderWxid,user);
            asyncSend(talker, result + randomEvent); return;
        }

        // ========== 打怪 ==========
        if("打怪".equals(content)){
            if(isBankruptProtected(user)){ asyncSend(talker,"❌破产保护中，无法打怪（金币为0）"); return; }
            int battlePetIdx=Integer.parseInt(user.get("battlePetIdx"));
            String petBagRaw=user.get("petBag");
            if(battlePetIdx==-1||petBagRaw==null||petBagRaw.isEmpty()){asyncSend(talker,"❌请先领养宠物并设置出战");return;}
            String[] pets=petBagRaw.split(";");
            if(battlePetIdx>=pets.length||pets[battlePetIdx]==null||pets[battlePetIdx].isEmpty()){asyncSend(talker,"❌出战宠物不存在，请重新设置出战");return;}
            String[] pet=upgradePetData(pets[battlePetIdx]);
            int petHp=Integer.parseInt(pet[2]);
            if(petHp<=0){asyncSend(talker,"❌出战宠物血量为0，请先【回血】或使用复活药水");return;}
            long now=System.currentTimeMillis(); long cd=10L*60*1000;
            long battleTs=Long.parseLong(user.get("battleTs")); int battleTimes=Integer.parseInt(user.get("battleTimes"));
            if((now-battleTs)<cd&&battleTimes>=5){asyncSend(talker,"❌10分钟最多打怪5次，请等待冷却");return;}
            if((now-battleTs)>=cd){ battleTimes=0; user.put("battleTs",String.valueOf(now)); }
            battleTimes++; user.put("battleTimes",String.valueOf(battleTimes));
            int petLv=Integer.parseInt(pet[1]),petMaxHp=Integer.parseInt(pet[3]);
            int monsterLv=petLv+(int)((Math.random()-0.45)*4); if(monsterLv<1)monsterLv=1;
            String[] mNames={"野狼","野熊","山猫","毒蜥蜴","魔野兔"};
            String mName=mNames[(int)(Math.random()*mNames.length)];
            int mHp=60+monsterLv*18,mAtk=8+monsterLv*6;
            boolean hasLucky = "1".equals(user.get("luckyBuff"));
            double winRate = hasLucky ? 1.0 : 0.70;
            if(hasLucky) user.put("luckyBuff","0");
            double roll=Math.random();
            int gold=Integer.parseInt(user.get("gold")),exp=Integer.parseInt(user.get("exp"));
            int petBattles = Integer.parseInt(pet[6]) + 1;
            pet[6] = String.valueOf(petBattles);
            if(roll<winRate){
                int takeDmg = (int)(mAtk * (0.3 + Math.random() * 0.3));
                petHp -= takeDmg;
                if(petHp < 0) petHp = 0;
                pet[2] = String.valueOf(petHp);
                int goldGain=20+petLv*12+(int)(Math.random()*30),expGain=15+monsterLv*8;
                if(isDoubleGoldActive(user)) goldGain*=2;
                if(isDoubleExpActive(user)) expGain*=2;
                gold+=goldGain; exp+=expGain;
                user.put("gold",String.valueOf(gold)); user.put("exp",String.valueOf(exp));
                int petWins = Integer.parseInt(pet[7]) + 1;
                pet[7] = String.valueOf(petWins);
                int needExp=petLv*120;
                String resultMsg = "";
                if(exp>=needExp){
                    exp-=needExp; petLv++; petMaxHp+=25; petHp=petMaxHp;
                    pet[1]=String.valueOf(petLv);pet[3]=String.valueOf(petMaxHp);pet[2]=String.valueOf(petHp);
                    user.put("exp",String.valueOf(exp));
                    pets[battlePetIdx]=pet[0]+","+pet[1]+","+pet[2]+","+pet[3]+","+pet[4]+","+pet[5]+","+pet[6]+","+pet[7];
                    StringBuilder nb=new StringBuilder();for(String ps:pets){if(ps!=null&&!ps.isEmpty())nb.append(ps).append(";");}
                    user.put("petBag",nb.toString());
                    resultMsg = (hasLucky?"🍀幸运符生效！":"")+"⚔️战斗胜利！击败"+mName+"Lv."+monsterLv+"\n获得"+goldGain+"金币 "+expGain+"经验\n💥受到反击伤害"+takeDmg+"\n🎉宠物升级至Lv."+petLv+"，HP回满\n❤️HP:"+petHp+"/"+petMaxHp+"\n当前金币："+gold;
                }else{
                    pets[battlePetIdx]=pet[0]+","+pet[1]+","+pet[2]+","+pet[3]+","+pet[4]+","+pet[5]+","+pet[6]+","+pet[7];
                    StringBuilder nb=new StringBuilder();for(String ps:pets){if(ps!=null&&!ps.isEmpty())nb.append(ps).append(";");}
                    user.put("petBag",nb.toString());
                    resultMsg = (hasLucky?"🍀幸运符生效！":"")+"⚔️战斗胜利！击败"+mName+"Lv."+monsterLv+"\n获得"+goldGain+"金币 "+expGain+"经验\n💥受到反击伤害"+takeDmg+"\n❤️HP:"+petHp+"/"+petMaxHp+"\n当前金币："+gold;
                }
                if(Math.random()<0.25){ user.put("catchCtx",mName+","+monsterLv+","+mHp); resultMsg += "\n💥怪物残血！回复【是】捕捉 /【否】放弃"; }
                else user.put("catchCtx","");
                String randomEvent = triggerRandomEvent(talker, user, senderWxid);
                incTask(user,"battle");
                saveUser(senderWxid,user);
                asyncSend(talker, resultMsg + randomEvent); return;
            }else{
                int dmg=(int)(mAtk*(0.4+Math.random()*0.4));
                petHp-=dmg; if(petHp<0)petHp=0;
                pet[2]=String.valueOf(petHp);
                pets[battlePetIdx]=pet[0]+","+pet[1]+","+pet[2]+","+pet[3]+","+pet[4]+","+pet[5]+","+pet[6]+","+pet[7];
                StringBuilder nb=new StringBuilder();for(String ps:pets){if(ps!=null&&!ps.isEmpty())nb.append(ps).append(";");}
                user.put("petBag",nb.toString());
                user.put("catchCtx","");
                incTask(user,"battle");
                saveUser(senderWxid,user);
                asyncSend(talker,"💔战斗失败！遭遇"+mName+"Lv."+monsterLv+"\n宠物受到"+dmg+"伤害\n❤️HP:"+petHp+"/"+petMaxHp); return;
            }
        }

        // ========== 回血 ==========
        if("回血".equals(content)){
            int battlePetIdx=Integer.parseInt(user.get("battlePetIdx"));
            String petBagRaw=user.get("petBag");
            if(battlePetIdx==-1||petBagRaw==null||petBagRaw.isEmpty()){asyncSend(talker,"❌请先设置出战宠物");return;}
            String[] pets=petBagRaw.split(";");
            if(battlePetIdx>=pets.length||pets[battlePetIdx]==null||pets[battlePetIdx].isEmpty()){asyncSend(talker,"❌出战宠物不存在");return;}
            String[] pet=upgradePetData(pets[battlePetIdx]);
            int petHp=Integer.parseInt(pet[2]),petMaxHp=Integer.parseInt(pet[3]);
            if(petHp>=petMaxHp){asyncSend(talker,"✅宠物已是满血");return;}
            int cost=(int)((petMaxHp-petHp)*0.45);
            if(cost < 1) cost = 1;
            int gold=Integer.parseInt(user.get("gold"));
            if(gold<cost){asyncSend(talker,"❌金币不足，回血需要"+cost+"，当前："+gold);return;}
            gold-=cost; petHp=petMaxHp; pet[2]=String.valueOf(petHp);
            pets[battlePetIdx]=pet[0]+","+pet[1]+","+pet[2]+","+pet[3]+","+pet[4]+","+pet[5]+","+pet[6]+","+pet[7];
            StringBuilder nb=new StringBuilder();for(String ps:pets){if(ps!=null&&!ps.isEmpty())nb.append(ps).append(";");}
            user.put("petBag",nb.toString()); user.put("gold",String.valueOf(gold));
            saveUser(senderWxid,user);
            asyncSend(talker,"✅回血完成，消耗"+cost+"金币，剩余金币："+gold); return;
        }

        // ========== 打工 ==========
        if("打工".equals(content)){
            if(isBankruptProtected(user)){ asyncSend(talker,"❌破产保护中，无法打工（金币为0，请等待恢复）"); return; }
            long workCd=5*60*1000,workTs=Long.parseLong(user.get("workTs")),now=System.currentTimeMillis();
            if(now-workTs<workCd){long remain=(workCd-(now-workTs))/1000;asyncSend(talker,"⏱打工冷却中，还需"+(remain/60)+"分"+(remain%60)+"秒");return;}
            user.put("workTs",String.valueOf(now));
            int gold=Integer.parseInt(user.get("gold")),earn=15+(int)(Math.random()*25);
            if(isDoubleGoldActive(user)) earn*=2;
            gold+=earn; user.put("gold",String.valueOf(gold));
            String extra="";
            if(Math.random()<0.12){int bonus=30+(int)(Math.random()*30); if(isDoubleGoldActive(user)) bonus*=2; gold+=bonus;user.put("gold",String.valueOf(gold));extra="\n🌟老板赏识！额外奖励"+bonus+"金币";}
            incTask(user,"work");
            saveUser(senderWxid,user);
            asyncSend(talker,"💼打工完成！辛苦赚了"+earn+"金币"+(isDoubleGoldActive(user)?"（双倍金币生效）":"")+extra+"\n当前金币："+gold); return;
        }

        // ========== PK ==========
        if(content.startsWith("PK")){
            if(isBankruptProtected(user)){ asyncSend(talker,"❌破产保护中，无法PK（金币为0）"); return; }
            long pkCd=3*60*1000,pkTs=Long.parseLong(user.get("pkTs")),now=System.currentTimeMillis();
            if(now-pkTs<pkCd){long remain=(pkCd-(now-pkTs))/1000;asyncSend(talker,"⏱PK冷却中，还需"+(remain/60)+"分"+(remain%60)+"秒");return;}
            String foeInput = content.substring(2).trim();
            String foeWx = resolveTarget(foeInput);
            if(foeWx==null||foeWx.equals(senderWxid)){asyncSend(talker,"❌请输入对手ID/wxid/昵称\n例：PK 00001\n（对方需使用过本插件）");return;}
            String pkTargetTsRaw = user.get("pkTargetTs");
            try{
                JSONObject pkTargetTs = JSON.parseObject(pkTargetTsRaw);
                if(pkTargetTs.containsKey(foeWx)){
                    long lastPk = pkTargetTs.getLongValue(foeWx);
                    if(now - lastPk < 10*60*1000){
                        long remain = (10*60*1000 - (now-lastPk))/1000;
                        asyncSend(talker,"⏱对同一玩家PK冷却中，还需"+(remain/60)+"分"+(remain%60)+"秒才能再次挑战该玩家");
                        return;
                    }
                }
                pkTargetTs.put(foeWx, now);
                user.put("pkTargetTs", pkTargetTs.toJSONString());
            }catch(Throwable t){
                JSONObject newMap = new JSONObject();
                newMap.put(foeWx, now);
                user.put("pkTargetTs", newMap.toJSONString());
            }
            int myIdx=Integer.parseInt(user.get("battlePetIdx")); String myBag=user.get("petBag");
            if(myIdx==-1||myBag==null||myBag.isEmpty()){asyncSend(talker,"❌你还没有出战宠物，请先【设置出战】");return;}
            String[] myPets=myBag.split(";");
            if(myIdx>=myPets.length||myPets[myIdx]==null||myPets[myIdx].isEmpty()){asyncSend(talker,"❌出战宠物异常");return;}
            Map<String,String> foe=getUser(foeWx);
            int foeIdx=Integer.parseInt(foe.get("battlePetIdx")); String foeBag=foe.get("petBag");
            if(foeIdx==-1||foeBag==null||foeBag.isEmpty()){asyncSend(talker,"❌对方还没有出战宠物，无法PK");return;}
            String[] foePets=foeBag.split(";");
            if(foeIdx>=foePets.length||foePets[foeIdx]==null||foePets[foeIdx].isEmpty()){asyncSend(talker,"❌对方出战宠物异常");return;}
            user.put("pkTs",String.valueOf(now));
            String[] myPet=upgradePetData(myPets[myIdx]),foePet=upgradePetData(foePets[foeIdx]);
            int myHp=Integer.parseInt(myPet[2]),myAtk=Integer.parseInt(myPet[4]),foeHp=Integer.parseInt(foePet[2]),foeAtk=Integer.parseInt(foePet[4]);
            int myBattles = Integer.parseInt(myPet[6]) + 1;
            myPet[6] = String.valueOf(myBattles);
            int foeBattles = Integer.parseInt(foePet[6]) + 1;
            foePet[6] = String.valueOf(foeBattles);
            StringBuilder log=new StringBuilder();
            log.append("⚔️宠物PK开始！\n🟢你：").append(myPet[0]).append(" Lv.").append(myPet[1]).append(" HP").append(myHp).append(" 攻").append(myAtk).append("\n🔴对手：").append(foePet[0]).append(" Lv.").append(foePet[1]).append(" HP").append(foeHp).append(" 攻").append(foeAtk).append("\n\n");
            boolean myTurn=true; int round=0;
            while(myHp>0&&foeHp>0&&round<12){
                round++;
                if(myTurn){ int dmg=(int)(myAtk*(0.8+Math.random()*0.4)); boolean crit=Math.random()<0.15; if(crit)dmg=(int)(dmg*1.5); foeHp-=dmg; if(foeHp<0)foeHp=0; log.append("R").append(round).append(crit?"💥暴击!":"").append(" 你的").append(myPet[0]).append("造成").append(dmg).append("伤害，对方剩").append(foeHp).append("HP\n"); }
                else{ int dmg=(int)(foeAtk*(0.8+Math.random()*0.4)); boolean crit=Math.random()<0.15; if(crit)dmg=(int)(dmg*1.5); myHp-=dmg; if(myHp<0)myHp=0; log.append("R").append(round).append(crit?"💥暴击!":"").append(" 对方的").append(foePet[0]).append("造成").append(dmg).append("伤害，你剩").append(myHp).append("HP\n"); }
                myTurn=!myTurn;
            }
            myPet[2] = String.valueOf(myHp);
            myPets[myIdx] = myPet[0]+","+myPet[1]+","+myPet[2]+","+myPet[3]+","+myPet[4]+","+myPet[5]+","+myPet[6]+","+myPet[7];
            StringBuilder myNb = new StringBuilder();
            for(String ps:myPets){ if(ps!=null&&!ps.isEmpty()) myNb.append(ps).append(";"); }
            user.put("petBag", myNb.toString());
            foePet[2] = String.valueOf(foeHp);
            foePets[foeIdx] = foePet[0]+","+foePet[1]+","+foePet[2]+","+foePet[3]+","+foePet[4]+","+foePet[5]+","+foePet[6]+","+foePet[7];
            StringBuilder foeNb = new StringBuilder();
            for(String ps:foePets){ if(ps!=null&&!ps.isEmpty()) foeNb.append(ps).append(";"); }
            foe.put("petBag", foeNb.toString());
            saveUser(foeWx, foe);
            if(myHp>0&&foeHp<=0){
                int myWins = Integer.parseInt(myPet[7]) + 1;
                myPet[7] = String.valueOf(myWins);
                myPets[myIdx] = myPet[0]+","+myPet[1]+","+myPet[2]+","+myPet[3]+","+myPet[4]+","+myPet[5]+","+myPet[6]+","+myPet[7];
                StringBuilder myNb2 = new StringBuilder();
                for(String ps:myPets){ if(ps!=null&&!ps.isEmpty()) myNb2.append(ps).append(";"); }
                user.put("petBag", myNb2.toString());
                int reward=30+Integer.parseInt(myPet[1])*5;
                if(isDoubleGoldActive(user)) reward*=2;
                int g=Integer.parseInt(user.get("gold"))+reward; user.put("gold",String.valueOf(g)); saveUser(senderWxid,user); log.append("\n🎉你赢了！获得").append(reward).append("金币").append(isDoubleGoldActive(user)?"（双倍金币生效）":"");
            }
            else if(foeHp>0&&myHp<=0){
                int foeWins = Integer.parseInt(foePet[7]) + 1;
                foePet[7] = String.valueOf(foeWins);
                foePets[foeIdx] = foePet[0]+","+foePet[1]+","+foePet[2]+","+foePet[3]+","+foePet[4]+","+foePet[5]+","+foePet[6]+","+foePet[7];
                StringBuilder foeNb2 = new StringBuilder();
                for(String ps:foePets){ if(ps!=null&&!ps.isEmpty()) foeNb2.append(ps).append(";"); }
                foe.put("petBag", foeNb2.toString());
                saveUser(foeWx, foe);
                saveUser(senderWxid,user); log.append("\n💔你输了...下次加油！");
            }
            else{ saveUser(senderWxid,user); log.append("\n⚖️平局，势均力敌！"); }
            asyncSend(talker,log.toString()); return;
        }

        // ========== 捕捉 ==========
        if("是".equals(content)){
            String catchCtx=user.get("catchCtx");
            if(catchCtx==null||catchCtx.isEmpty()) return;
            String[] ctx=catchCtx.split(","); String mName=ctx[0]; int mLv=Integer.parseInt(ctx[1]);
            String[] items=user.get("items").split(",");
            int b1=0,b2=0,b3=0;
            for(String it:items){ String[] kv=it.split("="); if(kv.length==2){ if("ball1".equals(kv[0]))b1=Integer.parseInt(kv[1]); else if("ball2".equals(kv[0]))b2=Integer.parseInt(kv[1]); else if("ball3".equals(kv[0]))b3=Integer.parseInt(kv[1]); } }
            int ballType=0;
            if(b3>0){ballType=3;b3--;} else if(b2>0){ballType=2;b2--;} else if(b1>0){ballType=1;b1--;}
            if(ballType==0){asyncSend(talker,"❌背包没有精灵球，怪物逃跑");user.put("catchCtx","");saveUser(senderWxid,user);return;}
            StringBuilder itemsb=new StringBuilder();
            for(String it:items){ if(it.isEmpty())continue; String[] kv=it.split("="); if(kv.length==2){ if("ball1".equals(kv[0]))itemsb.append("ball1=").append(b1).append(","); else if("ball2".equals(kv[0]))itemsb.append("ball2=").append(b2).append(","); else if("ball3".equals(kv[0]))itemsb.append("ball3=").append(b3).append(","); else itemsb.append(it).append(","); } }
            user.put("items",itemsb.toString());
            double succRate = ballType==1?0.25:ballType==2?0.45:0.70;
            if(Math.random()<succRate){
                int newPetHp=40+mLv*12,newAtk=6+mLv*5;
                user.put("petBag",user.get("petBag")+mName+","+mLv+","+newPetHp+","+newPetHp+","+newAtk+","+nowMs+",0,0;");
                asyncSend(talker,"✅捕捉成功！获得【"+mName+"】，去我的宠物查看");
            }else{ asyncSend(talker,"❌精灵球晃动，怪物挣脱逃跑"); }
            user.put("catchCtx","");saveUser(senderWxid,user); return;
        }
        if("否".equals(content)){
            String catchCtx=user.get("catchCtx");
            if(catchCtx!=null&&!catchCtx.isEmpty()){ user.put("catchCtx","");saveUser(senderWxid,user); asyncSend(talker,"你放弃捕捉，怪物逃走"); }
            return;
        }

        // ========== 每日任务 ==========
        if("每日任务".equals(content)){
            String[] todayTasks=getTodayTasks(nowMs);
            int rewardGot=Integer.parseInt(user.get("dailyRewardGet"));
            boolean allDone=true;
            StringBuilder sb=new StringBuilder("====每日任务====\n");
            for(String tk:todayTasks){
                int cnt=getTaskCount(user,tk),target=taskTarget(tk);
                if(cnt<target) allDone=false;
                sb.append(taskName(tk)).append("：").append(cnt>=target?"✅完成":"❌"+cnt+"/"+target).append("\n");
            }
            if(allDone){
                if(rewardGot==0){ int g=Integer.parseInt(user.get("gold"))+100; user.put("gold",String.valueOf(g)); user.put("dailyRewardGet","1"); saveUser(senderWxid,user); sb.append("\n🎉全部完成！领取奖励100金币"); }
                else sb.append("\n✅今日奖励已领取，明天再来");
            }else{ sb.append("\n完成全部任务领取100金币奖励"); }
            asyncSend(talker,sb.toString()); return;
        }

        // ========== 好友系统 ==========
        if("好友列表".equals(content)){
            String friends = user.get("friends");
            if(friends == null || friends.isEmpty()){ asyncSend(talker,"👥好友列表为空\n添加好友：添加好友 ID/wxid/昵称\n例：添加好友 00001"); return; }
            String[] friendList = friends.split(",");
            StringBuilder sb = new StringBuilder("👥====好友列表（共"+friendList.length+"人）====\n");
            for(int i=0;i<friendList.length;i++){
                String fw = friendList[i];
                if(fw.isEmpty()) continue;
                Map<String,String> fu = getUser(fw);
                String fnick = fu.get("nick");
                String fpet = "无宠物";
                int fidx = Integer.parseInt(fu.get("battlePetIdx"));
                String fbag = fu.get("petBag");
                if(fidx != -1 && fbag != null && !fbag.isEmpty()){
                    String[] fpets = fbag.split(";");
                    if(fidx < fpets.length && fpets[fidx] != null && !fpets[fidx].isEmpty()){
                        String[] fp = upgradePetData(fpets[fidx]);
                        fpet = fp[0]+" Lv."+fp[1]+" HP"+fp[2]+"/"+fp[3];
                    }
                }
                sb.append(i+1).append(". ").append(fnick).append("\n  出战宠物：").append(fpet).append("\n  金币：").append(fu.get("gold")).append("｜等级：Lv.").append(fu.get("level")).append("\n");
            }
            sb.append("\n指令：好友PK ID/昵称｜删除好友 ID/昵称");
            asyncSend(talker, sb.toString()); return;
        }
        if(content.startsWith("添加好友")){
            String targetInput = content.substring(4).trim();
            String targetWx = resolveTarget(targetInput);
            if(targetWx == null || targetWx.equals(senderWxid)){ asyncSend(talker,"❌格式：添加好友 ID/wxid/昵称\n例：添加好友 00001"); return; }
            Map<String,String> targetUser = getUser(targetWx);
            if(targetUser.get("nick").equals("未设置昵称")){ asyncSend(talker,"❌对方未使用过本插件，无法添加"); return; }
            String friends = user.get("friends");
            if(friends == null) friends = "";
            if(friends.contains(targetWx)){ asyncSend(talker,"❌你们已经是好友了"); return; }
            if(!friends.isEmpty() && !friends.endsWith(",")) friends += ",";
            friends += targetWx + ",";
            user.put("friends", friends);
            saveUser(senderWxid, user);
            asyncSend(talker,"✅已添加好友："+targetUser.get("nick")); return;
        }
        if(content.startsWith("删除好友")){
            String targetInput = content.substring(4).trim();
            String targetWx = resolveTarget(targetInput);
            if(targetWx == null){ asyncSend(talker,"❌格式：删除好友 ID/wxid/昵称\n例：删除好友 00001"); return; }
            String friends = user.get("friends");
            if(friends == null || !friends.contains(targetWx)){ asyncSend(talker,"❌你们不是好友"); return; }
            friends = friends.replace(targetWx + ",", "");
            user.put("friends", friends);
            saveUser(senderWxid, user);
            asyncSend(talker,"✅已删除好友"); return;
        }
        if(content.startsWith("好友PK")){
            String targetInput = content.substring(4).trim();
            String targetWx = resolveTarget(targetInput);
            if(targetWx == null || targetWx.equals(senderWxid)){ asyncSend(talker,"❌格式：好友PK ID/wxid/昵称\n例：好友PK 00001"); return; }
            String friends = user.get("friends");
            if(friends == null || !friends.contains(targetWx)){ asyncSend(talker,"❌对方不是你的好友，先添加好友"); return; }
            int myIdx=Integer.parseInt(user.get("battlePetIdx")); String myBag=user.get("petBag");
            if(myIdx==-1||myBag==null||myBag.isEmpty()){asyncSend(talker,"❌你还没有出战宠物");return;}
            Map<String,String> foe=getUser(targetWx);
            int foeIdx=Integer.parseInt(foe.get("battlePetIdx")); String foeBag=foe.get("petBag");
            if(foeIdx==-1||foeBag==null||foeBag.isEmpty()){asyncSend(talker,"❌对方还没有出战宠物");return;}
            String[] myPets=myBag.split(";"), foePets=foeBag.split(";");
            String[] myPet=upgradePetData(myPets[myIdx]),foePet=upgradePetData(foePets[foeIdx]);
            int myHp=Integer.parseInt(myPet[2]),myAtk=Integer.parseInt(myPet[4]),foeHp=Integer.parseInt(foePet[2]),foeAtk=Integer.parseInt(foePet[4]);
            StringBuilder log=new StringBuilder();
            log.append("⚔️好友切磋（无奖惩）\n🟢你：").append(myPet[0]).append(" HP").append(myHp).append(" 攻").append(myAtk).append("\n🔴").append(foe.get("nick")).append("：").append(foePet[0]).append(" HP").append(foeHp).append(" 攻").append(foeAtk).append("\n\n");
            boolean myTurn=true; int round=0;
            while(myHp>0&&foeHp>0&&round<12){
                round++;
                if(myTurn){ int dmg=(int)(myAtk*(0.8+Math.random()*0.4)); foeHp-=dmg; if(foeHp<0)foeHp=0; log.append("R").append(round).append(" 你的").append(myPet[0]).append("造成").append(dmg).append("伤害，对方剩").append(foeHp).append("HP\n"); }
                else{ int dmg=(int)(foeAtk*(0.8+Math.random()*0.4)); myHp-=dmg; if(myHp<0)myHp=0; log.append("R").append(round).append(" 对方的").append(foePet[0]).append("造成").append(dmg).append("伤害，你剩").append(myHp).append("HP\n"); }
                myTurn=!myTurn;
            }
            if(myHp>0&&foeHp<=0) log.append("\n🎉你赢了切磋！（无奖励）");
            else if(foeHp>0&&myHp<=0) log.append("\n💔你输了切磋！（无惩罚）");
            else log.append("\n⚖️平局！");
            asyncSend(talker,log.toString()); return;
        }

        // ========== 世界Boss ==========
        if("#刷新Boss".equals(content)){
            if(!ADMIN_SESSION.containsKey(senderWxid)){ asyncSend(talker,"❌请先登录管理员（授权者不可用）"); return; }
            String[] bossNames = {"远古巨龙","深渊魔王","暗影巨兽","天界战神","混沌邪神"};
            String bossName = bossNames[(int)(Math.random()*bossNames.length)];
            int bossHp = 5000 + (int)(Math.random()*5000);
            int bossAtk = 100 + (int)(Math.random()*100);
            BOSS_STATE.put("active", "1");
            BOSS_STATE.put("name", bossName);
            BOSS_STATE.put("hp", String.valueOf(bossHp));
            BOSS_STATE.put("maxHp", String.valueOf(bossHp));
            BOSS_STATE.put("atk", String.valueOf(bossAtk));
            BOSS_STATE.put("participants", "");
            BOSS_STATE.put("startTime", String.valueOf(nowMs));
            saveBossState();
            asyncSend(talker,"🐉世界Boss已刷新！\nBoss："+bossName+"\n血量："+bossHp+"\n攻击："+bossAtk+"\n\n发送【#参与打boss】加入战斗！\n（至少需要5人参与，3分钟后开战）");
            return;
        }
        if("#参与打boss".equals(content)){
            if(!"1".equals(BOSS_STATE.get("active"))){ asyncSend(talker,"❌当前没有世界Boss，等待管理员刷新"); return; }
            String participants = BOSS_STATE.get("participants");
            if(participants == null) participants = "";
            if(participants.contains(senderWxid)){ asyncSend(talker,"❌你已经参与了本次Boss战"); return; }
            if(!participants.isEmpty() && !participants.endsWith(",")) participants += ",";
            participants += senderWxid + ",";
            BOSS_STATE.put("participants", participants);
            saveBossState();
            int count = 0;
            for(String p : participants.split(",")){ if(p != null && !p.isEmpty()) count++; }
            asyncSend(talker,"✅你已加入世界Boss战！\n当前参与人数："+count+"/5\n"+(count>=5?"人数已满，即将开战！":"还需"+(5-count)+"人参与"));
            if(count >= 5){
                new Thread(new Runnable(){
                    public void run(){
                        try{ Thread.sleep(3000); }catch(Throwable t){}
                        String bossName = BOSS_STATE.get("name");
                        int bossHp = Integer.parseInt(BOSS_STATE.get("hp"));
                        int bossMaxHp = Integer.parseInt(BOSS_STATE.get("maxHp"));
                        int bossAtk = Integer.parseInt(BOSS_STATE.get("atk"));
                        String parts = BOSS_STATE.get("participants");
                        ArrayList<String> playerList = new ArrayList<String>();
                        for(String p : parts.split(",")){ if(p != null && !p.isEmpty()) playerList.add(p); }
                        int playerCount = playerList.size();
                        int totalDmg = 0;
                        StringBuilder battleLog = new StringBuilder();
                        battleLog.append("🐉=====世界Boss战开始！=====\nBoss：").append(bossName).append(" HP").append(bossHp).append("\n参与人数：").append(playerCount).append("人\n\n");
                        int round = 0;
                        while(bossHp > 0 && round < 20){
                            round++;
                            int roundDmg = 0;
                            for(String pw : playerList){
                                if(pw.isEmpty()) continue;
                                Map<String,String> pu = getUser(pw);
                                int pidx = Integer.parseInt(pu.get("battlePetIdx"));
                                String pbag = pu.get("petBag");
                                int pAtk = 10;
                                if(pidx != -1 && pbag != null && !pbag.isEmpty()){
                                    String[] ppets = pbag.split(";");
                                    if(pidx < ppets.length && ppets[pidx] != null && !ppets[pidx].isEmpty()){
                                        String[] pp = upgradePetData(ppets[pidx]);
                                        pAtk = Integer.parseInt(pp[4]);
                                    }
                                }
                                int dmg = (int)(pAtk * (1.5 + Math.random()));
                                if(Math.random() < 0.1) dmg *= 2;
                                bossHp -= dmg;
                                roundDmg += dmg;
                                if(bossHp <= 0) break;
                            }
                            totalDmg += roundDmg;
                            battleLog.append("第").append(round).append("轮：全体造成").append(roundDmg).append("伤害，Boss剩余").append(bossHp>0?bossHp:0).append("HP\n");
                            if(bossHp <= 0) break;
                            int bossDmg = bossAtk / playerCount;
                            battleLog.append("Boss反击：每人受到").append(bossDmg).append("伤害\n");
                        }
                        if(bossHp <= 0){
                            battleLog.append("\n🎉Boss被击败！总伤害：").append(totalDmg).append("\n每位参与者获得500金币奖励！");
                            for(String pw : playerList){
                                if(pw.isEmpty()) continue;
                                Map<String,String> pu = getUser(pw);
                                int g = Integer.parseInt(pu.get("gold")) + 500;
                                pu.put("gold", String.valueOf(g));
                                saveUser(pw, pu);
                            }
                        }else{
                            battleLog.append("\n💔Boss未被击败，剩余").append(bossHp).append("HP");
                        }
                        BOSS_STATE.put("active", "0");
                        BOSS_STATE.put("participants", "");
                        saveBossState();
                        sendText(talker, battleLog.toString());
                    }
                }).start();
            }
            return;
        }

        // ========== 娱乐功能 ==========
        if("人品".equals(content)){
            double rnd=Math.random()*100; String desc;
            if(rnd>=90)desc="🌟绝世欧皇，今日运气爆棚！"; else if(rnd>=70)desc="✨运气不错，适合打怪钓鱼"; else if(rnd>=40)desc="🙂平平无奇，正常发挥"; else if(rnd>=20)desc="😮‍💨运气一般，谨慎行动"; else desc="💣非酋附体，建议收手";
            asyncSend(talker,"🎲今日人品："+(int)rnd+"分\n"+desc); return;
        }
        if("抽签".equals(content)){
            String[] qian={"【大吉】万事顺遂","【吉】运势尚可","【平】得失参半","【凶】运势低迷"};
            asyncSend(talker,"🎋抽签结果：\n"+qian[(int)(Math.random()*qian.length)]); return;
        }
        if("求财神".equals(content)){
            if(isBankruptProtected(user)){ asyncSend(talker,"❌破产保护中，无法求财神"); return; }
            String today = getBeijingDateStr();
            String fortuneDay = user.get("fortuneDay");
            int fortuneCount = Integer.parseInt(user.get("fortuneCount"));
            if(!today.equals(fortuneDay)){ fortuneCount = 0; user.put("fortuneDay",today); }
            if(fortuneCount >= 3){ asyncSend(talker,"⏱求财神每天最多3次，明天再来吧"); return; }
            fortuneCount++; user.put("fortuneCount",String.valueOf(fortuneCount));
            double luck=Math.random(); int gold=Integer.parseInt(user.get("gold"));
            if(luck>0.65){ int getGold=20+(int)(Math.random()*60); if(isDoubleGoldActive(user)) getGold*=2; gold+=getGold; user.put("gold",String.valueOf(gold));saveUser(senderWxid,user); asyncSend(talker,"💰财神显灵！获得 "+getGold+" 金币，当前金币："+gold+"（今日第"+fortuneCount+"/3次）"); }
            else { saveUser(senderWxid,user); asyncSend(talker,"😶财神今日没空，什么都没给到你（今日第"+fortuneCount+"/3次）"); }
            return;
        }
        if(content.startsWith("点歌")){
            if("0".equals(user.get("musicEnable"))){asyncSend(talker,"❌点歌功能已关闭（管理员可发送 #开启点歌 开启）");return;}
            String songName=content.substring("点歌".length()).trim();
            if(songName.isEmpty()){asyncSend(talker,"🎵请发送：点歌 歌曲名");return;}
            final String sn = songName;
            new Thread(new Runnable(){ public void run(){ GetNeteaseMusic(talker, sn); } }).start();
            return;
        }
        if("网易云".equals(content)||"语录".equals(content)||"一言".equals(content)||"热门语录".equals(content)){
            new Thread(new Runnable(){ public void run(){ GetHitokoto(talker); } }).start();
            return;
        }
        if("今日新闻".equals(content) || "/60s".equals(content) || "60s".equals(content) || "/每日60秒".equals(content)){
            // 对接能用的每日60秒新闻脚本：用户发今日新闻，触发sendToday用框架原生sendImage发图片
            new Thread(new Runnable(){ public void run(){ sendToday(talker); } }).start();
            return;
        }
        if("讲笑话".equals(content)){
            try{
                String resp=get("https://v1.hitokoto.cn/?c=l",null);
                if(resp!=null&&!resp.isEmpty()){
                    JSONObject obj=JSON.parseObject(resp);
                    String joke=obj.getString("hitokoto");
                    if(joke!=null&&!joke.isEmpty()){ asyncSend(talker,"😂"+joke); return; }
                }
            }catch(Exception e){}
            String[] jokes={"为什么程序员分不清万圣节和圣诞节？因为 Oct 31 = Dec 25","有一天土豆走在路上摔了一跤，变成薯片","什么动物最容易摔倒？狐狸，因为它很狡猾(脚滑)"};
            asyncSend(talker,"😂"+jokes[(int)(Math.random()*jokes.length)]); return;
        }

        // ========== 盲盒 ==========
        if("盲盒".equals(content)){
            if(isBankruptProtected(user)){ asyncSend(talker,"❌破产保护中，无法开盲盒（金币为0）"); return; }
            long now = System.currentTimeMillis();
            long mbTs = Long.parseLong(user.get("mysteryBoxTs"));
            int mbCount = Integer.parseInt(user.get("mysteryBoxCount"));
            if(now - mbTs >= 10*60*1000){
                mbCount = 0;
                user.put("mysteryBoxTs", String.valueOf(now));
            }
            if(mbCount >= 10){
                long remain = (10*60*1000 - (now - mbTs))/1000;
                asyncSend(talker,"❌10分钟内最多开10次盲盒，还需等待"+(remain/60)+"分"+(remain%60)+"秒");
                return;
            }
            mbCount++;
            user.put("mysteryBoxCount", String.valueOf(mbCount));
            int gold=Integer.parseInt(user.get("gold"));
            if(gold<30){asyncSend(talker,"❌开盲盒需要30金币，当前："+gold);return;}
            gold-=30; double r=Math.random(); String res;
            if(r<0.4){int gain=(int)(Math.random()*80)+10; if(isDoubleGoldActive(user)) gain*=2; gold+=gain;res="🎉盲盒开出"+gain+"金币！"+(isDoubleGoldActive(user)?"（双倍金币生效）":"");}
            else if(r<0.75)res="😐盲盒空空如也，啥也没有";
            else{int lose=(int)(Math.random()*25)+5;gold-=lose;if(gold<0)gold=0;res="💔倒霉！盲盒扣掉"+lose+"金币";}
            user.put("gold",String.valueOf(gold));saveUser(senderWxid,user);
            asyncSend(talker,res+"\n剩余金币："+gold+"\n（今日10分钟内已开"+mbCount+"/10次）"); return;
        }

        // ========== 开宝箱 ==========
        if("开宝箱".equals(content)){
            if(isBankruptProtected(user)){ asyncSend(talker,"❌破产保护中，无法开宝箱（金币为0）"); return; }
            String today2 = getBeijingDateStr();
            String boxDay = user.get("boxDay");
            int boxCount = Integer.parseInt(user.get("boxCount"));
            if(!today2.equals(boxDay)){ boxCount = 0; user.put("boxDay",today2); }
            if(boxCount >= 20){ asyncSend(talker,"⏱开宝箱每天最多20次，明天再来吧"); return; }
            boxCount++; user.put("boxCount",String.valueOf(boxCount));
            int gold=Integer.parseInt(user.get("gold"));
            if(gold<50){asyncSend(talker,"❌开宝箱需要50金币，当前："+gold);return;}
            gold-=50; double r=Math.random(); String res;
            if(r<0.35){int gain=(int)(Math.random()*160)+40; if(isDoubleGoldActive(user)) gain*=2; gold+=gain;res="🎁宝箱大爆发！获得"+gain+"金币"+(isDoubleGoldActive(user)?"（双倍金币生效）":"");}
            else if(r<0.8){int gain=(int)(Math.random()*40)+10; if(isDoubleGoldActive(user)) gain*=2; gold+=gain;res="📦宝箱开出"+gain+"金币"+(isDoubleGoldActive(user)?"（双倍金币生效）":"");}
            else res="💨宝箱里面是空的！";
            user.put("gold",String.valueOf(gold));
            incTask(user,"box");
            saveUser(senderWxid,user);
            asyncSend(talker,res+"\n剩余金币："+gold); return;
        }

        // ========== 大富翁 ==========
        if("大富翁".equals(content)){
            if(isBankruptProtected(user)){ asyncSend(talker,"❌破产保护中，无法玩大富翁（金币为0）"); return; }
            long windowMs=10*60*1000,lastPlayTs=Long.parseLong(user.get("dafuTs")),now=System.currentTimeMillis();
            int dafuCount=Integer.parseInt(user.get("dafuCount"));
            if(now-lastPlayTs>=windowMs){ dafuCount=0; user.put("dafuTs",String.valueOf(now)); }
            if(dafuCount>=10){long remainSec=(windowMs-(now-lastPlayTs))/1000;asyncSend(talker,"⏱大富翁10分钟内最多10次，还需等待"+(remainSec/60)+"分"+(remainSec%60)+"秒");return;}
            dafuCount++; user.put("dafuCount",String.valueOf(dafuCount));
            int pos=Integer.parseInt(user.get("dafuPos")),step=(int)(Math.random()*6)+1; pos+=step;
            String msg;
            if(pos>=30){int reward=220; if(isDoubleGoldActive(user)) reward*=2; int g=Integer.parseInt(user.get("gold"))+reward;user.put("gold",String.valueOf(g));pos=0;msg="🎲掷出"+step+"点！到达终点！获得"+reward+"金币"+(isDoubleGoldActive(user)?"（双倍金币生效）":"")+"，位置重置回起点";}
            else msg="🎲掷出"+step+"点！当前位置："+pos+"/30";
            user.put("dafuPos",String.valueOf(pos));
            incTask(user,"rich");
            saveUser(senderWxid,user);
            asyncSend(talker,msg); return;
        }
        if(content.startsWith("配对")){
            String targetNick=content.substring("配对".length()).trim();
            asyncSend(talker,"💞你与【"+targetNick+"】匹配度："+(int)(Math.random()*100)+"分"); return;
        }
    }catch(Throwable e){
        try{
            String errMsg = "⚠️脚本执行出错：" + e.getClass().getSimpleName() + ": " + e.getMessage();
            if(talker != null && !talker.isEmpty()){
                asyncSend(talker, errMsg);
            }
        }catch(Throwable t){}
    }
}