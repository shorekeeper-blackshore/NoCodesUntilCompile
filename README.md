# NoCodesUntilCompile

**编译期让 AI 写代码：一个 Maven 注解处理器（APT），在 `javac` 运行期间调用大模型生成 Java 实现，写回本次编译并参与编译。**

你只写一个带 `@Entrance` 的 `Main`（需求、模型、密钥、思考等级都能写在注解里），
处理器调用 DeepSeek / OpenAI / Anthropic 兼容接口生成 `AiProgramEntrance` 的实现类，
做内存预编译校验、把 javac 诊断回喂给模型自愈，最后通过 `Filer` 写回源码，
运行时 `AiApt.run(Main.class, args)` 再反射加载并执行它——形态与 `SpringApplication.run` 一致。

配置是可选的：`@Entrance` 一个成员都不写也能跑；`application.yml` 只用来临时压住注解（例如换模型/换 key 做对比）。

```text
@Entrance Main ──> AiCodeProcessor（编译期）
                        │  1. 读 @Entrance（逐目标默认值）+ application.yml（可选）/ -A 参数 / 环境变量
                        │  2. 组 prompt → 调用模型（带缓存）
                        │  3. 抽取 java 代码块，补包名/类名/implements
                        │  4. 内存内预编译（JavaCompiler）
                        │  5. 有错就把诊断回喂模型，最多 N 轮自愈
                        │  6. 失败按 fallback 兜底，构建不中断
                        ▼
              Filer.createSourceFile() → 参与这一轮编译
                        ▼
        AiApt.run(Main.class, args) → 反射加载生成的类并运行
```

配置优先级（高 → 低）：`javac -Aai.apt.*` / `-Dai.apt.*` / 环境变量 `AI_APT_*` > `application.yml` 里写下的非空值
> 内置默认值；`@Entrance` 里显式写出的项只是「当前目标的默认值」，排在配置文件之下。

## 目录结构

```text
NoCodesUntilCompile/
├── ai-apt-processor/     编译期注解处理器（零第三方依赖，只用 JDK）
│   └── src/main/java/com/blackshore/apt/processor/
│       ├── AiCodeProcessor.java       @Entrance 扫描 + Filer 写回
│       ├── ai/                        OpenAI / Anthropic 两种协议的客户端（含思考等级）
│       ├── code/                      代码抽取、预编译校验、自愈、兜底模板
│       ├── config/                    application.yml 宽松解析 + @Entrance 覆盖层（AnnotationOverlay）
│       ├── json/                      手写 JSON 序列化/解析
│       └── prompt/                    system/user/repair 提示模板
├── app/                  宿主工程：Main、@Entrance、AiApt、AiProgramEntrance
├── tools/StubServer.java 本地桩服务（不参与构建），无真实 key 时联调整条链路
└── pom.xml               聚合 + 父工程，统一 JDK 21 与插件版本
```

处理器单独成模块是必需的：`javac` 在编译开始前就要拿到处理器 class 与
`META-INF/services` 注册文件；与业务代码同模块时 IDEA 自带构建会直接报
`ServiceConfigurationError: Provider ... not found`。

## 环境要求

- JDK 21+（`maven.compiler.release=21`，JDK 21 起需显式 `<proc>full</proc>` 开启注解处理）
- Maven 3.9+

## 快速开始

```bash
# 1. 配置模型密钥（不要写进源码；也可以在注解里写 apiKey = "${AI_APT_API_KEY}"）
export AI_APT_API_KEY=sk-你的密钥

# 2. 编译：处理器会调用模型生成 com.blackshore.apt.aiMain
mvn clean compile

# 3. 运行（生成的类参与了 app 的编译）
java -cp app/target/classes com.blackshore.apt.Main hello world
```

**没配 `AI_APT_API_KEY` 也能构建**：处理器会识别为“未配置”，跳过网络请求，
按 `fallback=demo` 写出内置示例实现，编译照常成功——所以 CI / 新克隆的仓库不会卡住。

### 换需求 / 换模型（写在注解里）

需求、模型、端点、密钥、思考等级、采样参数、自愈轮数、缓存目录都能直接写在 `@Entrance` 上：

```java
@Entrance(
        value = {                                  // 需求：数组每个元素一行，拼接后就是完整 prompt
                "实现一个命令行计算器，支持 + - * / 与括号",
                "打印结果，除零给出提示"
        },
        model = @Entrance.Model(
                value = "deepseek-flash",          // 模型名，缺省按协议取默认模型
                baseUrl = "https://api.deepseek.com/v1",
                apiKey = "${AI_APT_API_KEY}",      // 或直接写死（不推荐）；空值=未配置，走 fallback
                thinking = "auto",                 // auto/off/minimal/low/medium/high
                temperature = 0.2,
                maxTokens = 0),                    // 0=不限制输出长度
        repair = @Entrance.Repair(rounds = 1),
        fallback = @Entrance.Fallback(value = "demo"))
public class Main {
    public static void main(String[] args) {
        AiApt.run(Main.class, args);
    }
}
```

- **可以完全不配**：`@Entrance` 一个成员都不写也能跑，未写的项全走内置默认值
- **每个成员独立判断**：嵌套分组里的单个成员也一样，没写才用默认值
- **多入口**：多个类各写一个 `@Entrance`（配合 `className`/`pkg` 区分生成目标）就能各用各的模型与需求
- **嫌配置太长就写组合注解**（类比 `@SpringBootApplication`），宿主类只剩一行：

```java
@Entrance(value = "…", model = @Entrance.Model(value = "deepseek-flash", apiKey = "${AI_APT_API_KEY}"))
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface AiApp {}

@AiApp
public class Main {
    public static void main(String[] args) { AiApt.run(Main.class, args); }
}
```

> `@Entrance` 是 `SOURCE` 保留级，组合注解必须与宿主类在**同一次编译**里，打进独立 jar 给别人用读不到配置。

### 配置文件仍然可用（优先级更高）

`application.yml` 是可选的；一旦写了某项，就压住注解里的同名项——适合临时换模型 / 换 key 做对比，不用改源码。
本仓库的 `app` 默认不放该文件（旧值备份在 `src/main/resources/application.yml.bak`，不入库）。

```yaml
# app/src/main/resources/application.yml
ai-apt:
  model:
    provider: openai                 # openai / anthropic；写 deepseek、qwen、claude 会自动归一
    base-url: https://api.deepseek.com/v1
    api-key: ${AI_APT_API_KEY:}      # 空值=未配置，不会发请求
    name: deepseek-chat
    thinking: medium                 # auto / off / minimal / low / medium / high
  prompt: |
    实现一个命令行计算器，支持 + - * / ……
```

- 配置优先级：`@Entrance`（逐目标默认值）< `application.yml` < 环境变量 `AI_APT_*` < `-Dai.apt.*` < `-Aai.apt.*`
- 被配置文件压住的注解项会逐条打印在编译日志里，方便排查「我明明写了注解却不生效」
- 键名很宽松：`base-url` / `baseUrl` / `base_url`、`api-key` / `apiKey` / `key` 都认

### 本地联调（不需要真实密钥、不花钱）

```bash
java tools/StubServer.java 8765          # 按 OpenAI/Anthropic 协议返回固定代码
export AI_APT_API_KEY=test-key
export AI_APT_BASE_URL=http://127.0.0.1:8765/v1
export STUB_MODE=fix                     # 第一次故意返回坏代码，验证“编译错误回喂自愈”
mvn clean compile
```

### 常用开关

| 配置 | 注解写法 | 作用 |
| --- | --- | --- |
| `enabled` | `@Entrance(enabled = false)` / 配置文件全局 `enabled` | 只关掉这一个入口 / 关掉整个 AI 生成流程 |
| `model.thinking` | `@Entrance.Model(thinking = "medium")` | `auto`（缺省，不下发，沿用模型默认）/ `off` / `minimal` / `low` / `medium` / `high`；OpenAI 兼容协议走 `reasoning_effort`，Anthropic 走 `thinking.budget_tokens`（此时按协议不再下发 temperature） |
| `model.maxTokens` | `@Entrance.Model(maxTokens = 8000)` | 单次输出上限，默认 0 = 不限制（Anthropic 必填时给 16384） |
| `fallback` | `@Entrance.Fallback(value = "demo")` | `demo`（内置示例）/ `stub`（运行时报错的占位）/ `error`（编译失败） |
| `fail-on-error` | `@Entrance.Fallback(failOnError = true)` | 生成失败时是否中断构建 |
| `repair.enabled` / `repair.rounds` | `@Entrance.Repair` | 把编译诊断回喂模型自愈，最多几轮 |
| `retry.enabled` / `retry.attempts` / `retry.backoffMillis` | `@Entrance.Retry` | 请求失败的重试次数与线性退避 |
| `cache.enabled` / `cache.dir` | `@Entrance.Cache` | 按 prompt + 配置指纹缓存生成结果，命中不重复计费（默认在 `target/` 下，随 clean 失效） |
| `accept-unverified` | `@Entrance.Fallback(acceptUnverified = true)` | 预编译没过也采用模型最后一次输出 |
| `pkg` / `className` | `@Entrance(pkg = …, className = …)` 或 `@Entrance.Output` | 生成类的包名与类名（默认被注解类所在包 + `aiMain`） |

## License

[GPL-3.0](LICENSE) © 2026 shorekeeper

本项目以 GNU General Public License v3.0 发布（强 copyleft）：可自由使用、修改、分发，
但**分发修改后的版本时必须同样以 GPL-3.0 开源，并保留版权声明**；软件按“原样”提供，不附带任何担保。

如果需要闭源集成或更宽松的授权（例如 MIT / Apache-2.0），属于单独的商业授权，请联系作者协商。
