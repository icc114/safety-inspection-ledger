from pathlib import Path

p=Path('desktop/src/main/java/cn/safetyledger/pc/ArchiveService.java')
t=p.read_text(encoding='utf-8')
old='''        for (Map.Entry<String,Long> deleted : collectedTombstones.entrySet()) {
            String relative = index.getProperty(deleted.getKey() + ".path", "");
            if (relative.isBlank()) continue;
            Path oldFolder = root.resolve(relative).normalize();
'''
new='''        for (Map.Entry<String,Long> deleted : collectedTombstones.entrySet()) {
            String relative = index.getProperty(deleted.getKey() + ".path", "");
            if (relative.isBlank()) continue;
            long newestRecord = longValue(index.getProperty(deleted.getKey() + ".updated"), -1);
            Path oldFolder = root.resolve(relative).normalize();
            // A later administrator restore must win over an older deletion marker from a stale peer.
            if (newestRecord > deleted.getValue()) {
                if (oldFolder.startsWith(root)) Files.deleteIfExists(oldFolder.resolve("已从移动端删除.txt"));
                continue;
            }
'''
if old not in t: raise SystemExit('archive tombstone loop not found')
t=t.replace(old,new,1)
p.write_text(t,encoding='utf-8')

p=Path('desktop/src/main/java/cn/safetyledger/pc/CloudClient.java')
t=p.read_text(encoding='utf-8')
old='''    public void registerPcDevice(String deviceId, String displayName) throws Exception {
        long now = System.currentTimeMillis();
        String json = "{\\\"version\\\":1,\\\"deviceId\\\":\\\"" + escapeJson(deviceId) + "\\\",\\\"displayName\\\":\\\"" + escapeJson(displayName) + "\\\",\\\"role\\\":\\\"FIELD\\\",\\\"lastSeenAt\\\":" + now + ",\\\"updatedAt\\\":" + now + ",\\\"platform\\\":\\\"WINDOWS\\\"}";
        sendBytes("PUT", controlFileUrl(deviceId + ".device.json"), json.getBytes(StandardCharsets.UTF_8), null, 200,201,204);
    }
'''
new='''    public boolean isDeviceLoggedOut(String deviceId) throws Exception {
        HttpResponse<byte[]> response = send("GET", controlFileUrl(deviceId + ".logout"), HttpRequest.BodyPublishers.noBody(), null, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 404) return false;
        if (response.statusCode() / 100 == 2) return true;
        throw failure("无法读取电脑设备登出状态", response.statusCode(), response.body());
    }

    public void registerPcDevice(String deviceId, String displayName) throws Exception {
        String role = existingDeviceRole(deviceId);
        long now = System.currentTimeMillis();
        String json = "{\\\"version\\\":1,\\\"deviceId\\\":\\\"" + escapeJson(deviceId) + "\\\",\\\"displayName\\\":\\\"" + escapeJson(displayName) + "\\\",\\\"role\\\":\\\"" + role + "\\\",\\\"lastSeenAt\\\":" + now + ",\\\"updatedAt\\\":" + now + ",\\\"platform\\\":\\\"WINDOWS\\\"}";
        sendBytes("PUT", controlFileUrl(deviceId + ".device.json"), json.getBytes(StandardCharsets.UTF_8), null, 200,201,204);
    }

    private String existingDeviceRole(String deviceId) throws Exception {
        HttpResponse<byte[]> response = send("GET", controlFileUrl(deviceId + ".device.json"), HttpRequest.BodyPublishers.noBody(), null, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 404) return "FIELD";
        if (response.statusCode() / 100 != 2) throw failure("无法读取电脑设备信息", response.statusCode(), response.body());
        String json = new String(response.body(), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("\\\"role\\\"\\s*:\\s*\\\"(OWNER|ADMIN|FIELD|LOGGED_OUT)\\\"").matcher(json);
        return matcher.find() ? matcher.group(1) : "FIELD";
    }
'''
if old not in t: raise SystemExit('pc device registration block not found')
t=t.replace(old,new,1)
p.write_text(t,encoding='utf-8')

p=Path('desktop/src/main/java/cn/safetyledger/pc/SafetyLedgerDesktop.java')
t=p.read_text(encoding='utf-8')
old='''CloudClient client=new CloudClient(config.endpoint,config.space,config.password.toCharArray());client.testReadWrite();client.registerPcDevice(config.deviceId,config.deviceName);return "连接成功；本电脑已登记到设备管理";'''
new='''CloudClient client=new CloudClient(config.endpoint,config.space,config.password.toCharArray());client.testReadWrite();if(client.isDeviceLoggedOut(config.deviceId))throw new SecurityException("此电脑已被管理员登出；请先在管理员手机中允许该设备重新加入");client.registerPcDevice(config.deviceId,config.deviceName);return "连接成功；本电脑已登记到设备管理";'''
if old not in t: raise SystemExit('desktop test connection block not found')
t=t.replace(old,new,1)
old='''CloudClient client=new CloudClient(config.endpoint,config.space,config.password.toCharArray());client.prepare();client.registerPcDevice(config.deviceId,config.deviceName);'''
new='''CloudClient client=new CloudClient(config.endpoint,config.space,config.password.toCharArray());client.prepare();if(client.isDeviceLoggedOut(config.deviceId))throw new SecurityException("此电脑已被管理员登出；请先在管理员手机中允许该设备重新加入");client.registerPcDevice(config.deviceId,config.deviceName);'''
if old not in t: raise SystemExit('desktop sync registration block not found')
t=t.replace(old,new,1)
p.write_text(t,encoding='utf-8')
print('PC security finalization applied')
