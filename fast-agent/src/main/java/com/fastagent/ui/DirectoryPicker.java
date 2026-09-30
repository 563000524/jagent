package com.fastagent.ui;

import com.fastagent.AppLog;
import javafx.application.Platform;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 系统「选择文件夹」对话框。
 *
 * <p>界面跑在 {@code WebView} 里，浏览器端拿不到可靠的本地绝对路径（也弹不出原生目录框），
 * 所以「新建工作空间」的目录必须由 JavaFX 侧弹 {@link DirectoryChooser} 来选。
 *
 * <p>调用方是 HTTP 请求线程，而对话框只能在 JavaFX 应用线程上开 —— 这里用
 * {@link Platform#runLater} 投递，再用 {@link CompletableFuture} 把结果等回来。
 * 等待期间那个请求线程会阻塞到用户选完或取消（Tomcat 线程池足够，且这是用户主动操作）。
 */
public final class DirectoryPicker {

    /** JavaFX 主窗口；未启动时为 null（例如纯浏览器里跑前端调试） */
    private static volatile Window owner;
    /** 防连点弹出多个对话框 */
    private static final AtomicBoolean OPEN = new AtomicBoolean(false);

    private DirectoryPicker() {
    }

    /** 由 {@code MainWindow} 在窗口创建后登记，窗口关闭时清空 */
    public static void setOwner(Window window) {
        owner = window;
    }

    /** 是否处于桌面模式（有窗口可挂对话框） */
    public static boolean available() {
        return owner != null;
    }

    /**
     * 弹出选择框，返回选中的绝对路径；用户取消返回 {@code null}。
     *
     * @param initialDir 初始目录（可为空或不存在）
     * @throws IllegalStateException 非桌面模式，或已有选择框打开
     */
    public static String pick(String title, String initialDir) {
        Window w = owner;
        if (w == null) {
            throw new IllegalStateException("当前不是桌面模式（未检测到应用窗口），请手动填写路径");
        }
        if (!OPEN.compareAndSet(false, true)) {
            throw new IllegalStateException("已经有一个文件夹选择框打开了");
        }
        try {
            CompletableFuture<String> box = new CompletableFuture<>();
            Platform.runLater(() -> {
                try {
                    DirectoryChooser chooser = new DirectoryChooser();
                    chooser.setTitle(title == null || title.isBlank() ? "选择文件夹" : title);
                    File init = initialDir == null || initialDir.isBlank() ? null : new File(initialDir.trim());
                    if (init != null && init.isDirectory()) {
                        chooser.setInitialDirectory(init);
                    }
                    File picked = chooser.showDialog(w);
                    box.complete(picked == null ? null : picked.getAbsolutePath());
                } catch (Throwable t) {
                    box.completeExceptionally(t);
                }
            });
            // 用户可能把对话框开着很久；30 分钟只是兜底，避免请求线程永久挂住
            return box.get(30, TimeUnit.MINUTES);
        } catch (RuntimeException e) {
            // 非桌面模式 / 重入：原样抛给 Controller 转成可读提示
            throw e;
        } catch (Exception e) {
            AppLog.warn("选择文件夹失败: %s", e.getMessage());
            throw new IllegalStateException("选择文件夹失败: " + e.getMessage(), e);
        } finally {
            OPEN.set(false);
        }
    }
}
