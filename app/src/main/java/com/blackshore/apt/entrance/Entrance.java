package com.blackshore.apt.entrance;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个“AI 代码生成目标”：编译时注解处理器会读取需求，让 AI 生成一个实现
 * {@link AiProgramEntrance} 的类，并通过 Filer 写回本次编译。
 *
 * <p>需求默认取自 resources/application.yml 的 prompt；这里的属性用于针对单个入口做覆盖。
 * 生成的类默认为 {@code 本注解所在类的同包.aiMain}，与 {@code Main} 反射加载的名字一致。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface Entrance {

    /** 覆盖 application.yml 中的 prompt，描述这个入口要实现的需求。 */
    String prompt() default "";

    /** 覆盖生成类的简单类名（包名沿用配置或被注解类所在包）。 */
    String className() default "";
}
