package com.fastagent.auth;

import com.fastagent.AppLog;
import com.fastagent.UserPaths;
import com.fastagent.service.ApiException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录与令牌管理。
 *
 * <p>当前阶段不接数据库、不接用户表：账号直接写死在 {@link #ACCOUNTS}。
 * 令牌存在内存里，进程重启即失效 —— 单机桌面应用的正常行为。
 * 后续接真实用户体系时，只需替换本类的实现，对外接口不变。
 */
@Service
public class AuthService {

    /** 写死的默认账号。如需多人，在此追加即可（后续应替换为数据库）。 */
    private static final Map<String, String> ACCOUNTS = Map.of(
            "cjh", "123456");

    /** token -> userId */
    private final Map<String, String> tokens = new ConcurrentHashMap<>();

    public record LoginResult(String token, String userId) {
    }

    public LoginResult login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new ApiException(400, "用户名和密码不能为空");
        }
        String user = username.trim();
        String expect = ACCOUNTS.get(user);
        if (expect == null || !expect.equals(password)) {
            AppLog.warn("登录失败: user=%s", user);
            throw new ApiException(401, "用户名或密码错误");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        tokens.put(token, user);
        // 登录成功即建齐该用户的数据目录（~/.jagent/<userId>/）。
        // 不能让「目录何时出现」取决于用户先点了哪个页面 —— 用户随时可能直接去
        // 「配置中心」或文件管理器里找它。启动时建不了是因为那时还不知道谁登录。
        UserPaths.of(user).ensureLayout();
        AppLog.info("登录成功: user=%s token=%s… 数据目录=%s", user, token.substring(0, 8),
                UserPaths.of(user).root());
        return new LoginResult(token, user);
    }

    public void logout(String token) {
        if (token != null && tokens.remove(token) != null) {
            AppLog.info("已登出 token=%s…", token.substring(0, Math.min(8, token.length())));
        }
    }

    /** 令牌无效或已登出时返回 null */
    public String userOf(String token) {
        return token == null || token.isBlank() ? null : tokens.get(token);
    }
}
