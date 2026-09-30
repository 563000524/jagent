package com.fastagent.web;

import com.fastagent.artifact.ArtifactMeta;
import com.fastagent.artifact.FileRef;

/**
 * 返回给前端的一个「文件」，用于渲染文件卡片与右侧预览。
 *
 * <p>不把 {@link ArtifactMeta} / {@link FileRef} 直接吐给前端：里面有 {@code userId}
 * 等与界面无关的字段，而且落盘结构改了不该影响接口形状。
 *
 * @param id          文件 id，预览/下载/另存为都按它取
 * @param name        文件名（含扩展名）
 * @param size        字节数
 * @param description agent 写的说明（只有交付物有），可为空
 * @param action      这一轮对它做了什么：{@code delivered}（交付成品）/ {@code read} /
 *                    {@code write} / {@code edit}
 * @param preview     预览方式：{@code image}（直接出图）/ {@code text}（取文本渲染）/ {@code none}
 * @param createdAt   时间（epoch 毫秒），前端据此把卡片挂到对应的那条回答下面
 * @param url         下载地址（不含令牌）。前端若要直接用链接下载，自行拼上 ?token=
 */
public record ArtifactView(String id, String name, long size, String description,
                           String action, String preview, long createdAt, String url) {

    public static ArtifactView of(ArtifactMeta m) {
        if (m == null) {
            return null;
        }
        return new ArtifactView(m.getId(), m.getName(), m.getSize(), m.getDescription(),
                "delivered", previewOf(m.getName()), m.getCreatedAt(), url(m.getId()));
    }

    public static ArtifactView of(FileRef r) {
        if (r == null) {
            return null;
        }
        return new ArtifactView(r.getId(), r.getName(), r.getSize(), null,
                r.getAction(), previewOf(r.getName()), r.getUpdatedAt(), url(r.getId()));
    }

    public static String url(String id) {
        return "/api/files/" + id;
    }

    /**
     * 按扩展名决定怎么预览。
     *
     * <p>只分两种可预览的：图片直接显示，文本取回内容渲染。其余（docx/xlsx/pdf/压缩包…）
     * 在 WebView 里没法渲染，前端会提示「该类型不支持预览」并把「打开」作为主操作。
     */
    public static String previewOf(String name) {
        String ext = extOf(name);
        return switch (ext) {
            case "png", "jpg", "jpeg", "gif", "bmp", "webp", "svg", "ico" -> "image";
            case "txt", "md", "markdown", "json", "xml", "yml", "yaml", "csv", "tsv", "log",
                 "ini", "conf", "cfg", "properties", "env", "toml",
                 "java", "js", "mjs", "cjs", "ts", "jsx", "tsx", "vue", "css", "scss", "less",
                 "html", "htm", "py", "rb", "go", "rs", "c", "cpp", "h", "hpp", "cs",
                 "php", "sql", "sh", "bat", "ps1", "gradle", "kt", "swift", "lua", "r" -> "text";
            default -> "none";
        };
    }

    private static String extOf(String name) {
        if (name == null) {
            return "";
        }
        int i = name.lastIndexOf('.');
        return i > 0 && i < name.length() - 1 ? name.substring(i + 1).toLowerCase() : "";
    }
}
