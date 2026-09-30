import com.fastagent.FastAgentApplication;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 接口冒烟探针：登录鉴权 + 工作空间 + 会话。
 *
 * <p>不启动窗口，直接对真实 HTTP 端口打请求，覆盖：
 * 未登录拦截、错误密码、正确登录、工作空间 CRUD、会话 CRUD、
 * 工作空间目录落盘、越权拒绝。
 *
 * <pre>
 * mvn -q dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
 * javac -encoding UTF-8 -cp "target/classes;$(cat target/cp.txt)" -d target/probe tools/ApiProbe.java
 * java -cp "target/probe;target/classes;$(cat target/cp.txt)" ApiProbe
 * </pre>
 */
public class ApiProbe {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static int failed;
    private static String token;

    public static void main(String[] args) throws Exception {
        System.out.println("PROBE ===== 接口冒烟开始 =====");

        ConfigurableApplicationContext ctx = startSpring();
        int port = resolvePort(ctx);
        String base = "http://127.0.0.1:" + port;
        System.out.println("PROBE base=" + base);

        // 1) 未登录必须被拦
        Res r = get(base + "/api/workspaces", null);
        expect("未登录访问 /api/workspaces → 401", r.status == 401, "实际 " + r.status);

        // 2) 静态首页（未登录也能拿到页面，由前端控制跳登录）
        Res idx = get(base + "/", null);
        expect("静态首页可访问且含 #app", idx.status == 200 && idx.body.contains("id=\"app\""),
                "status=" + idx.status);

        // 3) 健康检查免登录（探针自身要用）
        Res h = get(base + "/api/system/health", null);
        expect("health 免登录可访问", h.status == 200, "status=" + h.status);

        // 4) 错误密码
        Res bad = post(base + "/api/auth/login", "{\"username\":\"cjh\",\"password\":\"wrong\"}", null);
        expect("错误密码被拒 → 401", bad.status == 401, "status=" + bad.status);

        // 5) 正确账号
        Res login = post(base + "/api/auth/login", "{\"username\":\"cjh\",\"password\":\"123456\"}", null);
        token = extract(login.body, "token");
        expect("cjh/123456 登录成功且返回 token", login.status == 200 && token != null && !token.isBlank(),
                "body=" + brief(login.body));

        // 6) 带令牌访问
        Res ws = get(base + "/api/workspaces", token);
        expect("登录后可列工作空间", ws.status == 200 && ws.body.trim().startsWith("["),
                "body=" + brief(ws.body));

        // 7) 创建工作空间
        Res created = post(base + "/api/workspaces",
                "{\"name\":\"探针工作空间\",\"description\":\"由 ApiProbe 自动创建\"}", token);
        String wsId = extract(created.body, "id");
        expect("创建工作空间成功", created.status == 200 && wsId != null, "body=" + brief(created.body));

        if (wsId == null) {
            finish(ctx);
            return;
        }

        // 8) 目录与 AGENTS.md 是否真的落盘
        Path dir = Paths.get("workspaces", wsId);
        expect("工作空间目录与 AGENTS.md 已生成",
                Files.isDirectory(dir) && Files.exists(dir.resolve("AGENTS.md")),
                dir.toAbsolutePath().toString());

        // 9) 新工作空间会话列表应为空
        Res sessions = get(base + "/api/workspaces/" + wsId + "/sessions", token);
        expect("新工作空间会话列表为空", sessions.status == 200 && sessions.body.trim().equals("[]"),
                "body=" + brief(sessions.body));

        // 10) 新建会话
        Res s = post(base + "/api/workspaces/" + wsId + "/sessions", "{}", token);
        String sid = extract(s.body, "sessionId");
        expect("新建会话成功", s.status == 200 && sid != null, "body=" + brief(s.body));

        // 11) 工作空间级记录
        Res rec = get(base + "/api/workspaces/" + wsId + "/records", token);
        expect("读取工作空间记录（含 AGENTS.md 原文）",
                rec.status == 200 && rec.body.contains("agents") && rec.body.contains("memory"),
                "body=" + brief(rec.body));

        // 12) 会话级记录（新会话应为空）
        if (sid != null) {
            Res msgs = get(base + "/api/workspaces/" + wsId + "/sessions/" + sid + "/messages", token);
            expect("新会话消息为空", msgs.status == 200 && msgs.body.trim().equals("[]"),
                    "body=" + brief(msgs.body));
        }

        // 13) 无效令牌被拒
        Res noauth = get(base + "/api/workspaces/" + wsId, "invalid-token-xxx");
        expect("无效 token 被拒 → 401", noauth.status == 401, "status=" + noauth.status);

        // 14) 未配 Key 时发消息应返回 400 + 可读原因（而非空流）
        if (sid != null) {
            Res chat = post(base + "/api/chat/stream",
                    "{\"workspaceId\":\"" + wsId + "\",\"sessionId\":\"" + sid + "\",\"text\":\"\"}", token);
            expect("空消息 → 400 + message", chat.status == 400 && chat.body.contains("message"),
                    "status=" + chat.status + " body=" + brief(chat.body));
        }

        // 15) 清理
        Res del = delete(base + "/api/workspaces/" + wsId, token);
        expect("删除工作空间成功", del.status == 200, "status=" + del.status);

        finish(ctx);
    }

    private static void finish(ConfigurableApplicationContext ctx) {
        ctx.close();
        System.out.println("PROBE ===== 结束，失败项 = " + failed + " =====");
        System.out.println(failed == 0 ? "PROBE RESULT = ALL-GREEN" : "PROBE RESULT = HAS-FAILURES");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---------- Spring ----------

    private static ConfigurableApplicationContext startSpring() {
        System.setProperty("server.port", "0");
        System.setProperty("logging.level.root", "warn");
        SpringApplication app = new SpringApplication(FastAgentApplication.class);
        app.setBannerMode(Banner.Mode.OFF);
        long t0 = System.currentTimeMillis();
        ConfigurableApplicationContext ctx = app.run();
        System.out.println("PROBE spring-boot 启动耗时 " + (System.currentTimeMillis() - t0) + "ms");
        return ctx;
    }

    private static int resolvePort(ConfigurableApplicationContext ctx) {
        if (ctx instanceof ServletWebServerApplicationContext web && web.getWebServer() != null) {
            return web.getWebServer().getPort();
        }
        String raw = ctx.getEnvironment().getProperty("local.server.port");
        return raw == null ? -1 : Integer.parseInt(raw);
    }

    // ---------- HTTP ----------

    private record Res(int status, String body) {
    }

    private static Res get(String url, String tokenOrNull) {
        return send(builder(url, tokenOrNull).GET().build());
    }

    private static Res post(String url, String json, String tokenOrNull) {
        return send(builder(url, tokenOrNull)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    private static Res delete(String url, String tokenOrNull) {
        return send(builder(url, tokenOrNull).DELETE().build());
    }

    private static HttpRequest.Builder builder(String url, String tokenOrNull) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20));
        if (tokenOrNull != null) {
            b.header("Authorization", "Bearer " + tokenOrNull);
        }
        return b;
    }

    private static Res send(HttpRequest req) {
        try {
            HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            return new Res(r.statusCode(), r.body());
        } catch (Exception e) {
            return new Res(-1, "EX " + e);
        }
    }

    // ---------- 断言 ----------

    private static void expect(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("PROBE [ OK ] " + name);
        } else {
            failed++;
            System.out.println("PROBE [FAIL] " + name + " | " + detail);
        }
    }

    private static String extract(String json, String key) {
        if (json == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String brief(String s) {
        if (s == null) {
            return "null";
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "…";
    }
}
