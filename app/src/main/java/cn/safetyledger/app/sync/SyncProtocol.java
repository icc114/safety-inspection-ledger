package cn.safetyledger.app.sync;
public final class SyncProtocol{public static final int VERSION=1;private SyncProtocol(){}public record ChangeEnvelope(int protocolVersion,String entityType,String entityId,long revision,String deviceId,long updatedAt,String operation,String payloadHash){}public enum ConflictPolicy{KEEP_BOTH_NEWEST_AS_PRIMARY,REQUIRE_USER_RESOLUTION}}
