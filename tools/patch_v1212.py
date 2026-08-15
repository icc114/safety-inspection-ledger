from pathlib import Path


def replace_required(path, old, new, count=1):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'pattern not found in {path}: {old[:160]!r}')
    p.write_text(text.replace(old, new, count), encoding='utf-8')


# Version bump.
replace_required('app/build.gradle', "versionCode 14\n        versionName '1.2.11'", "versionCode 15\n        versionName '1.2.12'")

p = Path('app/src/main/java/cn/safetyledger/app/SettingsActivity.java')
text = p.read_text(encoding='utf-8')
text = text.replace('管理已配对设备 / 快速刷新', '管理已配对设备')
text = text.replace('正在快速读取云端设备列表…', '正在读取云端设备列表…')
text = text.replace('设备列表刷新只读取云端设备目录', '设备管理只读取云端设备目录')

start = text.index('        boolean finalCanManage = canManage;\n        new AlertDialog.Builder(this)\n                .setTitle("已配对设备")')
end_marker = '\n    }\n\n    private void chooseDeviceRole'
end = text.index(end_marker, start)

replacement = '''        boolean finalCanManage = canManage;

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
                title += "\\n云端已发现，完整同步后显示设备名称";
            }
            Button deviceButton = Ui.secondaryButton(this, title);
            deviceButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            deviceButton.setTextSize(14);
            deviceButton.setMinHeight(Ui.dp(this, 68));
            deviceButton.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
            deviceButton.setOnClickListener(view -> {
                if (ids.get(index).equals(localId)) {
                    Ui.toast(this, "这是本机；请点击其他设备设置角色");
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
                .show();'''

text = text[:start] + replacement + text[end:]
p.write_text(text, encoding='utf-8')
