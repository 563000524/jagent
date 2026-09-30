import com.fastagent.FastAgentApplication;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 无窗口冒烟探针（JavaFX WebView + Spring Boot 版）。
 *
 * <p>交付前必须跑：能在不弹出窗口的前提下验证整条链路，
 * 否则就是盲交付。
 *
 * <ol>
 *   <li>Spring Boot 能否启动并拿到真实端口；</li>
 *   <li>REST 接口状态码是否正确，尤其是「校验失败必须返回 400 + JSON」
 *       这条错误分支 —— 它决定了前端能否显示可读原因；</li>
 *   <li>JavaFX WebView 能否加载页面、Vue 能否挂载、前端能否调通后端。</li>
 * </ol>
 *
 * <p>用法（对<b>打包产物</b>跑，并用 --limit-modules 模拟 jlink 裁剪后的 runtime）：
 * <pre>
 * javac -encoding UTF-8 -cp "dist\FastAgent\app\*" -d build-probe tools\FxProbe.java
 * java --limit-modules java.se,jdk.charsets,... -cp "build-probe;dist\FastAgent\app\*" FxProbe
 * </pre>
 */
public class FxProbe {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static int failed;

    public static void main(String[] args) throws Exception {
        System.out.println("PROBE ===== WebView 方案冒烟开始 =====");

        ConfigurableApplicationContext ctx = startSpring();
        int port = resolvePort(ctx);
        System.out.println("PROBE port=" + port);
        if (port <= 0) {
            fail("未拿到内嵌服务端口");
            ctx.close();
            System.exit(2);
        }

        checkHttp(port);
        checkWebView(port);

        ctx.close();
        System.out.println("PROBE ===== 结束，失败项 = " + failed + " =====");
        System.out.println(failed == 0 ? "PROBE RESULT = ALL-GREEN" : "PROBE RESULT = HAS-FAILURES");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---------- Spring ----------

    private static ConfigurableApplicationContext startSpring() {
        System.setProperty("server.port", "0");
        SpringApplication app = new SpringApplication(FastAgentApplication.class);
        app.setBannerMode(Banner.Mode.OFF);
        long t0 = System.currentTimeMillis();
        ConfigurableApplicationContext ctx = app.run();
        ok("spring-boot 启动成功，耗时 " + (System.currentTimeMillis() - t0) + "ms");
        return ctx;
    }

    private static int resolvePort(ConfigurableApplicationContext ctx) {
        if (ctx instanceof ServletWebServerApplicationContext web && web.getWebServer() != null) {
            return web.getWebServer().getPort();
        }
        String raw = ctx.getEnvironment().getProperty("local.server.port");
        try {
            return raw == null ? -1 : Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---------- HTTP 接口 ----------

    private static void checkHttp(int port) throws Exception {
        String health = get(port, "/api/system/health");
        if (health == null) {
            fail("GET /api/system/health 失败");
        } else {
            ok("health 返回 " + (health.contains("logPath") ? "含 logPath" : "缺少 logPath"));
        }

        String config = get(port, "/api/config");
        if (config == null) {
            fail("GET /api/config 失败");
        } else {
            ok("config 返回 " + (config.contains("hasApiKey") ? "含 hasApiKey" : "缺 hasApiKey")
                    + "，摘要=" + brief(config));
        }

        String sessions = get(port, "/api/sessions");
        if (sessions == null) {
            fail("GET /api/sessions 失败");
        } else {
            ok("sessions 返回 " + (sessions.trim().startsWith("[") ? "数组" : "非数组"));
        }

        String index = getRaw(port, "/");
        if (index == null) {
            fail("GET / 失败（静态首页不可达）");
        } else {
            ok("静态首页 " + (index.contains("id=\"app\"") ? "含 #app 挂载点" : "缺 #app"));
        }

        // 错误分支：空消息必须 400 + JSON，前端才能 catch 到可读原因
        HttpResponse<String> res = post(port, "/api/chat/stream", "{\"sessionId\":\"\",\"text\":\"\"}");
        if (res == null) {
            fail("POST /api/chat/stream 无响应");
        } else if (res.statusCode() != 400) {
            fail("空消息应返回 400，实际 " + res.statusCode());
        } else if (!res.body().contains("message")) {
            fail("400 响应缺少 message 字段: " + brief(res.body()));
        } else {
            ok("空消息被拒: 400 " + brief(res.body()));
        }
    }

    // ---------- JavaFX WebView ----------

    private static void checkWebView(int port) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> report = new AtomicReference<>("");

        Platform.startup(() -> {
            try {
                WebView view = new WebView();
                WebEngine engine = view.getEngine();
                // 必须 attach 到 Scene：否则 load worker 的状态推进不可靠
                new Scene(new BorderPane(view), 1180, 780);

                engine.getLoadWorker().stateProperty().addListener((o, a, st) -> {
                    if (st == Worker.State.SUCCEEDED) {
                        // 模块脚本是异步执行的，页面 load 完成时 Vue 可能还没挂载，
                        // 所以轮询前端自己打的就绪标记，而不是看 load 状态
                        Thread t = new Thread(() -> {
                            report.set(waitForReady(engine));
                            done.countDown();
                        });
                        t.setDaemon(true);
                        t.start();
                    } else if (st == Worker.State.FAILED) {
                        report.set("load FAILED: " + engine.getLoadWorker().getException());
                        done.countDown();
                    }
                });
                engine.load("http://127.0.0.1:" + port + "/");
            } catch (Throwable t) {
                report.set("start FAILED: " + t);
                done.countDown();
            }
        });

        if (!done.await(90, TimeUnit.SECONDS)) {
            fail("WebView 探针超时 90s");
            return;
        }
        String r = report.get();
        System.out.println("PROBE webview: " + r);
        if (r.contains("NOT-MOUNTED") || r.contains("FAILED") || r.contains("TIMEOUT")) {
            fail("WebView 内前端未正常挂载");
        } else if (r.contains("NO-API-CALLS")) {
            fail("前端未调用任何 /api/ 接口（同源请求可能被拦）");
        } else {
            ok("WebView 加载 + Vue 挂载 + 前端调通后端");
        }
    }

    private static String waitForReady(WebEngine engine) {
        long deadline = System.currentTimeMillis() + 40_000;
        while (System.currentTimeMillis() < deadline) {
            String flag = eval(engine, "!!window.__FAST_AGENT_READY__");
            if ("true".equals(flag)) {
                return collect(engine);
            }
            sleep(300);
        }
        return "TIMEOUT waiting __FAST_AGENT_READY__";
    }

    private static String collect(WebEngine engine) {
        String title = eval(engine, "document.title");
        String mounted = eval(engine,
                "(function(){var e=document.querySelector('.app');"
                        + "return e? 'mounted children='+e.children.length : 'NOT-MOUNTED';})()");
        // 前端实际发出过哪些 /api/ 请求，最能说明「前端是否真的连上了后端」
        String apis = eval(engine,
                "(function(){var rs=performance.getEntriesByType('resource')||[];"
                        + "var hit=rs.map(function(r){return r.name})"
                        + ".filter(function(n){return n.indexOf('/api/')>=0})"
                        + ".map(function(n){return n.replace(/^https?:\\/\\/[^/]+/,'')});"
                        + "return hit.length? hit.join(',') : 'NO-API-CALLS';})()");
        String ua = eval(engine, "navigator.userAgent");
        return "title=" + title + " | vue=" + mounted + " | api=" + apis + " | ua=" + brief(ua, 60);
    }

    /** 在 FX 线程上执行脚本并等待结果 */
    private static String eval(WebEngine engine, String script) {
        String[] holder = new String[1];
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                holder[0] = String.valueOf(engine.executeScript(script));
            } catch (Throwable t) {
                holder[0] = "ERR " + t.getClass().getSimpleName();
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                return "TIMEOUT";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "INTERRUPTED";
        }
        return holder[0];
    }

    // ---------- HTTP 工具 ----------

    private static String get(int port, String path) {
        HttpResponse<String> res = send(port, path, null);
        if (res == null || res.statusCode() != 200) {
            return null;
        }
        return res.body();
    }

    private static String getRaw(int port, String path) {
        return get(port, path);
    }

    private static HttpResponse<String> post(int port, String path, String json) {
        return send(port, path, json);
    }

    private static HttpResponse<String> send(int port, String path, String jsonBody) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(20));
            if (jsonBody == null) {
                b.GET();
            } else {
                b.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
            }
            return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            System.out.println("PROBE http error " + path + " -> " + e);
            return null;
        }
    }

    // ---------- 断言 ----------

    private static void ok(String msg) {
        System.out.println("PROBE [ OK ] " + msg);
    }

    private static void fail(String msg) {
        failed++;
        System.out.println("PROBE [FAIL] " + msg);
    }

    private static String brief(String s) {
        return brief(s, 120);
    }

    private static String brief(String s, int max) {
        if (s == null) {
            return "null";
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
