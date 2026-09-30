package com.fastagent.util;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 配置文件的 JSON 读写约定。
 *
 * <p>目标是与 WorkBuddy 的配置文件观感一致，便于两边互看/互换：
 * <pre>
 * [
 *   {
 *     "id": "deepseek-v4-flash",
 *     ...
 *   }
 * ]
 * </pre>
 * Jackson 默认的空格风格是 {@code [ { ... } ]} 且键值分隔符写成 {@code " : "}，
 * 所以这里换掉数组缩进与分隔符。
 */
public final class JsonFiles {

    private JsonFiles() {
    }

    /** 读配置用：忽略多出来的字段，避免手改文件时因为多了个注释字段就整份失效 */
    public static ObjectMapper reader() {
        return new ObjectMapper()
                // 工具配置里有 java.time.Duration 字段，少了 JavaTimeModule 会在读到时报错
                .findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** 写配置用：缩进 2 空格、每行一个数组元素、冒号后单空格 */
    public static ObjectMapper writer() {
        return reader().setDefaultPrettyPrinter(prettyPrinter());
    }

    private static DefaultPrettyPrinter prettyPrinter() {
        return new FaPrettyPrinter();
    }

    /** 覆盖分隔符写法；缩进在构造里设定，{@code createInstance()} 会带上同样的设置 */
    private static final class FaPrettyPrinter extends DefaultPrettyPrinter {

        FaPrettyPrinter() {
            // 固定用 \n，不用 SYS_LF：Windows 上 SYS_LF 是 \r\n，同一份配置在两个平台
            // 会写出不同字节，diff 与版本库都不友好
            DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
            indentObjectsWith(indenter);
            indentArraysWith(indenter);
        }

        @Override
        public DefaultPrettyPrinter createInstance() {
            return new FaPrettyPrinter();
        }

        @Override
        public void writeObjectFieldValueSeparator(com.fasterxml.jackson.core.JsonGenerator g)
                throws java.io.IOException {
            g.writeRaw(": ");
        }

        /**
         * 空集合写成 {@code []} / {@code {}}。
         *
         * <p>Jackson 默认给空数组写 {@code [ ]}、空对象写 {@code { }}，与 JS 侧
         * {@code JSON.stringify} 的输出不一致；配置文件要和 WorkBuddy 的观感对齐，
         * 所以在这里把那个填充空格去掉。
         *
         * <p>绕过父类实现时必须自己把 {@code _nesting} 减回去，否则后续缩进会错位。
         */
        @Override
        public void writeEndArray(com.fasterxml.jackson.core.JsonGenerator g, int nrOfValues)
                throws java.io.IOException {
            if (nrOfValues == 0) {
                _nesting--;
                g.writeRaw(']');
                return;
            }
            super.writeEndArray(g, nrOfValues);
        }

        @Override
        public void writeEndObject(com.fasterxml.jackson.core.JsonGenerator g, int nrOfValues)
                throws java.io.IOException {
            if (nrOfValues == 0) {
                _nesting--;
                g.writeRaw('}');
                return;
            }
            super.writeEndObject(g, nrOfValues);
        }
    }
}
