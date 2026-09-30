import io.agentscope.core.agui.adapter.strategy.AgentEventConverterRegistry;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * 探针：打印 AG-UI 适配器的「AgentScope 事件 -> AG-UI 事件转换器」注册表。
 *
 * <p>关心的问题：一段正文会不会被转换两次（一次来自增量事件、一次来自整条消息事件），
 * 那样前端拼出来的正文就是双份，与落库的正文不等 —— 界面上就表现为「响应重复」并且
 * 生成结束后叠出第二个气泡。
 */
public class AguiConverterDump {

    public static void main(String[] args) throws Exception {
        Object reg = AgentEventConverterRegistry.class.getDeclaredConstructor().newInstance();
        for (Field f : AgentEventConverterRegistry.class.getDeclaredFields()) {
            f.setAccessible(true);
            Object v = f.get(reg);
            if (v instanceof Map<?, ?> map) {
                System.out.println("=== field " + f.getName() + " (" + map.size() + ") ===");
                map.forEach((k, c) -> System.out.println("  " + keyName(k) + "  ->  " + c.getClass().getName()));
            } else {
                System.out.println("=== field " + f.getName() + " -> " + v);
            }
        }
    }

    private static String keyName(Object k) {
        if (k instanceof Class<?> c) {
            return c.getName();
        }
        if (k instanceof String s) {
            return s;
        }
        return k.getClass().getName() + ":" + k;
    }
}
