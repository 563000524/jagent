package com.fastagent.util;

import com.fastagent.AppLog;

import java.io.IOException;

/** 用系统文件管理器打开目录，或打开/定位某个文件。 */
public final class Opener {

    private Opener() {
    }

    /**
     * 用 explorer.exe 打开目录。
     *
     * <p>不用 {@code java.awt.Desktop}：在 JavaFX 进程里混用 AWT 有卡死风险。
     *
     * @return 是否成功发起
     */
    public static boolean openDirectory(String dir) {
        AppLog.info("打开目录: %s", dir);
        return start("explorer.exe", dir);
    }

    /**
     * 用系统默认程序打开文件（下载卡片上的「打开」）。
     *
     * <p>走 {@code rundll32 url.dll,FileProtocolHandler} 而不是 {@code cmd /c start}：
     * 不必拼命令行、不受路径里空格与引号的影响。
     */
    public static boolean openFile(String file) {
        AppLog.info("打开文件: %s", file);
        return start("rundll32.exe", "url.dll,FileProtocolHandler", file);
    }

    /** 打开目录并选中该文件（下载卡片上的「打开所在文件夹」） */
    public static boolean revealFile(String file) {
        AppLog.info("定位文件: %s", file);
        return start("explorer.exe", "/select," + file);
    }

    private static boolean start(String... command) {
        try {
            new ProcessBuilder(command).start();
            return true;
        } catch (IOException e) {
            AppLog.error(e, "打开失败: %s", String.join(" ", command));
            return false;
        }
    }
}
