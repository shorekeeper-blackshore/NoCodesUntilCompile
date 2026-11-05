package com.blackshore.apt.processor.config;

/**
 * 一次生成目标的完整形状：写去哪个包、入口类叫什么、能不能拆成多个文件、能不能建子包。
 *
 * <p>由 {@link AiAptConfig} 加上处理器算出的包名/类名合成，prompt 组装与源码抽取共用同一份，
 * 避免出现「提示词告诉模型可以建子包、抽取器却按不允许处理」这类口径不一致。</p>
 */
public record GenerationTarget(String pkg, String className, String interfaceFqn, boolean enforceInterface,
                               boolean multiFile, int maxFiles, boolean subPackages, int subPackageDepth) {

    public static GenerationTarget of(AiAptConfig config, String pkg, String className) {
        return new GenerationTarget(pkg, className, AiAptConfig.ENTRANCE_INTERFACE, config.enforceInterface(),
                config.multiFileEnabled(), config.maxGeneratedFiles(),
                config.subPackagesEnabled(), config.subPackageDepth());
    }

    /** 入口类的全限定名，运行期反射加载的就是它，任何时候都不受子包影响。 */
    public String entranceFqn() {
        return pkg + "." + className;
    }

    /** 子包只允许开在目标包之下；开关关掉、深度上限为 0 或包名越界都算不允许。 */
    public boolean allowsSubPackage(String declaredPkg) {
        return subPackages && subPackageDepth > 0 && declaredPkg != null && declaredPkg.startsWith(pkg + ".");
    }

    /** 声明的包相对目标包有几级子包（不在子树内时返回 0）。 */
    public int depthUnder(String declaredPkg) {
        if (!allowsSubPackage(declaredPkg)) {
            return 0;
        }
        return declaredPkg.substring(pkg.length() + 1).split("\\.").length;
    }

    /** prompt 与日志里描述输出形态用的短句。 */
    public String describeForm() {
        if (!multiFile) {
            return "单文件";
        }
        return subPackages && subPackageDepth > 0
                ? "多文件≤" + maxFiles + "，允许 " + pkg + ".* 子包≤" + subPackageDepth + "级"
                : "多文件≤" + maxFiles + "，全部平铺在 " + pkg;
    }
}
