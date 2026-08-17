package cn.safetyledger.app.sync;

import android.database.sqlite.SQLiteException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/** Converts transport and local persistence exceptions into messages ordinary users can act on. */
public final class SyncErrorFormatter {
    private SyncErrorFormatter() {}

    public static boolean isNetwork(Throwable error) {
        for (Throwable current=error; current!=null; current=current.getCause()) {
            if (current instanceof SocketTimeoutException || current instanceof ConnectException
                    || current instanceof UnknownHostException || current instanceof NoRouteToHostException) return true;
            String m=current.getMessage(); if(m==null) continue; String s=m.toLowerCase();
            if(s.contains("failed to connect")||s.contains("timeout")||s.contains("timed out")
                    ||s.contains("unable to resolve host")||s.contains("network is unreachable")
                    ||s.contains("no route to host")) return true;
        }
        return false;
    }

    public static boolean isDatabase(Throwable error) {
        for (Throwable current=error; current!=null; current=current.getCause()) {
            if (current instanceof SQLiteException) return true;
            String m=current.getMessage(); if(m==null) continue; String s=m.toLowerCase();
            if(s.contains("sqlite_constraint")||s.contains("constraint failed")
                    ||s.contains("database is locked")||s.contains("sqlite")) return true;
        }
        return false;
    }

    public static String format(Throwable error) {
        if (isNetwork(error)) return "网络连接问题：暂时无法连接云同步服务器。请检查 Wi‑Fi/移动网络、VPN/代理后重试；本机检查记录不会因此丢失，网络恢复后可继续同步。";
        if (isDatabase(error)) return "本机数据库操作失败，本次操作已经停止。详细技术信息已写入诊断日志；请保留现有数据并升级到修复版本后重试，不需要清除应用数据。";
        String message=null;
        for(Throwable current=error;current!=null;current=current.getCause()) if(current.getMessage()!=null&&!current.getMessage().isBlank())message=current.getMessage();
        return message==null?error.getClass().getSimpleName():message;
    }

    public static String notificationTitle(Throwable error) {
        if (isNetwork(error)) return "安全检查台账同步失败 · 网络问题";
        if (isDatabase(error)) return "安全检查台账操作失败 · 本机数据库";
        return "安全检查台账同步失败";
    }
}
