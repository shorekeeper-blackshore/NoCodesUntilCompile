package com.blackshore.apt;

import com.blackshore.apt.entrance.Entrance;

/**
 * 宿主入口：编译期由 {@link Entrance} 触发 AI 生成一个实现
 * {@link com.blackshore.apt.entrance.AiProgramEntrance} 的类，运行期由 {@link AiApt} 反射加载并执行，
 * 形态与 Spring Boot 启动类一致（一个注解 + 一句 run）。
 *
 * <p>注解里只写与处理器内置默认值<b>不同</b>的部分：DeepSeek 的端点、模型名、密钥、采样温度，
 * 加上本次的需求文本。其余全部走默认值，需要时再补写对应分组即可：</p>
 * <ul>
 *   <li>生成目标：{@code com.blackshore.apt.aiMain}（包名沿用本类所在包，类名默认 aiMain，
 *       与 {@link AiApt} 运行期的推断一致）</li>
 *   <li>模型接入：provider=openai、thinking=auto（不下发思考等级）、maxTokens=0（不限制输出长度）、
 *       timeoutSeconds=120、tokenField=auto</li>
 *   <li>自愈与兜底：repair=开/2 轮、retry=开/3 次/退避 1000ms、cache=开/{@code target/ai-apt/cache}、
 *       fallback=demo、fail-on-error=关、accept-unverified=关</li>
 *   <li>构建产物：javadoc 头部=开、enforce-interface=开、work-dir={@code target/ai-apt}</li>
 * </ul>
 *
 * <p>本工程不再放 application.yml（原值备份在 {@code src/main/resources/application.yml.bak}）；
 * 一旦该文件写上某一项就会压住这里的注解，适合临时换模型/换 key 做对比而不改源码。</p>
 *
 * <p>api-key 目前是明文写在注解里；不想落源码就改成 {@code apiKey = "${AI_APT_API_KEY}"}
 * 并设置同名环境变量（环境变量 {@code AI_APT_*} 会被处理器直接读取）。拿不到有效 key 时不会发起
 * AI 调用，按 fallback=demo 写兜底实现，构建照常通过。</p>
 */
@Entrance(
        value = {
                "实现一个 Hello World 程序：",
                "1. 打印 \"Hello, AI APT Compiler! 注解版\"；",
                "2. 计算 1 到 200 的累加和并打印结果；",
                "3. 如果命令行传入了参数，把参数逐条打印出来。"
        },
        model = @Entrance.Model(
                value = "deepseek-flash",                       // 模型名必须服务端支持，写错会返回 HTTP 400
                baseUrl = "https://api.deepseek.com/v1",        // 也可只写域名，处理器会自动补 /v1
                apiKey = "", // 或 "${AI_APT_API_KEY}" 走环境变量
                thinking = "auto",
                temperature = 0.2)                              // 留空则不下发该字段
)
public class Main {

    public static void main(String[] args) {
        AiApt.run(Main.class, args);
    }
}
