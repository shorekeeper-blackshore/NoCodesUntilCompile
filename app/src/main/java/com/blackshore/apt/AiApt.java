package com.blackshore.apt;

import com.blackshore.apt.entrance.AiProgramEntrance;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 运行期装载 AI 生成的入口并执行，形态对标 {@code SpringApplication.run(App.class, args)}：
 * 宿主类只需要一句 {@code AiApt.run(Main.class, args);}。
 *
 * <p>入口类名的查找顺序：{@code -Dai.apt.entrance=包名.类名} &gt; 宿主类同包的 {@code aiMain}
 * &gt; {@code com.blackshore.apt.aiMain}。这三处要和编译期配置（application.yml 的 output.class-name
 * 或 {@code @Entrance(pkg/className)}）保持一致——改了注解里的 className，记得同步
 * {@link #DEFAULT_SIMPLE_NAME}，或者干脆用 -D 指定。</p>
 */
public final class AiApt {

    /** 与 {@link com.blackshore.apt.entrance.Entrance} 里 className 的缺省值一致。 */
    public static final String DEFAULT_SIMPLE_NAME = "aiMain";

    /** 运行期覆盖入口类名的系统属性。 */
    public static final String ENTRANCE_PROPERTY = "ai.apt.entrance";

    /** 注解处理器的兜底缺省包名，宿主与它不同包时仍能命中。 */
    private static final String FALLBACK_PACKAGE = "com.blackshore.apt";

    private AiApt() {
    }

    /** 加载并运行 AI 生成的入口；找不到时把尝试过的类名都打出来。 */
    public static void run(Class<?> host, String[] args) {
        for (String name : candidates(host)) {
            Class<?> generated;
            try {
                generated = Class.forName(name, true, host.getClassLoader());
            } catch (ClassNotFoundException e) {
                continue;
            } catch (LinkageError e) {
                throw new IllegalStateException("AI 生成的入口类 " + name + " 无法加载: " + e, e);
            }
            try {
                AiProgramEntrance entrance = (AiProgramEntrance) generated.getConstructor().newInstance();
                entrance.run(args);
                return;
            } catch (ReflectiveOperationException | RuntimeException | Error e) {
                throw new IllegalStateException("运行 AI 生成的入口 " + name + " 失败: " + e, e);
            }
        }
        List<String> tried = candidates(host);
        System.err.println("未找到 AI 生成的入口类，尝试过: " + String.join(", ", tried));
        System.err.println("请先执行 mvn clean compile（编译期由注解处理器调用 AI 生成该类）；"
                + "也可以 -D" + ENTRANCE_PROPERTY + "=包名.类名 指定。");
        throw new IllegalStateException("缺少 AI 生成的入口类: " + String.join(" / ", tried));
    }

    /** 依次尝试的候选类名，去重后保持顺序。 */
    public static List<String> candidates(Class<?> host) {
        Set<String> names = new LinkedHashSet<>();
        String override = System.getProperty(ENTRANCE_PROPERTY);
        if (override != null && !override.isBlank()) {
            names.add(override.trim());
        }
        String pkg = packageOf(host);
        if (pkg != null && !pkg.isEmpty()) {
            names.add(pkg + "." + DEFAULT_SIMPLE_NAME);
        }
        names.add(FALLBACK_PACKAGE + "." + DEFAULT_SIMPLE_NAME);
        return new ArrayList<>(names);
    }

    /** 优先用 Class#getPackage()，拿不到（无名包、模块化边界等）时从类名反推。 */
    private static String packageOf(Class<?> host) {
        if (host == null) {
            return "";
        }
        if (host.getPackage() != null) {
            return host.getPackage().getName();
        }
        int index = host.getName().lastIndexOf('.');
        return index < 0 ? "" : host.getName().substring(0, index);
    }
}
