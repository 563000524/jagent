package com.fastagent;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 落到「软件所在目录」的轻量文件日志。
 *
 * <p>打包成 exe 后没有控制台可看，排查问题只能靠这份日志，因此：
 * 每条记录都带 {@code 文件:行号}，写入后立即 flush，超过 4MB 自动轮转。
 */
public final class AppLog {

    private static final long MAX_SIZE = 4L << 20;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static RandomAccessFile file;
    private static Path path;
    private static Path dir;

    private AppLog() {
    }

    /** 初始化日志，返回日志文件路径 */
    public static synchronized Path init() {
        if (file != null) {
            return path;
        }
        Path target = AppPaths.logDir();
        try {
            Files.createDirectories(target);
            Path f = target.resolve("fast-agent.log");
            file = new RandomAccessFile(f.toFile(), "rw");
            file.seek(file.length());
            path = f;
            dir = target;
            rotateIfNeeded();
        } catch (IOException e) {
            System.err.println("[AppLog] 日志文件初始化失败: " + e);
        }
        return path;
    }

    public static Path path() {
        return path;
    }

    public static Path dir() {
        return dir;
    }

    public static void info(String format, Object... args) {
        write("INFO", format, args);
    }

    public static void warn(String format, Object... args) {
        write("WARN", format, args);
    }

    public static void error(String format, Object... args) {
        write("ERROR", format, args);
    }

    public static void error(Throwable t, String format, Object... args) {
        write("ERROR", format + " | " + t.getClass().getSimpleName() + ": " + t.getMessage(), args);
        for (StackTraceElement el : t.getStackTrace()) {
            if (el.getClassName().startsWith("com.fastagent")) {
                write("ERROR", "    at %s", el);
            }
        }
    }

    private static synchronized void write(String level, String format, Object... args) {
        String msg = args == null || args.length == 0 ? format : String.format(format, args);
        String caller = "?";
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        // 0=Thread,1=write,2=info/warn/error,3=调用点
        if (stack.length > 3) {
            StackTraceElement el = stack[3];
            caller = el.getFileName() + ":" + el.getLineNumber();
        }
        String line = String.format("%s [%-5s] %-24s %s%n", LocalDateTime.now().format(TS), level, caller, msg);

        if (file != null) {
            try {
                file.write(line.getBytes(StandardCharsets.UTF_8));
                rotateIfNeeded();
            } catch (IOException ignored) {
                // 日志写失败不应影响主流程
            }
        }
        System.err.print(line);
    }

    private static void rotateIfNeeded() {
        if (file == null || path == null) {
            return;
        }
        try {
            if (file.length() < MAX_SIZE) {
                return;
            }
            file.close();
            Files.move(path, path.resolveSibling("fast-agent.log.1"), StandardCopyOption.REPLACE_EXISTING);
            file = new RandomAccessFile(path.toFile(), "rw");
        } catch (IOException ignored) {
            // 忽略轮转失败
        }
    }

    public static synchronized void close() {
        if (file != null) {
            try {
                file.close();
            } catch (IOException ignored) {
                // ignore
            }
            file = null;
        }
    }
}
