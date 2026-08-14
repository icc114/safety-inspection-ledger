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
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
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
import cn.safetyledger.app.sync.HttpSyncProvider;
import cn.safetyledger.app.sync.SyncProvider;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.UUID;

public final class SettingsActivity extends Activity {
    private static final int EXPORT = 801;
    private static final int IMPORT = 802;
    private LedgerRepository repo;
    private char[] pendingPassword;
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
    private TextView syncStatus;

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
                "备份文件为加密的 .safetydata，包含模板、记录、照片、签名和整改状态。可复制到另一台手机或电脑，再选择该文件恢复。PDF 不能作为数据备份导入。",
                13, false);
        note.setTextColor(Ui.MUTED);
        card.addView(note);
        Button backup = Ui.button(this, "备份到手机文件夹");
        Button restoreButton = Ui.secondaryButton(this, "从备份文件恢复");
        Button migration = Ui.compactButton(this, "查看设备迁移说明", false);
        backup.setOnClickListener(view -> askPassword(true));
        restoreButton.setOnClickListener(view -> askPassword(false));
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
        card.addView(Ui.sectionTitle(this, "3", "多设备角色", "决定同步冲突处理和数据保留策略"));
        deviceName = Ui.input(this, "设备名称，例如：我的主手机");
        deviceName.setSingleLine(true);
        deviceName.setText(repo.setting("device_name", Build.MANUFACTURER + " " + Build.MODEL));
        deviceRole = spinner(new String[]{"主设备（保留完整数据）", "现场采集设备（拍照填报）"});
        deviceRole.setSelection("FIELD".equals(repo.setting("device_role", "PRIMARY")) ? 1 : 0);
        card.addView(fieldLabel("设备名称"));
        card.addView(deviceName);
        card.addView(Ui.gap(this, 6));
        card.addView(fieldLabel("本机角色"));
        card.addView(deviceRole, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        TextView note = Ui.text(this,
                "建议本手机设为主设备，另一台工作手机设为现场采集设备。Windows 客户端将作为另一主设备，把收到的原始文件保存在电脑本地目录。当前 Android 版已保存角色和同步队列；Windows 客户端及后台云传输仍需后续交付。",
                13, false);
        note.setTextColor(Ui.MUTED);
        card.addView(note);
        Button save = Ui.compactButton(this, "保存设备角色", true);
        save.setOnClickListener(view -> {
            repo.putSetting("device_name", deviceName.getText().toString().trim());
            repo.putSetting("device_role", deviceRole.getSelectedItemPosition() == 0 ? "PRIMARY" : "FIELD");
            Ui.toast(this, "设备角色已保存");
        });
        card.addView(save);
        return card;
    }

    private LinearLayout cloudCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.sectionTitle(this, "4", "云同步", "服务提供商可切换，失败不影响本地填报"));
        provider = spinner(new String[]{"WebDAV", "Cloudflare", "飞牛 NAS / WebDAV",
                "Google Drive", "OneDrive", "自定义 HTTP 服务器"});
        endpoint = Ui.input(this, "服务地址 / 账号授权地址");
        user = Ui.input(this, "用户名");
        secret = Ui.input(this, "密码");
        token = Ui.input(this, "Token（可选）");
        space = Ui.input(this, "同步空间名称");
        space.setText("safety-ledger");
        encryption = Ui.input(this, "端到端加密密码");
        secret.setInputType(0x81);
        token.setInputType(0x81);
        encryption.setInputType(0x81);
        for (EditText input : new EditText[]{endpoint, user, secret, token, space, encryption}) {
            input.setSingleLine(true);
        }
        card.addView(provider, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        card.addView(Ui.gap(this, 5));
        card.addView(endpoint);
        card.addView(Ui.gap(this, 5));
        LinearLayout account = Ui.row(this);
        account.addView(user, Ui.weight(1));
        account.addView(Ui.horizontalGap(this, 5));
        account.addView(secret, Ui.weight(1));
        card.addView(account);
        card.addView(Ui.gap(this, 5));
        card.addView(token);
        card.addView(Ui.gap(this, 5));
        LinearLayout namespace = Ui.row(this);
        namespace.addView(space, Ui.weight(1));
        namespace.addView(Ui.horizontalGap(this, 5));
        namespace.addView(encryption, Ui.weight(1));
        card.addView(namespace);
        syncStatus = Ui.text(this, "同步状态：未配置", 14, true);
        card.addView(syncStatus);
        LinearLayout actions = Ui.row(this);
        Button test = Ui.compactButton(this, "测试连接", false);
        Button save = Ui.compactButton(this, "保存并启用", true);
        Button now = Ui.compactButton(this, "立即同步", false);
        test.setOnClickListener(view -> testConnection(false));
        save.setOnClickListener(view -> testConnection(true));
        now.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("后台同步尚未启用")
                .setMessage("本地待同步队列会继续保留。实际云端传输 provider 尚未完成，不会假装同步成功或删除旧云数据。")
                .setPositiveButton("确定", null).show());
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
        Button password = Ui.secondaryButton(this, "设置 / 修改永久删除密码");
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
            secret.setText(store.decrypt(cursor.getString(3)));
            token.setText(store.decrypt(cursor.getString(4)));
            space.setText(cursor.getString(5));
            encryption.setText(store.decrypt(cursor.getString(6)));
            syncStatus.setText("同步状态：待同步（provider 传输尚未启用）");
        } catch (Exception error) {
            syncStatus.setText("同步状态：配置读取失败");
        }
    }

    private void askPassword(boolean export) {
        EditText password = Ui.input(this, export ? "设置备份密码（至少 8 位）" : "输入备份密码");
        password.setInputType(0x81);
        new AlertDialog.Builder(this)
                .setTitle(export ? "备份到手机文件夹" : "从备份文件恢复")
                .setMessage(export ? "下一步可在手机文件选择器中指定保存文件夹。密码无法找回，请妥善保管。"
                        : "下一步请选择 .safetydata 文件；PDF 不能导入。")
                .setView(password)
                .setPositiveButton("选择文件位置", (dialog, which) -> {
                    pendingPassword = password.getText().toString().toCharArray();
                    if (pendingPassword.length < 8) {
                        Ui.toast(this, "密码至少 8 位");
                        pendingPassword = null;
                        return;
                    }
                    Intent intent = export
                            ? new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                    .setType("application/octet-stream")
                                    .putExtra(Intent.EXTRA_TITLE,
                                            "安全检查台账-" + System.currentTimeMillis() + ".safetydata")
                            : new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                    .setType("*/*")
                                    .addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(intent, export ? EXPORT : IMPORT);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || pendingPassword == null) return;
        if (request == EXPORT) exportData(data.getData());
        else if (request == IMPORT) importData(data.getData());
    }

    private void exportData(Uri uri) {
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            new BackupService(this).exportData(output, pendingPassword);
            Ui.toast(this, "APP 数据已加密备份到所选文件夹");
        } catch (Exception error) {
            Ui.toast(this, "备份失败：" + error.getMessage());
        } finally {
            pendingPassword = null;
        }
    }

    private void importData(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            restore = new BackupService(this).decryptAndValidate(input, pendingPassword);
            new AlertDialog.Builder(this)
                    .setTitle("选择恢复方式")
                    .setItems(new String[]{"合并恢复（保留本机数据，不覆盖冲突）",
                            "完整恢复（替换本机数据）"}, (dialog, which) -> {
                        try {
                            BackupService service = new BackupService(this);
                            if (which == 0) {
                                int count = service.mergeRestore(restore);
                                Ui.toast(this, "合并恢复完成，新增 " + count + " 条；冲突已保留副本");
                            } else {
                                service.fullRestore(restore);
                                Ui.toast(this, "完整恢复完成，APP 将重新启动");
                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    finishAffinity();
                                    System.exit(0);
                                }, 1000);
                            }
                        } catch (Exception error) {
                            Ui.toast(this, "恢复失败：" + error.getMessage());
                        }
                    })
                    .setOnCancelListener(dialog -> { if (restore != null) restore.close(); })
                    .show();
        } catch (Exception error) {
            Ui.toast(this, "导入失败：" + error.getMessage());
        } finally {
            pendingPassword = null;
        }
    }

    private void setDeletePassword() {
        EditText password = Ui.input(this, "新密码（至少 6 位）");
        password.setInputType(0x81);
        new AlertDialog.Builder(this)
                .setTitle("永久删除密码")
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
        String password = secret.getText().toString();
        String tokenValue = token.getText().toString();
        syncStatus.setText("同步状态：正在测试…");
        new Thread(() -> {
            SyncProvider.ConnectionResult result = new HttpSyncProvider(
                    type.contains("WebDAV") ? "WebDAV" : "HTTP")
                    .test(Map.of("endpoint", url, "username", username,
                            "secret", password, "token", tokenValue));
            runOnUiThread(() -> {
                if (result.success()) {
                    syncStatus.setText("同步状态：连接成功" + (saveOnSuccess ? "，配置已启用" : ""));
                    if (saveOnSuccess) saveProvider(type, url, username, password, tokenValue);
                    new AlertDialog.Builder(this).setTitle("连接成功")
                            .setMessage(result.message()).setPositiveButton("确定", null).show();
                } else {
                    syncStatus.setText("同步状态：失败 · " + result.message());
                    syncNotification(result.message());
                    new AlertDialog.Builder(this).setTitle("连接失败")
                            .setMessage(result.message()).setPositiveButton("确定", null).show();
                }
            });
        }).start();
    }

    private void saveProvider(String type, String url, String username, String password,
                              String tokenValue) {
        try {
            SecretStore store = new SecretStore();
            SQLiteDatabase database = repo.raw();
            long now = System.currentTimeMillis();
            database.execSQL("UPDATE sync_providers SET enabled=0");
            database.execSQL("INSERT INTO sync_providers(id,provider_type,display_name,endpoint,username,encrypted_secret,token_ciphertext,sync_space,encryption_secret,enabled,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    new Object[]{UUID.randomUUID().toString(), type, type, url, username,
                            store.encrypt(password), store.encrypt(tokenValue),
                            space.getText().toString().trim(),
                            store.encrypt(encryption.getText().toString()), 1, now, now});
        } catch (Exception error) {
            syncStatus.setText("同步状态：配置加密保存失败");
            Ui.toast(this, "配置保存失败：" + error.getMessage());
        }
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
