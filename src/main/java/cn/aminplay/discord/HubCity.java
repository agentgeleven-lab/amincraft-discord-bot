package cn.aminplay.discord;

import org.bukkit.Bukkit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 2026-10-08 bot-plots：MiniGameHub（city-rules-v2）注册的 Bukkit 服务 cn.friendhub.city.CityApplicationApi（类脑市地块申请），只用 JDK 类型。
 * 和 {@link HubAuthKeys} 一样按类名反射调用，本插件不需要拿 MiniGameHub 编译。服务不在 = {@link #find()} 返回 null（机器人显示「不可用」）；
 * 某个方法不在 = 只有那个功能不可用（{@link UnsupportedOperationException}）。接口形状见 workstreams/city-v2/接口约定.md「bot-plots」段。
 */
final class HubCity implements PlotDesk.City {
    static final String API = "cn.friendhub.city.CityApplicationApi";

    private final Object provider;
    private final Method submit, join, list, get, approve, reject, answerCoDev, notified, searchPlots, setEventSink, status;

    private HubCity(Class<?> api, Object provider) {
        this.provider = provider;
        submit = m(api, "submit", String.class, String.class, String.class, String.class);
        join = m(api, "join", String.class, String.class, String.class);
        list = m(api, "list", String.class);
        get = m(api, "get", String.class);
        approve = m(api, "approve", String.class, String.class, String.class);
        reject = m(api, "reject", String.class, String.class, String.class, String.class);
        answerCoDev = m(api, "answerCoDev", String.class, String.class, boolean.class, String.class);
        notified = m(api, "notified", String.class, String.class, boolean.class);
        searchPlots = m(api, "searchPlots", String.class, int.class);
        setEventSink = m(api, "setEventSink", Consumer.class);
        status = m(api, "status");
    }

    private static Method m(Class<?> api, String name, Class<?>... args) {
        try {
            return api.getMethod(name, args);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    static HubCity find() {
        try {
            for (Class<?> c : Bukkit.getServicesManager().getKnownServices()) {
                if (!c.getName().equals(API)) continue;
                Object p = Bukkit.getServicesManager().load(c);
                if (p != null) return new HubCity(c, p);
            }
        } catch (Exception | LinkageError ignored) {
        }
        return null;
    }

    /** 缺的方法名（给 /discordbot plots 看）。 */
    String missing() {
        StringBuilder b = new StringBuilder();
        Object[][] all = {{"submit", submit}, {"join", join}, {"list", list}, {"get", get}, {"approve", approve}, {"reject", reject},
                {"answerCoDev", answerCoDev}, {"notified", notified}, {"searchPlots", searchPlots}, {"setEventSink", setEventSink}, {"status", status}};
        for (Object[] x : all) if (x[1] == null) b.append(b.length() == 0 ? "" : "、").append(x[0]);
        return b.toString();
    }

    private Object call(Method m, Object... args) throws Exception {
        if (m == null) throw new UnsupportedOperationException("CityApplicationApi 没有这个方法");
        try {
            return m.invoke(provider, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof Exception x ? x : e;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of("status", "error");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object o) {
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    @Override public Map<String, Object> submit(String plot, String uuid, String reason, String source) throws Exception { return map(call(submit, plot, uuid, reason, source)); }
    @Override public Map<String, Object> join(String id, String uuid, String source) throws Exception { return map(call(join, id, uuid, source)); }
    @Override public List<Map<String, Object>> list(String filter) throws Exception { return maps(call(list, filter)); }
    @Override public Map<String, Object> get(String id) throws Exception { return map(call(get, id)); }
    @Override public Map<String, Object> approve(String id, String by, String source) throws Exception { return map(call(approve, id, by, source)); }
    @Override public Map<String, Object> reject(String id, String by, String reason, String source) throws Exception { return map(call(reject, id, by, reason, source)); }
    @Override public Map<String, Object> answerCoDev(String req, String uuid, boolean yes, String source) throws Exception { return map(call(answerCoDev, req, uuid, yes, source)); }

    @Override
    public void notified(String req, String uuid, boolean dm) throws Exception {
        if (notified != null) call(notified, req, uuid, dm);
    }

    @Override
    public List<Map<String, Object>> searchPlots(String query, int limit) throws Exception {
        return searchPlots == null ? List.of() : maps(call(searchPlots, query, limit));
    }

    void setEventSink(Consumer<Map<String, Object>> sink) throws Exception { call(setEventSink, sink); }

    Map<String, Object> status() throws Exception { return status == null ? Map.of() : map(call(status)); }
}
