package com.fastagent.ui;

import com.fastagent.AppLog;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

/**
 * JavaFX 外壳：一个 {@link WebView} 承载整个 Vue 应用。
 *
 * <p>窗口本身是 JavaFX 原生的 {@code Stage}，可拖动、最小化、缩放；
 * 内容区是 WebView 控件（内嵌 WebKit 引擎），Vue 页面只是该控件的渲染内容。
 * 之所以用 WebView 而不是原生控件，是为了拿到完整 CSS / Markdown / 富文本排版能力。
 *
 * <p>页面通过 {@code http://127.0.0.1:<port>/} 加载，前端因此是一份标准 Web 应用：
 * 浏览器里能调试，壳里能跑，不必依赖任何专有桥接。
 */
public class MainWindow extends Application {

    private static volatile int port = 0;

    public static void setPort(int p) {
        port = p;
    }

    @Override
    public void start(Stage stage) {
        WebView webView = new WebView();
        WebEngine engine = webView.getEngine();

        engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == Worker.State.SUCCEEDED) {
                AppLog.info("页面加载完成: %s", engine.getLocation());
            } else if (state == Worker.State.FAILED) {
                AppLog.error("页面加载失败: %s | %s", engine.getLocation(), engine.getLoadWorker().getException());
            }
        });
        // 页面内的 JS 异常也记进日志，排查「界面没反应」时是关键线索
        engine.setOnError(e -> AppLog.error("WebView JS 错误: %s", e.getMessage()));

        // 把 window.confirm / alert 接到 JavaFX 原生对话框上。
        //
        // 这一步不是可有可无的：WebEngine 默认**没有** confirm handler，此时 JS 的
        // confirm() 一律返回 false，于是 `if (!confirm(...)) return` 会静默失效 ——
        // 表现就是「点删除按钮毫无反应」。界面自己换了确认框组件，这里是第二道保险，
        // 保证以后新写的代码即使直接用 window.confirm 也不会再无声失败。
        engine.setConfirmHandler(message -> {
            try {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.initOwner(stage);
                alert.setTitle("请确认");
                alert.setHeaderText(null);
                alert.setContentText(message);
                alert.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
                return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
            } catch (Exception e) {
                // 极端情况下（例如弹窗期间的状态限制）不让确认框把操作卡死：
                // 记日志并放行，用户点的本来就是「确认」
                AppLog.error(e, "confirm 对话框失败，按「确认」处理");
                return true;
            }
        });
        // 注意 JavaFX 的命名不一致：confirm/prompt 是 setXxxHandler，
        // 而 alert 是属性风格的 setOnAlert（没有 setAlertHandler）
        engine.setOnAlert(event -> {
            try {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.initOwner(stage);
                alert.setTitle("提示");
                alert.setHeaderText(null);
                alert.setContentText(event.getData());
                alert.showAndWait();
            } catch (Exception e) {
                AppLog.error(e, "alert 对话框失败: %s", event.getData());
            }
        });

        BorderPane root = new BorderPane(webView);
        Scene scene = new Scene(root, 1180, 780);

        // Ctrl+R 重新加载，方便改完前端直接看效果
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.R, KeyCombination.CONTROL_DOWN),
                engine::reload);

        String url = "http://127.0.0.1:" + port + "/";
        AppLog.info("加载页面: %s", url);
        engine.load(url);

        // 登记 owner 窗口，供原生对话框端点使用（同包类，无需 import）：
        // DirectoryPicker = 选择文件夹；FileSaveDialog = 下载卡片上的「另存为」
        DirectoryPicker.setOwner(stage);
        FileSaveDialog.setOwner(stage);

        stage.setTitle("办公智能体");
        stage.setScene(scene);
        stage.setMinWidth(920);
        stage.setMinHeight(620);
        stage.setOnCloseRequest(e -> AppLog.info("收到窗口关闭请求"));
        stage.show();

        Platform.setImplicitExit(true);
    }

    @Override
    public void stop() {
        // 窗口没了就不能再弹对话框，否则端点会在已关闭的窗口上挂住
        DirectoryPicker.setOwner(null);
        FileSaveDialog.setOwner(null);
        AppLog.info("JavaFX 已退出");
    }
}
