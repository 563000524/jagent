package com.fastagent.ui;

import com.fastagent.AppLog;
import javafx.application.Platform;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 系统「另存为」对话框。
 *
 * <p>为什么必须由 JavaFX 侧来做：界面跑在 {@code WebView} 里，而 JavaFX 21 的
 * {@code WebEngine} <b>没有任何下载能力</b>（{@code javafx-web-21.0.5} 里不存在
 * download 相关的公开方法）—— 点一个 {@code Content-Disposition: attachment} 的链接
 * 或 blob 链接，WebKit 会静默丢弃，不会存文件。所以「下载」这个动作只能由外壳完成：
 * 弹原生保存框，选好路径后由后端把文件复制过去。
 *
 * <p>与 {@link DirectoryPicker} 同款做法：请求线程投 {@link Platform#runLater}，
 * 再用 {@link CompletableFuture} 把用户的选择等回来（阻塞但不占用 JavaFX 线程）。
 */
public final class FileSaveDialog {

    /** JavaFX 主窗口；未启动时为 null（例如纯浏览器里跑前端调试） */
    private static volatile Window owner;
    /** 防连点弹出多个对话框 */
    private static final AtomicBoolean OPEN = new AtomicBoolean(false);

    private FileSaveDialog() {
    }

    /** 由 {@code MainWindow} 在窗口创建后登记，窗口关闭时清空 */
    public static void setOwner(Window window) {
        owner = window;
    }

    public static boolean available() {
        return owner != null;
    }

    /**
     * 弹出保存框，返回用户选中的绝对路径；用户取消返回 {@code null}。
     *
     * @param title     对话框标题
     * @param fileName  默认文件名（含扩展名）
     * @param initialDir 起始目录（可为空或不存在）
     * @throws IllegalStateException 非桌面模式，或已有对话框打开
     */
    public static String save(String title, String fileName, String initialDir) {
        Window w = owner;
        if (w == null) {
            throw new IllegalStateException("当前不是桌面模式（未检测到应用窗口），无法另存为");
        }
        if (!OPEN.compareAndSet(false, true)) {
            throw new IllegalStateException("已经有一个保存框打开了");
        }
        try {
            CompletableFuture<String> box = new CompletableFuture<>();
            Platform.runLater(() -> {
                try {
                    FileChooser chooser = new FileChooser();
                    chooser.setTitle(title == null || title.isBlank() ? "另存为" : title);
                    if (fileName != null && !fileName.isBlank()) {
                        chooser.setInitialFileName(fileName);
                    }
                    File init = initialDir == null || initialDir.isBlank() ? null : new File(initialDir.trim());
                    if (init != null && init.isDirectory()) {
                        chooser.setInitialDirectory(init);
                    }
                    File picked = chooser.showSaveDialog(w);
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
            AppLog.warn("另存为失败: %s", e.getMessage());
            throw new IllegalStateException("另存为失败: " + e.getMessage(), e);
        } finally {
            OPEN.set(false);
        }
    }
}
