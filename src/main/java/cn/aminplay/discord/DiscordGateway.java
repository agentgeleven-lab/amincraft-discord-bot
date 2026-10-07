package cn.aminplay.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Discord Gateway v10 长连接：心跳、断线续连（resume）、指数退避重连。
 * 所有状态只在单线程 sched 上读写；WebSocket 回调只负责把事件转交过去。
 */
final class DiscordGateway {
    interface Handler {
        void dispatch(String type, JsonObject d);

        void fatal(int code, String reason);
    }

    static final int INTENT_GUILDS = 1;
    static final int INTENT_GUILD_MESSAGES = 1 << 9;
    static final int INTENT_MESSAGE_CONTENT = 1 << 15;

    /** 测试时可用 -Daminplay.discord.gateway 指向本地假 Gateway（2026-10-07 daily replay bundle 实机测试）。 */
    private static final String DEFAULT_URL = System.getProperty("aminplay.discord.gateway", "wss://gateway.discord.gg");
    /** 这些关闭码重连也没用（token 错、intent 未开启等）。 */
    private static final Set<Integer> FATAL = Set.of(4004, 4010, 4011, 4012, 4013, 4014);

    private final HttpClient http;
    private final String token;
    private final int intents;
    private final Handler handler;
    private final Logger log;
    private final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AminPlayDiscord-gateway");
        t.setDaemon(true);
        return t;
    });

    private Conn conn;
    private Integer seq;
    private String sessionId;
    private String resumeUrl;
    private ScheduledFuture<?> heartbeat;
    private boolean ackReceived = true;
    private int failures;
    private volatile boolean stopped;
    private volatile JsonObject presence;

    DiscordGateway(HttpClient http, String token, int intents, Handler handler, Logger log) {
        this.http = http;
        this.token = token;
        this.intents = intents;
        this.handler = handler;
        this.log = log;
    }

    void start() {
        sched.execute(() -> connect(false));
    }

    void shutdown() {
        stopped = true;
        sched.execute(() -> closeCurrent(1000));
        sched.shutdown();
        try {
            sched.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 设置机器人状态文字（自定义状态）。 */
    void updatePresence(String state) {
        JsonObject activity = new JsonObject();
        activity.addProperty("name", "Custom Status");
        activity.addProperty("type", 4);
        activity.addProperty("state", state);
        JsonArray acts = new JsonArray();
        acts.add(activity);
        JsonObject p = new JsonObject();
        p.add("since", null);
        p.add("activities", acts);
        p.addProperty("status", "online");
        p.addProperty("afk", false);
        presence = p;
        if (stopped) return;
        sched.execute(() -> {
            if (conn != null && sessionId != null) send(conn, op(3, p));
        });
    }

    // ---- 连接管理（只在 sched 线程） ----

    private void connect(boolean resume) {
        if (stopped) return;
        String base = resume && resumeUrl != null ? resumeUrl : DEFAULT_URL;
        Conn c = new Conn(resume);
        conn = c;
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(base + "/?v=10&encoding=json"), c)
                .whenComplete((ws, err) -> {
                    if (err != null) sched.execute(() -> {
                        if (conn != c) return;
                        conn = null;
                        log.warning("连接 Discord 失败：" + err.getMessage());
                        scheduleReconnect(resume);
                    });
                });
    }

    private void onPayload(Conn c, String text) {
        if (c != conn) return;
        JsonObject p;
        try {
            p = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            log.warning("无法解析 Gateway 消息：" + e.getMessage());
            return;
        }
        int op = p.get("op").getAsInt();
        JsonElement d = p.get("d");
        switch (op) {
            case 10 -> {
                long interval = d.getAsJsonObject().get("heartbeat_interval").getAsLong();
                startHeartbeat(c, interval);
                if (c.resume && sessionId != null && seq != null) {
                    JsonObject r = new JsonObject();
                    r.addProperty("token", token);
                    r.addProperty("session_id", sessionId);
                    r.addProperty("seq", seq);
                    send(c, op(6, r));
                } else {
                    send(c, op(2, identify()));
                }
            }
            case 11 -> ackReceived = true;
            case 1 -> sendHeartbeat(c);
            case 7 -> reconnectNow(true);
            case 9 -> {
                boolean resumable = d != null && d.isJsonPrimitive() && d.getAsBoolean();
                if (!resumable) {
                    sessionId = null;
                    seq = null;
                }
                closeCurrent(4000);
                sched.schedule(() -> connect(resumable), 1 + ThreadLocalRandom.current().nextInt(4), TimeUnit.SECONDS);
            }
            case 0 -> {
                if (p.has("s") && !p.get("s").isJsonNull()) seq = p.get("s").getAsInt();
                String type = p.get("t").getAsString();
                JsonObject data = d != null && d.isJsonObject() ? d.getAsJsonObject() : new JsonObject();
                if (type.equals("READY")) {
                    sessionId = data.get("session_id").getAsString();
                    resumeUrl = data.has("resume_gateway_url") ? data.get("resume_gateway_url").getAsString() : null;
                    failures = 0;
                } else if (type.equals("RESUMED")) {
                    failures = 0;
                    log.info("Discord 连接已恢复");
                }
                try {
                    handler.dispatch(type, data);
                } catch (RuntimeException e) {
                    log.log(Level.WARNING, "处理 Discord 事件 " + type + " 出错", e);
                }
            }
            default -> {
            }
        }
    }

    private void onClosed(Conn c, int code, String reason) {
        if (c != conn) return;
        conn = null;
        cancelHeartbeat();
        if (stopped) return;
        if (FATAL.contains(code)) {
            handler.fatal(code, reason);
            return;
        }
        if (code == 4007 || code == 4009) {
            sessionId = null;
            seq = null;
        }
        log.info("Discord 连接断开（" + code + " " + reason + "），稍后重连");
        scheduleReconnect(sessionId != null);
    }

    private void reconnectNow(boolean resume) {
        closeCurrent(4000);
        connect(resume);
    }

    private void scheduleReconnect(boolean resume) {
        if (stopped) return;
        failures++;
        long delay = Math.min(60, 1L << Math.min(failures, 6));
        sched.schedule(() -> connect(resume), delay, TimeUnit.SECONDS);
    }

    /** 关闭当前连接。用非 1000 的关闭码，Discord 才会保留会话以便 resume。 */
    private void closeCurrent(int code) {
        Conn c = conn;
        conn = null;
        cancelHeartbeat();
        if (c != null && c.ws != null) {
            WebSocket ws = c.ws;
            try {
                ws.sendClose(code, "bye");
            } catch (RuntimeException ignored) {
            }
            if (!sched.isShutdown()) sched.schedule(ws::abort, 2, TimeUnit.SECONDS);
            else ws.abort();
        }
    }

    private void startHeartbeat(Conn c, long interval) {
        cancelHeartbeat();
        ackReceived = true;
        long first = (long) (interval * ThreadLocalRandom.current().nextDouble());
        heartbeat = sched.scheduleAtFixedRate(() -> {
            if (c != conn) return;
            if (!ackReceived) {
                log.warning("Discord 心跳无响应，重新连接");
                reconnectNow(true);
                return;
            }
            ackReceived = false;
            sendHeartbeat(c);
        }, first, interval, TimeUnit.MILLISECONDS);
    }

    private void cancelHeartbeat() {
        if (heartbeat != null) heartbeat.cancel(false);
        heartbeat = null;
    }

    private void sendHeartbeat(Conn c) {
        JsonObject p = new JsonObject();
        p.addProperty("op", 1);
        p.addProperty("d", seq);
        send(c, p);
    }

    private JsonObject identify() {
        JsonObject props = new JsonObject();
        props.addProperty("os", "linux");
        props.addProperty("browser", "aminplay");
        props.addProperty("device", "aminplay");
        JsonObject d = new JsonObject();
        d.addProperty("token", token);
        d.addProperty("intents", intents);
        d.add("properties", props);
        if (presence != null) d.add("presence", presence);
        return d;
    }

    private void send(Conn c, JsonObject payload) {
        WebSocket ws = c.ws;
        if (ws == null) return;
        try {
            ws.sendText(payload.toString(), true).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.fine("Gateway 发送失败：" + e);
        }
    }

    private static JsonObject op(int op, JsonElement d) {
        JsonObject p = new JsonObject();
        p.addProperty("op", op);
        p.add("d", d);
        return p;
    }

    private final class Conn implements WebSocket.Listener {
        final boolean resume;
        volatile WebSocket ws;
        private final StringBuilder buf = new StringBuilder();

        Conn(boolean resume) {
            this.resume = resume;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            ws = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String s = buf.toString();
                buf.setLength(0);
                sched.execute(() -> onPayload(this, s));
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!sched.isShutdown()) sched.execute(() -> onClosed(this, statusCode, reason));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (!sched.isShutdown()) sched.execute(() -> onClosed(this, -1, String.valueOf(error)));
        }
    }
}
