package com.blackshore.apt.processor.code;

import com.blackshore.apt.processor.util.Utils;

/**
 * 一个待写出的 Java 编译单元：全限定名（包名 + 顶层类型名）与源码。
 *
 * <p>多文件生成时入口文件排在第一个，其余文件是它引用的辅助类型；入口文件永远在目标包，
 * 辅助文件可以住在目标包之下的子包里。文件名始终由顶层类型名决定，
 * 这样 {@code Filer.createSourceFile} 与内存预编译拿到的名字天然一致，也满足
 * 「public 顶层类型必须与文件名同名」这条 javac 硬约束。</p>
 */
public record SourceFile(String fqn, String source) {

    /** 顶层类型简单名。 */
    public String simpleName() {
        return Utils.simpleName(fqn);
    }

    /** 写盘与内存编译用的文件名。 */
    public String fileName() {
        return simpleName() + ".java";
    }

    /** 按包层级的相对路径（如 {@code com/x/model/Order.java}）：子包场景下用它区分同名的不同文件。 */
    public String pathName() {
        return fqn.replace('.', '/') + ".java";
    }

    /** 所在包名（默认包时为空串）。 */
    public String packageName() {
        int index = fqn.lastIndexOf('.');
        return index < 0 ? "" : fqn.substring(0, index);
    }

    /** 源码行数，日志用。 */
    public int lines() {
        return (int) source.lines().count();
    }
}
