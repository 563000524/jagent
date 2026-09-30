package com.fastagent;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 进程级路径解析：程序目录、日志目录、以及用户数据根 {@code ~/.jagent}。
 *
 * <p><b>按用户</b>的数据路径见 {@link UserPaths}（{@code ~/.jagent/<userId>/}）；
 * <b>工作空间</b>的数据在用户指定目录下的 {@code .jagentspace/}（见
 * {@code Workspace.dataDir()}）。本类只管与「谁登录」无关的那几个。
 *
 * <p>「程序目录」的判定很关键：打包后是 exe 所在目录；开发态从 class 文件位置反推
 * 后端工程根，<b>不用 {@code user.dir}</b> —— 后者是「执行 mvn 的目录」，
 * 在父目录用 {@code -f} 调用时会算到别处。
 */
public final class AppPaths {

    /** 用户数据根目录名，位于当前用户主目录下 */
    private static final String BASE_DIR_NAME = ".jagent";

    /** 覆盖数据根位置（测试隔离与特殊部署用） */
    public static final String PROP_DATA_DIR = "fastagent.data.dir";

    private static Path exeDir;
    private static Path logDir;
    private static Path baseDir;

    private AppPaths() {
    }

    /** 程序目录：开发态 = 后端工程目录；打包后 = exe 所在目录 */
    public static synchronized Path exeDir() {
        if (exeDir != null) {
            return exeDir;
        }
        // 1) jpackage 生成的启动器会注入该属性，指向 exe 全路径
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            exeDir = Paths.get(appPath).toAbsolutePath().getParent();
            return exeDir;
        }
        // 2) 开发态：代码位于 <工程>/target/classes，往上两级就是工程根
        Path fromCode = projectDirFromCodeSource();
        if (fromCode != null) {
            exeDir = fromCode;
            return exeDir;
        }
        // 3) 兜底：当前工作目录（例如 java -jar）
        exeDir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        return exeDir;
    }

    /**
     * 从 class 文件位置反推工程根：{@code <工程>/target/{classes,test-classes}} → {@code <工程>}。
     * 判定不成立（例如从 jar 里加载）时返回 {@code null}。
     */
    private static Path projectDirFromCodeSource() {
        try {
            URL url = AppPaths.class.getProtectionDomain().getCodeSource().getLocation();
            if (url == null || !"file".equals(url.getProtocol())) {
                return null;
            }
            Path p = Paths.get(url.toURI());
            Path namePath = p.getFileName();
            if (namePath == null) {
                return null;
            }
            String name = namePath.toString();
            if (!"classes".equals(name) && !"test-classes".equals(name)) {
                return null;
            }
            Path target = p.getParent();
            if (target == null || target.getFileName() == null
                    || !"target".equals(target.getFileName().toString())) {
                return null;
            }
            return target.getParent();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 日志目录：优先程序目录；不可写（如装在 Program Files）时回落
     * {@code %LOCALAPPDATA%\fast-agent}。
     */
    public static synchronized Path logDir() {
        if (logDir != null) {
            return logDir;
        }
        Path exe = exeDir();
        if (exe != null && writable(exe)) {
            logDir = exe;
        } else {
            String local = System.getenv("LOCALAPPDATA");
            Path fallback = (local != null && !local.isBlank())
                    ? Paths.get(local, "fast-agent")
                    : Paths.get(System.getProperty("java.io.tmpdir"), "fast-agent");
            try {
                Files.createDirectories(fallback);
            } catch (IOException ignored) {
                // 交给后续写入时报错
            }
            logDir = fallback;
        }
        return logDir;
    }

    public static Path logFile() {
        return logDir().resolve("fast-agent.log");
    }

    /**
     * 用户数据根 {@code ~/.jagent}。
     *
     * <p>放在<b>当前用户主目录</b>（系统盘的用户工作目录）下，不再跟着程序目录走：
     * 程序目录可能在只读位置（Program Files），也可能随版本升级被整体替换，
     * 不该带着用户配置一起搬。
     *
     * <p>建不出来（极端只读环境）时回落到系统临时目录，保证有地方可写。
     * 可用 {@code -Dfastagent.data.dir=<路径>} 覆盖。
     */
    public static synchronized Path baseDir() {
        if (baseDir != null) {
            return baseDir;
        }
        String override = System.getProperty(PROP_DATA_DIR);
        if (override != null && !override.isBlank()) {
            baseDir = Paths.get(override.trim()).toAbsolutePath();
            try {
                Files.createDirectories(baseDir);
            } catch (IOException ignored) {
                // 交给后续写入时报错
            }
            return baseDir;
        }
        Path home = Paths.get(System.getProperty("user.home", "."));
        Path candidate = home.resolve(BASE_DIR_NAME);
        try {
            Files.createDirectories(candidate);
            baseDir = candidate;
        } catch (IOException e) {
            Path fallback = Paths.get(System.getProperty("java.io.tmpdir"), BASE_DIR_NAME);
            try {
                Files.createDirectories(fallback);
            } catch (IOException ignored) {
                // 交给后续写入时报错
            }
            baseDir = fallback;
            AppLog.warn("用户主目录不可写，数据根回落到: %s", baseDir);
        }
        return baseDir;
    }

    /**
     * 播种一个文件：已存在就不动。
     *
     * <p>播种出来的都是「用户可直接手改」的文件，路径会打进日志。
     */
    static void seedFile(Path f, String content) throws IOException {
        if (Files.exists(f)) {
            return;
        }
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
        AppLog.info("已生成默认文件: %s", f);
    }

    static boolean writable(Path dir) {
        if (dir == null) {
            return false;
        }
        try {
            Files.createDirectories(dir);
            Path probe = dir.resolve(".write-probe");
            Files.writeString(probe, "ok");
            Files.deleteIfExists(probe);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 仅用于测试：重置缓存 */
    public static synchronized void resetForTest() {
        exeDir = null;
        logDir = null;
        baseDir = null;
    }
}
