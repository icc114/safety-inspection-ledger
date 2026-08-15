from pathlib import Path


def replace(path, old, new, count=1):
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:120]!r}")
    p.write_text(text.replace(old, new, count), encoding="utf-8")


def replace_after(path, marker, old, new, count=1):
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    pos = text.find(marker)
    if pos < 0:
        raise SystemExit(f"marker not found in {path}: {marker!r}")
    before, after = text[:pos], text[pos:]
    if old not in after:
        raise SystemExit(f"pattern after marker not found in {path}: {old[:120]!r}")
    p.write_text(before + after.replace(old, new, count), encoding="utf-8")


# Android's XML parser differs by vendor. Reject DTD/entity declarations before
# parsing, then apply parser hardening features only when the implementation
# supports them. This prevents a valid WebDAV multistatus from failing solely
# because disallow-doctype-decl is not implemented.
replace(
    "app/src/main/java/cn/safetyledger/app/sync/WebDavClient.java",
    '''        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(response.body));''',
    '''        String xmlText = new String(response.body, StandardCharsets.UTF_8);
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
                .parse(new java.io.ByteArrayInputStream(response.body));''')

replace(
    "app/src/main/java/cn/safetyledger/app/sync/WebDavClient.java",
    '''    private String readable(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static void copy''',
    '''    private static void setXmlFeatureSafely(DocumentBuilderFactory factory,
                                            String feature, boolean enabled) {
        try {
            factory.setFeature(feature, enabled);
        } catch (javax.xml.parsers.ParserConfigurationException
                 | RuntimeException | AbstractMethodError ignored) {
            // Android vendors ship different XML parser implementations. DTD/ENTITY
            // text is rejected before parsing, so unsupported hardening flags must not
            // make otherwise valid WebDAV XML impossible to read.
        }
    }

    private String readable(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static void copy''')


# A normal sync no longer performs a complete test probe (PUT/GET/DELETE) first.
# It only prepares the DAV directories, then performs the real list/merge/upload.
replace(
    "app/src/main/java/cn/safetyledger/app/sync/CloudSyncService.java",
    '''        SyncProvider.ConnectionResult probe = client.testReadWrite(config.space);
        if (!probe.success()) {
            String message = probe.message();
            if ("Cloudflare".equals(config.type)) {
                if (message.contains("需要设备授权") || message.contains("HTTP 401")) {
                    message = "Cloudflare 自动配对被拒绝：当前地址不是本版兼容网关，或仍使用旧私有授权协议。请重新部署仓库 cloudflare-worker；若云端提供设备 Token，也可在高级认证中填写。原始响应："
                            + message;
                } else if (message.contains("HTTP 404") || message.contains("HTTP 405")
                        || message.contains("HTTP 500") || message.contains("HTTP 503")
                        || message.contains("不是可读的 WebDAV")) {
                    message = "Cloudflare 服务与当前 APK 协议不匹配。1.2.6 需要仓库 cloudflare-worker 的 WebDAV 兼容 Worker，并绑定私有 R2 为 SAFETY_LEDGER_BUCKET；旧版 D1/env.DB Worker 不能直接使用。原始响应："
                            + message;
                }
            }
            throw new java.io.IOException(message);
        }

        String deviceId''',
    '''        try {
            client.prepare(config.space);
        } catch (Exception error) {
            String message = error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage();
            if ("Cloudflare".equals(config.type)) {
                if (message.contains("需要设备授权") || message.contains("HTTP 401")) {
                    message = "Cloudflare 自动配对被拒绝，请确认同步空间名称和密码一致。原始响应：" + message;
                } else if (message.contains("HTTP 404") || message.contains("HTTP 405")
                        || message.contains("HTTP 500") || message.contains("HTTP 503")
                        || message.contains("不是可读的 WebDAV")) {
                    message = "Cloudflare R2 Worker 未通过 WebDAV 目录准备：" + message;
                }
            }
            throw new java.io.IOException(message, error);
        }

        String deviceId''')


settings = "app/src/main/java/cn/safetyledger/app/SettingsActivity.java"

replace(settings,
        '''    private Button advancedAuthButton;
    private TextView syncStatus;''',
        '''    private Button advancedAuthButton;
    private Button syncSaveButton;
    private TextView syncEnabledStatus;
    private TextView syncStatus;''')

# Only expose real working architectures. Cloudflare stays first so a UI fallback
# cannot silently change the provider to WebDAV.
replace(settings,
        '''        provider = spinner(new String[]{"WebDAV", "Cloudflare", "飞牛 NAS / WebDAV",
                "Google Drive", "OneDrive", "自定义 HTTP 服务器"});
        endpoint = Ui.input(this, "服务地址 / 账号授权地址");''',
        '''        provider = spinner(new String[]{"Cloudflare", "WebDAV / NAS"});
        endpoint = Ui.input(this, "Cloudflare Worker / WebDAV 服务地址");''')

replace(settings,
        '''        syncStatus = Ui.text(this, "同步状态：未配置", 14, true);
        card.addView(syncStatus);
        LinearLayout actions = Ui.row(this);
        Button test = Ui.compactButton(this, "测试连接", false);
        Button save = Ui.compactButton(this, "保存并启用", true);
        Button now = Ui.compactButton(this, "立即同步", false);
        test.setOnClickListener(view -> testConnection(false));
        save.setOnClickListener(view -> testConnection(true));
        now.setOnClickListener(view -> syncNow());
        actions.addView(test, Ui.weight(1));
        actions.addView(Ui.horizontalGap(this, 5));
        actions.addView(save, Ui.weight(1));''',
        '''        syncEnabledStatus = Ui.text(this, "云同步：未启用", 14, true);
        card.addView(syncEnabledStatus);
        syncStatus = Ui.text(this, "同步状态：未配置", 14, true);
        card.addView(syncStatus);
        LinearLayout actions = Ui.row(this);
        Button test = Ui.compactButton(this, "测试连接", false);
        syncSaveButton = Ui.compactButton(this, "保存并启用", true);
        Button now = Ui.compactButton(this, "立即同步", false);
        test.setOnClickListener(view -> testConnection(false));
        syncSaveButton.setOnClickListener(view -> saveAndEnable());
        now.setOnClickListener(view -> syncNow());
        actions.addView(test, Ui.weight(1));
        actions.addView(Ui.horizontalGap(this, 5));
        actions.addView(syncSaveButton, Ui.weight(1));''')

replace(settings,
        '''                "SELECT provider_type,endpoint,username,encrypted_secret,token_ciphertext,sync_space,encryption_secret FROM sync_providers WHERE enabled=1 LIMIT 1",
                null)) {''',
        '''                "SELECT provider_type,endpoint,username,encrypted_secret,token_ciphertext,sync_space,encryption_secret FROM sync_providers WHERE enabled=1 ORDER BY updated_at DESC LIMIT 1",
                null)) {''')

replace(settings,
        '''            if (!cursor.moveToFirst()) return;
            String type = cursor.getString(0);
            for (int i = 0; i < provider.getCount(); i++) {
                if (provider.getItemAtPosition(i).equals(type)) provider.setSelection(i);
            }
            endpoint.setText(cursor.getString(1));''',
        '''            if (!cursor.moveToFirst()) {
                syncEnabledStatus.setText("云同步：未启用");
                syncStatus.setText("同步状态：未配置");
                return;
            }
            String type = cursor.getString(0);
            String displayType = "Cloudflare".equals(type) ? "Cloudflare" : "WebDAV / NAS";
            setProviderSelection(displayType);
            syncEnabledStatus.setText("云同步：已启用 · " + displayType);
            syncEnabledStatus.setTextColor(Color.rgb(22, 128, 57));
            if (syncSaveButton != null) syncSaveButton.setText("已启用 · 保存修改");
            endpoint.setText(cursor.getString(1));''')

# Saving is now separate from connection testing. It saves in place, visibly marks
# the configuration enabled, and starts the real sync without navigating away.
replace(settings,
        '''    private void testConnection(boolean saveOnSuccess) {''',
        '''    private void saveAndEnable() {
        String type = (String) provider.getSelectedItem();
        String url = endpoint.getText().toString().trim();
        String username = user.getText().toString().trim();
        String password = secret.getText().toString().isBlank()
                ? savedServerPassword : secret.getText().toString();
        String tokenValue = token.getText().toString().isBlank()
                ? savedToken : token.getText().toString();
        String spaceName = space.getText().toString().trim();
        String spacePassword = encryption.getText().toString().isBlank()
                ? savedSpacePassword : encryption.getText().toString();

        if (url.isBlank()) {
            Ui.toast(this, "请填写服务地址");
            return;
        }
        if (spaceName.isBlank()) {
            Ui.toast(this, "请填写同步空间名称");
            return;
        }
        if (spacePassword.length() < 8) {
            Ui.toast(this, "同步空间密码至少 8 位");
            return;
        }
        if (url.contains("workers.dev") && !"Cloudflare".equals(type)) {
            type = "Cloudflare";
            setProviderSelection(type);
            Ui.toast(this, "已根据 workers.dev 地址自动切换为 Cloudflare");
        }

        if (saveProvider(type, url, username, password, tokenValue, spaceName, spacePassword)) {
            setProviderSelection(type);
            syncEnabledStatus.setText("云同步：已启用 · " + type);
            syncEnabledStatus.setTextColor(Color.rgb(22, 128, 57));
            syncSaveButton.setText("已启用 · 保存修改");
            syncStatus.setText("同步状态：配置已保存，正在首次同步…");
            Ui.toast(this, "云同步已启用，正在同步");
            syncNow();
        }
    }

    private void testConnection(boolean saveOnSuccess) {''')

# workers.dev means Cloudflare even if the user accidentally selected WebDAV/NAS.
replace_after(settings,
              '''    private void testConnection(boolean saveOnSuccess) {''',
              '''        String spacePassword = encryption.getText().toString().isBlank()
                ? savedSpacePassword : encryption.getText().toString();
        if (spaceName.isBlank()) {''',
              '''        String spacePassword = encryption.getText().toString().isBlank()
                ? savedSpacePassword : encryption.getText().toString();
        if (url.contains("workers.dev") && !"Cloudflare".equals(type)) {
            type = "Cloudflare";
            setProviderSelection(type);
            Ui.toast(this, "已根据 workers.dev 地址自动切换为 Cloudflare");
        }
        if (spaceName.isBlank()) {''')

replace(settings,
        '''            encryption.setHint("••••••••（同步密码已保存）");
            CloudSyncScheduler.schedule(this);
            return true;''',
        '''            encryption.setHint("••••••••（同步密码已保存）");
            repo.putSetting("last_sync_error", "");
            syncEnabledStatus.setText("云同步：已启用 · " + type);
            syncEnabledStatus.setTextColor(Color.rgb(22, 128, 57));
            if (syncSaveButton != null) syncSaveButton.setText("已启用 · 保存修改");
            CloudSyncScheduler.schedule(this);
            return true;''')

replace(settings,
        '''                    String role = "FIELD".equals(result.role()) ? "工作人员" : "管理员";
                    syncStatus.setText("同步状态：已同步 · 本机角色 " + role);
                    Ui.toast(this, "同步完成：接收 " + result.peerDevices()''',
        '''                    String role = "FIELD".equals(result.role()) ? "工作人员" : "管理员";
                    String type = (String) provider.getSelectedItem();
                    syncEnabledStatus.setText("云同步：已启用 · " + type);
                    syncEnabledStatus.setTextColor(Color.rgb(22, 128, 57));
                    if (syncSaveButton != null) syncSaveButton.setText("已启用 · 保存修改");
                    String time = DateFormat.getTimeInstance(DateFormat.SHORT)
                            .format(new Date(result.completedAt()));
                    syncStatus.setText("同步状态：成功 · " + time + " · 本机角色 " + role);
                    Ui.toast(this, "同步完成：接收 " + result.peerDevices()''')

replace(settings,
        '''    private String roleName(String role) {
        return "FIELD".equals(role) ? "工作人员" : "管理员";
    }

    private void setAdvancedAuthVisible''',
        '''    private String roleName(String role) {
        return "FIELD".equals(role) ? "工作人员" : "管理员";
    }

    private void setProviderSelection(String type) {
        if (provider == null || type == null) return;
        String wanted = "Cloudflare".equals(type) ? "Cloudflare" : "WebDAV / NAS";
        for (int i = 0; i < provider.getCount(); i++) {
            if (wanted.equals(provider.getItemAtPosition(i))) {
                provider.setSelection(i, false);
                return;
            }
        }
    }

    private void setAdvancedAuthVisible''')

replace("app/build.gradle",
        "versionCode 10\n        versionName '1.2.7'",
        "versionCode 11\n        versionName '1.2.8'")

replace(".github/workflows/android-build.yml",
        "安全检查台账-1.2.7.apk", "安全检查台账-1.2.8.apk", count=2)
replace(".github/workflows/android-build.yml",
        "name: 安全检查台账-1.2.7", "name: 安全检查台账-1.2.8")

# The temporary patch workflow is not part of the release branch.
workflow = Path(".github/workflows/patch-v128.yml")
if workflow.exists():
    workflow.unlink()
