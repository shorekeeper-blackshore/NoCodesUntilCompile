import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地桩服务器（联调用，不参与 Maven 构建）：
 * 按 OpenAI / Anthropic 两种协议返回固定的 AI 响应，用于在没有真实 api-key 时验证
 * 处理器的请求构造、代码抽取（含多文件）、预编译校验与编译错误自愈链路。
 *
 * 启动：
 *   java tools/StubServer.java 8765
 * 环境变量：
 *   STUB_MODE=ok       始终返回“包名/类名写错但可纠正”的代码
 *   STUB_MODE=fix      第一次返回带语法错误的代码，第二次返回正确代码（用于验证自愈）
 *   STUB_MODE=multi    返回入口类 + 辅助类两个文件（用于验证多文件生成）
 *   STUB_MODE=pkg      返回入口类 + model/service 子包共 3 个文件（用于验证子包与包结构）
 *   STUB_MODE=pkgclash 辅助类想写进工程已有的包（用于验证不往已有包里插生成代码）
 *   STUB_MODE=conflict 返回一个与工程已有类重名的辅助类（用于验证类名占用回喂与兜底）
 * 配合：
 *   $env:AI_APT_API_KEY="test-key"; $env:AI_APT_BASE_URL="http://127.0.0.1:8765/v1"
 *   mvn clean compile
 */
public class StubServer {

    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final String MODE = System.getenv().getOrDefault("STUB_MODE", "ok");

    /** 故意把包名和类名写错，验证处理器的纠正能力。 */
    private static final String GOOD_CODE = """
            package com.example.wrong;

            import com.blackshore.apt.entrance.AiProgramEntrance;

            public class HelloAiEntrance implements AiProgramEntrance {
                @Override
                public void run(String[] args) {
                    System.out.println("Hello from stubbed AI response");
                    System.out.println("args = " + java.util.Arrays.toString(args));
                }
            }
            """;

    /** 缺少右括号，触发 javac 诊断，验证“编译错误回喂模型”的自愈链路。 */
    private static final String BROKEN_CODE = """
            package com.blackshore.apt;

            import com.blackshore.apt.entrance.AiProgramEntrance;

            public class aiMain implements AiProgramEntrance {
                @Override
                public void run(String[] args) {
                    System.out.println("忘记了一个右括号"
                }
            }
            """;

    /** 多文件输出：两个 ```java 代码块，入口类引用同包的辅助类。 */
    private static final String MULTI_CONTENT = """
            已拆成两个文件：
            ```java
            package com.blackshore.apt;

            import com.blackshore.apt.entrance.AiProgramEntrance;

            public class aiMain implements AiProgramEntrance {
                @Override
                public void run(String[] args) {
                    System.out.println(new aiMainGreeter().greet());
                    System.out.println("args = " + java.util.Arrays.toString(args));
                }
            }
            ```
            辅助类单独一个文件：
            ```java
            package com.blackshore.apt;

            class aiMainGreeter {

                String greet() {
                    return "Hello from the second generated file";
                }
            }
            ```
            """;

    /** 多文件 + 子包：入口钉在目标包，模型与服务各住各的子包。 */
    private static final String PKG_CONTENT = """
            按职责拆成 3 个文件：
            ```java
            package com.blackshore.apt;

            import com.blackshore.apt.entrance.AiProgramEntrance;
            import com.blackshore.apt.model.Greeting;
            import com.blackshore.apt.service.GreetingService;

            public class aiMain implements AiProgramEntrance {
                @Override
                public void run(String[] args) {
                    Greeting greeting = new GreetingService().compose("AI APT Compiler");
                    System.out.println(greeting.text());
                    System.out.println("字符数 = " + greeting.length());
                }
            }
            ```
            ```java
            package com.blackshore.apt.model;

            public record Greeting(String text) {

                public int length() {
                    return text.length();
                }
            }
            ```
            ```java
            package com.blackshore.apt.service;

            import com.blackshore.apt.model.Greeting;

            public class GreetingService {

                public Greeting compose(String who) {
                    return new Greeting("Hello, " + who + "!");
                }
            }
            ```
            """;

    /** 辅助类想写进工程已有的 com.blackshore.apt.entrance 包，处理器应该拦下来并回喂模型。 */
    private static final String PKG_CLASH_CONTENT = """
            ```java
            package com.blackshore.apt;

            import com.blackshore.apt.entrance.AiProgramEntrance;
            import com.blackshore.apt.entrance.Greeting;

            public class aiMain implements AiProgramEntrance {
                @Override
                public void run(String[] args) {
                    System.out.println(new Greeting().text());
                }
            }
            ```
            ```java
            package com.blackshore.apt.entrance;

            public class Greeting {

                public String text() {
                    return "想挤进已有的包";
                }
            }
            ```
            """;

    /** 辅助类与工程里已有的 com.blackshore.apt.AiApt 重名，验证处理器不会把它写回编译。 */
    private static final String CONFLICT_CONTENT = """
            ```java
            package com.blackshore.apt;

            import com.blackshore.apt.entrance.AiProgramEntrance;

            public class aiMain implements AiProgramEntrance {
                @Override
                public void run(String[] args) {
                    System.out.println(new AiApt().hello());
                }
            }
            ```
            ```java
            package com.blackshore.apt;

            public class AiApt {

                public String hello() {
                    return "和工程里已有的类重名了";
                }
            }
            ```
            """;

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(args.length > 0 ? args[0] : "8765");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            int call = CALLS.incrementAndGet();
            StringBuilder dump = new StringBuilder();
            dump.append("=== 请求 #").append(call).append(' ').append(path).append('\n');
            exchange.getRequestHeaders().forEach((name, values) -> dump.append("  ").append(name).append(": ").append(values).append('\n'));
            dump.append("  body: ").append(request).append('\n');
            System.out.println(dump);

            String code = "fix".equals(MODE) && call == 1 ? BROKEN_CODE : GOOD_CODE;
            String content = switch (MODE) {
                case "multi" -> MULTI_CONTENT;
                case "pkg" -> PKG_CONTENT;
                case "pkgclash" -> PKG_CLASH_CONTENT;
                case "conflict" -> CONFLICT_CONTENT;
                default -> "生成结果如下：\n```java\n" + code + "```\n";
            };
            String response = path.endsWith("messages") ? anthropic(content) : openai(content);
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        System.out.println("STUB_READY mode=" + MODE + " port=" + port);
    }

    private static String openai(String content) {
        return "{\"id\":\"stub\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":"
                + json(content)
                + ",\"reasoning_content\":\"想了一会儿\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":22}}";
    }

    private static String anthropic(String content) {
        return "{\"id\":\"msg_stub\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-stub\","
                + "\"content\":[{\"type\":\"thinking\",\"thinking\":\"先想一下\"},{\"type\":\"text\",\"text\":"
                + json(content)
                + "}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":33,\"output_tokens\":44}}";
    }

    private static String json(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
