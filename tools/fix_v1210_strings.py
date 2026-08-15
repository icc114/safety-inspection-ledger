from pathlib import Path

p = Path('app/src/main/java/cn/safetyledger/app/SettingsActivity.java')
text = p.read_text(encoding='utf-8')

replacements = {
    '"其他可用设备已经正常同步；以下旧/损坏快照已跳过，不再阻塞同步：\n\n"': '"其他可用设备已经正常同步；以下旧/损坏快照已跳过，不再阻塞同步：\\n\\n"',
    '+ "\n\n如果这些都是之前测试版留下的，可使用下方“清空云端旧测试设备 / 重新建立同步空间”。"': '+ "\\n\\n如果这些都是之前测试版留下的，可使用下方“清空云端旧测试设备 / 重新建立同步空间”。"',
    '"这会删除当前同步空间中所有设备上传的 .safetydata 云端快照，并清空本机的旧配对设备列表。\n\n不会删除本机检查记录、照片、签名或模板。\n\n适合正式投入使用前清理旧测试版设备。其他仍需使用的正式手机之后再次“立即同步”即可重新加入。"': '"这会删除当前同步空间中所有设备上传的 .safetydata 云端快照，并清空本机的旧配对设备列表。\\n\\n不会删除本机检查记录、照片、签名或模板。\\n\\n适合正式投入使用前清理旧测试版设备。其他仍需使用的正式手机之后再次“立即同步”即可重新加入。"',
    '+ " 个旧设备快照。\n\n本机已成为首位管理员。现在让另一台正式手机使用完全相同的同步空间名称和同步密码点击“立即同步”；随后回到本机点“刷新并管理已配对设备 / 设置角色”，即可看到并管理它。"': '+ " 个旧设备快照。\\n\\n本机已成为首位管理员。现在让另一台正式手机使用完全相同的同步空间名称和同步密码点击“立即同步”；随后回到本机点“刷新并管理已配对设备 / 设置角色”，即可看到并管理它。"',
}

for old, new in replacements.items():
    if old not in text:
        raise SystemExit('expected multiline Java string not found: ' + repr(old[:90]))
    text = text.replace(old, new, 1)

p.write_text(text, encoding='utf-8')
print('Java message escaping fixed')
