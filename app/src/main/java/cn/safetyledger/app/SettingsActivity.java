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
    private Button syncSaveButton;
    private TextView syncEnabledStatus;
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
        syncEnabledStatus = Ui.text(this, "云同步：未启用", 14, true);
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
        actions.addView(syncSaveButton, Ui.weight(1));
        actions.addView(Ui.horizontalGap(this, 5));
        actions.addView(now, Ui.weight(1));
        card.addView(actions);
        return card;
    }

    private LinearLayout securityCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "5", "安全与本地存储", null));
        Button password = Ui.secondaryButton(this, "设置 / 修改永久删除密码");
        password.setOnClickListener(view -> setDeletePassword());
        card.addView(password);
        TextView archiveState = Ui.text(this, "自动归档：关闭（不会自动删除本机记录）", 14, true);
        archiveState.setTextColor(Ui.BLUE_DARK);
        card.addView(archiveState);
        TextView archive = Ui.text(this,
                "“超过六个月自动归档”原本用于手机空间不足时，将已完成且已在 PC/云端完整校验的旧记录转为归档副本，再按用户选择释放手机上的原始照片。当前版本不执行自动归档、不自动删除任何记录或照片；待 PC 客户端和归档校验全部完成后再提供可选开关。",
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
                new AlertDialog.Builder(this)
                        .setTitle("本机安全密钥已重置")
                        .setMessage("手机系统使旧的本机加密密钥失效。APP 已自动清理无法解密的云端凭据；检查记录、照片、签名和模板均未删除。请重新输入同步密码后点击“保存并启用”。")
                        .setPositiveButton("知道了", null).show();
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
            syncStatus.setText("同步状态：配置已保存，正在首次同步…");
            Ui.toast(this, "云同步已启用，正在同步");
            syncNow();
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
        syncStatus.setText("同步状态：正在测试…");
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
                    if (detail.contains("需要设备授权") || detail.contains("HTTP 401")) {
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
        syncStatus.setText("同步状态：正在下载、合并并上传…");
        new Thread(() -> {
            try {
                CloudSyncService.Result result = new CloudSyncService(this).syncNow();
                runOnUiThread(() -> {
                    String role = "FIELD".equals(result.role()) ? "工作人员" : "管理员";
                    String type = (String) provider.getSelectedItem();
                    syncEnabledStatus.setText("云同步：已启用 · " + type);
                    syncEnabledStatus.setTextColor(Color.rgb(22, 128, 57));
                    if (syncSaveButton != null) syncSaveButton.setText("已启用 · 保存修改");
                    String time = DateFormat.getTimeInstance(DateFormat.SHORT)
                            .format(new Date(result.completedAt()));
                    syncStatus.setText("同步状态：成功 · " + time + " · 本机角色 " + role);
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
