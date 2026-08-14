package cn.safetyledger.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import cn.safetyledger.app.backup.BackupService;
import cn.safetyledger.app.data.LedgerRepository;
import cn.safetyledger.app.security.PasswordHash;
import cn.safetyledger.app.security.SecretStore;
import cn.safetyledger.app.sync.CloudSyncScheduler;
import cn.safetyledger.app.sync.CloudSyncService;
import cn.safetyledger.app.sync.SyncProvider;
import cn.safetyledger.app.sync.WebDavClient;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public final class SettingsActivity extends Activity {
    private static final int EXPORT = 801;
    private static final int IMPORT = 802;
    private LedgerRepository repo;
    private BackupService.RestorePackage restore;
    private Spinner provider;
    private Spinner deviceRole;
    private EditText deviceName;
    private EditText endpoint;
    private EditText user;
    private EditText secret;
    private EditText token;
    private EditText space;
    private EditText encryption;
    private LinearLayout advancedAuthBox;
    private Button advancedAuthButton;
    private TextView syncStatus;
    private String savedServerPassword = "";
    private String savedToken = "";
    private String savedSpacePassword = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.setupWindow(this);
        repo = new LedgerRepository(this);
        render();
    }

    private void render() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.addView(Ui.appBar(this, "基础设置"));
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = Ui.column(this);
        content.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 26));
        content.addView(managementCard());
        content.addView(Ui.gap(this, 10));
        content.addView(backupCard());
        content.addView(Ui.gap(this, 10));
        content.addView(deviceCard());
        content.addView(Ui.gap(this, 10));
        content.addView(cloudCard());
        content.addView(Ui.gap(this, 10));
        content.addView(securityCard());
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        loadProvider();
    }

    private LinearLayout managementCard() {
        LinearLayout card = Ui.card(this);
        card.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        card.addView(Ui.sectionTitle(this, "1", "检查基础设置", "模板、检查类别和检查项目"));
        card.addView(Ui.gap(this, 5));
        card.addView(menuRow("检查模板管理", "新建、编辑、停用模板及调整检查项目", () ->
                Ui.start(this, TemplateActivity.class)));
        card.addView(Ui.divider(this));
        card.addView(menuRow("回收站", "恢复误删记录或使用密码永久删除", () ->
                Ui.start(this, TrashActivity.class)));
        return card;
    }

    private LinearLayout backupCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "2", "APP 数据备份与恢复", "类似手机系统备份，可离线复制到另一设备"));
        TextView note = Ui.text(this,
                "备份文件为 .safetydata，包含模板、记录、照片、签名和整改状态。无需另设密码，可直接复制到另一台手机或电脑后导入；文件仍带有 AES-256-GCM 完整性保护。PDF 不能作为数据备份导入。",
                13, false);
        note.setTextColor(Ui.MUTED);
        card.addView(note);
        Button backup = Ui.button(this, "备份到手机文件夹");
        Button restoreButton = Ui.secondaryButton(this, "从备份文件恢复");
        Button migration = Ui.compactButton(this, "查看设备迁移说明", false);
        backup.setOnClickListener(view -> chooseBackupDestination());
        restoreButton.setOnClickListener(view -> chooseBackupFile());
        migration.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("设备迁移")
                .setMessage("1. 旧设备点击“备份到手机文件夹”。\n2. 将 .safetydata 复制到新手机或电脑。\n3. 新设备安装同包名 APP，点击“从备份文件恢复”。\n4. 选择完整恢复后，模板、检查记录、照片、签名和整改状态会一起恢复。")
                .setPositiveButton("知道了", null).show());
        card.addView(backup);
        card.addView(Ui.gap(this, 7));
        card.addView(restoreButton);
        card.addView(Ui.gap(this, 7));
        card.addView(migration);
        return card;
    }

    private LinearLayout deviceCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "3", "多设备角色", "首台设备自动成为管理员，后加入设备默认为工作人员"));
        deviceName = Ui.input(this, "设备名称，例如：管理员手机");
        deviceName.setSingleLine(true);
        deviceName.setText(repo.setting("device_name", Build.MANUFACTURER + " " + Build.MODEL));
        deviceRole = spinner(new String[]{"管理员（管理全部资料）", "工作人员（现场检查填报）"});
        deviceRole.setSelection("FIELD".equals(repo.setting("device_role", "PRIMARY")) ? 1 : 0);
        deviceRole.setEnabled(false);
        card.addView(fieldLabel("设备名称"));
        card.addView(deviceName);
        card.addView(Ui.gap(this, 6));
        card.addView(fieldLabel("本机角色"));
        card.addView(deviceRole, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        TextView note = Ui.text(this,
                "首次创建同步空间的设备自动成为管理员；使用相同同步空间名称和密码加入的手机默认为工作人员。管理员可接收全部设备记录并管理已配对设备。Windows 客户端仍需后续交付。",
                13, false);
        note.setTextColor(Ui.MUTED);
        card.addView(note);
        Button save = Ui.compactButton(this, "保存设备名称", true);
        save.setOnClickListener(view -> {
            repo.putSetting("device_name", deviceName.getText().toString().trim());
            Ui.toast(this, "设备名称已保存；角色由云端配对结果自动识别");
        });
        card.addView(save);
        card.addView(Ui.gap(this, 6));
        Button manage = Ui.secondaryButton(this, "管理已配对设备 / 设置角色");
        manage.setOnClickListener(view -> manageDevices());
        card.addView(manage);
        return card;
    }

    private LinearLayout cloudCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "4", "云同步", "服务提供商可切换，失败不影响本地填报"));
        provider = spinner(new String[]{"WebDAV", "Cloudflare", "飞牛 NAS / WebDAV",
                "Google Drive", "OneDrive", "自定义 HTTP 服务器"});
        endpoint = Ui.input(this, "服务地址 / 账号授权地址");
        space = Ui.input(this, "同步空间名称");
        space.setText("safety-ledger");
        encryption = Ui.input(this, "同步密码（配对、加密和永久删除）");
        user = Ui.input(this, "WebDAV / NAS 用户名");
        secret = Ui.input(this, "WebDAV / NAS 登录密码");
        token = Ui.input(this, "Cloudflare 设备 Token / Bearer Token");
        maskPassword(secret);
        maskPassword(token);
        maskPassword(encryption);
        for (EditText input : new EditText[]{endpoint, user, secret, token, space, encryption}) {
            input.setSingleLine(true);
        }
        card.addView(provider, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        card.addView(Ui.gap(this, 5));
        card.addView(endpoint);
        card.addView(Ui.gap(this, 5));
        card.addView(space);
        card.addView(Ui.gap(this, 5));
        card.addView(encryption);
        card.addView(Ui.gap(this, 5));
        advancedAuthButton = Ui.compactButton(this, "高级服务器认证（WebDAV / 设备 Token）", false);
        advancedAuthButton.setOnClickListener(view ->
                setAdvancedAuthVisible(advancedAuthBox.getVisibility() != View.VISIBLE));
        card.addView(advancedAuthButton);
        advancedAuthBox = Ui.column(this);
        advancedAuthBox.setPadding(0, Ui.dp(this, 5), 0, 0);
        LinearLayout account = Ui.row(this);
        account.addView(user, Ui.weight(1));
        account.addView(Ui.horizontalGap(this, 5));
        account.addView(secret, Ui.weight(1));
        advancedAuthBox.addView(account);
        advancedAuthBox.addView(Ui.gap(this, 5));
        advancedAuthBox.addView(token);
        TextView advancedNote = Ui.text(this,
                "只有 WebDAV/NAS 服务器账号或 Cloudflare 提示“需要设备授权”时才填写。设备 Token 由云端生成，不等于同步密码。",
                12, false);
        advancedNote.setTextColor(Ui.MUTED);
        advancedAuthBox.addView(advancedNote);
        advancedAuthBox.setVisibility(View.GONE);
        card.addView(advancedAuthBox);
        TextView explanation = Ui.text(this,
                "日常只需填写同步空间名称和一个同步密码。多台设备必须完全一致；保存成功后密码立即清空并以圆点提示已保存，同时作为回收站永久删除密码。",
                12, false);
        explanation.setTextColor(Ui.MUTED);
        card.addView(explanation);
        syncStatus = Ui.text(this, "同步状态：未配置", 14, true);
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
        actions.addView(save, Ui.weight(1));
        actions.addView(Ui.horizontalGap(this, 5));
        actions.addView(now, Ui.weight(1));
        card.addView(actions);
        return card;
    }

    private LinearLayout securityCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "5", "安全与本地存储", null));
        Button password = Ui.secondaryButton(this, "未使用云同步：设置本机永久删除密码");
        password.setOnClickListener(view -> setDeletePassword());
        card.addView(password);
        TextView archive = Ui.text(this,
                "未整改完成的记录不会自动清理。超过六个月的自动归档与释放空间功能将在云端文件能够真实校验后启用，当前保持关闭以避免资料丢失。",
                13, false);
        archive.setTextColor(Ui.MUTED);
        card.addView(archive);
        return card;
    }

    private LinearLayout menuRow(String title, String subtitle, Runnable action) {
        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        TextView text = Ui.text(this, title + "\n" + subtitle, 15, true);
        TextView arrow = Ui.text(this, "›", 26, true);
        arrow.setTextColor(Ui.BLUE_DARK);
        row.addView(text, Ui.weight(1));
        row.addView(arrow);
        row.setOnClickListener(view -> action.run());
        return row;
    }

    private TextView fieldLabel(String value) {
        TextView label = Ui.text(this, value, 13, true);
        label.setTextColor(Ui.MUTED);
        return label;
    }

    private Spinner spinner(String[] values) {
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, values));
        spinner.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        spinner.setBackground(Ui.shape(this, Color.WHITE, Ui.LINE, 10));
        return spinner;
    }

    private void loadProvider() {
        try (Cursor cursor = repo.raw().rawQuery(
                "SELECT provider_type,endpoint,username,encrypted_secret,token_ciphertext,sync_space,encryption_secret FROM sync_providers WHERE enabled=1 LIMIT 1",
                null)) {
            if (!cursor.moveToFirst()) return;
            String type = cursor.getString(0);
            for (int i = 0; i < provider.getCount(); i++) {
                if (provider.getItemAtPosition(i).equals(type)) provider.setSelection(i);
            }
            endpoint.setText(cursor.getString(1));
            user.setText(cursor.getString(2));
            SecretStore store = new SecretStore();
            savedServerPassword = store.decrypt(cursor.getString(3));
            savedToken = store.decrypt(cursor.getString(4));
            savedSpacePassword = store.decrypt(cursor.getString(6));
            secret.setText("");
            token.setText("");
            encryption.setText("");
            secret.setHint(savedServerPassword.isBlank() ? "WebDAV / NAS 登录密码" : "••••••••（登录密码已保存）");
            token.setHint(savedToken.isBlank() ? "Cloudflare 设备 Token / Bearer Token" : "••••••••（设备 Token 已保存）");
            encryption.setHint(savedSpacePassword.isBlank()
                    ? "同步密码（至少 8 位）" : "••••••••（同步密码已保存）");
            if (!cursor.getString(2).isBlank() || !savedServerPassword.isBlank()
                    || !savedToken.isBlank()) setAdvancedAuthVisible(true);
            space.setText(cursor.getString(5));
            String error = repo.setting("last_sync_error", "");
            String last = repo.setting("last_sync_at", "");
            if (!error.isBlank()) syncStatus.setText("同步状态：上次失败 · " + error);
            else if (!last.isBlank()) {
                String time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(new Date(Long.parseLong(last)));
                syncStatus.setText("同步状态：已同步 · " + time);
            } else syncStatus.setText("同步状态：配置已启用，可点击立即同步");
        } catch (Exception error) {
            syncStatus.setText("同步状态：配置读取失败");
        }
    }

    private void chooseBackupDestination() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType("application/octet-stream")
                .putExtra(Intent.EXTRA_TITLE,
                        "安全检查台账-" + System.currentTimeMillis() + ".safetydata")
                .addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, EXPORT);
    }

    private void chooseBackupFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, IMPORT);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == EXPORT) exportData(data.getData());
        else if (request == IMPORT) importData(data.getData());
    }

    private void exportData(Uri uri) {
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            new BackupService(this).exportPortable(output);
            Ui.toast(this, "APP 数据已备份到所选文件夹，无需另设密码");
        } catch (Exception error) {
            Ui.toast(this, "备份失败：" + error.getMessage());
        }
    }

    private void importData(Uri uri) {
        BackupService service = new BackupService(this);
        try (InputStream probe = getContentResolver().openInputStream(uri)) {
            if (service.isPortable(probe)) {
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    showRestoreChoice(service.decryptAndValidatePortable(input));
                }
                return;
            }
        } catch (Exception error) {
            Ui.toast(this, "导入失败：" + readableError(error));
            return;
        }
        try (InputStream probe = getContentResolver().openInputStream(uri)) {
            if (service.isLegacyEncrypted(probe)) {
                askLegacyBackupPassword(uri);
            } else {
                Ui.toast(this, "该文件不是 APP 数据备份，PDF 不能导入");
            }
        } catch (Exception error) {
            Ui.toast(this, "导入失败：" + readableError(error));
        }
    }

    private void askLegacyBackupPassword(Uri uri) {
        EditText password = Ui.input(this, "旧版备份密码");
        maskPassword(password);
        new AlertDialog.Builder(this)
                .setTitle("兼容旧版加密备份")
                .setMessage("这是 1.2.3 或更早版本创建的密码备份，请输入创建该文件时设置的旧密码。新版本备份不再询问密码。")
                .setView(password)
                .setPositiveButton("验证并导入", (dialog, which) -> {
                    try (InputStream input = getContentResolver().openInputStream(uri)) {
                        BackupService service = new BackupService(this);
                        showRestoreChoice(service.decryptAndValidate(input,
                                password.getText().toString().toCharArray()));
                    } catch (Exception error) {
                        Ui.toast(this, "导入失败：" + readableError(error));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showRestoreChoice(BackupService.RestorePackage restorePackage) {
        restore = restorePackage;
        new AlertDialog.Builder(this)
                .setTitle("选择恢复方式")
                .setItems(new String[]{"合并恢复（保留本机较新数据与冲突副本）",
                        "完整恢复（替换本机业务数据）"}, (dialog, which) -> {
                    try {
                        BackupService service = new BackupService(this);
                        if (which == 0) {
                            int count = service.mergeRestore(restore);
                            restore = null;
                            Ui.toast(this, "合并恢复完成，处理 " + count + " 项；较新数据不会被静默覆盖");
                        } else {
                            service.fullRestore(restore);
                            restore = null;
                            Intent restart = getPackageManager().getLaunchIntentForPackage(getPackageName());
                            if (restart == null) restart = new Intent(this, LedgerActivity.class);
                            restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                            startActivity(restart);
                            finishAffinity();
                        }
                        } catch (Exception error) {
                            if (restore != null) restore.close();
                            restore = null;
                            Ui.toast(this, "恢复失败：" + readableError(error));
                        }
                })
                .setOnCancelListener(dialog -> {
                    if (restore != null) restore.close();
                    restore = null;
                })
                .show();
    }

    private void setDeletePassword() {
        EditText password = Ui.input(this, "新密码（至少 6 位）");
        maskPassword(password);
        new AlertDialog.Builder(this)
                .setTitle("本机永久删除密码")
                .setView(password)
                .setPositiveButton("保存", (dialog, which) -> {
                    char[] value = password.getText().toString().toCharArray();
                    if (value.length < 6) {
                        Ui.toast(this, "密码至少 6 位");
                        return;
                    }
                    try {
                        repo.putSetting("delete_password_hash", PasswordHash.create(value));
                        Ui.toast(this, "永久删除密码已设置");
                    } catch (Exception error) {
                        Ui.toast(this, "密码设置失败");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void testConnection(boolean saveOnSuccess) {
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
        if (spaceName.isBlank()) {
            Ui.toast(this, "请填写同步空间名称");
            return;
        }
        if (saveOnSuccess && spacePassword.length() < 8) {
            Ui.toast(this, "同步空间密码至少 8 位");
            return;
        }
        syncStatus.setText("同步状态：正在测试…");
        new Thread(() -> {
            SyncProvider.ConnectionResult result;
            if (type.contains("WebDAV") || "Cloudflare".equals(type)
                    || "自定义 HTTP 服务器".equals(type)) {
                result = new WebDavClient(url, username, password, tokenValue)
                        .testReadWrite(spaceName);
                if (!result.success() && "Cloudflare".equals(type)) {
                    String detail = result.message();
                    if (detail.contains("需要设备授权") || detail.contains("HTTP 401")) {
                        detail = "云端要求设备授权。请展开“高级服务器认证”，填写该 Cloudflare 服务生成的设备 Token 后重试。设备 Token 不等于同步密码；若云端没有 Token 管理入口，需要部署项目 cloudflare-worker 中的兼容网关。\n\n原始响应："
                                + detail;
                    } else {
                        detail = "Cloudflare 地址可访问，但未通过安全台账兼容网关的读写校验："
                                + detail;
                    }
                    result = new SyncProvider.ConnectionResult(false, detail);
                }
            } else {
                result = new SyncProvider.ConnectionResult(false,
                        type + " 的官方授权尚未接入；请选择 WebDAV、飞牛 WebDAV或兼容 Worker");
            }
            SyncProvider.ConnectionResult checked = result;
            runOnUiThread(() -> {
                if (checked.success()) {
                    boolean saved = true;
                    if (saveOnSuccess) {
                        saved = saveProvider(type, url, username, password, tokenValue,
                                spaceName, spacePassword);
                    }
                    syncStatus.setText("同步状态：连接成功" + (saveOnSuccess && saved ? "，配置已启用" : ""));
                    new AlertDialog.Builder(this).setTitle("连接成功")
                            .setMessage(checked.message() + (saveOnSuccess && saved
                                    ? "\n\n配置已加密保存，密码输入框已经隐藏。现在将开始首次同步。" : ""))
                            .setPositiveButton("确定", null).show();
                    if (saveOnSuccess && saved) syncNow();
                } else {
                    syncStatus.setText("同步状态：失败 · " + checked.message());
                    syncNotification(checked.message());
                    new AlertDialog.Builder(this).setTitle("连接失败")
                            .setMessage(checked.message()).setPositiveButton("确定", null).show();
                }
            });
        }).start();
    }

    private boolean saveProvider(String type, String url, String username, String password,
                              String tokenValue, String spaceName, String spacePassword) {
        try {
            SecretStore store = new SecretStore();
            SQLiteDatabase database = repo.raw();
            long now = System.currentTimeMillis();
            database.execSQL("UPDATE sync_providers SET enabled=0");
            database.execSQL("INSERT OR REPLACE INTO sync_providers(id,provider_type,display_name,endpoint,username,encrypted_secret,token_ciphertext,sync_space,encryption_secret,enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    new Object[]{"active-provider", type, type, url, username,
                            store.encrypt(password), store.encrypt(tokenValue),
                            spaceName, store.encrypt(spacePassword), 1, now, now});
            if (repo.setting("device_id", "").isBlank()) {
                repo.putSetting("device_id", UUID.randomUUID().toString());
            }
            repo.putSetting("delete_password_hash",
                    PasswordHash.create(spacePassword.toCharArray()));
            savedServerPassword = password;
            savedToken = tokenValue;
            savedSpacePassword = spacePassword;
            secret.setText(""); token.setText(""); encryption.setText("");
            secret.setHint(password.isBlank() ? "WebDAV / NAS 登录密码"
                    : "••••••••（登录密码已保存）");
            token.setHint(tokenValue.isBlank() ? "Cloudflare 设备 Token / Bearer Token"
                    : "••••••••（设备 Token 已保存）");
            encryption.setHint("••••••••（同步密码已保存）");
            CloudSyncScheduler.schedule(this);
            return true;
        } catch (Exception error) {
            syncStatus.setText("同步状态：配置加密保存失败");
            Ui.toast(this, "配置保存失败：" + error.getMessage());
            return false;
        }
    }

    private void syncNow() {
        syncStatus.setText("同步状态：正在下载、合并并上传…");
        new Thread(() -> {
            try {
                CloudSyncService.Result result = new CloudSyncService(this).syncNow();
                runOnUiThread(() -> {
                    String role = "FIELD".equals(result.role()) ? "工作人员" : "管理员";
                    syncStatus.setText("同步状态：已同步 · 本机角色 " + role);
                    Ui.toast(this, "同步完成：接收 " + result.peerDevices()
                            + " 台设备，合并 " + result.changedRows() + " 项数据");
                    deviceRole.setSelection("FIELD".equals(result.role()) ? 1 : 0);
                });
            } catch (Exception error) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName()
                        : error.getMessage();
                repo.putSetting("last_sync_error", message);
                runOnUiThread(() -> {
                    syncStatus.setText("同步状态：失败 · " + message);
                    syncNotification(message);
                    new AlertDialog.Builder(this).setTitle("同步失败")
                            .setMessage(message).setPositiveButton("确定", null).show();
                });
            }
        }, "manual-cloud-sync").start();
    }

    private void manageDevices() {
        List<String> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<String> roles = new ArrayList<>();
        try (Cursor cursor = repo.raw().rawQuery(
                "SELECT device_id,display_name,role,last_seen_at FROM sync_devices ORDER BY CASE role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END,display_name",
                null)) {
            while (cursor.moveToNext()) {
                ids.add(cursor.getString(0));
                roles.add(cursor.getString(2));
                labels.add(cursor.getString(1) + "\n" + roleName(cursor.getString(2)));
            }
        }
        if (ids.isEmpty()) {
            Ui.toast(this, "尚无已配对设备，请先完成一次云同步");
            return;
        }
        String localId = repo.setting("device_id", "");
        String localRole = "FIELD";
        for (int i = 0; i < ids.size(); i++) if (ids.get(i).equals(localId)) localRole = roles.get(i);
        boolean canManage = "OWNER".equals(localRole) || "ADMIN".equals(localRole);
        boolean finalCanManage = canManage;
        new AlertDialog.Builder(this)
                .setTitle("已配对设备")
                .setMessage(canManage ? "点击设备可设置为管理员或工作人员。角色修改会在下次同步后传到其他手机。"
                        : "本机是工作人员，只能查看设备列表。")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (!finalCanManage) {
                        Ui.toast(this, "只有管理员可以修改设备角色");
                        return;
                    }
                    if ("OWNER".equals(roles.get(which))) {
                        Ui.toast(this, "首位管理员不能降级");
                        return;
                    }
                    chooseDeviceRole(ids.get(which), labels.get(which));
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void chooseDeviceRole(String deviceId, String label) {
        new AlertDialog.Builder(this)
                .setTitle(label.replace("\n", " · "))
                .setItems(new String[]{"设为管理员", "设为工作人员"}, (dialog, which) -> {
                    String role = which == 0 ? "ADMIN" : "FIELD";
                    repo.raw().execSQL("UPDATE sync_devices SET role=?,updated_at=? WHERE device_id=?",
                            new Object[]{role, System.currentTimeMillis(), deviceId});
                    Ui.toast(this, "设备已设为" + roleName(role) + "，正在同步角色变更");
                    syncNow();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String roleName(String role) {
        return "FIELD".equals(role) ? "工作人员" : "管理员";
    }

    private void setAdvancedAuthVisible(boolean visible) {
        if (advancedAuthBox == null || advancedAuthButton == null) return;
        advancedAuthBox.setVisibility(visible ? View.VISIBLE : View.GONE);
        advancedAuthButton.setText(visible ? "收起高级服务器认证" : "高级服务器认证（WebDAV / 设备 Token）");
    }

    private void maskPassword(EditText input) {
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setTransformationMethod(PasswordTransformationMethod.getInstance());
    }

    private String readableError(Throwable error) {
        Throwable current = error;
        String message = null;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return message == null ? error.getClass().getSimpleName() : message;
    }

    private void syncNotification(String message) {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 991);
        }
        Notification notification = new Notification.Builder(this, SafetyLedgerApp.SYNC_CHANNEL)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("安全检查台账同步失败")
                .setContentText(message)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(1001, notification);
    }
}
