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
 *   <li>输出形态：multi-file=开（AI 可以一次给出入口类 + 辅助类的多个 .java 文件，上限 5 个），
 *       sub-packages=开（辅助类可以按职责住进 {@code com.blackshore.apt.model} / {@code .service} / {@code .util}
 *       这样的子包，最深 2 级；越界、撞已有包或撞已有类都会被拦下并回喂模型改名，仍不行才走兜底），
 *       运行期仍然只加载入口类 aiMain（它始终直接住在本包，不会被挪进子包）</li>
 * </ul>
 *
 * <p>本工程不再放 application.yml（原值备份在 {@code src/main/resources/application.yml.bak}）；
 * 一旦该文件写上某一项就会压住这里的注解，适合临时换模型/换 key 做对比而不改源码。</p>
 *
 * <p>api-key 走占位符 {@code "${AI_APT_API_KEY}"}：密钥只放在环境变量里，不进源码（仓库是公开的）。
 * 想临时换 key / 换模型又不想动源码，就写 {@code app/src/main/resources/application.yml}（它会压住注解）。
 * 拿不到有效 key 时不会发起 AI 调用，按 fallback=demo 写兜底实现，构建照常通过。</p>
 */
@Entrance(
        value = {
                "实现一个简单面向对象程序：",
                "1. 抽象人类，学生类、老师类继承自人类；",
                "2. 数据模型放 model 子包、对象创建与打印逻辑放 service 子包，入口类留在本包；",
                "3. 创建 2 个对象并打印对象信息。"
        },
        model = @Entrance.Model(
                value = "deepseek-flash",                       // 模型名必须服务端支持，写错会返回 HTTP 400
                baseUrl = "https://api.deepseek.com/v1",        // 也可只写域名，处理器会自动补 /v1
                apiKey = "${AI_APT_API_KEY}",                   // 密钥走环境变量；也可直接写（不推荐，仓库公开）
                thinking = "auto",
                temperature = 0.2)                              // 留空则不下发该字段
)
public class Main {

    public static void main(String[] args) {
        AiApt.run(Main.class, args);
    }
}
