package org.slf4j.impl;

import android.util.Log;

import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;

/**
 * Minimal slf4j Logger that forwards to android.util.Log.
 * Level policy: DEBUG and below are dropped to keep logcat noise down.
 */
public class LogcatLoggerAdapter extends org.slf4j.helpers.MarkerIgnoringBase {
    private static final int MAX_TAG_LEN = 23;

    private final String mTag;

    LogcatLoggerAdapter(String name) {
        this.name = name;
        String tag = name;
        int dot = tag.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < tag.length()) {
            tag = tag.substring(dot + 1);
        }
        mTag = tag.length() > MAX_TAG_LEN ? tag.substring(0, MAX_TAG_LEN) : tag;
    }

    private void log(String level, String format, Object arg1, Object arg2, Throwable t) {
        String msg;
        if (format == null) {
            msg = "";
        } else if (arg2 instanceof Throwable) {
            FormattingTuple ft = MessageFormatter.format(format, arg1);
            msg = ft.getMessage();
            t = (Throwable) arg2;
        } else if (arg1 == null && arg2 == null) {
            msg = format;
        } else if (arg2 == null) {
            msg = MessageFormatter.format(format, arg1).getMessage();
        } else {
            msg = MessageFormatter.format(format, arg1, arg2).getMessage();
        }
        println(level, msg, t);
    }

    private void println(String level, String msg, Throwable t) {
        switch (level) {
            case "I":
                if (t != null) Log.i(mTag, msg, t); else Log.i(mTag, msg);
                break;
            case "W":
                if (t != null) Log.w(mTag, msg, t); else Log.w(mTag, msg);
                break;
            case "E":
                if (t != null) Log.e(mTag, msg, t); else Log.e(mTag, msg);
                break;
            default:
                if (t != null) Log.d(mTag, msg, t); else Log.d(mTag, msg);
                break;
        }
    }

    @Override
    public boolean isTraceEnabled() { return false; }
    @Override
    public void trace(String msg) { }
    @Override
    public void trace(String format, Object arg) { }
    @Override
    public void trace(String format, Object arg1, Object arg2) { }
    @Override
    public void trace(String format, Object... arguments) { }
    @Override
    public void trace(String msg, Throwable t) { }

    @Override
    public boolean isDebugEnabled() { return false; }
    @Override
    public void debug(String msg) { }
    @Override
    public void debug(String format, Object arg) { }
    @Override
    public void debug(String format, Object arg1, Object arg2) { }
    @Override
    public void debug(String format, Object... arguments) { }
    @Override
    public void debug(String msg, Throwable t) { }

    @Override
    public boolean isInfoEnabled() { return true; }
    @Override
    public void info(String msg) { println("I", msg, null); }
    @Override
    public void info(String format, Object arg) { log("I", format, arg, null, null); }
    @Override
    public void info(String format, Object arg1, Object arg2) { log("I", format, arg1, arg2, null); }
    @Override
    public void info(String format, Object... arguments) {
        FormattingTuple ft = MessageFormatter.arrayFormat(format, arguments);
        println("I", ft.getMessage(), ft.getThrowable());
    }
    @Override
    public void info(String msg, Throwable t) { println("I", msg, t); }

    @Override
    public boolean isWarnEnabled() { return true; }
    @Override
    public void warn(String msg) { println("W", msg, null); }
    @Override
    public void warn(String format, Object arg) { log("W", format, arg, null, null); }
    @Override
    public void warn(String format, Object arg1, Object arg2) { log("W", format, arg1, arg2, null); }
    @Override
    public void warn(String format, Object... arguments) {
        FormattingTuple ft = MessageFormatter.arrayFormat(format, arguments);
        println("W", ft.getMessage(), ft.getThrowable());
    }
    @Override
    public void warn(String msg, Throwable t) { println("W", msg, t); }

    @Override
    public boolean isErrorEnabled() { return true; }
    @Override
    public void error(String msg) { println("E", msg, null); }
    @Override
    public void error(String format, Object arg) { log("E", format, arg, null, null); }
    @Override
    public void error(String format, Object arg1, Object arg2) { log("E", format, arg1, arg2, null); }
    @Override
    public void error(String format, Object... arguments) {
        FormattingTuple ft = MessageFormatter.arrayFormat(format, arguments);
        println("E", ft.getMessage(), ft.getThrowable());
    }
    @Override
    public void error(String msg, Throwable t) { println("E", msg, t); }
}
