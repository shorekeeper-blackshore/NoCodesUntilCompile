package com.blackshore.apt;

import com.blackshore.apt.entrance.AiProgramEntrance;
import com.blackshore.apt.entrance.Entrance;

/**
 * 宿主入口：编译期由 {@link Entrance} 触发 AI 生成实现 {@link AiProgramEntrance} 的类，
 * 运行时反射加载并执行。
 *
 * <p>生成的类默认为 {@code com.blackshore.apt.aiMain}（与 application.yml 中 output.class-name 一致），
 * 也可以用 {@code -Dai.apt.entrance=包名.类名} 临时指定。</p>
 */
@Entrance
public class Main {

    private static final String DEFAULT_ENTRANCE = "com.blackshore.apt.aiMain";

    public static void main(String[] args) throws Exception {
        String className = System.getProperty("ai.apt.entrance", DEFAULT_ENTRANCE);
        Class<?> generated;
        try {
            generated = Class.forName(className, true, Main.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            System.err.println("未找到 AI 生成的入口类: " + className);
            System.err.println("请先执行 mvn clean compile（编译期由注解处理器调用 AI 生成该类）。");
            throw e;
        }
        AiProgramEntrance entrance = (AiProgramEntrance) generated.getConstructor().newInstance();
        entrance.run(args);
    }
}
