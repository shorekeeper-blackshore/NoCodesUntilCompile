package com.blackshore.apt.processor.util;

import javax.annotation.processing.Messager;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 处理器统一日志出口：一条消息同时进入编译控制台与 work-dir/apt-compiler.log，
 * 便于事后回溯 AI 每一次调用的输入输出。
 */
public final class AptLog {

    private static final String TAG = "[AI-APT] ";

    private final Messager messager;
    private final List<String> history = new ArrayList<>();
    private Path logFile;
    private int warnings;
    private int errors;

    public AptLog(Messager messager) {
        this.messager = messager;
    }

    /** 附加日志文件后，后续日志会同步落盘；落盘失败不影响编译。 */
    public void attachFile(Path file) {
        this.logFile = file;
        if (file != null) {
            try {
                Utils.mkdirs(file.getParent());
                if (!Files.exists(file)) {
                    Files.writeString(file, "", StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
            } catch (IOException e) {
                logFile = null;
            }
        }
    }

    public void info(String format, Object... args) {
        emit(Diagnostic.Kind.NOTE, format, args);
    }

    public void stage(String format, Object... args) {
        emit(Diagnostic.Kind.NOTE, format, args);
    }

    public void warn(String format, Object... args) {
        warnings++;
        emit(Diagnostic.Kind.WARNING, format, args);
    }

    public void error(String format, Object... args) {
        errors++;
        emit(Diagnostic.Kind.ERROR, format, args);
    }

    public int warnings() {
        return warnings;
    }

    public int errors() {
        return errors;
    }

    public List<String> history() {
        return List.copyOf(history);
    }

    private void emit(Diagnostic.Kind kind, String format, Object... args) {
        String message = format(format, args);
        history.add(kind + ": " + message);
        try {
            messager.printMessage(kind, message);
        } catch (IllegalStateException ignored) {
            // javac 在 processingOver 之后可能拒绝消息，忽略即可
        }
        appendToFile("[" + kind.name() + "] " + message);
    }

    private String format(String format, Object... args) {
        String body = format;
        try {
            body = args == null || args.length == 0 ? format : String.format(format, args);
        } catch (RuntimeException e) {
            // 占位符与参数不匹配时退回原始文本，避免日志把主流程带崩
            body = format + " " + java.util.Arrays.toString(args);
        }
        return TAG + body;
    }

    private void appendToFile(String line) {
        if (logFile == null) {
            return;
        }
        try {
            Files.writeString(logFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 日志文件写失败不影响编译
        }
    }
}
