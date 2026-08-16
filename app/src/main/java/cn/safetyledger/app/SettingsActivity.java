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
import android.widget.Switch;
import android.widget.TextView;

import cn.safetyledger.app.backup.BackupService;
import cn.safetyledger.app.data.LedgerRepository;
import cn.safetyledger.app.security.SecretStore;
import cn.safetyledger.app.sync.CloudSyncScheduler;
import cn.safetyledger.app.sync.CloudSyncService;
import cn.safetyledger.app.sync.SyncProvider;
import cn.safetyledger.app.sync.WebDavClient;
import cn.safetyledger.app.sync.SyncErrorFormatter;
import cn.safetyledger.app.sync.SyncLog;

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
    private Button syncSaveButton;
    private TextView syncEnabledStatus;
    private TextView syncStatus;
    private TextView deviceSyncStatus;
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
        card.addView(menuRow("检查模板管理", "新建、编辑、停用模板及调整检查项目", () -> {
            if ("FIELD".equals(repo.setting("device_role", "PRIMARY"))) {
                Ui.toast(this, "工作人员设备只能使用已同步模板，模板维护请由管理员设备完成");
            } else {
                Ui.start(this, TemplateActivity.class);
            }
        }));
        card.addView(Ui.divider(this));
        card.addView(menuRow("回收站", "本机恢复、云端30天回收站和管理员恢复", () ->
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
        Button backup = Ui.button(this, "导出数据");
        Button restoreButton = Ui.secondaryButton(this, "导入数据");
        Button migration = Ui.compactButton(this, "设备迁移", false);
        backup.setOnClickListener(view -> chooseBackupDestination());
        restoreButton.setOnClickListener(view -> chooseBackupFile());
        migration.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("设备迁移")
                .setItems(new String[]{"从本机导出迁移数据", "导入另一设备的数据", "查看迁移说明"},
                        (dialog, which) -> {
                            if (which == 0) chooseBackupDestination();
                            else if (which == 1) chooseBackupFile();
                            else new AlertDialog.Builder(this)
                                    .setTitle("离线设备迁移")
                                    .setMessage("1. 旧设备点击“导出数据”并选择手机文件夹。\n2. 将 .safetydata 文件复制到新手机或电脑。\n3. 新设备安装本 APP，点击“导入数据”。\n4. 选择合并恢复或完整恢复。\n\n同一数据包包含数据库、模板、记录、照片、签名和整改状态；仓库 tools/SafetyDataTool.java 可在 Windows、macOS 和 Linux 上校验、查看与解包。")
                                    .setPositiveButton("知道了", null).show();
                        })
                .setNegativeButton("取消", null).show());
        card.addView(backup);
        card.addView(Ui.gap(this, 7));
        card.addView(restoreButton);
        card.addView(Ui.gap(this, 7));
        card.addView(migration);
        return card;
    }

    private LinearLayout deviceCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "3", "设备管理", "设备信息同步与检查内容同步相互独立"));
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
                "设备管理与检查内容已经分开：这里只同步设备名称、角色、最后在线时间和登出状态，不上传或下载检查记录、照片、签名。首台设备为管理员，后加入设备默认为工作人员。",
                13, false);
        note.setTextColor(Ui.MUTED);
        card.addView(note);
        Button save = Ui.compactButton(this, "保存设备名称", true);
        save.setOnClickListener(view -> {
            repo.putSetting("device_name", deviceName.getText().toString().trim());
            Ui.toast(this, "设备名称已保存；进入“管理已配对设备”时会单独同步设备信息");
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
        Button manage = Ui.secondaryButton(this, "管理已配对设备");
        manage.setOnClickListener(view -> syncDeviceInfo(true));
        card.addView(manage);
        return card;
    }

    private LinearLayout cloudCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "4", "检查内容同步", "仅同步模板、检查记录、照片、签名和整改内容"));
        provider = spinner(new String[]{"Cloudflare", "WebDAV / NAS"});
        endpoint = Ui.input(this, "Cloudflare Worker / WebDAV 服务地址");
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
        TextView syncStrategy = Ui.text(this,
                "检查内容与设备管理完全分开。本机检查记录、照片、签名等有变更后约 2–5 分钟合并后台同步；无本地变更时约每 2 小时检查一次其他设备的检查内容。设备角色变化不会触发整包照片同步。",
                12, false);
        syncStrategy.setTextColor(Ui.MUTED);
        card.addView(syncStrategy);
        syncEnabledStatus = Ui.text(this, "云同步：未启用", 14, true);
        card.addView(syncEnabledStatus);
        syncStatus = Ui.text(this, "检查内容：未配置", 14, true);
        card.addView(syncStatus);
        LinearLayout actions = Ui.row(this);
        Button test = Ui.compactButton(this, "测试连接", false);
        syncSaveButton = Ui.compactButton(this, "保存并启用", true);
        Button now = Ui.compactButton(this, "同步检查内容", false);
        test.setOnClickListener(view -> testConnection(false));
        syncSaveButton.setOnClickListener(view -> saveAndEnable());
        now.setOnClickListener(view -> syncNow());
        actions.addView(test, Ui.weight(1));
        actions.addView(Ui.horizontalGap(this, 5));
        actions.addView(syncSaveButton, Ui.weight(1));
        actions.addView(Ui.horizontalGap(this, 5));
        actions.addView(now, Ui.weight(1));
        card.addView(actions);
        card.addView(Ui.gap(this, 7));
        Button logs = Ui.secondaryButton(this, "查看 / 导出同步日志");
        logs.setOnClickListener(view -> Ui.start(this, SyncLogActivity.class));
        card.addView(logs);
        card.addView(Ui.gap(this, 7));
        Button resetCloud = Ui.dangerButton(this, "清空云端旧测试设备 / 重新建立同步空间");
        resetCloud.setOnClickListener(view -> confirmResetCloudSpace());
        card.addView(resetCloud);
        TextView resetNote = Ui.text(this,
                "仅在准备废弃旧测试设备时使用：删除当前同步空间里的设备快照，但不会删除本机检查记录、照片、签名或模板。重建后本机成为首位管理员，其他正式设备再次同步后会重新加入。",
                12, false);
        resetNote.setTextColor(Ui.MUTED);
        card.addView(resetNote);
        return card;
    }

    private LinearLayout securityCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "5", "安全与本地存储", null));
        TextView deletion = Ui.text(this,
                "永久删除不再设置额外的本机密码。启用云同步后，从回收站永久删除时统一验证“云同步空间密码”；云端回收站保留 30 天，管理员可在期限内恢复。",
                13, false);
        deletion.setTextColor(Ui.MUTED); card.addView(deletion); card.addView(Ui.gap(this,8));
        Switch archive = new Switch(this);
        archive.setText("超过 6 个月自动归档（仅清理未处理原图副本）");
        archive.setTextSize(14); archive.setChecked("1".equals(repo.setting("auto_archive_enabled","0")));
        archive.setOnCheckedChangeListener((button,checked)->{
            repo.putSetting("auto_archive_enabled",checked?"1":"0");
            Ui.toast(this,checked?"自动归档已开启":"自动归档已关闭");
        });
        card.addView(archive);
        TextView note = Ui.text(this,
                "开启后，仅在一次云同步成功之后处理：对超过 6 个月且已完成/已整改的记录，删除手机中重复保存的“未处理原始照片副本”；检查记录、水印照片、整改/复查照片和签名全部保留，不会自动删除检查记录。",
                12, false);
        note.setTextColor(Ui.MUTED);card.addView(note);
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
                "SELECT provider_type,endpoint,username,encrypted_secret,token_ciphertext,sync_space,encryption_secret,enabled FROM sync_providers ORDER BY enabled DESC,updated_at DESC LIMIT 1",
                null)) {
            if (!cursor.moveToFirst()) {
                syncEnabledStatus.setText("云同步：未启用");
                syncStatus.setText("同步状态：未配置");
                return;
            }
            String type = cursor.getString(0);
            String displayType = "Cloudflare".equals(type) ? "Cloudflare" : "WebDAV / NAS";
            boolean enabled = cursor.getInt(7) == 1;
            setProviderSelection(displayType);
            syncEnabledStatus.setText(enabled ? "云同步：已启用 · " + displayType
                    : "云同步：未启用 · 已保留上次配置");
            syncEnabledStatus.setTextColor(enabled ? Color.rgb(22, 128, 57) : Ui.MUTED);
            if (syncSaveButton != null) syncSaveButton.setText(enabled ? "已启用 · 保存修改" : "保存并启用");
            endpoint.setText(cursor.getString(1));
            user.setText(cursor.getString(2));
            SecretStore store = new SecretStore();
            try {
                savedServerPassword = store.decrypt(cursor.getString(3));
                savedToken = store.decrypt(cursor.getString(4));
                savedSpacePassword = store.decrypt(cursor.getString(6));
            } catch (SecretStore.ResetRequiredException invalidated) {
                repo.raw().execSQL("UPDATE sync_providers SET encrypted_secret='',token_ciphertext='',encryption_secret='',enabled=0 WHERE id='active-provider'");
                CloudSyncScheduler.cancel(this);
                savedServerPassword = "";
                savedToken = "";
                savedSpacePassword = "";
                syncEnabledStatus.setText("云同步：未启用 · 本机安全密钥已重置");
                syncEnabledStatus.setTextColor(Ui.DANGER);
                syncStatus.setText("同步状态：请重新输入同步密码，然后点击保存并启用");
                if (syncSaveButton != null) syncSaveButton.setText("保存并启用");
                secret.setText("");
                token.setText("");
                encryption.setText("");
                secret.setHint("WebDAV / NAS 登录密码");
                token.setHint("Cloudflare 设备 Token / Bearer Token");
                encryption.setHint("同步密码（至少 8 位）");
                space.setText(cursor.getString(5));
                repo.putSetting("last_sync_error", "");
                new AlertDialog.Builder(this)
                        .setTitle("本机安全密钥已重置")
                        .setMessage("手机系统使旧的本机加密密钥失效。APP 已自动清理无法解密的云端凭据；检查记录、照片、签名和模板均未删除。服务地址和同步空间名称已保留，请重新输入同步密码后点击“保存并启用”。")
                        .setPositiveButton("知道了", null).show();
                return;
            }
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
        AlertDialog busy = busyDialog("正在导出数据库、照片和签名…");
        new Thread(() -> {
            String failure = null;
            try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new java.io.IOException("无法写入所选文件");
                new BackupService(this).exportPortable(output);
            } catch (Exception error) {
                failure = readableError(error);
            }
            String finalFailure = failure;
            runOnUiThread(() -> {
                busy.dismiss();
                Ui.toast(this, finalFailure == null
                        ? "数据已导出，可复制到另一手机或电脑"
                        : "导出失败：" + finalFailure);
            });
        }, "safetydata-export").start();
    }

    private void importData(Uri uri) {
        AlertDialog busy = busyDialog("正在校验数据包和全部文件…");
        new Thread(() -> {
            BackupService.RestorePackage restorePackage = null;
            boolean legacy = false;
            String failure = null;
            try {
                BackupService service = new BackupService(this);
                boolean portable;
                try (InputStream probe = getContentResolver().openInputStream(uri)) {
                    if (probe == null) throw new java.io.IOException("无法读取所选文件");
                    portable = service.isPortable(probe);
                }
                if (portable) {
                    try (InputStream input = getContentResolver().openInputStream(uri)) {
                        if (input == null) throw new java.io.IOException("无法读取所选文件");
                        restorePackage = service.decryptAndValidatePortable(input);
                    }
                } else {
                    try (InputStream probe = getContentResolver().openInputStream(uri)) {
                        if (probe == null) throw new java.io.IOException("无法读取所选文件");
                        legacy = service.isLegacyEncrypted(probe);
                    }
                    if (!legacy) failure = "该文件不是 APP 数据备份，PDF 不能导入";
                }
            } catch (Exception error) {
                failure = readableError(error);
            }
            BackupService.RestorePackage finalPackage = restorePackage;
            boolean finalLegacy = legacy;
            String finalFailure = failure;
            runOnUiThread(() -> {
                busy.dismiss();
                if (finalPackage != null) showRestoreChoice(finalPackage);
                else if (finalLegacy) askLegacyBackupPassword(uri);
                else Ui.toast(this, "导入失败：" + finalFailure);
            });
        }, "safetydata-import-check").start();
    }

    private void askLegacyBackupPassword(Uri uri) {
        EditText password = Ui.input(this, "旧版备份密码");
        maskPassword(password);
        new AlertDialog.Builder(this)
                .setTitle("兼容旧版加密备份")
                .setMessage("这是 1.2.3 或更早版本创建的密码备份，请输入创建该文件时设置的旧密码。新版本备份不再询问密码。")
                .setView(password)
                .setPositiveButton("验证并导入", (dialog, which) -> {
                    char[] value = password.getText().toString().toCharArray();
                    AlertDialog busy = busyDialog("正在验证旧版备份密码…");
                    new Thread(() -> {
                        BackupService.RestorePackage restorePackage = null;
                        String failure = null;
                        try (InputStream input = getContentResolver().openInputStream(uri)) {
                            if (input == null) throw new java.io.IOException("无法读取所选文件");
                            restorePackage = new BackupService(this)
                                    .decryptAndValidate(input, value);
                        } catch (Exception error) {
                            failure = readableError(error);
                        }
                        BackupService.RestorePackage finalPackage = restorePackage;
                        String finalFailure = failure;
                        runOnUiThread(() -> {
                            busy.dismiss();
                            if (finalPackage != null) showRestoreChoice(finalPackage);
                            else Ui.toast(this, "导入失败：" + finalFailure);
                        });
                    }, "safetydata-legacy-import").start();
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
                    BackupService.RestorePackage selected = restore;
                    restore = null;
                    AlertDialog busy = busyDialog(which == 0
                            ? "正在合并记录和照片…" : "正在完整恢复 APP 数据…");
                    new Thread(() -> {
                        int count = 0;
                        String failure = null;
                        try {
                            BackupService service = new BackupService(this);
                            if (which == 0) count = service.mergeRestore(selected);
                            else service.fullRestore(selected);
                        } catch (Exception error) {
                            selected.close();
                            failure = readableError(error);
                        }
                        int finalCount = count;
                        String finalFailure = failure;
                        runOnUiThread(() -> {
                            busy.dismiss();
                            if (finalFailure != null) {
                                Ui.toast(this, "恢复失败：" + finalFailure);
                            } else if (which == 0) {
                                Ui.toast(this, "合并恢复完成，处理 " + finalCount
                                        + " 项；较新数据不会被静默覆盖");
                            } else {
                                Intent restart = getPackageManager()
                                        .getLaunchIntentForPackage(getPackageName());
                                if (restart == null) restart = new Intent(this, LedgerActivity.class);
                                restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(restart);
                                finishAffinity();
                            }
                        });
                    }, "safetydata-restore").start();
                })
                .setOnCancelListener(dialog -> {
                    if (restore != null) restore.close();
                    restore = null;
                })
                .show();
    }

    private void saveAndEnable() {
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
            syncStatus.setText("检查内容：配置已保存；有检查变更时自动同步，也可手动同步");
            Ui.toast(this, "云同步已启用；正在单独登记设备信息");
            syncDeviceInfo(false);
        }
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
        if (url.contains("workers.dev") && !"Cloudflare".equals(type)) {
            type = "Cloudflare";
            setProviderSelection(type);
            Ui.toast(this, "已根据 workers.dev 地址自动切换为 Cloudflare");
        }
        if (spaceName.isBlank()) {
            Ui.toast(this, "请填写同步空间名称");
            return;
        }
        if ("Cloudflare".equals(type) && spacePassword.length() < 8
                && tokenValue.isBlank() && username.isBlank()) {
            Ui.toast(this, "Cloudflare 自动配对需要至少 8 位同步密码");
            return;
        }
        if (saveOnSuccess && spacePassword.length() < 8) {
            Ui.toast(this, "同步空间密码至少 8 位");
            return;
        }
        final String resolvedType = type;
        syncStatus.setText("检查内容：正在测试连接…");
        SyncLog.info(this, "测试连接", "开始；类型=" + resolvedType + "；同步空间=" + spaceName);
        new Thread(() -> {
            SyncProvider.ConnectionResult result;
            if (resolvedType.contains("WebDAV") || "Cloudflare".equals(resolvedType)
                    || "自定义 HTTP 服务器".equals(resolvedType)) {
                result = new WebDavClient(url, username, password, tokenValue,
                        "Cloudflare".equals(resolvedType) ? spaceName : "",
                        "Cloudflare".equals(resolvedType) ? spacePassword : "")
                        .testReadWrite(spaceName);
                if (!result.success() && "Cloudflare".equals(resolvedType)) {
                    String detail = result.message();
                    if (detail.startsWith("网络连接问题：")) {
                        // Preserve the transport diagnosis. A timeout does not prove the Worker is reachable.
                    } else if (detail.contains("需要设备授权") || detail.contains("HTTP 401")) {
                        detail = "已使用同步空间名称和同步密码自动发起设备配对，但这个地址仍拒绝授权。它不是本版兼容网关，或仍使用旧私有授权协议。请重新部署仓库 cloudflare-worker；如果云端另外生成了设备 Token，也可在高级认证中填写。\n\n原始响应："
                                + detail;
                    } else {
                        detail = "Cloudflare 地址可访问，但未通过安全台账兼容网关的读写校验："
                                + detail;
                    }
                    result = new SyncProvider.ConnectionResult(false, detail);
                }
            } else {
                result = new SyncProvider.ConnectionResult(false,
                        resolvedType + " 的官方授权尚未接入；请选择 Cloudflare 或 WebDAV / NAS");
            }
            SyncProvider.ConnectionResult checked = result;
            runOnUiThread(() -> {
                if (checked.success()) {
                    SyncLog.info(this, "测试连接", "成功；" + checked.message());
                    boolean saved = true;
                    if (saveOnSuccess) {
                        saved = saveProvider(resolvedType, url, username, password, tokenValue,
                                spaceName, spacePassword);
                    }
                    syncStatus.setText("同步状态：连接成功" + (saveOnSuccess && saved ? "，配置已启用" : ""));
                    new AlertDialog.Builder(this).setTitle("连接成功")
                            .setMessage(checked.message() + (saveOnSuccess && saved
                                    ? "\n\n配置已加密保存，密码输入框已经隐藏。现在将开始首次同步。" : ""))
                            .setPositiveButton("确定", null).show();
                    if (saveOnSuccess && saved) syncNow();
                } else {
                    SyncLog.warn(this, "测试连接", "失败；" + checked.message());
                    syncStatus.setText("同步状态：失败 · " + checked.message());
                    syncNotification(checked.message());
                    new AlertDialog.Builder(this)
                            .setTitle(checked.message().startsWith("网络连接问题：") ? "网络连接问题" : "连接失败")
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
            savedServerPassword = password;
            savedToken = tokenValue;
            savedSpacePassword = spacePassword;
            secret.setText(""); token.setText(""); encryption.setText("");
            secret.setHint(password.isBlank() ? "WebDAV / NAS 登录密码"
                    : "••••••••（登录密码已保存）");
            token.setHint(tokenValue.isBlank() ? "Cloudflare 设备 Token / Bearer Token"
                    : "••••••••（设备 Token 已保存）");
            encryption.setHint("••••••••（同步密码已保存）");
            repo.putSetting("last_sync_error", "");
            syncEnabledStatus.setText("云同步：已启用 · " + type);
            syncEnabledStatus.setTextColor(Color.rgb(22, 128, 57));
            if (syncSaveButton != null) syncSaveButton.setText("已启用 · 保存修改");
            CloudSyncScheduler.schedule(this);
            return true;
        } catch (Exception error) {
            syncStatus.setText("同步状态：配置加密保存失败");
            Ui.toast(this, "配置保存失败：" + error.getMessage());
            return false;
        }
    }

    private void syncNow() {
        runSync(false);
    }

    private void syncDeviceInfo(boolean openAfter) {
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

    private void runSync(boolean openDevicesAfter) {
        SyncLog.info(this, "手动同步", "用户发起检查内容同步");
        new Thread(() -> {
            try {
                CloudSyncService.Result result = new CloudSyncService(this).syncNow(message ->
                        runOnUiThread(() -> syncStatus.setText("同步状态：" + message)));
                runOnUiThread(() -> {
                    if ("LOGGED_OUT".equals(result.role())) {
                        syncEnabledStatus.setText("云同步：未启用");
                        syncEnabledStatus.setTextColor(Ui.TEXT);
                        syncStatus.setText("同步状态：本设备已被管理员登出");
                        if (syncSaveButton != null) syncSaveButton.setText("保存并启用");
                        encryption.setText("");
                        encryption.setHint("同步密码（至少 8 位）");
                        new AlertDialog.Builder(this).setTitle("本设备已被登出")
                                .setMessage("管理员已将本设备从当前同步空间登出。云同步已停用并清除了本机保存的同步密码；本地检查记录、照片、签名和模板全部保留。")
                                .setPositiveButton("确定", null).show();
                        return;
                    }
                    String role = "FIELD".equals(result.role()) ? "工作人员" : "管理员";
                    String type = (String) provider.getSelectedItem();
                    syncEnabledStatus.setText("云同步：已启用 · " + type);
                    syncEnabledStatus.setTextColor(Color.rgb(22, 128, 57));
                    if (syncSaveButton != null) syncSaveButton.setText("已启用 · 保存修改");
                    String time = DateFormat.getTimeInstance(DateFormat.SHORT)
                            .format(new Date(result.completedAt()));
                    String suffix = result.skippedSnapshots() > 0
                            ? " · 跳过旧快照 " + result.skippedSnapshots() + " 个" : "";
                    syncStatus.setText("同步状态：成功 · " + time + " · 本机角色 " + role + suffix);
                    Ui.toast(this, "同步完成：接收 " + result.peerDevices()
                            + " 台设备，合并 " + result.changedRows() + " 项数据" + suffix);
                    deviceRole.setSelection("FIELD".equals(result.role()) ? 1 : 0);
                    if (result.skippedSnapshots() > 0 && !result.warning().isBlank()) {
                        new AlertDialog.Builder(this)
                                .setTitle("同步完成，但发现旧设备快照")
                                .setMessage("其他可用设备已经正常同步；以下旧/损坏快照已跳过，不再阻塞同步：\n\n"
                                        + result.warning()
                                        + "\n\n如果这些都是之前测试版留下的，可使用下方“清空云端旧测试设备 / 重新建立同步空间”。")
                                .setPositiveButton("知道了", null).show();
                    }
                    if (openDevicesAfter) manageDevices();
                });
            } catch (Exception error) {
                SyncLog.error(this, "手动同步失败", error);
                String message = readableError(error);
                repo.putSetting("last_sync_error", message);
                runOnUiThread(() -> {
                    syncStatus.setText("同步状态：失败 · " + message);
                    if (!message.contains("已有同步任务正在运行")) syncNotification(message);
                    new AlertDialog.Builder(this)
                            .setTitle(message.startsWith("网络连接问题：") ? "网络连接问题" : "同步失败")
                            .setMessage(message + "\n\n已自动写入同步诊断日志。请在本页点击“查看 / 导出同步日志”，导出 TXT 后即可直接发给开发者排查。")
                            .setPositiveButton("确定", null).show();
                });
            }
        }, openDevicesAfter ? "refresh-paired-devices" : "manual-cloud-sync").start();
    }

    private void confirmResetCloudSpace() {
        EditText confirmation = Ui.input(this, "请输入：清空云端");
        confirmation.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("重新建立云端同步空间")
                .setMessage("这会删除当前同步空间中所有设备上传的 .safetydata 云端快照，并清空本机的旧配对设备列表。\n\n不会删除本机检查记录、照片、签名或模板。\n\n适合正式投入使用前清理旧测试版设备。其他仍需使用的正式手机之后再次“立即同步”即可重新加入。")
                .setView(confirmation)
                .setPositiveButton("确认清空", (dialog, which) -> {
                    if (!"清空云端".equals(confirmation.getText().toString().trim())) {
                        Ui.toast(this, "未输入“清空云端”，已取消");
                        return;
                    }
                    resetCloudSpace();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void resetCloudSpace() {
        syncStatus.setText("同步状态：正在重新建立云端同步空间…");
        new Thread(() -> {
            try {
                CloudSyncService.ResetResult result = new CloudSyncService(this).resetCloudSpace(message ->
                        runOnUiThread(() -> syncStatus.setText("同步状态：" + message)));
                runOnUiThread(() -> {
                    deviceRole.setSelection(0);
                    String time = DateFormat.getTimeInstance(DateFormat.SHORT)
                            .format(new Date(result.completedAt()));
                    syncStatus.setText("同步状态：云端已重建 · " + time + " · 本机角色 管理员");
                    new AlertDialog.Builder(this)
                            .setTitle("云端同步空间已重新建立")
                            .setMessage("已清理 " + result.deletedSnapshots()
                                    + " 个旧设备快照。\n\n本机已成为首位管理员。现在让另一台正式手机使用完全相同的同步空间名称和同步密码点击“立即同步”；随后回到本机点“管理已配对设备”，即可看到并管理它。")
                            .setPositiveButton("知道了", null).show();
                });
            } catch (Exception error) {
                String message = readableError(error);
                runOnUiThread(() -> {
                    syncStatus.setText("同步状态：云端重建失败 · " + message);
                    new AlertDialog.Builder(this)
                            .setTitle(message.startsWith("网络连接问题：") ? "网络连接问题" : "云端重建失败")
                            .setMessage(message).setPositiveButton("确定", null).show();
                });
            }
        }, "reset-cloud-space").start();
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
                String seen = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(new Date(cursor.getLong(3)));
                String currentMark = cursor.getString(0).equals(repo.setting("device_id", ""))
                        ? "（本机）" : "";
                labels.add(cursor.getString(1) + currentMark + "\n" + roleName(cursor.getString(2))
                        + " · 最后同步 " + seen);
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

        LinearLayout deviceList = Ui.column(this);
        deviceList.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4));
        TextView note = Ui.text(this,
                canManage
                        ? "共发现 " + ids.size() + " 台设备。点击其他设备即可设置为管理员或工作人员。"
                        : "共发现 " + ids.size() + " 台设备。本机是工作人员，只能查看设备列表。",
                13, false);
        note.setTextColor(Ui.MUTED);
        deviceList.addView(note);
        deviceList.addView(Ui.gap(this, 6));

        for (int i = 0; i < ids.size(); i++) {
            final int index = i;
            boolean isLocal = ids.get(i).equals(localId);
            String title = labels.get(i);
            if (!isLocal && title.startsWith("设备 ")) {
                title += "\n云端已发现，完整同步后显示设备名称";
            }
            Button deviceButton = Ui.secondaryButton(this, title);
            deviceButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            deviceButton.setTextSize(14);
            deviceButton.setMinHeight(Ui.dp(this, 68));
            deviceButton.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
            deviceButton.setOnClickListener(view -> {
                if (ids.get(index).equals(localId)) {
                    confirmCurrentDeviceLogout();
                    return;
                }
                if (!finalCanManage) {
                    Ui.toast(this, "只有管理员可以修改设备角色");
                    return;
                }
                if ("OWNER".equals(roles.get(index))) {
                    Ui.toast(this, "首位管理员不能降级");
                    return;
                }
                chooseDeviceRole(ids.get(index), labels.get(index));
            });
            deviceList.addView(deviceButton, Ui.match());
            deviceList.addView(Ui.gap(this, 7));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(deviceList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int maxHeight = Ui.dp(this, 430);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeight));

        new AlertDialog.Builder(this)
                .setTitle("已配对设备")
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .show();
    }

    private void chooseDeviceRole(String deviceId, String label) {
        String currentRole = "FIELD";
        try (Cursor cursor = repo.raw().rawQuery(
                "SELECT role FROM sync_devices WHERE device_id=?", new String[]{deviceId})) {
            if (cursor.moveToFirst()) currentRole = cursor.getString(0);
        }
        if ("LOGGED_OUT".equals(currentRole)) {
            new AlertDialog.Builder(this)
                    .setTitle(label.replace("\n", " · "))
                    .setItems(new String[]{"允许重新加入为工作人员"}, (dialog, which) -> allowDeviceRejoin(deviceId))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(label.replace("\n", " · "))
                .setItems(new String[]{"设为管理员", "设为工作人员", "登出此设备"}, (dialog, which) -> {
                    if (which == 2) {
                        confirmRemoteDeviceLogout(deviceId, label);
                        return;
                    }
                    String role = which == 0 ? "ADMIN" : "FIELD";
                    updateDeviceRoleDirect(deviceId, role);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateDeviceRoleDirect(String deviceId, String role) {
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

    private void confirmRemoteDeviceLogout(String deviceId, String label) {
        new AlertDialog.Builder(this)
                .setTitle("登出设备")
                .setMessage("确定让“" + label.replace("\n", " · ") + "”退出当前同步空间吗？\n\n"
                        + "云端会立即移除该设备快照，并写入轻量登出标记；该设备下次联网同步时会自动停用云同步并清除本机保存的同步密码。"
                        + "\n\n不会删除该设备本地的检查记录、照片、签名或模板。")
                .setPositiveButton("确认登出", (dialog, which) -> logoutRemoteDevice(deviceId))
                .setNegativeButton("取消", null)
                .show();
    }

    private void logoutRemoteDevice(String deviceId) {
        if (deviceSyncStatus != null) deviceSyncStatus.setText("设备同步：正在登出设备…");
        new Thread(() -> {
            try {
                CloudSyncService.DeviceLogoutResult result = new CloudSyncService(this).logoutDevice(deviceId);
                runOnUiThread(() -> {
                    String time = DateFormat.getTimeInstance(DateFormat.SHORT)
                            .format(new Date(result.completedAt()));
                    if (deviceSyncStatus != null) deviceSyncStatus.setText("设备同步：设备已登出 · " + time);
                    Ui.toast(this, "设备已登出；本地检查资料不会被删除");
                });
            } catch (Exception error) {
                String message = readableError(error);
                runOnUiThread(() -> {
                    if (deviceSyncStatus != null) deviceSyncStatus.setText("设备同步：设备登出失败 · " + message);
                    new AlertDialog.Builder(this).setTitle("设备登出失败")
                            .setMessage(message).setPositiveButton("确定", null).show();
                });
            }
        }, "logout-remote-device").start();
    }

    private void allowDeviceRejoin(String deviceId) {
        if (deviceSyncStatus != null) deviceSyncStatus.setText("设备同步：正在允许重新加入…");
        new Thread(() -> {
            try {
                CloudSyncService.DeviceLogoutResult result = new CloudSyncService(this).allowDeviceRejoin(deviceId);
                runOnUiThread(() -> {
                    String time = DateFormat.getTimeInstance(DateFormat.SHORT)
                            .format(new Date(result.completedAt()));
                    if (deviceSyncStatus != null) deviceSyncStatus.setText("设备同步：已允许重新加入 · " + time);
                    Ui.toast(this, "已允许重新加入；该设备需重新输入同步密码并启用云同步");
                });
            } catch (Exception error) {
                String message = readableError(error);
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("操作失败")
                        .setMessage(message).setPositiveButton("确定", null).show());
            }
        }, "allow-device-rejoin").start();
    }

    private void confirmCurrentDeviceLogout() {
        new AlertDialog.Builder(this)
                .setTitle("退出当前同步空间")
                .setMessage("确定让本机退出当前云同步吗？\n\n"
                        + "本机检查记录、照片、签名和模板全部保留；只会移除本机云端快照、停用云同步，并清除本机保存的同步密码。")
                .setPositiveButton("确认退出", (dialog, which) -> logoutCurrentDevice())
                .setNegativeButton("取消", null)
                .show();
    }

    private void logoutCurrentDevice() {
        if (deviceSyncStatus != null) deviceSyncStatus.setText("设备同步：正在退出当前同步空间…");
        new Thread(() -> {
            try {
                new CloudSyncService(this).logoutCurrentDevice();
                runOnUiThread(() -> {
                    syncEnabledStatus.setText("云同步：未启用");
                    syncEnabledStatus.setTextColor(Ui.TEXT);
                    if (deviceSyncStatus != null) deviceSyncStatus.setText("设备同步：本机已退出当前同步空间");
                    if (syncSaveButton != null) syncSaveButton.setText("保存并启用");
                    encryption.setText("");
                    encryption.setHint("同步密码（至少 8 位）");
                    new AlertDialog.Builder(this).setTitle("已退出")
                            .setMessage("本机已退出当前同步空间。本地检查资料全部保留；以后需要重新加入时，请重新填写云同步配置和同步密码。")
                            .setPositiveButton("确定", null).show();
                });
            } catch (Exception error) {
                String message = readableError(error);
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("退出失败")
                        .setMessage(message).setPositiveButton("确定", null).show());
            }
        }, "logout-current-device").start();
    }

    private String roleName(String role) {
        if ("LOGGED_OUT".equals(role)) return "已登出";
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

    private void setAdvancedAuthVisible(boolean visible) {
        if (advancedAuthBox == null || advancedAuthButton == null) return;
        advancedAuthBox.setVisibility(visible ? View.VISIBLE : View.GONE);
        advancedAuthButton.setText(visible ? "收起高级服务器认证" : "高级服务器认证（WebDAV / 设备 Token）");
    }

    private void maskPassword(EditText input) {
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setTransformationMethod(PasswordTransformationMethod.getInstance());
    }

    private AlertDialog busyDialog(String message) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("请稍候")
                .setMessage(message)
                .setCancelable(false)
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        return dialog;
    }

    private String readableError(Throwable error) { return SyncErrorFormatter.format(error); }

    private void syncNotification(String message) {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 991);
        }
        Notification notification = new Notification.Builder(this, SafetyLedgerApp.SYNC_CHANNEL)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle(message.startsWith("网络连接问题：") ? "安全检查台账同步失败 · 网络问题" : "安全检查台账同步失败")
                .setContentText(message)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(1001, notification);
    }
}
