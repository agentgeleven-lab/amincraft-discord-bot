package cn.aminplay.discord;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 2026-10-08 privacy：选择退出的 Discord 用户 ID（optout.yml）。
 * 退出后：收集不再记录他的消息，聊天互通也不再把他的消息转发到游戏里。
 */
final class OptOut {
    private final File file;
    private final Logger log;
    private final Set<String> ids = new LinkedHashSet<>();

    OptOut(File file, Logger log) {
        this.file = file;
        this.log = log;
        if (file != null && file.exists()) ids.addAll(YamlConfiguration.loadConfiguration(file).getStringList("opted-out"));
    }

    synchronized boolean has(String userId) {
        return userId != null && ids.contains(userId);
    }

    /** true = 状态有变化。 */
    synchronized boolean set(String userId, boolean out) {
        boolean changed = out ? ids.add(userId) : ids.remove(userId);
        if (changed) save();
        return changed;
    }

    synchronized int count() {
        return ids.size();
    }

    private void save() {
        if (file == null) return;
        YamlConfiguration y = new YamlConfiguration();
        y.set("opted-out", new ArrayList<>(ids));
        try {
            y.save(file);
        } catch (IOException e) {
            log.warning("保存 optout.yml 失败：" + e.getMessage());
        }
    }
}
