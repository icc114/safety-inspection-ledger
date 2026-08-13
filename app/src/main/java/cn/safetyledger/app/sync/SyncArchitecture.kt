package cn.safetyledger.app.sync

import java.io.InputStream
data class ProviderConfig(val type:String,val endpoint:String,val username:String?=null,val secret:String?=null,val token:String?=null)
data class RemoteObject(val key:String,val etag:String,val updatedAt:Long)
sealed interface ConnectionResult { data object Success:ConnectionResult; data class Failure(val message:String):ConnectionResult }
interface CloudProvider { suspend fun test():ConnectionResult; suspend fun list(cursor:String?):List<RemoteObject>; suspend fun upload(key:String,data:InputStream,sha256:String); suspend fun download(key:String):InputStream; suspend fun delete(key:String) }
interface ProviderFactory { fun create(config:ProviderConfig):CloudProvider }
// Provider-neutral boundary intentionally precedes Cloudflare/WebDAV/Drive/OneDrive/custom HTTP implementations.
