from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    if new in text:
        print(f'already applied: {path}')
        return
    if old not in text:
        raise SystemExit(f'expected text not found in {path}: {old[:120]!r}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')
    print(f'updated: {path}')


# ---------- Ui.java: larger, better centered back chevron ----------
replace_once(
    'app/src/main/java/cn/safetyledger/app/Ui.java',
'''        Button back = iconButton(activity, "‹");
        back.setOnClickListener(view -> activity.finish());
        TextView text = text(activity, title, 20, true);
        text.setTextColor(Color.WHITE);
        bar.addView(back, new LinearLayout.LayoutParams(dp(activity, 38), dp(activity, 38)));
''',
'''        Button back = iconButton(activity, "‹");
        back.setTextSize(30);
        back.setPadding(0, 0, 0, dp(activity, 2));
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(view -> activity.finish());
        TextView text = text(activity, title, 20, true);
        text.setTextColor(Color.WHITE);
        bar.addView(back, new LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 42)));
''')

# ---------- LedgerActivity calendar header ----------
replace_once(
    'app/src/main/java/cn/safetyledger/app/LedgerActivity.java',
'''        Button previous = Ui.secondaryButton(this, "‹");
        Button next = Ui.secondaryButton(this, "›");
        previous.setTextSize(24);
        next.setTextSize(24);
        todayTitle = Ui.text(this, "", 17, true);
        todayTitle.setGravity(Gravity.CENTER);
        Button reset = Ui.secondaryButton(this, "回到今天");
        reset.setMinHeight(Ui.dp(this, 38));
''',
'''        Button previous = Ui.secondaryButton(this, "‹");
        Button next = Ui.secondaryButton(this, "›");
        previous.setTextSize(28);
        next.setTextSize(28);
        previous.setPadding(0, 0, 0, Ui.dp(this, 2));
        next.setPadding(0, 0, 0, Ui.dp(this, 2));
        todayTitle = Ui.text(this, "", 17, true);
        todayTitle.setGravity(Gravity.CENTER);
        Button reset = Ui.secondaryButton(this, "回到今天");
        reset.setMinHeight(Ui.dp(this, 38));
        reset.setTextSize(12);
        reset.setSingleLine(true);
        reset.setPadding(Ui.dp(this, 5), 0, Ui.dp(this, 5), 0);
''')
replace_once(
    'app/src/main/java/cn/safetyledger/app/LedgerActivity.java',
'''        dateNavigation.addView(previous, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 40)));
        dateNavigation.addView(todayTitle, Ui.weight(1));
        dateNavigation.addView(next, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 40)));
        dateNavigation.addView(Ui.horizontalGap(this, 6));
        dateNavigation.addView(reset, new LinearLayout.LayoutParams(Ui.dp(this, 86), Ui.dp(this, 40)));
''',
'''        dateNavigation.addView(previous, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        dateNavigation.addView(todayTitle, Ui.weight(1));
        dateNavigation.addView(next, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        dateNavigation.addView(Ui.horizontalGap(this, 5));
        dateNavigation.addView(reset, new LinearLayout.LayoutParams(Ui.dp(this, 94), Ui.dp(this, 40)));
''')

# ---------- LedgerActivity filters in one compact row ----------
replace_once(
    'app/src/main/java/cn/safetyledger/app/LedgerActivity.java',
'''    private LinearLayout filterCard() {
        LinearLayout card = Ui.card(this);
        card.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        LinearLayout rangeRow = Ui.row(this);
        TextView rangeLabel = Ui.text(this, "显示范围", 14, true);
        range = spinner(new String[]{"当日", "本月", "本季度", "本年度", "全部"});
        range.setSelection(1);
        rangeRow.addView(rangeLabel, new LinearLayout.LayoutParams(Ui.dp(this, 82), Ui.dp(this, 42)));
        rangeRow.addView(range, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1));
        card.addView(rangeRow);
        card.addView(Ui.gap(this, 4));
        LinearLayout filters = Ui.row(this);
        type = spinner(types());
        statusFilter = spinner(new String[]{"全部状态", "草稿", "待整改", "整改中", "已整改完成", "检查完成"});
        filters.addView(type, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1));
        filters.addView(Ui.horizontalGap(this, 6));
        filters.addView(statusFilter, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1));
        card.addView(filters);
        AdapterView.OnItemSelectedListener listener = new SimpleSelect() {
            @Override void selected() { selected.clear(); page = 1; load(); }
        };
        range.setOnItemSelectedListener(listener);
        type.setOnItemSelectedListener(listener);
        statusFilter.setOnItemSelectedListener(listener);
        return card;
    }
''',
'''    private LinearLayout filterCard() {
        LinearLayout card = Ui.card(this);
        card.setPadding(Ui.dp(this, 9), Ui.dp(this, 7), Ui.dp(this, 9), Ui.dp(this, 7));
        LinearLayout filters = Ui.row(this);
        TextView rangeLabel = Ui.text(this, "显示范围", 12, true);
        rangeLabel.setPadding(0, 0, Ui.dp(this, 3), 0);
        range = spinner(new String[]{"当日", "本月", "本季度", "本年度", "全部"});
        range.setSelection(1);
        type = spinner(types());
        statusFilter = spinner(new String[]{"全部状态", "草稿", "待整改", "整改中", "已整改完成", "检查完成"});
        filters.addView(rangeLabel, new LinearLayout.LayoutParams(Ui.dp(this, 58), Ui.dp(this, 40)));
        filters.addView(range, new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 40)));
        filters.addView(Ui.horizontalGap(this, 4));
        filters.addView(type, new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1));
        filters.addView(Ui.horizontalGap(this, 4));
        filters.addView(statusFilter, new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1));
        card.addView(filters);
        AdapterView.OnItemSelectedListener listener = new SimpleSelect() {
            @Override void selected() { selected.clear(); page = 1; load(); }
        };
        range.setOnItemSelectedListener(listener);
        type.setOnItemSelectedListener(listener);
        statusFilter.setOnItemSelectedListener(listener);
        return card;
    }
''')

# ---------- LedgerActivity page-size spinner ----------
replace_once(
    'app/src/main/java/cn/safetyledger/app/LedgerActivity.java',
'''        header.addView(title, Ui.weight(1));
        header.addView(multiToggle, new LinearLayout.LayoutParams(Ui.dp(this, 92), Ui.dp(this, 38)));
        header.addView(Ui.horizontalGap(this, 5));
        header.addView(pageSize, new LinearLayout.LayoutParams(Ui.dp(this, 106), Ui.dp(this, 40)));
''',
'''        header.addView(title, Ui.weight(1));
        header.addView(multiToggle, new LinearLayout.LayoutParams(Ui.dp(this, 88), Ui.dp(this, 38)));
        header.addView(Ui.horizontalGap(this, 5));
        header.addView(pageSize, new LinearLayout.LayoutParams(Ui.dp(this, 122), Ui.dp(this, 40)));
''')

# ---------- LedgerActivity compact spinner typography ----------
replace_once(
    'app/src/main/java/cn/safetyledger/app/LedgerActivity.java',
'''    private Spinner spinner(String[] values) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, values);
        spinner.setAdapter(adapter);
        spinner.setBackground(Ui.shape(this, Color.WHITE, Ui.LINE, 10));
        spinner.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        return spinner;
    }
''',
'''    private Spinner spinner(String[] values) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = spinnerAdapter(values);
        spinner.setAdapter(adapter);
        spinner.setBackground(Ui.shape(this, Color.WHITE, Ui.LINE, 10));
        spinner.setPadding(Ui.dp(this, 5), 0, Ui.dp(this, 5), 0);
        spinner.setMinimumWidth(0);
        return spinner;
    }

    private ArrayAdapter<String> spinnerAdapter(String[] values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, values) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                if (view instanceof TextView text) {
                    text.setTextSize(13);
                    text.setSingleLine(true);
                    text.setPadding(Ui.dp(LedgerActivity.this, 4), 0,
                            Ui.dp(LedgerActivity.this, 2), 0);
                }
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }
''')
replace_once(
    'app/src/main/java/cn/safetyledger/app/LedgerActivity.java',
'''        type.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, values));
''',
'''        type.setAdapter(spinnerAdapter(values));
''')

# ---------- LedgerActivity weekday visibility and compact progress panel ----------
replace_once(
    'app/src/main/java/cn/safetyledger/app/LedgerActivity.java',
'''        for (String weekday : weekdays) {
            TextView heading = Ui.text(this, weekday, 12, true);
            heading.setGravity(Gravity.CENTER);
            grid.addView(heading, cellParams(18));
        }
''',
'''        for (String weekday : weekdays) {
            TextView heading = Ui.text(this, weekday, 11, true);
            heading.setPadding(0, 0, 0, 0);
            heading.setGravity(Gravity.CENTER);
            grid.addView(heading, cellParams(24));
        }
''')
replace_once(
    'app/src/main/java/cn/safetyledger/app/LedgerActivity.java',
'''        TextView legend = Ui.text(this, "★ 有检查记录    班 调休上班    休 休息日/法定节假日", 10, false);
        legend.setTextColor(Ui.MUTED);
        legend.setGravity(Gravity.CENTER);
        left.addView(legend, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 24)));

        calendarBox.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        calendarBox.addView(Ui.horizontalGap(this, 6));
        calendarBox.addView(monthProgressPanel(marked), new LinearLayout.LayoutParams(Ui.dp(this, 94), ViewGroup.LayoutParams.MATCH_PARENT));
''',
'''        TextView legend = Ui.text(this, "★ 有检查记录   班 调休上班   休 休息日/法定节假日", 9, false);
        legend.setPadding(0, 0, 0, 0);
        legend.setTextColor(Ui.MUTED);
        legend.setGravity(Gravity.CENTER);
        left.addView(legend, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 21)));

        calendarBox.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        calendarBox.addView(Ui.horizontalGap(this, 5));
        calendarBox.addView(monthProgressPanel(marked), new LinearLayout.LayoutParams(Ui.dp(this, 84), ViewGroup.LayoutParams.MATCH_PARENT));
''')
replace_once(
    'app/src/main/java/cn/safetyledger/app/LedgerActivity.java',
'''    private LinearLayout monthProgressPanel(Set<String> marked) {
        LinearLayout panel = Ui.column(this);
        panel.setPadding(Ui.dp(this, 7), Ui.dp(this, 8), Ui.dp(this, 7), Ui.dp(this, 8));
        panel.setBackground(Ui.shape(this, Color.WHITE, Ui.LINE, 12));
        TextView title = Ui.text(this, "本月检查进度", 12, true);
        title.setGravity(Gravity.CENTER);
        panel.addView(title);
        panel.addView(Ui.gap(this, 7));

        int planned = 4;
        Set<Integer> completedWeeks = new HashSet<>();
        for (String date : marked) {
            try {
                LocalDate parsed = LocalDate.parse(date);
                if (YearMonth.from(parsed).equals(month)) completedWeeks.add((parsed.getDayOfMonth() - 1) / 7);
            } catch (Exception ignored) {}
        }
        int completed = Math.min(planned, completedWeeks.size());
        int percent = planned == 0 ? 0 : Math.round(completed * 100f / planned);
        panel.addView(progressMetric("计划次数", planned + "次", Ui.BLUE));
        panel.addView(Ui.gap(this, 8));
        panel.addView(progressMetric("已完成", completed + "次", Color.rgb(38, 177, 91)));
        panel.addView(Ui.gap(this, 10));
        TextView rateLabel = Ui.text(this, "完成率", 11, true);
        rateLabel.setGravity(Gravity.CENTER);
        panel.addView(rateLabel);
        TextView rate = Ui.text(this, percent + "%", 22, true);
        rate.setTextColor(Ui.BLUE);
        rate.setGravity(Gravity.CENTER);
        rate.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 7));
        rate.setBackground(Ui.shape(this, Color.rgb(246, 250, 255), Ui.BLUE, 40));
        panel.addView(rate, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 62)));
        return panel;
    }

    private LinearLayout progressMetric(String label, String value, int color) {
        LinearLayout box = Ui.column(this);
        TextView name = Ui.text(this, label, 10, false);
        name.setTextColor(Ui.MUTED);
        name.setGravity(Gravity.CENTER);
        TextView number = Ui.text(this, value, 18, true);
        number.setTextColor(color);
        number.setGravity(Gravity.CENTER);
        box.addView(name);
        box.addView(number);
        return box;
    }
''',
'''    private LinearLayout monthProgressPanel(Set<String> marked) {
        LinearLayout panel = Ui.column(this);
        panel.setPadding(Ui.dp(this, 4), Ui.dp(this, 5), Ui.dp(this, 4), Ui.dp(this, 5));
        panel.setBackground(Ui.shape(this, Color.WHITE, Ui.LINE, 12));
        TextView title = Ui.text(this, "本月检查\n进度", 10, true);
        title.setPadding(0, 0, 0, 0);
        title.setGravity(Gravity.CENTER);
        panel.addView(title);
        panel.addView(Ui.gap(this, 4));

        int planned = 4;
        Set<Integer> completedWeeks = new HashSet<>();
        for (String date : marked) {
            try {
                LocalDate parsed = LocalDate.parse(date);
                if (YearMonth.from(parsed).equals(month)) completedWeeks.add((parsed.getDayOfMonth() - 1) / 7);
            } catch (Exception ignored) {}
        }
        int completed = Math.min(planned, completedWeeks.size());
        int percent = planned == 0 ? 0 : Math.round(completed * 100f / planned);
        panel.addView(progressMetric("计划次数", planned + "次", Ui.BLUE));
        panel.addView(Ui.gap(this, 5));
        panel.addView(progressMetric("已完成", completed + "次", Color.rgb(38, 177, 91)));
        panel.addView(Ui.gap(this, 5));
        TextView rateLabel = Ui.text(this, "完成率", 9, true);
        rateLabel.setPadding(0, 0, 0, 0);
        rateLabel.setGravity(Gravity.CENTER);
        panel.addView(rateLabel);
        panel.addView(Ui.gap(this, 2));
        TextView rate = Ui.text(this, percent + "%", 18, true);
        rate.setTextColor(Ui.BLUE);
        rate.setGravity(Gravity.CENTER);
        rate.setPadding(0, 0, 0, 0);
        rate.setBackground(Ui.shape(this, Color.rgb(246, 250, 255), Ui.BLUE, 40));
        panel.addView(rate, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44)));
        return panel;
    }

    private LinearLayout progressMetric(String label, String value, int color) {
        LinearLayout box = Ui.column(this);
        TextView name = Ui.text(this, label, 9, false);
        name.setPadding(0, 0, 0, 0);
        name.setTextColor(Ui.MUTED);
        name.setGravity(Gravity.CENTER);
        TextView number = Ui.text(this, value, 16, true);
        number.setPadding(0, 0, 0, 0);
        number.setTextColor(color);
        number.setGravity(Gravity.CENTER);
        box.addView(name);
        box.addView(number);
        return box;
    }
''')

# ---------- SettingsActivity: log entry + instrumentation ----------
replace_once(
    'app/src/main/java/cn/safetyledger/app/SettingsActivity.java',
'''import cn.safetyledger.app.sync.SyncErrorFormatter;
''',
'''import cn.safetyledger.app.sync.SyncErrorFormatter;
import cn.safetyledger.app.sync.SyncLog;
''')
replace_once(
    'app/src/main/java/cn/safetyledger/app/SettingsActivity.java',
'''        card.addView(actions);
        card.addView(Ui.gap(this, 7));
        Button resetCloud = Ui.dangerButton(this, "清空云端旧测试设备 / 重新建立同步空间");
''',
'''        card.addView(actions);
        card.addView(Ui.gap(this, 7));
        Button logs = Ui.secondaryButton(this, "查看 / 导出同步日志");
        logs.setOnClickListener(view -> Ui.start(this, SyncLogActivity.class));
        card.addView(logs);
        card.addView(Ui.gap(this, 7));
        Button resetCloud = Ui.dangerButton(this, "清空云端旧测试设备 / 重新建立同步空间");
''')
replace_once(
    'app/src/main/java/cn/safetyledger/app/SettingsActivity.java',
'''        final String resolvedType = type;
        syncStatus.setText("检查内容：正在测试连接…");
        new Thread(() -> {
''',
'''        final String resolvedType = type;
        syncStatus.setText("检查内容：正在测试连接…");
        SyncLog.info(this, "测试连接", "开始；类型=" + resolvedType + "；同步空间=" + spaceName);
        new Thread(() -> {
''')
replace_once(
    'app/src/main/java/cn/safetyledger/app/SettingsActivity.java',
'''                if (checked.success()) {
                    boolean saved = true;
''',
'''                if (checked.success()) {
                    SyncLog.info(this, "测试连接", "成功；" + checked.message());
                    boolean saved = true;
''')
replace_once(
    'app/src/main/java/cn/safetyledger/app/SettingsActivity.java',
'''                } else {
                    syncStatus.setText("同步状态：失败 · " + checked.message());
''',
'''                } else {
                    SyncLog.warn(this, "测试连接", "失败；" + checked.message());
                    syncStatus.setText("同步状态：失败 · " + checked.message());
''')
replace_once(
    'app/src/main/java/cn/safetyledger/app/SettingsActivity.java',
'''    private void runSync(boolean openDevicesAfter) {
        new Thread(() -> {
''',
'''    private void runSync(boolean openDevicesAfter) {
        SyncLog.info(this, "手动同步", "用户发起检查内容同步");
        new Thread(() -> {
''')
replace_once(
    'app/src/main/java/cn/safetyledger/app/SettingsActivity.java',
'''            } catch (Exception error) {
                String message = readableError(error);
                repo.putSetting("last_sync_error", message);
''',
'''            } catch (Exception error) {
                SyncLog.error(this, "手动同步失败", error);
                String message = readableError(error);
                repo.putSetting("last_sync_error", message);
''')
replace_once(
    'app/src/main/java/cn/safetyledger/app/SettingsActivity.java',
'''                    new AlertDialog.Builder(this)
                            .setTitle(message.startsWith("网络连接问题：") ? "网络连接问题" : "同步失败")
                            .setMessage(message).setPositiveButton("确定", null).show();
''',
'''                    new AlertDialog.Builder(this)
                            .setTitle(message.startsWith("网络连接问题：") ? "网络连接问题" : "同步失败")
                            .setMessage(message + "\n\n已自动写入同步诊断日志。请在本页点击“查看 / 导出同步日志”，导出 TXT 后即可直接发给开发者排查。")
                            .setPositiveButton("确定", null).show();
''')

# ---------- CloudSyncService: detailed safe diagnostics ----------
replace_once(
    'app/src/main/java/cn/safetyledger/app/sync/CloudSyncService.java',
'''        boolean publishedLocalFirst = false;
        try {
            config = requireConfig();
            progress(listener, "正在连接云端…");
            WebDavClient client = client(config);
            prepare(client, config);
            syncTrashSignalsInternal(client,config,false);

            String deviceId = ensureDeviceId();
''',
'''        boolean publishedLocalFirst = false;
        SyncLog.info(context, "内容同步", "开始");
        try {
            config = requireConfig();
            SyncLog.info(context, "同步配置", "provider=" + config.type
                    + "；endpoint=" + safeEndpoint(config.endpoint) + "；space=" + config.space);
            progress(listener, "正在连接云端…");
            WebDavClient client = client(config);
            prepare(client, config);
            SyncLog.info(context, "连接云端", "目录准备完成");
            syncTrashSignalsInternal(client,config,false);
            SyncLog.info(context, "云端回收站", "同步完成");

            String deviceId = ensureDeviceId();
            SyncLog.info(context, "设备", "device=" + shortDevice(deviceId));
''')
replace_once(
    'app/src/main/java/cn/safetyledger/app/sync/CloudSyncService.java',
'''            List<String> snapshots = client.listSnapshots(config.space);
            boolean emptyCloud = snapshots.isEmpty();
''',
'''            List<String> snapshots = client.listSnapshots(config.space);
            SyncLog.info(context, "云端快照", "发现 " + snapshots.size() + " 个快照");
            boolean emptyCloud = snapshots.isEmpty();
''')
replace_once(
    'app/src/main/java/cn/safetyledger/app/sync/CloudSyncService.java',
'''            if (pendingAtStart) {
                progress(listener, "正在发布本机修改，供其他设备并行接收…");
                uploadSnapshot(backup, client, config, deviceId);
                publishedLocalFirst = true;
''',
'''            if (pendingAtStart) {
                progress(listener, "正在发布本机修改，供其他设备并行接收…");
                SyncLog.info(context, "本机修改", "检测到待同步变更，先上传本机快照");
                uploadSnapshot(backup, client, config, deviceId);
                publishedLocalFirst = true;
                SyncLog.info(context, "本机修改", "首次快照上传完成");
''')
replace_once(
    'app/src/main/java/cn/safetyledger/app/sync/CloudSyncService.java',
'''                File remote = File.createTempFile("safety-cloud-in-", ".safetydata", context.getCacheDir());
                try {
                    client.download(config.space, name, remote);
                    try (FileInputStream input = new FileInputStream(remote)) {
''',
'''                File remote = File.createTempFile("safety-cloud-in-", ".safetydata", context.getCacheDir());
                try {
                    SyncLog.info(context, "接收设备", shortDevice(name) + "；开始下载");
                    client.download(config.space, name, remote);
                    SyncLog.info(context, "接收设备", shortDevice(name) + "；下载完成；bytes=" + remote.length());
                    try (FileInputStream input = new FileInputStream(remote)) {
''')
replace_once(
    'app/src/main/java/cn/safetyledger/app/sync/CloudSyncService.java',
'''                    }
                    peers++;
                } catch (Throwable peerError) {
                    skipped++;
''',
'''                    }
                    peers++;
                    SyncLog.info(context, "接收设备", shortDevice(name) + "；合并完成");
                } catch (Throwable peerError) {
                    skipped++;
                    SyncLog.error(context, "接收设备失败 " + shortDevice(name), peerError);
''')
replace_once(
    'app/src/main/java/cn/safetyledger/app/sync/CloudSyncService.java',
'''            progress(listener, "正在上传本机最新数据…");
            uploadSnapshot(backup, client, config, deviceId);
            runAutoArchiveAfterSuccessfulSync();
''',
'''            progress(listener, "正在上传本机最新数据…");
            SyncLog.info(context, "最终上传", "开始生成并上传聚合快照");
            uploadSnapshot(backup, client, config, deviceId);
            SyncLog.info(context, "最终上传", "完成");
            runAutoArchiveAfterSuccessfulSync();
''')
replace_once(
    'app/src/main/java/cn/safetyledger/app/sync/CloudSyncService.java',
'''            progress(listener, skipped == 0 ? "同步完成" : "同步完成，但有旧设备快照被跳过");
            return new Result(peers, changed, skipped, deviceRole(deviceId), now, warning);
        } finally {
''',
'''            progress(listener, skipped == 0 ? "同步完成" : "同步完成，但有旧设备快照被跳过");
            SyncLog.info(context, "内容同步", "成功；peerDevices=" + peers
                    + "；changedRows=" + changed + "；skippedSnapshots=" + skipped);
            return new Result(peers, changed, skipped, deviceRole(deviceId), now, warning);
        } catch (Exception error) {
            SyncLog.error(context, "内容同步失败", error);
            throw error;
        } finally {
''')
replace_once(
    'app/src/main/java/cn/safetyledger/app/sync/CloudSyncService.java',
'''        } catch (Exception error) {
            String message = readable(error);
            if ("Cloudflare".equals(config.type)) {
''',
'''        } catch (Exception error) {
            SyncLog.error(context, "准备云端目录失败", error);
            String message = readable(error);
            if ("Cloudflare".equals(config.type)) {
''')
replace_once(
    'app/src/main/java/cn/safetyledger/app/sync/CloudSyncService.java',
'''    private void uploadSnapshot(BackupService backup, WebDavClient client,
                                Config config, String deviceId) throws Exception {
        File outgoing = File.createTempFile("safety-cloud-out-", ".safetydata", context.getCacheDir());
        try {
            try (FileOutputStream output = new FileOutputStream(outgoing)) {
                backup.exportCloudSnapshot(output, config.spacePassword.clone());
            }
            client.upload(config.space, deviceId + ".safetydata", outgoing);
        } finally {
            outgoing.delete();
        }
    }
''',
'''    private void uploadSnapshot(BackupService backup, WebDavClient client,
                                Config config, String deviceId) throws Exception {
        File outgoing = File.createTempFile("safety-cloud-out-", ".safetydata", context.getCacheDir());
        try {
            SyncLog.info(context, "生成快照", "开始");
            try (FileOutputStream output = new FileOutputStream(outgoing)) {
                backup.exportCloudSnapshot(output, config.spacePassword.clone());
            }
            SyncLog.info(context, "生成快照", "完成；bytes=" + outgoing.length());
            client.upload(config.space, deviceId + ".safetydata", outgoing);
            SyncLog.info(context, "上传快照", "完成；bytes=" + outgoing.length());
        } finally {
            outgoing.delete();
        }
    }
''')
replace_once(
    'app/src/main/java/cn/safetyledger/app/sync/CloudSyncService.java',
'''    private static String readable(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    public record Result''',
'''    private static String readable(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static String safeEndpoint(String endpoint) {
        if (endpoint == null) return "";
        String value = endpoint.trim();
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        return value;
    }

    public record Result''')

print('All v1.2.25 source edits applied.')
