# NoCodesUntilCompile

**编译期让 AI 写代码：一个 Maven 注解处理器（APT），在 `javac` 运行期间调用大模型生成 Java 实现，写回本次编译并参与编译。**

你只写一个带 `@Entrance` 的 `Main`，处理器读取 `application.yml` 里的需求（prompt），
调用 DeepSeek / OpenAI / Anthropic 兼容接口生成 `AiProgramEntrance` 的实现类，
做内存预编译校验、把 javac 诊断回喂给模型自愈，最后通过 `Filer` 写回源码，
`Main` 再反射加载并执行它。

```text
@Entrance Main ──> AiCodeProcessor（编译期）
                        │  1. 读 application.yml / -A 参数 / 环境变量
                        │  2. 组 prompt → 调用模型（带缓存）
                        │  3. 抽取 java 代码块，补包名/类名/implements
                        │  4. 内存内预编译（JavaCompiler）
                        │  5. 有错就把诊断回喂模型，最多 N 轮自愈
                        │  6. 失败按 fallback 兜底，构建不中断
                        ▼
              Filer.createSourceFile() → 参与这一轮编译
                        ▼
        java com.blackshore.apt.Main → 反射加载生成的类并运行
```

## 目录结构

```text
NoCodesUntilCompile/
├── ai-apt-processor/     编译期注解处理器（零第三方依赖，只用 JDK）
│   └── src/main/java/com/blackshore/apt/processor/
│       ├── AiCodeProcessor.java       @Entrance 扫描 + Filer 写回
│       ├── ai/                        OpenAI / Anthropic 两种协议的客户端
│       ├── code/                      代码抽取、预编译校验、自愈、兜底模板
│       ├── config/                    application.yml 的宽松解析与参数合并
│       ├── json/                      手写 JSON 序列化/解析
│       └── prompt/                    system/user/repair 提示模板
├── app/                  宿主工程：Main、@Entrance、AiProgramEntrance、application.yml
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
# 1. 配置模型密钥（不要写进 application.yml）
export AI_APT_API_KEY=sk-你的密钥

# 2. 编译：处理器会调用模型生成 com.blackshore.apt.aiMain
mvn clean compile

# 3. 运行（生成的类参与了 app 的编译）
java -cp app/target/classes com.blackshore.apt.Main hello world
```

**没配 `AI_APT_API_KEY` 也能构建**：处理器会识别为“未配置”，跳过网络请求，
按 `fallback: demo` 写出内置示例实现，编译照常成功——所以 CI / 新克隆的仓库不会卡住。

### 换需求 / 换模型

修改 `app/src/main/resources/application.yml`：

```yaml
ai-apt:
  model:
    provider: openai                 # openai / anthropic；写 deepseek、qwen、claude 会自动归一
    base-url: https://api.deepseek.com/v1
    api-key: ${AI_APT_API_KEY:}      # 空值=未配置，不会发请求
    name: deepseek-chat
  prompt: |
    实现一个命令行计算器，支持 + - * / ……
```

- 需求也可以写在注解上：`@Entrance(prompt = "...", className = "myMain")`
- 配置优先级：`application.yml` < 环境变量 `AI_APT_*` < `-Dai.apt.*` < `-Aai.apt.*`
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

| 配置 | 作用 |
| --- | --- |
| `enabled` | 关掉整个 AI 生成流程 |
| `fallback` | `demo`（内置示例）/ `stub`（运行时报错的占位）/ `error`（编译失败） |
| `fail-on-error` | 生成失败时是否中断构建 |
| `repair.enabled` / `repair.rounds` | 把编译诊断回喂模型自愈，最多几轮 |
| `cache.enabled` / `cache.dir` | 按 prompt 指纹缓存生成结果，命中不重复计费（默认在 `target/` 下，随 clean 失效） |
| `accept-unverified` | 预编译没过也采用模型最后一次输出 |
| `output.package` / `output.class-name` | 生成类的包名与类名（默认 `com.blackshore.apt.aiMain`） |

## 密钥安全

仓库里**不包含任何真实 API Key**。`application.yml` 中的 `api-key`
只写成 `${AI_APT_API_KEY:}` 占位符，实际值通过环境变量（或
`-Dai.apt.apiKey=...` / `-Aai.apt.apiKey=...`）注入；`.env`、
`.idea/` 等本地文件已在 `.gitignore` 中排除。

## License

[MIT](LICENSE) © 2026 shorekeeper

可自由使用、修改、分发、商用，保留版权声明即可。
