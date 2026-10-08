package cn.aminplay.discord;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 2026-10-08 bot-plots：机器人发过的消息在哪（plots-posted.yml），只存编号和消息路径，不存申请内容：
 * 申请编号 → 管理频道里那条嵌入；共同开发者确认编号 → 发出去的私信。用来在游戏内审批 / 确认结束后改掉那条消息。
 */
final class PlotStore {
    static final int MAX = 1000;

    private final File file;
    private final Logger log;
    private final LinkedHashMap<String, String> posts = new LinkedHashMap<>();
    private final LinkedHashMap<String, List<String>> dms = new LinkedHashMap<>();

    PlotStore(File file, Logger log) {
        this.file = file;
        this.log = log;
        if (file == null || !file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String line : y.getStringList("posts")) {   // "<申请编号> <消息路径>"（编号里可能有 . 所以不用 yml 键）
            int sp = line.lastIndexOf(' ');
            if (sp > 0) posts.put(line.substring(0, sp), line.substring(sp + 1));
        }
        for (String line : y.getStringList("dms")) {
            int sp = line.lastIndexOf(' ');
            if (sp > 0) dms.computeIfAbsent(line.substring(0, sp), k -> new ArrayList<>()).add(line.substring(sp + 1));
        }
    }

    synchronized String post(String appId) {
        return posts.get(appId);
    }

    synchronized void putPost(String appId, String path) {
        posts.remove(appId);
        posts.put(appId, path);
        while (posts.size() > MAX) posts.remove(posts.keySet().iterator().next());
        save();
    }

    synchronized void removePost(String appId) {
        if (posts.remove(appId) != null) save();
    }

    synchronized void addDm(String requestId, String path) {
        dms.computeIfAbsent(requestId, k -> new ArrayList<>()).add(path);
        while (dms.size() > MAX) dms.remove(dms.keySet().iterator().next());
        save();
    }

    /** 取出并删掉这次确认的所有私信路径。 */
    synchronized List<String> takeDms(String requestId) {
        List<String> l = dms.remove(requestId);
        if (l != null) save();
        return l == null ? List.of() : l;
    }

    synchronized int postCount() {
        return posts.size();
    }

    synchronized int dmCount() {
        return dms.values().stream().mapToInt(List::size).sum();
    }

    private void save() {
        if (file == null) return;
        YamlConfiguration y = new YamlConfiguration();
        List<String> p = new ArrayList<>(), d = new ArrayList<>();
        for (Map.Entry<String, String> e : posts.entrySet()) p.add(e.getKey() + " " + e.getValue());
        for (Map.Entry<String, List<String>> e : dms.entrySet()) for (String s : e.getValue()) d.add(e.getKey() + " " + s);
        y.set("posts", p);
        y.set("dms", d);
        try {
            y.save(file);
        } catch (IOException e) {
            log.warning("保存 plots-posted.yml 失败：" + e.getMessage());
        }
    }
}
