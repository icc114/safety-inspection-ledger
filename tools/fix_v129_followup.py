from pathlib import Path


def replace(path, old, new, count=1):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'pattern not found in {path}: {old[:120]!r}')
    p.write_text(text.replace(old, new, count), encoding='utf-8')

# Preserve the Cloudflare endpoint/space and stop stale status text from overwriting the
# explicit Keystore reset message.
replace('app/src/main/java/cn/safetyledger/app/SettingsActivity.java',
'''                new AlertDialog.Builder(this)\n                        .setTitle("本机安全密钥已重置")\n                        .setMessage("手机系统使旧的本机加密密钥失效。APP 已自动清理无法解密的云端凭据；检查记录、照片、签名和模板均未删除。请重新输入同步密码后点击“保存并启用”。")\n                        .setPositiveButton("知道了", null).show();\n            }\n            secret.setText("");''',
'''                secret.setText("");\n                token.setText("");\n                encryption.setText("");\n                secret.setHint("WebDAV / NAS 登录密码");\n                token.setHint("Cloudflare 设备 Token / Bearer Token");\n                encryption.setHint("同步密码（至少 8 位）");\n                space.setText(cursor.getString(5));\n                repo.putSetting("last_sync_error", "");\n                new AlertDialog.Builder(this)\n                        .setTitle("本机安全密钥已重置")\n                        .setMessage("手机系统使旧的本机加密密钥失效。APP 已自动清理无法解密的云端凭据；检查记录、照片、签名和模板均未删除。服务地址和同步空间名称已保留，请重新输入同步密码后点击“保存并启用”。")\n                        .setPositiveButton("知道了", null).show();\n                return;\n            }\n            secret.setText("");''')

# Make roles enforce at least one concrete management boundary: field devices may use
# synced templates but cannot alter the shared template definition.
replace('app/src/main/java/cn/safetyledger/app/SettingsActivity.java',
'''        card.addView(menuRow("检查模板管理", "新建、编辑、停用模板及调整检查项目", () ->\n                Ui.start(this, TemplateActivity.class)));''',
'''        card.addView(menuRow("检查模板管理", "新建、编辑、停用模板及调整检查项目", () -> {\n            if ("FIELD".equals(repo.setting("device_role", "PRIMARY"))) {\n                Ui.toast(this, "工作人员设备只能使用已同步模板，模板维护请由管理员设备完成");\n            } else {\n                Ui.start(this, TemplateActivity.class);\n            }\n        }));''')

# Show last-seen time in the real paired-device management dialog.
replace('app/src/main/java/cn/safetyledger/app/SettingsActivity.java',
'''                roles.add(cursor.getString(2));\n                labels.add(cursor.getString(1) + "\\n" + roleName(cursor.getString(2)));''',
'''                roles.add(cursor.getString(2));\n                String seen = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)\n                        .format(new Date(cursor.getLong(3)));\n                labels.add(cursor.getString(1) + "\\n" + roleName(cursor.getString(2))\n                        + " · 最后同步 " + seen);''')

# Track which device created each inspection. The column already exists in schema v1;
# this makes it useful for Windows materialization, audit trails and future field-role scoping.
replace('app/src/main/java/cn/safetyledger/app/data/Entities.java',
'''        public String id,templateId,templateName,date,time,type,unit,location,onDuty,inspector1,inspector2,inspectee,conclusion,advice,responsible,deadline,rectification,recheck,status;''',
'''        public String id,templateId,templateName,date,time,type,unit,location,onDuty,inspector1,inspector2,inspectee,conclusion,advice,responsible,deadline,rectification,recheck,status,deviceId;''')

replace('app/src/main/java/cn/safetyledger/app/data/LedgerRepository.java',
'''    public Inspection newInspection(String templateId){Template t=template(templateId);if(t==null)throw new IllegalArgumentException("模板不存在");LocalDateTime n=LocalDateTime.now();Inspection x=new Inspection();x.id=UUID.randomUUID().toString();x.templateId=t.id;''',
'''    public Inspection newInspection(String templateId){Template t=template(templateId);if(t==null)throw new IllegalArgumentException("模板不存在");LocalDateTime n=LocalDateTime.now();Inspection x=new Inspection();x.id=UUID.randomUUID().toString();x.deviceId=ensureDeviceId();x.templateId=t.id;''')

replace('app/src/main/java/cn/safetyledger/app/data/LedgerRepository.java',
'''    private ContentValues inspectionValues(Inspection x,boolean create){ContentValues v=LedgerDatabase.values("template_id",x.templateId,"template_name",x.templateName,"inspection_date",x.date,"inspection_time",x.time,"inspection_type",x.type,"unit_name",x.unit,"location",x.location,"on_duty",x.onDuty,"inspector1",x.inspector1,"inspector2",x.inspector2,"inspectee",x.inspectee,"conclusion",x.conclusion,"rectification_advice",x.advice,"responsible_person",x.responsible,"deadline",x.deadline,"rectification_detail",x.rectification,"recheck_result",x.recheck,"status",x.status,"updated_at",x.updatedAt,"revision",1);if(create){v.put("id",x.id);v.put("created_at",x.createdAt);}return v;}''',
'''    private ContentValues inspectionValues(Inspection x,boolean create){ContentValues v=LedgerDatabase.values("template_id",x.templateId,"template_name",x.templateName,"inspection_date",x.date,"inspection_time",x.time,"inspection_type",x.type,"unit_name",x.unit,"location",x.location,"on_duty",x.onDuty,"inspector1",x.inspector1,"inspector2",x.inspector2,"inspectee",x.inspectee,"conclusion",x.conclusion,"rectification_advice",x.advice,"responsible_person",x.responsible,"deadline",x.deadline,"rectification_detail",x.rectification,"recheck_result",x.recheck,"status",x.status,"updated_at",x.updatedAt,"revision",1);if(x.deviceId!=null&&!x.deviceId.isBlank())v.put("device_id",x.deviceId);if(create){v.put("id",x.id);v.put("created_at",x.createdAt);}return v;}''')

replace('app/src/main/java/cn/safetyledger/app/data/LedgerRepository.java',
'''x.rectification=s(c,"rectification_detail");x.recheck=s(c,"recheck_result");x.status=s(c,"status");x.createdAt=LedgerDatabase.lng(c,"created_at");''',
'''x.rectification=s(c,"rectification_detail");x.recheck=s(c,"recheck_result");x.status=s(c,"status");x.deviceId=s(c,"device_id");x.createdAt=LedgerDatabase.lng(c,"created_at");''')

replace('app/src/main/java/cn/safetyledger/app/data/LedgerRepository.java',
'''    public String setting(String key,String def){try(Cursor c=raw().query("app_settings",new String[]{"setting_value"},"setting_key=?",new String[]{key},null,null,null)){return c.moveToFirst()?c.getString(0):def;}}''',
'''    private String ensureDeviceId(){String id=setting("device_id","");if(id==null||id.isBlank()){id=UUID.randomUUID().toString();putSetting("device_id",id);}return id;}\n    public String setting(String key,String def){try(Cursor c=raw().query("app_settings",new String[]{"setting_value"},"setting_key=?",new String[]{key},null,null,null)){return c.moveToFirst()?c.getString(0):def;}}''')

# Add explicit cross-platform metadata without changing the established portable container.
replace('app/src/main/java/cn/safetyledger/app/backup/BackupService.java',
'''            manifest.setProperty("formatVersion","1");\n            manifest.setProperty("schemaVersion",String.valueOf(LedgerDatabase.VERSION));''',
'''            manifest.setProperty("formatVersion","1");\n            manifest.setProperty("container","safety-ledger-portable");\n            manifest.setProperty("appPackage","cn.safetyledger.app");\n            manifest.setProperty("portableCompatibility","android-windows");\n            manifest.setProperty("schemaVersion",String.valueOf(LedgerDatabase.VERSION));''')

print('v1.2.9 follow-up applied')
