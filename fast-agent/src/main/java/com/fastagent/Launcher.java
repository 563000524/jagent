package com.fastagent;

import com.fastagent.ui.MainWindow;
import javafx.application.Application;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;

/**
 * 程序入口。
 *
 * <p>两点必须注意：
 * <ol>
 *   <li>本类<b>不是</b> {@link Application} 的子类——jpackage 生成的启动器要求 main class
 *       与 JavaFX Application 分离，否则打包后启动直接报错；</li>
 *   <li>先启动 Spring Boot 并拿到真实端口，再打开窗口。顺序若反过来，WebView 会加载到空白页。</li>
 * </ol>
 */
public class Launcher {

    private static final int MAX_PORT_ATTEMPTS = 8;
    private static final java.util.Random RANDOM = new java.util.Random();

    public static void main(String[] args) {
        Path logPath = AppLog.init();

        // 让 Spring / Tomcat 的内部日志也落到软件所在目录，出问题时一份目录看全
        System.setProperty("logging.file.name", AppPaths.logDir().resolve("spring-boot.log").toString());

        // 用户级目录（~/.jagent/<userId>/）不在启动时创建 —— 这时还不知道谁会登录，
        // 延后到该用户第一次访问配置 / 模型 / 技能时按需建（见 UserPaths.ensureLayout）。

        AppLog.info("================ 启动 fast-agent (JavaFX + WebView + Vue3 + Spring Boot) ================");
        AppLog.info("日志文件: %s", logPath);
        AppLog.info("程序目录: %s", AppPaths.exeDir());
        AppLog.info("工作目录: %s", System.getProperty("user.dir"));
        AppLog.info("用户数据根: %s（各用户的数据在 <根>/<userId>/ 下，首次访问时创建）", AppPaths.baseDir());
        AppLog.info("Java: %s %s", System.getProperty("java.version"), System.getProperty("java.vendor"));
        AppLog.info("JavaFX: %s", System.getProperty("javafx.version", "unknown"));

        ConfigurableApplicationContext ctx = startSpring(args);
        if (ctx == null) {
            AppLog.close();
            return;
        }

        int port = resolvePort(ctx);
        if (port <= 0) {
            AppLog.error("未能获取内嵌服务端口（实际值 %d），窗口不会打开", port);
            ctx.close();
            AppLog.close();
            return;
        }
        AppLog.info("内嵌服务已就绪: http://127.0.0.1:%d", port);

        try {
            MainWindow.setPort(port);
            Application.launch(MainWindow.class, args);
            AppLog.info("窗口已关闭，正常退出");
        } catch (Throwable t) {
            AppLog.error(t, "JavaFX 启动失败");
        } finally {
            ctx.close();
            AppLog.info("Spring 容器已关闭");
            AppLog.close();
        }
    }

    /**
     * 启动 Spring Boot。
     *
     * <p>端口先用 0 交给系统分配；Windows 上偶发「分配到的端口随后被占用」导致
     * BindException，此时换一个高位随机端口重试，而不是把窗口停在白屏。
     */
    private static ConfigurableApplicationContext startSpring(String[] args) {
        for (int attempt = 1; attempt <= MAX_PORT_ATTEMPTS; attempt++) {
            int port = attempt == 1 ? 0 : 30000 + RANDOM.nextInt(25000);
            System.setProperty("server.port", String.valueOf(port));
            try {
                return SpringApplication.run(FastAgentApplication.class, args);
            } catch (Throwable t) {
                boolean conflict = isPortConflict(t);
                AppLog.error(t, "Spring Boot 启动失败（第 %d 次，port=%s，端口冲突=%s）", attempt, port, conflict);
                if (!conflict || attempt == MAX_PORT_ATTEMPTS) {
                    AppLog.error("放弃启动：%s", t.getMessage());
                    return null;
                }
                AppLog.warn("端口被占用，换端口重试");
            }
        }
        return null;
    }

    private static boolean isPortConflict(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof java.net.BindException) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null && (msg.contains("Port ") && msg.contains("was already in use")
                    || msg.contains("Address already in use"))) {
                return true;
            }
            if (cur.getCause() == cur) {
                break;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static int resolvePort(ConfigurableApplicationContext ctx) {
        if (ctx instanceof ServletWebServerApplicationContext web) {
            if (web.getWebServer() != null) {
                return web.getWebServer().getPort();
            }
        }
        String raw = ctx.getEnvironment().getProperty("local.server.port");
        try {
            return raw == null ? -1 : Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
