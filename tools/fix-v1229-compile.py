from pathlib import Path


def replace_if_present(text: str, old: str, new: str) -> str:
    return text.replace(old, new) if old in text else text

# Keep Ui.text calls compatible with the integer text-size helper.
monthly_path = Path('app/src/main/java/cn/safetyledger/app/MonthlyPlanActivity.java')
monthly = monthly_path.read_text(encoding='utf-8')
monthly = replace_if_present(monthly, '12.5f, false', '12, false')
monthly = replace_if_present(monthly, '11.5f, false', '11, false')
monthly_path.write_text(monthly, encoding='utf-8')

ledger_path = Path('app/src/main/java/cn/safetyledger/app/LedgerActivity.java')
ledger = ledger_path.read_text(encoding='utf-8')
ledger = replace_if_present(ledger, '8.5f, true', '8, true')
ledger = replace_if_present(ledger, '8.5f, false', '8, false')

# Restore legal Java escaped newlines if an earlier migration produced physical line breaks.
ledger = replace_if_present(
    ledger,
    '"未设置计划\n点击这里新增"',
    '"未设置计划\\n点击这里新增"')
ledger = replace_if_present(
    ledger,
    'message.append("本月共保存 ").append(summary.totalInspections).append(" 条正式检查记录。\n\n");',
    'message.append("本月共保存 ").append(summary.totalInspections).append(" 条正式检查记录。\\n\\n");')
ledger = replace_if_present(
    ledger,
    "message.append('\n');",
    "message.append('\\n');")

# LedgerActivity already had an onResume at the end that refreshes filters/calendar and schedules
# cloud trash sync. Remove the duplicate early onResume introduced by the 1.2.29 migration.
duplicate_resume = '''\n    @Override\n    protected void onResume() {\n        super.onResume();\n        if (repo != null && calendarBox != null) {\n            syncCalendar();\n            load();\n        }\n    }\n'''
if ledger.count('protected void onResume()') > 1 and duplicate_resume in ledger:
    ledger = ledger.replace(duplicate_resume, '', 1)

ledger_path.write_text(ledger, encoding='utf-8')
