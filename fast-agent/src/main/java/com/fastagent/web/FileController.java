package com.fastagent.web;

import com.fastagent.AppLog;
import com.fastagent.artifact.ArtifactMeta;
import com.fastagent.artifact.ArtifactService;
import com.fastagent.artifact.FileRef;
import com.fastagent.artifact.FileRefStore;
import com.fastagent.auth.AuthSupport;
import com.fastagent.service.ApiException;
import com.fastagent.ui.FileSaveDialog;
import com.fastagent.util.Opener;
import com.fastagent.workspace.WorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 会话里的文件：列表、预览、下载、另存为、打开。
 *
 * <p>两个来源合在一张卡片列表里：
 * <ul>
 *   <li><b>交付物</b>（{@link ArtifactService}）：模型调 {@code deliver_artifact} 主动交付的成品，
 *       我们在 {@code ~/.jagent/<userId>/downloads/} 存了副本；</li>
 *   <li><b>涉及的文件</b>（{@link FileRefStore}）：一轮里被 {@code read_file / write_file /
 *       edit_file} 碰过的文件，只有原始路径与「读/写/改」标记。</li>
 * </ul>
 * 两者用同一套 id 空间与同一批接口；同一个文件既被改过又被交付时只留交付物那张卡
 * （副本更稳定，原始路径可能被后续编辑覆盖）。
 *
 * <p>所有接口都<b>按 id 取</b>，不接受前端传任意路径 —— 与本工程其余接口同一口径。
 *
 * <p><b>为什么除了 HTTP 下载还要「另存为」</b>：界面跑在 JavaFX {@code WebView} 里，
 * 它没有任何下载能力（见 {@code FileSaveDialog} 的注释），点链接不会存文件。
 * 所以桌面态的主路径是「弹原生保存框 + 后端复制」；{@link #download} 保留给
 * 浏览器里调试前端（{@code npm run dev}）以及将来 Web 化时用。
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    /** 内联预览用：WebView 里渲染不了的类型一律 octet-stream，前端据此提示「不支持预览」 */
    private static final Map<String, MediaType> IMAGE_TYPES = Map.of(
            "png", MediaType.IMAGE_PNG,
            "jpg", MediaType.IMAGE_JPEG,
            "jpeg", MediaType.IMAGE_JPEG,
            "gif", MediaType.IMAGE_GIF,
            "bmp", MediaType.parseMediaType("image/bmp"),
            "webp", MediaType.parseMediaType("image/webp"),
            "svg", MediaType.parseMediaType("image/svg+xml"),
            "ico", MediaType.parseMediaType("image/x-icon"));

    private final ArtifactService artifacts;
    private final FileRefStore fileRefs;
    private final WorkspaceService workspaces;

    public FileController(ArtifactService artifacts, FileRefStore fileRefs, WorkspaceService workspaces) {
        this.artifacts = artifacts;
        this.fileRefs = fileRefs;
        this.workspaces = workspaces;
    }

    /**
     * 某个会话里的文件（交付物 + 涉及到的文件），按时间升序。
     *
     * <p>带 {@code workspaceId} 只是为了复用「这个空间不属于你」的校验；
     * 过滤本身按 {@code (userId, sessionId)} 做（会话 id 全局唯一，不必再叠工作空间）。
     */
    @GetMapping
    public List<ArtifactView> list(@RequestParam String workspaceId,
                                   @RequestParam String sessionId,
                                   HttpServletRequest req) {
        String userId = AuthSupport.requireUserId(req);
        workspaces.require(userId, workspaceId);
        return merge(artifacts.list(userId, sessionId), fileRefs.list(userId, sessionId));
    }

    /**
     * 把两类来源合成一份卡片列表（交付物优先）。
     *
     * <p>单独抽出来是为了能被探针直接调 —— 这段的判定规则（什么算「同一个文件」）改一次就
     * 影响用户看到的卡片数量，值得有断言守着。
     */
    public static List<ArtifactView> merge(List<ArtifactMeta> delivered, List<FileRef> refs) {
        List<ArtifactView> out = new ArrayList<>();
        Set<String> deliveredKeys = new HashSet<>();
        for (ArtifactMeta m : delivered == null ? List.<ArtifactMeta>of() : delivered) {
            out.add(ArtifactView.of(m));
            deliveredKeys.add(sameFile(m.getName(), m.getSize()));
        }
        for (FileRef r : refs == null ? List.<FileRef>of() : refs) {
            // 同一个文件既被写/改过、又被交付过：只留交付物那张卡（副本更稳，能下载）。
            // 按「文件名 + 大小」而不是路径比对 —— 两条链路记下来的路径写法常常不同：
            // 交付工具记的是模型当时给的字符串（可能带 <userId>/ 前缀），文件引用记的是
            // 工具参数里的原始路径。实测同一个文件曾在卡片上出现两次，就是栽在路径写法上。
            if (deliveredKeys.contains(sameFile(r.getName(), r.getSize()))) {
                continue;
            }
            out.add(ArtifactView.of(r));
        }
        out.sort(Comparator.comparingLong(ArtifactView::createdAt));
        return out;
    }

    /** 内联输出（右侧预览用）：图片直接显示，文本取回内容渲染 */
    @GetMapping("/{id}/raw")
    public ResponseEntity<Resource> raw(@PathVariable String id, HttpServletRequest req) {
        String userId = AuthSupport.requireUserId(req);
        Resolved r = resolve(userId, id);
        MediaType type = IMAGE_TYPES.get(extOf(r.name()));
        if (type == null) {
            // 文本类按纯文本给，前端 fetch 后自己渲染；非文本类前端不会请求
            type = new MediaType("text", "plain", StandardCharsets.UTF_8);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(r.name(), StandardCharsets.UTF_8).build().toString())
                .contentType(type)
                .contentLength(r.size())
                .body(new FileSystemResource(r.file()));
    }

    /** 直接下载（浏览器态可用；桌面态的 WebView 存不了文件，走 save-as） */
    @GetMapping("/{id}")
    public ResponseEntity<Resource> download(@PathVariable String id, HttpServletRequest req) {
        String userId = AuthSupport.requireUserId(req);
        Resolved r = resolve(userId, id);
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(r.name(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(r.size())
                .body(new FileSystemResource(r.file()));
    }

    /**
     * 另存为：弹系统保存框，用户选好路径后把文件复制过去。
     *
     * @param body 可选 {@code {"initialDir": "..."}}，作为对话框起始目录
     * @return {@code {"path": "D:\\x\\报告.docx", "cancelled": false}}；取消时 path 为空串
     */
    @PostMapping("/{id}/save-as")
    public Map<String, Object> saveAs(@PathVariable String id,
                                      @RequestBody(required = false) Map<String, String> body,
                                      HttpServletRequest req) {
        String userId = AuthSupport.requireUserId(req);
        Resolved r = resolve(userId, id);
        String initialDir = body == null ? null : body.get("initialDir");

        String dest;
        try {
            dest = FileSaveDialog.save("另存为 " + r.name(), r.name(), initialDir);
        } catch (IllegalStateException e) {
            // 非桌面模式（浏览器调试）或已有对话框打开：给可读提示
            throw new ApiException(400, e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (dest == null) {
            out.put("path", "");
            out.put("cancelled", true);
            return out;
        }
        try {
            Files.copy(r.file(), Path.of(dest), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            AppLog.error(e, "另存为失败: %s -> %s", r.name(), dest);
            throw new ApiException(500, "保存失败: " + e.getMessage());
        }
        AppLog.info("另存为: user=%s %s -> %s", userId, r.name(), dest);
        out.put("path", dest);
        out.put("cancelled", false);
        return out;
    }

    /** 用系统默认程序打开（看文档最省事的一条路） */
    @PostMapping("/{id}/open")
    public Map<String, Object> open(@PathVariable String id, HttpServletRequest req) {
        Resolved r = resolve(AuthSupport.requireUserId(req), id);
        if (!Opener.openFile(r.file().toString())) {
            throw new ApiException(500, "无法打开文件: " + r.name());
        }
        return Map.of("ok", true);
    }

    /** 打开所在文件夹并选中该文件 */
    @PostMapping("/{id}/reveal")
    public Map<String, Object> reveal(@PathVariable String id, HttpServletRequest req) {
        Resolved r = resolve(AuthSupport.requireUserId(req), id);
        if (!Opener.revealFile(r.file().toString())) {
            throw new ApiException(500, "无法定位文件: " + r.name());
        }
        return Map.of("ok", true);
    }

    // ---------- 内部 ----------

    /** 解析后的实体：文件、展示名、大小 */
    private record Resolved(Path file, String name, long size) {
    }

    /**
     * 按 id 找出实体文件并校验归属。
     *
     * <p>先当交付物找（我们有副本，最稳），再当「涉及的文件」找（读原始路径）。
     * 找不到一律 404 —— 不泄露「这个 id 存不存在」。
     */
    private Resolved resolve(String userId, String id) {
        ArtifactMeta meta = artifacts.find(userId, id);
        if (meta != null) {
            Path f = artifacts.fileOf(meta);
            if (Files.isRegularFile(f)) {
                return new Resolved(f, meta.getName(), meta.getSize());
            }
        }
        FileRef ref = fileRefs.find(userId, id);
        if (ref != null) {
            Path f = Path.of(ref.getPath());
            if (Files.isRegularFile(f)) {
                return new Resolved(f, ref.getName(), ref.getSize());
            }
        }
        throw new ApiException(404, "文件不存在或已被清理");
    }

    /** 「同一个文件」的判定键：文件名（大小写不敏感）+ 字节数 */
    private static String sameFile(String name, long size) {
        return (name == null ? "" : name.toLowerCase(Locale.ROOT)) + '|' + size;
    }

    private static String extOf(String name) {
        if (name == null) {
            return "";
        }
        int i = name.lastIndexOf('.');
        return i > 0 && i < name.length() - 1 ? name.substring(i + 1).toLowerCase(Locale.ROOT) : "";
    }
}
