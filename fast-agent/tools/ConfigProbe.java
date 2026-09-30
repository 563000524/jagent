import com.fastagent.FastAgentApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * 配置类接口冒烟探针：模型配置 / 全局记忆 / 技能 / 工具 / 工作空间登记。
 *
 * <p><b>数据目录必须用系统属性显式隔离</b>：程序目录由 class 文件位置反推
 * （开发态恒为后端工程目录），改工作目录是没用的，探针会写到真实的 {@code ~/.jagent/<userId>/}。
 *
 * <pre>
 * mvn -q dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
 * javac -encoding UTF-8 -cp "target/classes;$(cat target/cp.txt)" -d target/probe tools/ConfigProbe.java
 * RUN=$(pwd)/target/probe-run && mkdir -p "$RUN" &amp;&amp; cd "$RUN"
 * java "-Dfastagent.data.dir=$RUN/.jagent" \
 *      "-Duser.home=$RUN" \
 *      -cp "../probe;../classes;$(cat ../cp.txt)" ConfigProbe
 * </pre>
 *
 * 两个属性都要给：{@code fastagent.data.dir} 是<b>数据根</b>（等价于 {@code ~/.jagent}），
 * {@code user.home} 隔离掉默认落点。用户级数据在数据根下的 {@code &lt;userId&gt;/} 里 ——
 * 本探针用 {@code cjh} 登录，所以断言全部针对那一层。
 * 工作空间不需要额外属性 —— 它的目录由探针显式指定在隔离目录里。
 */
public class ConfigProbe {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static int failed;
    private static String token;
    private static String base;

    public static void main(String[] args) throws Exception {
        System.out.println("PROBE ===== 配置接口冒烟开始 =====");
        System.out.println("PROBE cwd=" + Paths.get("").toAbsolutePath());

        String dataOverride = System.getProperty("fastagent.data.dir");
        if (dataOverride == null) {
            System.out.println("PROBE 必须用 -Dfastagent.data.dir 隔离，已中止");
            System.exit(2);
        }
        // 数据根（~/.jagent 的等价物）；用户级数据在它下面的 <userId>/ 里。
        // 探针用 cjh 登录，所以后面所有断言都针对这一层。
        Path baseDir = Paths.get(dataOverride).toAbsolutePath();
        String probeUser = "cjh";
        Path dataDir = baseDir.resolve(com.fastagent.UserPaths.of(probeUser).userId());
        // 工作空间建在隔离目录里 —— 用户指定目录，探针当然指定自己这份
        Path projectDir = baseDir.getParent().resolve("probe-project");
        System.out.println("PROBE baseDir=" + baseDir);
        System.out.println("PROBE dataDir=" + dataDir);
        System.out.println("PROBE projectDir=" + projectDir);

        // 程序目录的规则（不依赖当前工作目录）与数据根的位置
        Path appDir = com.fastagent.AppPaths.exeDir();
        expect("程序目录 = 后端工程目录（与执行 mvn 的目录无关）",
                "fast-agent".equals(String.valueOf(appDir.getFileName())), appDir.toString());
        // 数据根默认在「当前用户主目录」下的 .jagent，不再跟着程序目录走 ——
        // 程序目录可能在只读位置（Program Files），也会随版本升级被整体替换
        expect("数据根 = -Dfastagent.data.dir（默认应是用户主目录下的 .jagent）",
                baseDir.equals(com.fastagent.AppPaths.baseDir().toAbsolutePath()),
                "后端=" + com.fastagent.AppPaths.baseDir() + " 期望=" + baseDir);

        // 自己在 Java 侧清场：不能依赖 shell 的 rm -rf（本机有批量删除保护会拦下来），
        // 而残留数据会让「初始为空」这类断言全部失真。清完这个探针就可重复运行。
        wipe(baseDir);
        wipe(projectDir);

        // 旧版本配置迁移（~/.fast-agent → 数据根）已随本次需求废弃：
        // 数据目录改为按用户隔离（~/.jagent/<userId>/），历史配置不再自动搬运。
        ConfigurableApplicationContext ctx = startSpring();
        base = "http://127.0.0.1:" + resolvePort(ctx);
        System.out.println("PROBE base=" + base);

        // 后端解析出来的用户数据目录应与探针指定的隔离目录一致
        expect("后端用户数据目录 = 隔离目录/<userId>",
                dataDir.equals(com.fastagent.UserPaths.of(probeUser).root().toAbsolutePath()),
                "后端=" + com.fastagent.UserPaths.of(probeUser).root() + " 期望=" + dataDir);

        // ---------- 鉴权 ----------
        Res r401 = get("/api/models", null);
        expect("未登录访问 /api/models → 401", r401.status == 401, "实际 " + r401.status);

        Res login = post("/api/auth/login", "{\"username\":\"cjh\",\"password\":\"123456\"}", null);
        token = field(login.body, "token");
        expect("登录成功", login.status == 200 && token != null, "body=" + brief(login.body));

        // 选择文件夹：探针是无窗口环境（没有 JavaFX Stage），端点必须给可读提示而不是 500，
        // 否则浏览器里调试前端时「新建工作空间」会直接卡住。
        Res pick = post("/api/system/pick-directory", "{}", token);
        expect("无桌面窗口时 /api/system/pick-directory → 400 且提示可读",
                pick.status == 400 && pick.body.contains("桌面模式"),
                "status=" + pick.status + " body=" + brief(pick.body));

        // ---------- 目录布局 ----------
        expect("已建齐用户数据目录结构（数据根/<userId>/ 下）",
                Files.isDirectory(dataDir)
                        && Files.isDirectory(dataDir.resolve("memory"))
                        && Files.isDirectory(dataDir.resolve("tool"))
                        && Files.isDirectory(dataDir.resolve("skills")),
                "缺目录");
        expect("已播种 models.json / memory.md / tools.json",
                Files.exists(dataDir.resolve("models.json"))
                        && Files.exists(dataDir.resolve("memory/memory.md"))
                        && Files.exists(dataDir.resolve("tool/tools.json")),
                "缺文件");
        expect("已播种工作空间登记表，且初始为空数组（界面要能一眼看到索引放哪）",
                Files.exists(dataDir.resolve("workspaces.json"))
                        && Files.readString(dataDir.resolve("workspaces.json"), StandardCharsets.UTF_8).trim().equals("[]"),
                Files.exists(dataDir.resolve("workspaces.json"))
                        ? Files.readString(dataDir.resolve("workspaces.json"), StandardCharsets.UTF_8)
                        : "文件不存在");
        expect("工作空间目录内不再堆空间数据（改为用户指定目录）",
                !Files.exists(dataDir.resolve("workspaces")) || !Files.isDirectory(dataDir.resolve("workspaces")),
                "检测到数据根下的 workspaces/ 目录");

        // ---------- 初始为空 ----------
        expect("初始模型列表为空（不再有旧配置迁移进来的条目）",
                countModels(get("/api/models", token).body) == 0,
                "body=" + brief(get("/api/models", token).body));

        // ---------- 工作空间：用户指定目录 + 只存登记表 ----------
        String projJson = projectDir.toString().replace("\\", "\\\\");
        Res ws = post("/api/workspaces",
                "{\"name\":\"配置探针空间\",\"directory\":\"" + projJson + "\",\"description\":\"probe\"}",
                token);
        String wsId = field(ws.body, "id");
        expect("创建工作空间成功（指定目录）", ws.status == 200 && wsId != null, "body=" + brief(ws.body));

        Path wsData = projectDir.resolve(".jagentspace");
        expect("数据目录建在「用户指定目录/.jagentspace」下",
                Files.isDirectory(wsData)
                        && Files.exists(wsData.resolve("AGENTS.md"))
                        && Files.isDirectory(wsData.resolve(".state")),
                wsData.toString());
        expect("用户目录本身没有被塞进 agent 文件（AGENTS.md 在 .jagentspace 里，不在根上）",
                !Files.exists(projectDir.resolve("AGENTS.md")), projectDir.toString());

        Path registry = dataDir.resolve("workspaces.json");
        expect("登记表落盘到数据根/<userId>/workspaces.json", Files.isRegularFile(registry), registry.toString());
        JsonNode reg = JSON.readTree(registry.toFile());
        expect("登记表记录了空间数量与目录",
                reg.isArray() && reg.size() == 1
                        && reg.get(0).path("id").asText().equals(wsId)
                        && reg.get(0).path("directory").asText().equals(projectDir.toString()),
                reg.toString());

        expect("列表返回登记信息与派生目录",
                get("/api/workspaces", token).body.contains("\"dataDir\"")
                        && get("/api/workspaces", token).body.contains("\"directoryExists\":true"),
                brief(get("/api/workspaces", token).body));

        // 同一目录不允许登记两次
        Res dup = post("/api/workspaces",
                "{\"name\":\"重复的\",\"directory\":\"" + projJson + "\"}", token);
        expect("同一目录重复创建被拒 → 400",
                dup.status == 400 && dup.body.contains("已经是一个工作空间"),
                "status=" + dup.status + " body=" + brief(dup.body));

        // 相对路径被拒
        Res rel = post("/api/workspaces", "{\"name\":\"相对路径\",\"directory\":\"some/rel/path\"}", token);
        expect("相对路径被拒 → 400", rel.status == 400 && rel.body.contains("绝对路径"),
                "status=" + rel.status + " body=" + brief(rel.body));

        // ---------- 没配模型时必须给出可读提示 ----------
        Res noModel = post("/api/chat/stream",
                "{\"workspaceId\":\"" + wsId + "\",\"sessionId\":\"\",\"text\":\"hi\"}", token);
        expect("未配置模型时对话被拒 → 400 且提示模型",
                noModel.status == 400 && noModel.body.contains("模型"),
                "status=" + noModel.status + " body=" + brief(noModel.body));

        // ---------- 状态快照带上耗时与 token 字段 ----------
        // 前端在事件通道失效时靠快照兜底显示，字段缺了会静默少一行小字
        Res snap = get("/api/chat/snapshot?workspaceId=" + enc(wsId) + "&sessionId=s-none", token);
        expect("状态快照含 durationMs / token 字段",
                snap.status == 200 && snap.body.contains("\"durationMs\"")
                        && snap.body.contains("\"inputTokens\"")
                        && snap.body.contains("\"totalTokens\""),
                "body=" + brief(snap.body));

        // ---------- 模型配置 CRUD ----------
        Res empty = get("/api/models", token);
        expect("初始模型列表为空", empty.status == 200 && empty.body.contains("\"models\":[]"),
                "body=" + brief(empty.body));

        // id 里带斜杠：这是 WorkBuddy 的真实形态，用来验证「id 不能走路径参数」这条约束
        String sfId = "deepseek-ai/DeepSeek-V4-Flash";
        String sfBody = """
                {"id":"%s","name":"DeepSeek-V4-Flash (硅基流动)","vendor":"SiliconFlow",
                 "url":"https://api.siliconflow.cn/v1/chat/completions","apiKey":"sk-real-key-1234567890",
                 "supportsToolCall":true,"supportsImages":false,"supportsReasoning":true}
                """.formatted(sfId);
        Res add = post("/api/models", sfBody, token);
        expect("新增模型（id 含斜杠）成功", add.status == 200 && field(add.body, "activeId") != null,
                "body=" + brief(add.body));
        expect("新增返回的 Key 已脱敏", add.body.contains("****") && !add.body.contains("sk-real-key-1234567890"),
                "body=" + brief(add.body));

        // 落盘文件
        Path modelsFile = dataDir.resolve("models.json");
        JsonNode arr = JSON.readTree(modelsFile.toFile());
        expect("models.json 顶层是数组（与 WorkBuddy 同构）", arr.isArray() && arr.size() == 1,
                "size=" + arr.size());
        JsonNode m0 = arr.get(0);
        boolean fieldsOk = m0.has("id") && m0.has("name") && m0.has("vendor") && m0.has("url")
                && m0.has("apiKey") && m0.has("supportsToolCall") && m0.has("supportsImages")
                && m0.has("supportsReasoning");
        expect("字段与 WorkBuddy 一致（id/name/vendor/url/apiKey/supports*）", fieldsOk, m0.toString());
        expect("落盘的是真 Key（不是脱敏串）",
                "sk-real-key-1234567890".equals(m0.path("apiKey").asText()),
                "apiKey=" + m0.path("apiKey").asText());
        expect("active 标记已写入", m0.path("active").asBoolean(false), m0.toString());

        String modelsRaw = Files.readString(modelsFile, StandardCharsets.UTF_8);
        expect("models.json 排版与 WorkBuddy 一致（每行一个元素、冒号后单空格）",
                modelsRaw.startsWith("[\n  {\n    \"id\": ") && modelsRaw.contains("\n    \"name\": "),
                modelsRaw.substring(0, Math.min(90, modelsRaw.length())).replace("\n", "\\n"));

        // 再加一条，验证 activate
        String dpId = "deepseek-v4-flash";
        post("/api/models", """
                {"id":"%s","name":"DeepSeek-V4 Flash","vendor":"DeepSeek",
                 "url":"https://api.deepseek.com/chat/completions","apiKey":"sk-dp-0987654321",
                 "supportsToolCall":true,"supportsImages":false,"supportsReasoning":true}
                """.formatted(dpId), token);
        Res two = get("/api/models", token);
        expect("列表已有 2 条", countModels(two.body) == 2, "body=" + brief(two.body));

        Res act = post("/api/models/activate?id=" + enc(dpId), "", token);
        expect("切换当前模型成功", act.status == 200 && dpId.equals(field(act.body, "activeId")),
                "activeId=" + field(act.body, "activeId"));

        // 脱敏 Key 回传不应覆盖真 Key
        Res edit = post("/api/models", """
                {"id":"%s","name":"DeepSeek-V4 Flash 改名","vendor":"DeepSeek",
                 "url":"https://api.deepseek.com/chat/completions","apiKey":"sk-re****4321",
                 "supportsToolCall":true,"supportsImages":false,"supportsReasoning":true}
                """.formatted(dpId), token);
        expect("带脱敏 Key 的编辑请求被接受", edit.status == 200, "body=" + brief(edit.body));
        JsonNode after = JSON.readTree(modelsFile.toFile());
        String kept = null;
        for (JsonNode n : after) {
            if (dpId.equals(n.path("id").asText())) {
                kept = n.path("apiKey").asText();
            }
        }
        expect("脱敏 Key 未覆盖已存真 Key", "sk-dp-0987654321".equals(kept), "实际=" + kept);

        // ---------- 顺序即优先级 ----------
        // 当前落盘顺序 = 添加顺序：sfId 在前
        JsonNode orderBefore = JSON.readTree(modelsFile.toFile());
        expect("调整前顺序 = 添加顺序", sfId.equals(orderBefore.get(0).path("id").asText()),
                orderBefore.toString());

        Res ro = post("/api/models/reorder", "[\"" + dpId + "\",\"" + sfId + "\"]", token);
        expect("重排接口成功", ro.status == 200, "body=" + brief(ro.body));
        JsonNode orderAfter = JSON.readTree(modelsFile.toFile());
        expect("落盘顺序已按优先级调整，且两条都在（不会因排序丢配置）",
                orderAfter.size() == 2
                        && dpId.equals(orderAfter.get(0).path("id").asText())
                        && sfId.equals(orderAfter.get(1).path("id").asText()),
                orderAfter.toString());
        expect("重排不碰 API Key", "sk-dp-0987654321".equals(orderAfter.get(0).path("apiKey").asText()),
                orderAfter.get(0).path("apiKey").asText());

        // ---------- 编辑改名：仍是同一条，不是新增 ----------
        // 这是本次修的核心 bug：界面上的「模型名」就是主键，改了它必须仍是同一条记录
        String renamed = "deepseek-chat";
        Res rn = post("/api/models?originalId=" + enc(dpId), """
                {"id":"%s","name":"DeepSeek Chat（改过名）","vendor":"DeepSeek",
                 "url":"https://api.deepseek.com/chat/completions","apiKey":"",
                 "supportsToolCall":true,"supportsImages":false,"supportsReasoning":true}
                """.formatted(renamed), token);
        expect("改名后仍是同一条（总数不变）", rn.status == 200 && countModels(rn.body) == 2,
                "status=" + rn.status + " body=" + brief(rn.body));
        JsonNode afterRename = JSON.readTree(modelsFile.toFile());
        boolean hasNew = false, hasOld = false;
        String renamedKey = null;
        for (JsonNode n : afterRename) {
            if (renamed.equals(n.path("id").asText())) {
                hasNew = true;
                renamedKey = n.path("apiKey").asText();
            }
            if (dpId.equals(n.path("id").asText())) {
                hasOld = true;
            }
        }
        expect("新模型名生效、旧名消失", hasNew && !hasOld, afterRename.toString());
        expect("改名后原 API Key 仍在（换 id 不该丢 Key）",
                "sk-dp-0987654321".equals(renamedKey), "实际=" + renamedKey);
        expect("改名后默认模型标记跟着走",
                renamed.equals(field(rn.body, "activeId")), "activeId=" + field(rn.body, "activeId"));

        // 改名撞上别的模型的模型名：必须拒绝，否则等于把别人悄悄覆盖掉
        Res clash = post("/api/models?originalId=" + enc(sfId), """
                {"id":"%s","name":"想抢占的名字","vendor":"SiliconFlow",
                 "url":"https://api.siliconflow.cn/v1/chat/completions","apiKey":"sk-real-key-1234567890",
                 "supportsToolCall":true,"supportsImages":false,"supportsReasoning":true}
                """.formatted(renamed), token);
        expect("改名撞上其他模型的模型名 → 400 且提示可读",
                clash.status == 400 && clash.body.contains("占用"),
                "status=" + clash.status + " body=" + brief(clash.body));
        expect("被拒绝的改名没有改动文件（两条都还在）",
                JSON.readTree(modelsFile.toFile()).size() == 2,
                JSON.readTree(modelsFile.toFile()).toString());

        // ---------- 会话级模型 ----------
        Res ses1 = post("/api/workspaces/" + wsId + "/sessions", "", token);
        String sid1 = field(ses1.body, "sessionId");
        expect("新建会话成功，且没有写 modelId（新会话取默认模型）",
                ses1.status == 200 && sid1 != null && !ses1.body.contains("\"modelId\""),
                "body=" + brief(ses1.body));

        Res setM = post("/api/workspaces/" + wsId + "/sessions/" + sid1 + "/model",
                "{\"modelId\":\"" + renamed + "\"}", token);
        expect("为会话指定模型成功", setM.status == 200 && renamed.equals(field(setM.body, "modelId")),
                "body=" + brief(setM.body));

        Res sesList = get("/api/workspaces/" + wsId + "/sessions", token);
        expect("会话列表能读到该模型（重新进入这个会话时据此恢复）",
                sesList.body.contains("\"" + renamed + "\""), "body=" + brief(sesList.body));

        Res ses2 = post("/api/workspaces/" + wsId + "/sessions", "", token);
        expect("另建的新会话不带模型（会话之间互不影响）",
                ses2.status == 200 && !ses2.body.contains("\"modelId\""), "body=" + brief(ses2.body));

        Res clearM = post("/api/workspaces/" + wsId + "/sessions/" + sid1 + "/model",
                "{\"modelId\":\"\"}", token);
        expect("清空会话模型后回落默认（不会再记着）",
                clearM.status == 200 && !clearM.body.contains("\"modelId\""),
                "body=" + brief(clearM.body));

        // ---------- 全局记忆 ----------
        Res mem = get("/api/global/memory", token);
        expect("读取全局记忆成功且路径指向数据根/<userId>",
                mem.status == 200 && mem.body.contains("memory") && mem.body.contains("全局记忆"),
                "body=" + brief(mem.body));

        Res memSaved = put("/api/global/memory",
                "{\"content\":\"# 全局记忆\\n\\n- 回答要结论先行\\n- 探针写入\"}", token);
        expect("保存全局记忆成功", memSaved.status == 200 && memSaved.body.contains("结论先行"),
                "body=" + brief(memSaved.body));
        String onDisk = Files.readString(dataDir.resolve("memory/memory.md"), StandardCharsets.UTF_8);
        expect("memory.md 内容已落盘", onDisk.contains("结论先行"), "实际=" + onDisk);

        // ---------- 技能 ----------
        Res sk0 = get("/api/global/skills", token);
        expect("初始技能列表为空", sk0.status == 200 && sk0.body.contains("\"skills\":[]"),
                "body=" + brief(sk0.body));

        Path skillDir = dataDir.resolve("skills/probe-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: probe-skill
                description: 探针技能，用于验证技能目录被扫描
                ---

                # 探针技能
                """);
        Res sk1 = get("/api/global/skills", token);
        expect("新增技能目录后能被列出",
                sk1.status == 200 && sk1.body.contains("probe-skill") && sk1.body.contains("探针技能"),
                "body=" + brief(sk1.body));

        // README.md 不是技能
        expect("skills/README.md 未被当成技能", !sk1.body.contains("\"name\":\"README\""),
                "body=" + brief(sk1.body));

        // ---------- 工具 ----------
        Res tl = get("/api/global/tools", token);
        expect("读取工具配置成功", tl.status == 200 && tl.body.contains("tools.json"),
                "body=" + brief(tl.body));
        expect("内置工具已枚举（非空）", tl.body.contains("\"source\":\"内置\""), "body=" + brief(tl.body));
        expect("工具默认全部启用", tl.body.contains("\"enabled\":true"), "body=" + brief(tl.body));
        expect("工具配置里带 shellEnabled（默认关闭）",
                tl.body.contains("\"shellEnabled\":false"), "body=" + brief(tl.body));

        // shell 开关：这个字段 AgentScope 的 ToolsConfig 里没有，曾被「转成 ToolsConfig 再转回来」
        // 的路径丢过一次，所以专门验一遍「能存、能读回、能落盘」
        Res tSaved = put("/api/global/tools",
                "{\"allow\":[],\"deny\":[\"execute\",\"web_search\"],\"mcpServers\":{},\"shellEnabled\":true}", token);
        expect("保存工具配置成功", tSaved.status == 200, "body=" + brief(tSaved.body));
        JsonNode toolsJson = JSON.readTree(dataDir.resolve("tool/tools.json").toFile());
        expect("tools.json 三个键都在（空集合不被省略）",
                toolsJson.has("allow") && toolsJson.has("deny") && toolsJson.has("mcpServers"),
                toolsJson.toString());
        String toolsRaw = Files.readString(dataDir.resolve("tool/tools.json"), StandardCharsets.UTF_8);
        expect("空集合写成 [] / {} 而不是 [ ] / { }",
                toolsRaw.contains("\"allow\": []") && toolsRaw.contains("\"mcpServers\": {}"),
                toolsRaw.replace("\n", "\\n"));
        expect("deny 已落盘",
                toolsJson.path("deny").toString().contains("execute")
                        && toolsJson.path("deny").toString().contains("web_search"),
                toolsJson.toString());
        expect("deny 的工具在清单里显示为未启用",
                tSaved.body.contains("\"name\":\"execute\",\"source\":\"内置\",\"enabled\":false"),
                "body=" + brief(tSaved.body));
        expect("shellEnabled 能读回（存 true 后返回 true）",
                tSaved.body.contains("\"shellEnabled\":true"), "body=" + brief(tSaved.body));
        expect("shellEnabled 已落盘",
                JSON.readTree(dataDir.resolve("tool/tools.json").toFile()).path("shellEnabled").asBoolean(false),
                JSON.readTree(dataDir.resolve("tool/tools.json").toFile()).toString());

        // 复位成关闭，不影响后续语义
        put("/api/global/tools",
                "{\"allow\":[],\"deny\":[\"execute\",\"web_search\"],\"mcpServers\":{},\"shellEnabled\":false}", token);

        // ---------- 通用设置 ----------
        Res cfg = get("/api/config", token);
        expect("读取设置含各类路径", cfg.status == 200 && cfg.body.contains("skillsDir")
                && cfg.body.contains("modelsPath") && cfg.body.contains("memoryPath"),
                "body=" + brief(cfg.body));

        Res cfgSaved = post("/api/config",
                "{\"systemPrompt\":\"你是探针助手\",\"temperature\":0.3,\"enableThinking\":false,\"maxHistory\":8}",
                token);
        expect("保存设置成功",
                cfgSaved.status == 200 && cfgSaved.body.contains("你是探针助手") && cfgSaved.body.contains("0.3"),
                "body=" + brief(cfgSaved.body));
        expect("config.yaml 已落盘", Files.exists(dataDir.resolve("config.yaml")), "文件缺失");

        // ---------- 路径接口 ----------
        Res paths = get("/api/global/paths", token);
        expect("路径接口可用", paths.status == 200 && paths.body.contains("agentDir")
                && paths.body.contains("workspaces"), "body=" + brief(paths.body));

        // ---------- 删除模型 ----------
        Res del = delete("/api/models?id=" + enc(sfId), token);
        expect("删除模型成功", del.status == 200 && countModels(del.body) == 1, "body=" + brief(del.body));

        // ---------- 移除工作空间：默认不动磁盘 ----------
        Res rm1 = delete("/api/workspaces/" + wsId, token);
        expect("移除工作空间成功", rm1.status == 200, "body=" + brief(rm1.body));
        expect("登记已摘掉（列表为空）", countWorkspaces(get("/api/workspaces", token).body) == 0,
                brief(get("/api/workspaces", token).body));
        expect("默认不删磁盘：数据目录仍在", Files.isDirectory(wsData), wsData.toString());
        expect("用户目录也仍在", Files.isDirectory(projectDir), projectDir.toString());

        // purge=true 时只删 .jagentspace，用户目录保留
        Res ws2 = post("/api/workspaces",
                "{\"name\":\"待清理\",\"directory\":\"" + projJson + "\"}", token);
        String wsId2 = field(ws2.body, "id");
        expect("重新登记同一目录成功（摘掉后允许再建）", ws2.status == 200 && wsId2 != null,
                "body=" + brief(ws2.body));
        Files.writeString(projectDir.resolve("用户自己的文件.txt"), "不要动我", StandardCharsets.UTF_8);
        Res rm2 = delete("/api/workspaces/" + wsId2 + "?purge=true", token);
        expect("purge 移除成功", rm2.status == 200 && rm2.body.contains("\"purged\":true"),
                "body=" + brief(rm2.body));
        expect("purge 删掉了 .jagentspace", !Files.exists(wsData), wsData.toString());
        expect("purge 没碰用户自己的文件",
                Files.exists(projectDir.resolve("用户自己的文件.txt")), projectDir.toString());

        System.out.println("PROBE ===== 结束，失败项 = " + failed + " =====");
        System.out.println(failed == 0 ? "PROBE RESULT = ALL-GREEN" : "PROBE RESULT = HAS-FAILURES");

        ctx.close();
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---------- 断言与工具 ----------

    /** 递归删除；清理失败不抛异常，交给后面的断言暴露问题 */
    private static void wipe(Path p) {
        if (!Files.exists(p)) {
            return;
        }
        try (var walk = Files.walk(p)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(x -> {
                try {
                    Files.deleteIfExists(x);
                } catch (Exception ignored) {
                    // 忽略：被占用等情况下后面的断言会报出来
                }
            });
        } catch (Exception ignored) {
            // 同上
        }
    }

    private static void expect(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("PROBE [ OK ] " + name);
        } else {
            failed++;
            System.out.println("PROBE [FAIL] " + name + " —— " + detail);
        }
    }

    private static int countModels(String body) {
        try {
            JsonNode n = JSON.readTree(body);
            return n.path("models").size();
        } catch (Exception e) {
            return -1;
        }
    }

    /** 工作空间列表接口返回的是数组本身 */
    private static int countWorkspaces(String body) {
        try {
            return JSON.readTree(body).size();
        } catch (Exception e) {
            return -1;
        }
    }

    /** 从 JSON 文本里取一个字符串字段（探针只用于简单取值，不追求通用性） */
    private static String field(String body, String key) {
        try {
            JsonNode n = JSON.readTree(body);
            JsonNode v = n.get(key);
            return v == null || v.isNull() ? null : v.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static String brief(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 220 ? s : s.substring(0, 220) + "…";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ---------- HTTP ----------

    private record Res(int status, String body) {
    }

    private static Res get(String path, String tk) throws Exception {
        return send("GET", path, null, tk);
    }

    private static Res post(String path, String body, String tk) throws Exception {
        return send("POST", path, body, tk);
    }

    private static Res put(String path, String body, String tk) throws Exception {
        return send("PUT", path, body, tk);
    }

    private static Res delete(String path, String tk) throws Exception {
        return send("DELETE", path, null, tk);
    }

    private static Res send(String method, String path, String body, String tk) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(120));
        if (tk != null) {
            b.header("Authorization", "Bearer " + tk);
        }
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Res(r.statusCode(), r.body());
    }

    /**
     * 启动 Spring Boot。
     *
     * <p>端口用命令行参数传（优先级高于 application.yml）；0 分配到的端口在 Windows 上
     * 偶发「随后被占用」，与 Launcher 一样换高位随机端口重试。
     */
    private static ConfigurableApplicationContext startSpring() {
        for (int i = 1; i <= 8; i++) {
            int port = i == 1 ? 0 : 30000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(25000);
            SpringApplication app = new SpringApplication(FastAgentApplication.class);
            app.setBannerMode(Banner.Mode.OFF);
            try {
                return app.run("--server.port=" + port, "--server.address=127.0.0.1");
            } catch (Throwable t) {
                System.out.println("PROBE 启动失败（第 " + i + " 次，port=" + port + "）: " + t.getMessage());
            }
        }
        throw new IllegalStateException("Spring Boot 连续 8 次启动失败");
    }

    private static int resolvePort(ConfigurableApplicationContext ctx) {
        if (ctx instanceof ServletWebServerApplicationContext web && web.getWebServer() != null) {
            return web.getWebServer().getPort();
        }
        String raw = ctx.getEnvironment().getProperty("local.server.port");
        return raw == null ? -1 : Integer.parseInt(raw);
    }
}
