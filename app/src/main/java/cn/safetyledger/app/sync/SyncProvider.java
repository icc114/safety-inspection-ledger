package cn.safetyledger.app.sync;
import java.util.Map;
public interface SyncProvider{String type();ConnectionResult test(Map<String,String>config);record ConnectionResult(boolean success,String message){} }
