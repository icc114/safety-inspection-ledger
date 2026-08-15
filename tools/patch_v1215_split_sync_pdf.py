from pathlib import Path


def must_replace(path, old, new, count=1):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'pattern not found in {path}: {old[:180]!r}')
    p.write_text(text.replace(old, new, count), encoding='utf-8')


# Version.
must_replace('app/build.gradle', "versionCode 17\n        versionName '1.2.14'", "versionCode 18\n        versionName '1.2.15'")

# ---------------------------------------------------------------------------
# WebDAV transport: keep large business snapshots and tiny device-control files
# in separate directories.
# ---------------------------------------------------------------------------
p = Path('app/src/main/java/cn/safetyledger/app/sync/WebDavClient.java')
text = p.read_text(encoding='utf-8')
text = text.replace('''        mkcol(spaceUrl(space));
        mkcol(devicesUrl(space));
    }
''', '''        mkcol(spaceUrl(space));
        mkcol(devicesUrl(space));
        mkcol(deviceControlUrl(space));
    }
''', 1)
marker = '''    public void download(String space, String name, File target) throws Exception {
'''
insert = '''    /** Tiny device-management metadata channel; never contains inspection/photo data. */
    public List<String> listDeviceProfiles(String space) throws Exception {
        ResponseInfo response = execute("PROPFIND", deviceControlUrl(space), PROPFIND, "1");
        if (!response.successDav()) throw failure("无法读取设备管理目录", response);
        String xmlText = new String(response.body, StandardCharsets.UTF_8);
        String upperXml = xmlText.toUpperCase(java.util.Locale.ROOT);
        if (upperXml.contains("<!DOCTYPE") || upperXml.contains("<!ENTITY")) {
            throw new java.io.IOException("服务器返回了不安全的 XML DTD/ENTITY，已拒绝解析");
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        setXmlFeatureSafely(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setXmlFeatureSafely(factory, "http://xml.org/sax/features/external-general-entities", false);
        setXmlFeatureSafely(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        try { factory.setXIncludeAware(false); } catch (RuntimeException | AbstractMethodError ignored) {}
        try { factory.setExpandEntityReferences(false); } catch (RuntimeException | AbstractMethodError ignored) {}
        Document document = factory.newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(response.body));
        NodeList hrefs = document.getElementsByTagNameNS("*", "href");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < hrefs.getLength(); i++) {
            String href = hrefs.item(i).getTextContent();
            int slash = href.lastIndexOf('/');
            String name = URLDecoder.decode(slash >= 0 ? href.substring(slash + 1) : href,
                    StandardCharsets.UTF_8.name());
            if (name.endsWith(".device.json") && !names.contains(name)) names.add(name);
        }
        return names;
    }

    public void uploadDeviceProfile(String space, String deviceId, String json) throws Exception {
        putBytes(controlFileUrl(space, deviceId + ".device.json"),
                json.getBytes(StandardCharsets.UTF_8));
    }

    public String downloadDeviceProfile(String space, String deviceId) throws Exception {
        return new String(getBytes(controlFileUrl(space, deviceId + ".device.json")),
                StandardCharsets.UTF_8);
    }

    public void deleteDeviceProfile(String space, String deviceId) throws Exception {
        delete(controlFileUrl(space, deviceId + ".device.json"));
    }

'''
if marker not in text:
    raise SystemExit('download marker missing')
text = text.replace(marker, insert + marker, 1)
text = text.replace('''        String name = deviceId + ".logout";
        if (loggedOut) {
            putBytes(fileUrl(space, name),
                    String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
        } else {
            delete(fileUrl(space, name));
        }
''', '''        String name = deviceId + ".logout";
        if (loggedOut) {
            putBytes(controlFileUrl(space, name),
                    String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
        } else {
            delete(controlFileUrl(space, name));
            // Clean up markers written by 1.2.13/1.2.14.
            delete(fileUrl(space, name));
        }
''', 1)
text = text.replace('''        Request request = request(fileUrl(space, deviceId + ".logout")).head().build();
        try (Response response = http.newCall(request).execute()) {
            if (response.code() == 404) return false;
            if (!response.isSuccessful()) throw failure("读取设备登出状态失败", response);
            return true;
        }
''', '''        Request request = request(controlFileUrl(space, deviceId + ".logout")).head().build();
        try (Response response = http.newCall(request).execute()) {
            if (response.isSuccessful()) return true;
            if (response.code() != 404) throw failure("读取设备登出状态失败", response);
        }
        // Backward compatibility with old markers stored beside snapshots.
        Request legacy = request(fileUrl(space, deviceId + ".logout")).head().build();
        try (Response response = http.newCall(legacy).execute()) {
            if (response.code() == 404) return false;
            if (!response.isSuccessful()) throw failure("读取设备登出状态失败", response);
            return true;
        }
''', 1)
text = text.replace('''    private String spaceUrl(String space) { return endpoint + segment(wireSpace(space)) + "/"; }
    private String devicesUrl(String space) { return spaceUrl(space) + "devices/"; }
    private String fileUrl(String space, String name) { return devicesUrl(space) + segment(name); }
''', '''    private String spaceUrl(String space) { return endpoint + segment(wireSpace(space)) + "/"; }
    /** Large inspection-content snapshots. Kept at the legacy path for compatibility. */
    private String devicesUrl(String space) { return spaceUrl(space) + "devices/"; }
    /** Small device name/role/logout metadata. */
    private String deviceControlUrl(String space) { return spaceUrl(space) + "device-control/"; }
    private String fileUrl(String space, String name) { return devicesUrl(space) + segment(name); }
    private String controlFileUrl(String space, String name) { return deviceControlUrl(space) + segment(name); }
''', 1)
p.write_text(text, encoding='utf-8')

# ---------------------------------------------------------------------------
# Cloud snapshots omit untouched originals. Offline export still keeps them.
# ---------------------------------------------------------------------------
p = Path('app/src/main/java/cn/safetyledger/app/backup/BackupService.java')
text = p.read_text(encoding='utf-8')
text = text.replace('''    public void exportData(OutputStream destination,char[]password)throws Exception{
        if(password.length<8)throw new IllegalArgumentException("密码至少 8 位");
        exportInternal(destination,password,MAGIC);
    }
    public void exportPortable(OutputStream destination)throws Exception{
        exportInternal(destination,PORTABLE_KEY.clone(),PORTABLE_MAGIC);
    }
    private void exportInternal(OutputStream destination,char[]password,byte[]magic)throws Exception{
''', '''    public void exportData(OutputStream destination,char[]password)throws Exception{
        if(password.length<8)throw new IllegalArgumentException("密码至少 8 位");
        exportInternal(destination,password,MAGIC,true);
    }
    /** Cloud transport only needs the business JPEGs; untouched originals stay on the source phone. */
    public void exportCloudSnapshot(OutputStream destination,char[]password)throws Exception{
        if(password.length<8)throw new IllegalArgumentException("密码至少 8 位");
        exportInternal(destination,password,MAGIC,false);
    }
    public void exportPortable(OutputStream destination)throws Exception{
        exportInternal(destination,PORTABLE_KEY.clone(),PORTABLE_MAGIC,true);
    }
    private void exportInternal(OutputStream destination,char[]password,byte[]magic,boolean includeOriginals)throws Exception{
''', 1)
text = text.replace('''                File media=new File(context.getFilesDir(),"business_media");
                zipDir(z,media,"business_media/");
''', '''                File media=new File(context.getFilesDir(),"business_media");
                zipDir(z,media,"business_media/",includeOriginals);
''', 1)
text = text.replace('''    private void entry(ZipOutputStream z,String name,byte[]b)throws IOException{z.putNextEntry(new ZipEntry(name));z.write(b);z.closeEntry();}private void file(ZipOutputStream z,String name,File f)throws IOException{z.putNextEntry(new ZipEntry(name));Files.copy(f.toPath(),z);z.closeEntry();}private void zipDir(ZipOutputStream z,File dir,String prefix)throws IOException{if(!dir.isDirectory())return;File[]fs=dir.listFiles();if(fs==null)return;for(File f:fs)if(f.isDirectory())zipDir(z,f,prefix+f.getName()+"/");else file(z,prefix+f.getName(),f);}
''', '''    private void entry(ZipOutputStream z,String name,byte[]b)throws IOException{z.putNextEntry(new ZipEntry(name));z.write(b);z.closeEntry();}private void file(ZipOutputStream z,String name,File f)throws IOException{z.putNextEntry(new ZipEntry(name));Files.copy(f.toPath(),z);z.closeEntry();}private void zipDir(ZipOutputStream z,File dir,String prefix,boolean includeOriginals)throws IOException{if(!dir.isDirectory())return;File[]fs=dir.listFiles();if(fs==null)return;for(File f:fs)if(f.isDirectory())zipDir(z,f,prefix+f.getName()+"/",includeOriginals);else if(includeOriginals||!f.getName().endsWith("-original.bin"))file(z,prefix+f.getName(),f);}
''', 1)
# Device-management tables must not ride inside inspection-content merge.
text = text.replace('''                "signatures","app_settings","sync_providers","sync_devices","tombstones",
''', '''                "signatures","app_settings","sync_providers","tombstones",
''', 1)
text = text.replace('''        if(tableExists(d,"main","app_settings")){
            d.delete("app_settings","key IN ('device_id','cloud_role','device_role','last_sync_at','last_sync_error')",null);
        }
''', '''        if(tableExists(d,"main","app_settings")){
            d.delete("app_settings","key IN ('device_id','cloud_role','device_role','last_sync_at','last_sync_error')",null);
        }
        if(tableExists(d,"main","sync_devices")) d.delete("sync_devices",null,null);
''', 1)
p.write_text(text, encoding='utf-8')

# ---------------------------------------------------------------------------
# CloudSyncService: split tiny device-control sync from large inspection sync.
# ---------------------------------------------------------------------------
p = Path('app/src/main/java/cn/safetyledger/app/sync/CloudSyncService.java')
text = p.read_text(encoding='utf-8')
text = text.replace('import java.util.concurrent.atomic.AtomicBoolean;\n', 'import java.util.concurrent.atomic.AtomicBoolean;\n\nimport org.json.JSONObject;\n', 1)
text = text.replace('''    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
''', '''    private static final AtomicBoolean CONTENT_RUNNING = new AtomicBoolean(false);
    private static final AtomicBoolean DEVICE_RUNNING = new AtomicBoolean(false);
''', 1)
text = text.replace('''        if (!RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("已有同步任务正在运行，请等待当前同步完成后再试");
        }
''', '''        if (!CONTENT_RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("检查内容同步正在运行，请等待当前同步完成后再试");
        }
''', 1)
text = text.replace('''            RUNNING.set(false);
        }
    }

    /**
     * Lightweight paired-device discovery.''', '''            CONTENT_RUNNING.set(false);
        }
    }

    /**
     * Independent device-management synchronization. Only small JSON metadata and logout markers
     * are transferred; no inspection database, photo, signature or .safetydata file is touched.
     */
    public DiscoveryResult syncDeviceManagement() throws Exception {
        if (!DEVICE_RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("设备信息同步正在运行，请稍后再试");
        }
        Config config = null;
        try {
            config = requireConfig();
            WebDavClient client = client(config);
            prepare(client, config);
            String currentId = ensureDeviceId();
            if (client.isDeviceLoggedOut(config.space, currentId)) {
                disableLocalSync(true, currentId);
                long now = System.currentTimeMillis();
                repo.raw().execSQL("UPDATE sync_devices SET role='LOGGED_OUT',updated_at=? WHERE device_id=?",
                        new Object[]{now, currentId});
                return new DiscoveryResult(0, now, "LOGGED_OUT");
            }

            List<String> profiles = client.listDeviceProfiles(config.space);
            boolean ownProfile = profiles.contains(currentId + ".device.json");
            long now = System.currentTimeMillis();

            // Pull authoritative role/name metadata first so this device cannot overwrite an
            // administrator role change with stale local state.
            for (String file : profiles) {
                String id = file.substring(0, file.length() - ".device.json".length());
                try {
                    JSONObject json = new JSONObject(client.downloadDeviceProfile(config.space, id));
                    applyDeviceProfile(id, json, now);
                } catch (Exception ignored) {
                    // One malformed old control file must not block the rest of device management.
                }
            }

            if (!ownProfile && deviceRole(currentId) == null) {
                registerCurrentDevice(currentId, profiles.isEmpty());
            } else if (deviceRole(currentId) == null) {
                registerCurrentDevice(currentId, false);
            }
            String role = deviceRole(currentId);
            if ("LOGGED_OUT".equals(role)) {
                disableLocalSync(true, currentId);
                return new DiscoveryResult(Math.max(0, profiles.size() - 1), now, role);
            }

            // Publish only this phone's presence/name and its already-authorized role.
            String name = repo.setting("device_name", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
            client.uploadDeviceProfile(config.space, currentId,
                    deviceProfileJson(currentId, name, role == null ? "FIELD" : role, now));
            repo.raw().execSQL("UPDATE sync_devices SET display_name=?,last_seen_at=?,updated_at=? WHERE device_id=?",
                    new Object[]{name, now, now, currentId});
            updateLocalRoleSettings(currentId);
            repo.putSetting("last_device_sync_at", String.valueOf(now));
            repo.putSetting("last_device_sync_error", "");

            int remote = 0;
            try (Cursor cursor = repo.raw().rawQuery("SELECT count(*) FROM sync_devices WHERE device_id<>?",
                    new String[]{currentId})) { if (cursor.moveToFirst()) remote = cursor.getInt(0); }
            return new DiscoveryResult(remote, now, deviceRole(currentId));
        } finally {
            if (config != null) Arrays.fill(config.spacePassword, '\\0');
            DEVICE_RUNNING.set(false);
        }
    }

    /** Backward-compatible name used by the settings screen. */
    public DiscoveryResult discoverDevices() throws Exception { return syncDeviceManagement(); }

    public DeviceRoleResult updateDeviceRole(String targetDeviceId, String role) throws Exception {
        if (!("ADMIN".equals(role) || "FIELD".equals(role))) throw new IllegalArgumentException("设备角色无效");
        if (!DEVICE_RUNNING.compareAndSet(false, true)) throw new IllegalStateException("设备信息同步正在运行，请稍后再试");
        Config config = null;
        try {
            config = requireConfig(); WebDavClient client = client(config); prepare(client, config);
            String currentId = ensureDeviceId(); String currentRole = deviceRole(currentId);
            if (!("OWNER".equals(currentRole) || "ADMIN".equals(currentRole))) throw new SecurityException("只有管理员可以修改设备角色");
            if (targetDeviceId == null || targetDeviceId.isBlank() || targetDeviceId.equals(currentId)) throw new IllegalArgumentException("请选择其他设备");
            String targetRole = deviceRole(targetDeviceId);
            if ("OWNER".equals(targetRole)) throw new SecurityException("首位管理员不能降级");
            String name = deviceName(targetDeviceId); long now = System.currentTimeMillis();
            repo.raw().execSQL("UPDATE sync_devices SET role=?,updated_at=? WHERE device_id=?", new Object[]{role, now, targetDeviceId});
            client.uploadDeviceProfile(config.space, targetDeviceId, deviceProfileJson(targetDeviceId, name, role, now));
            return new DeviceRoleResult(targetDeviceId, role, now);
        } finally {
            if (config != null) Arrays.fill(config.spacePassword, '\\0');
            DEVICE_RUNNING.set(false);
        }
    }

    /**
     * Lightweight paired-device discovery.''', 1)
# Remove old discoverDevices implementation, now duplicated between our insertion and logout comment.
start = text.find('''    /**\n     * Lightweight paired-device discovery. It only reads the WebDAV/R2 device directory''')
if start < 0:
    raise SystemExit('old discovery start not found')
end = text.find('''    /**\n     * Administrator logout for another device.''', start)
if end < 0:
    raise SystemExit('old discovery end not found')
text = text[:start] + text[end:]
# Remaining management operations need their own small lock, not the content lock.
text = text.replace('''        if (!RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("已有同步任务正在运行，请等待当前同步完成后再试");
        }
''', '''        if (!DEVICE_RUNNING.compareAndSet(false, true)) {
            throw new IllegalStateException("设备信息同步正在运行，请稍后再试");
        }
''')
text = text.replace('RUNNING.set(false);', 'DEVICE_RUNNING.set(false);')
# Make remote logout/rejoin maintain the control profile and never upload a full snapshot.
text = text.replace('''            repo.raw().delete("sync_queue", "entity_type='sync_device' AND entity_id=?",
                    new String[]{targetDeviceId});
            return new DeviceLogoutResult(targetDeviceId, now);
''', '''            repo.raw().delete("sync_queue", "entity_type='sync_device' AND entity_id=?",
                    new String[]{targetDeviceId});
            client.uploadDeviceProfile(config.space, targetDeviceId,
                    deviceProfileJson(targetDeviceId, deviceName(targetDeviceId), "LOGGED_OUT", now));
            return new DeviceLogoutResult(targetDeviceId, now);
''', 1)
text = text.replace('''            // Upload the administrator snapshot once so a previously logged-out client can learn
            // that its role is FIELD again after the marker is removed.
            uploadSnapshot(new BackupService(context), client, config, currentId);
            return new DeviceLogoutResult(targetDeviceId, now);
''', '''            client.uploadDeviceProfile(config.space, targetDeviceId,
                    deviceProfileJson(targetDeviceId, deviceName(targetDeviceId), "FIELD", now));
            return new DeviceLogoutResult(targetDeviceId, now);
''', 1)
text = text.replace('''            client.deleteSnapshot(config.space, deviceId + ".safetydata");
            disableLocalSync(false, deviceId);
''', '''            client.deleteSnapshot(config.space, deviceId + ".safetydata");
            client.deleteDeviceProfile(config.space, deviceId);
            disableLocalSync(false, deviceId);
''', 1)
# Content snapshot uses smaller cloud package.
text = text.replace('backup.exportData(output, config.spacePassword.clone());', 'backup.exportCloudSnapshot(output, config.spacePassword.clone());', 1)
# Add helper methods before firstOwner().
helper_marker = '''    private String firstOwner() {
'''
helpers = '''    private void applyDeviceProfile(String deviceId, JSONObject json, long now) {
        String name = json.optString("displayName", "设备 " + shortDevice(deviceId));
        String role = json.optString("role", "FIELD");
        if (!("OWNER".equals(role) || "ADMIN".equals(role) || "FIELD".equals(role) || "LOGGED_OUT".equals(role))) role = "FIELD";
        long seen = json.optLong("lastSeenAt", now);
        long updated = json.optLong("updatedAt", seen);
        repo.raw().execSQL("INSERT OR IGNORE INTO sync_devices(device_id,display_name,role,first_seen_at,last_seen_at,updated_at) VALUES(?,?,?,?,?,?)",
                new Object[]{deviceId, name, role, now, seen, updated});
        repo.raw().execSQL("UPDATE sync_devices SET display_name=?,role=?,last_seen_at=?,updated_at=? WHERE device_id=?",
                new Object[]{name, role, seen, updated, deviceId});
        if (deviceId.equals(repo.setting("device_id", ""))) updateLocalRoleSettings(deviceId);
    }

    private String deviceProfileJson(String deviceId, String name, String role, long now) throws Exception {
        return new JSONObject().put("version", 1).put("deviceId", deviceId)
                .put("displayName", name == null ? "" : name).put("role", role)
                .put("lastSeenAt", now).put("updatedAt", now).toString();
    }

    private String deviceName(String deviceId) {
        try (Cursor cursor = repo.raw().rawQuery("SELECT display_name FROM sync_devices WHERE device_id=?", new String[]{deviceId})) {
            return cursor.moveToFirst() ? cursor.getString(0) : "设备 " + shortDevice(deviceId);
        }
    }

    private void updateLocalRoleSettings(String deviceId) {
        String role = deviceRole(deviceId);
        repo.putSetting("cloud_role", role == null ? "FIELD" : role);
        repo.putSetting("device_role", "OWNER".equals(role) || "ADMIN".equals(role) ? "PRIMARY" : "FIELD");
    }

'''
if helper_marker not in text:
    raise SystemExit('firstOwner marker missing')
text = text.replace(helper_marker, helpers + helper_marker, 1)
# Simplify registerCurrentDevice's setting update through shared helper.
text = text.replace('''        currentRole = deviceRole(deviceId);
        repo.putSetting("cloud_role", currentRole == null ? "FIELD" : currentRole);
        repo.putSetting("device_role", "OWNER".equals(currentRole) || "ADMIN".equals(currentRole)
                ? "PRIMARY" : "FIELD");
''', '''        updateLocalRoleSettings(deviceId);
''', 1)
# Discovery record now includes role; add DeviceRoleResult.
text = text.replace('''    public record DiscoveryResult(int remoteDevices, long completedAt) {}
    public record DeviceLogoutResult(String deviceId, long completedAt) {}
''', '''    public record DiscoveryResult(int remoteDevices, long completedAt, String role) {}
    public record DeviceRoleResult(String deviceId, String role, long completedAt) {}
    public record DeviceLogoutResult(String deviceId, long completedAt) {}
''', 1)
p.write_text(text, encoding='utf-8')

# ---------------------------------------------------------------------------
# Scheduler: small device metadata and large inspection content have different jobs.
# ---------------------------------------------------------------------------
p = Path('app/src/main/java/cn/safetyledger/app/sync/CloudSyncScheduler.java')
text = p.read_text(encoding='utf-8')
text = text.replace('''    public static final int CHANGE_JOB_ID = 1142027;
    private static final long TWO_HOURS = 2L * 60L * 60L * 1000L;
''', '''    public static final int CHANGE_JOB_ID = 1142027;
    public static final int DEVICE_JOB_ID = 1142028;
    private static final long TWO_HOURS = 2L * 60L * 60L * 1000L;
    private static final long DEVICE_INTERVAL = 30L * 60L * 1000L;
''', 1)
text = text.replace('''        scheduler.schedule(periodic);
    }
''', '''        scheduler.schedule(periodic);
        JobInfo devices = new JobInfo.Builder(DEVICE_JOB_ID,
                new ComponentName(context, CloudSyncJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(DEVICE_INTERVAL)
                .setPersisted(true)
                .build();
        scheduler.schedule(devices);
    }
''', 1)
text = text.replace('''        scheduler.cancel(PERIODIC_JOB_ID);
        scheduler.cancel(CHANGE_JOB_ID);
''', '''        scheduler.cancel(PERIODIC_JOB_ID);
        scheduler.cancel(CHANGE_JOB_ID);
        scheduler.cancel(DEVICE_JOB_ID);
''', 1)
p.write_text(text, encoding='utf-8')

p = Path('app/src/main/java/cn/safetyledger/app/sync/CloudSyncJobService.java')
text = p.read_text(encoding='utf-8')
text = text.replace('''            try {
                new CloudSyncService(this).syncNow();
''', '''            try {
                CloudSyncService service = new CloudSyncService(this);
                if (params.getJobId() == CloudSyncScheduler.DEVICE_JOB_ID) service.syncDeviceManagement();
                else service.syncNow();
''', 1)
p.write_text(text, encoding='utf-8')

# ---------------------------------------------------------------------------
# Settings UI: explicit device-sync step and inspection-content-sync step.
# ---------------------------------------------------------------------------
p = Path('app/src/main/java/cn/safetyledger/app/SettingsActivity.java')
text = p.read_text(encoding='utf-8')
text = text.replace('''    private TextView syncEnabledStatus;
    private TextView syncStatus;
''', '''    private TextView syncEnabledStatus;
    private TextView syncStatus;
    private TextView deviceSyncStatus;
''', 1)
text = text.replace('''        TextView note = Ui.text(this,
                "首次创建同步空间的设备自动成为管理员；使用相同同步空间名称和密码加入的手机默认为工作人员。管理员可接收全部设备记录并管理已配对设备。Windows 客户端仍需后续交付。",
                13, false);
''', '''        TextView note = Ui.text(this,
                "设备管理与检查内容已经分开：这里只同步设备名称、角色、最后在线时间和登出状态，不上传或下载检查记录、照片、签名。首台设备为管理员，后加入设备默认为工作人员。",
                13, false);
''', 1)
text = text.replace('''        save.setOnClickListener(view -> {
            repo.putSetting("device_name", deviceName.getText().toString().trim());
            Ui.toast(this, "设备名称已保存；角色由云端配对结果自动识别");
        });
        card.addView(save);
        card.addView(Ui.gap(this, 6));
        Button manage = Ui.secondaryButton(this, "管理已配对设备");
        manage.setOnClickListener(view -> refreshAndManageDevices());
        card.addView(manage);
''', '''        save.setOnClickListener(view -> {
            repo.putSetting("device_name", deviceName.getText().toString().trim());
            Ui.toast(this, "设备名称已保存；点“同步设备信息”即可立即更新到其他设备");
        });
        card.addView(save);
        card.addView(Ui.gap(this, 6));
        deviceSyncStatus = Ui.text(this, "设备同步：尚未同步", 13, true);
        deviceSyncStatus.setTextColor(Ui.MUTED);
        String lastDeviceSync = repo.setting("last_device_sync_at", "");
        if (!lastDeviceSync.isBlank()) {
            try { deviceSyncStatus.setText("设备同步：已同步 · " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(Long.parseLong(lastDeviceSync)))); }
            catch (Exception ignored) {}
        }
        card.addView(deviceSyncStatus);
        LinearLayout deviceActions = Ui.row(this);
        Button syncDevices = Ui.compactButton(this, "同步设备信息", true);
        Button manage = Ui.compactButton(this, "管理已配对设备", false);
        syncDevices.setOnClickListener(view -> syncDeviceInfo(false));
        manage.setOnClickListener(view -> manageDevices());
        deviceActions.addView(syncDevices, Ui.weight(1));
        deviceActions.addView(Ui.horizontalGap(this, 5));
        deviceActions.addView(manage, Ui.weight(1));
        card.addView(deviceActions);
''', 1)
text = text.replace('''        card.addView(Ui.sectionTitle(this, "4", "云同步", "服务提供商可切换，失败不影响本地填报"));
''', '''        card.addView(Ui.sectionTitle(this, "4", "检查内容同步", "仅同步模板、检查记录、照片、签名和整改内容"));
''', 1)
text = text.replace('''                "自动同步策略：本机记录、照片、签名等有变更后约 2–5 分钟合并后台同步；无本地变更时约每 2 小时检查一次云端。设备管理只读取云端设备目录，不再执行全量记录/照片同步。",
''', '''                "检查内容与设备管理完全分开。本机检查记录、照片、签名等有变更后约 2–5 分钟合并后台同步；无本地变更时约每 2 小时检查一次其他设备的检查内容。设备角色变化不会触发整包照片同步。",
''', 1)
text = text.replace('''        Button now = Ui.compactButton(this, "立即同步", false);
''', '''        Button now = Ui.compactButton(this, "同步检查内容", false);
''', 1)
text = text.replace('''            syncStatus.setText("同步状态：配置已保存，正在首次同步…");
            Ui.toast(this, "云同步已启用，正在同步");
            syncNow();
''', '''            syncStatus.setText("检查内容：配置已保存；有检查变更时自动同步，也可手动同步");
            Ui.toast(this, "云同步已启用；正在单独登记设备信息");
            syncDeviceInfo(false);
''', 1)
# Replace refreshAndManageDevices with device-only sync method, preserving manageDevices as a separate action.
old = '''    private void refreshAndManageDevices() {
        syncStatus.setText("同步状态：正在读取云端设备列表…");
        new Thread(() -> {
            try {
                CloudSyncService.DiscoveryResult result = new CloudSyncService(this).discoverDevices();
                runOnUiThread(() -> {
                    String time = DateFormat.getTimeInstance(DateFormat.SHORT)
                            .format(new Date(result.completedAt()));
                    syncStatus.setText("同步状态：设备列表已刷新 · " + time
                            + " · 云端其他设备 " + result.remoteDevices() + " 台 · 未执行全量同步");
                    manageDevices();
                });
            } catch (Exception error) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName()
                        : error.getMessage();
                runOnUiThread(() -> {
                    syncStatus.setText("同步状态：设备列表刷新失败 · " + message);
                    new AlertDialog.Builder(this).setTitle("刷新设备列表失败")
                            .setMessage(message).setPositiveButton("确定", null).show();
                });
            }
        }, "quick-device-discovery").start();
    }
'''
new = '''    private void syncDeviceInfo(boolean openAfter) {
        if (deviceSyncStatus != null) deviceSyncStatus.setText("设备同步：正在同步设备名称和角色…");
        new Thread(() -> {
            try {
                CloudSyncService.DiscoveryResult result = new CloudSyncService(this).syncDeviceManagement();
                runOnUiThread(() -> {
                    String time = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(result.completedAt()));
                    if ("LOGGED_OUT".equals(result.role())) {
                        if (deviceSyncStatus != null) deviceSyncStatus.setText("设备同步：本设备已被管理员登出");
                        syncEnabledStatus.setText("云同步：未启用");
                        return;
                    }
                    if (deviceSyncStatus != null) deviceSyncStatus.setText("设备同步：成功 · " + time + " · 其他设备 " + result.remoteDevices() + " 台");
                    deviceRole.setSelection("FIELD".equals(result.role()) ? 1 : 0);
                    if (openAfter) manageDevices();
                });
            } catch (Exception error) {
                String message = readableError(error);
                runOnUiThread(() -> {
                    if (deviceSyncStatus != null) deviceSyncStatus.setText("设备同步：失败 · " + message);
                    new AlertDialog.Builder(this).setTitle("设备信息同步失败").setMessage(message).setPositiveButton("确定", null).show();
                });
            }
        }, "device-management-sync").start();
    }
'''
if old not in text:
    raise SystemExit('refreshAndManageDevices block not found')
text = text.replace(old, new, 1)
# Direct role management writes tiny control metadata instead of queueing a content snapshot.
text = text.replace('''                    String role = which == 0 ? "ADMIN" : "FIELD";
                    repo.raw().execSQL("UPDATE sync_devices SET role=?,updated_at=? WHERE device_id=?",
                            new Object[]{role, System.currentTimeMillis(), deviceId});
                    repo.queueDeviceRole(deviceId);
                    Ui.toast(this, "设备已设为" + roleName(role) + "，已加入后台同步队列");
''', '''                    String role = which == 0 ? "ADMIN" : "FIELD";
                    updateDeviceRoleDirect(deviceId, role);
''', 1)
# Add helper before remote logout confirmation.
helper_marker = '''    private void confirmRemoteDeviceLogout(String deviceId, String label) {
'''
helper = '''    private void updateDeviceRoleDirect(String deviceId, String role) {
        if (deviceSyncStatus != null) deviceSyncStatus.setText("设备同步：正在更新设备角色…");
        new Thread(() -> {
            try {
                CloudSyncService.DeviceRoleResult result = new CloudSyncService(this).updateDeviceRole(deviceId, role);
                runOnUiThread(() -> {
                    String time = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(result.completedAt()));
                    if (deviceSyncStatus != null) deviceSyncStatus.setText("设备同步：角色已更新 · " + time);
                    Ui.toast(this, "设备已设为" + roleName(result.role()) + "；未同步检查照片或记录");
                });
            } catch (Exception error) {
                String message = readableError(error);
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("设备角色更新失败").setMessage(message).setPositiveButton("确定", null).show());
            }
        }, "device-role-update").start();
    }

'''
if helper_marker not in text:
    raise SystemExit('role helper marker not found')
text = text.replace(helper_marker, helper + helper_marker, 1)
p.write_text(text, encoding='utf-8')

# ---------------------------------------------------------------------------
# Photo metadata: reverse geocode GPS into Chinese text, never watermark raw coordinates.
# ---------------------------------------------------------------------------
p = Path('app/src/main/java/cn/safetyledger/app/media/MediaService.java')
text = p.read_text(encoding='utf-8')
text = text.replace('import android.location.Location;\n', 'import android.location.Address;\nimport android.location.Geocoder;\nimport android.location.Location;\n', 1)
text = text.replace('''        String gpsText = "";
        if (latitude != null && longitude != null) {
            gpsText = chineseCoordinates(latitude, longitude);
            lines.add("拍摄位置：" + gpsText);
        }
''', '''        String locationText = "";
        if (latitude != null && longitude != null) {
            locationText = reverseGeocode(latitude, longitude);
            if (!locationText.isBlank()) lines.add("拍摄地点：" + locationText);
        }
''', 1)
text = text.replace('''        media.location = gpsText;
''', '''        media.location = locationText;
''', 1)
start = text.find('''    private String chineseCoordinates(double latitude, double longitude) {''')
if start < 0:
    raise SystemExit('coordinate helper not found')
end = text.find('''    private void drawWatermark''', start)
if end < 0:
    raise SystemExit('watermark helper marker not found')
reverse = '''    @SuppressWarnings("deprecation")
    private String reverseGeocode(double latitude, double longitude) {
        if (!Geocoder.isPresent()) return "";
        try {
            Geocoder geocoder = new Geocoder(context, Locale.CHINA);
            List<Address> addresses = geocoder.getFromLocation(latitude, longitude, 1);
            if (addresses == null || addresses.isEmpty()) return "";
            Address address = addresses.get(0);
            String line = address.getMaxAddressLineIndex() >= 0 ? address.getAddressLine(0) : "";
            if (line == null) line = "";
            line = line.trim().replaceFirst("^中国", "").replaceFirst("^中华人民共和国", "");
            line = line.replaceAll("\\s*\\d{6}$", "").trim();
            if (!line.isBlank()) return line;
            StringBuilder value = new StringBuilder();
            for (String part : new String[]{address.getAdminArea(), address.getSubAdminArea(),
                    address.getLocality(), address.getSubLocality(), address.getThoroughfare(),
                    address.getFeatureName()}) {
                if (part != null && !part.isBlank() && value.indexOf(part) < 0) value.append(part);
            }
            return value.toString();
        } catch (Exception ignored) {
            // If GPS cannot be converted to a readable place name, omit location watermark.
            return "";
        }
    }

'''
text = text[:start] + reverse + text[end:]
p.write_text(text, encoding='utf-8')

# Update photo import message.
p = Path('app/src/main/java/cn/safetyledger/app/MainActivity.java')
text = p.read_text(encoding='utf-8')
text = text.replace('''            Ui.toast(this, "照片已保存；可读取的原始时间和 GPS 已写入水印");
''', '''            Ui.toast(this, "照片已保存；有原始拍摄时间或可解析地点时才添加文字水印");
''', 1)
p.write_text(text, encoding='utf-8')

# ---------------------------------------------------------------------------
# PDF: inspection photos first, then rectification photos; never print coordinates as location.
# ---------------------------------------------------------------------------
p = Path('app/src/main/java/cn/safetyledger/app/pdf/PdfExporter.java')
text = p.read_text(encoding='utf-8')
text = text.replace('import java.util.ArrayList;\n', 'import java.util.ArrayList;\nimport java.util.Comparator;\n', 1)
text = text.replace('''    private void drawPhotos(Canvas canvas, Inspection record, int start) {
        text(canvas, WIDTH / 2f, 31, formTitle(record) + " · 照片附件",
                20, Paint.Align.CENTER, true);
        text(canvas, MARGIN, 53, displayDate(record) + "  " + record.location,
                11, Paint.Align.LEFT, false);
        for (int i = 0; i < 4 && start + i < record.media.size(); i++) {
            Media media = record.media.get(start + i);
''', '''    private void drawPhotos(Canvas canvas, Inspection record, int start) {
        text(canvas, WIDTH / 2f, 31, formTitle(record) + " · 照片附件",
                20, Paint.Align.CENTER, true);
        text(canvas, MARGIN, 53, displayDate(record) + "  " + record.location,
                11, Paint.Align.LEFT, false);
        List<Media> ordered = orderedPhotos(record.media);
        for (int i = 0; i < 4 && start + i < ordered.size(); i++) {
            Media media = ordered.get(start + i);
''', 1)
text = text.replace('''            text(canvas, x + 5, y + height - 9, label + "  " + media.location,
                    9, Paint.Align.LEFT, false);
        }
    }

    private String formTitle''', '''            String place = readablePhotoPlace(media.location);
            text(canvas, x + 5, y + height - 9, label + (place.isBlank() ? "" : "  " + place),
                    9, Paint.Align.LEFT, false);
        }
    }

    private List<Media> orderedPhotos(List<Media> source) {
        List<Media> ordered = new ArrayList<>(source);
        ordered.sort(Comparator.comparingInt((Media media) -> photoPriority(media.category))
                .thenComparingLong(media -> media.capturedAt));
        return ordered;
    }

    private int photoPriority(String category) {
        if ("RECTIFICATION".equals(category)) return 1;
        if ("RECHECK".equals(category)) return 2;
        return 0; // SCENE/inspection photos always come first.
    }

    private String readablePhotoPlace(String value) {
        if (value == null) return "";
        String text = value.trim();
        if (text.isBlank()) return "";
        // Old test versions stored raw coordinates such as 北纬 39.x，东经 116.x.
        // Never print those numeric coordinates as a place name in the formal PDF.
        if (text.contains("北纬") || text.contains("南纬") || text.contains("东经") || text.contains("西经")) return "";
        if (text.matches(".*[-+]?\\d{2,3}\\.\\d{3,}.*[-+]?\\d{2,3}\\.\\d{3,}.*")) return "";
        return text;
    }

    private String formTitle''', 1)
p.write_text(text, encoding='utf-8')

print('v1.2.15 patch prepared')
