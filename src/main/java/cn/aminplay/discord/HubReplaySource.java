package cn.aminplay.discord;

import org.bukkit.Bukkit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * 2026-10-07 daily replay bundle：MiniGameHub 注册的 Bukkit 服务 cn.friendhub.replay.ReplayBundleApi（只用 JDK 类型）。
 * 这里用反射调用，所以本插件不需要拿 MiniGameHub 编译；MiniGameHub 没装 / 没开时 {@link #find()} 返回 null。
 */
final class HubReplaySource implements ReplayPoster.Source {
    static final String API = "cn.friendhub.replay.ReplayBundleApi";

    private final Object provider;
    private final Method pending, markPosted, markFailed, bundleNow, status;

    private HubReplaySource(Class<?> api, Object provider) throws NoSuchMethodException {
        this.provider = provider;
        pending = api.getMethod("pendingBundles");
        markPosted = api.getMethod("markPosted", String.class, String.class);
        markFailed = api.getMethod("markFailed", String.class, String.class);
        bundleNow = api.getMethod("bundleNow");
        status = api.getMethod("status");
    }

    /** 当前可用的接口，或 null。 */
    static HubReplaySource find() {
        try {
            for (Class<?> c : Bukkit.getServicesManager().getKnownServices()) {
                if (!c.getName().equals(API)) continue;
                Object p = Bukkit.getServicesManager().load(c);
                if (p != null) return new HubReplaySource(c, p);
            }
        } catch (Exception | LinkageError ignored) {
        }
        return null;
    }

    private Object call(Method m, Object... args) throws Exception {
        try {
            return m.invoke(provider, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof Exception x ? x : e;
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<Map<String, Object>> pending() throws Exception {
        Object o = call(pending);
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    @Override
    public void markPosted(String id, String where) throws Exception {
        call(markPosted, id, where);
    }

    @Override
    public void markFailed(String id, String error) throws Exception {
        call(markFailed, id, error);
    }

    @Override
    public String bundleNow() throws Exception {
        Object o = call(bundleNow);
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map<String, Object> status() throws Exception {
        Object o = call(status);
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
