import com.fastagent.AppPaths;
import com.fastagent.UserPaths;
import com.fastagent.config.ConfigService;
import com.fastagent.model.ModelsService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 只做初始化，不起 Spring、不开窗口。
 *
 * <p>用来在「还没启动过后端时先把某个用户的数据目录建出来」，或者打包前预置一份配置。
 * 幂等，重复运行不会覆盖已有文件。
 *
 * <p>用法：{@code java InitConfig [userId]}，省略时用 {@code cjh}。
 * 数据目录是 {@code ~/.jagent/<userId>/}（可用 {@code -Dfastagent.data.dir} 换数据根）；
 * <b>不传该属性时写的是真实目录</b>，这是刻意的 —— 它就是给真人用的。
 *
 * <p>注意 {@code config.yaml} 与 {@code models.json} 不是「目录建好就有」，
 * 而是在首次读配置时由各自的 Service 落盘，所以这里显式读一次。
 */
public class InitConfig {

    public static void main(String[] args) throws Exception {
        String userId = args.length > 0 && args[0] != null && !args[0].isBlank()
                ? args[0].trim()
                : "cjh";

        System.out.println("程序目录: " + AppPaths.exeDir());
        System.out.println("数据根  : " + AppPaths.baseDir());
        UserPaths paths = UserPaths.of(userId);
        System.out.println("用户目录: " + paths.root() + "（userId=" + paths.userId() + "）");

        paths.ensureLayout();
        // 读一次即落默认文件：config.yaml / models.json
        new ConfigService().get(userId);
        new ModelsService().list(userId);

        Path root = paths.root();
        System.out.println();
        System.out.println("=== " + root + " 下的文件 ===");
        if (Files.isDirectory(root)) {
            try (Stream<Path> s = Files.walk(root)) {
                List<Path> files = s.filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(Path::toString))
                        .toList();
                for (Path f : files) {
                    System.out.printf("  %-9d %s%n", Files.size(f), root.relativize(f));
                }
                System.out.println("共 " + files.size() + " 个文件");
            }
        } else {
            System.out.println("  （目录不存在，初始化失败）");
        }
    }
}
