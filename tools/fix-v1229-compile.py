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

# re.sub replacement strings in the first migration converted Java \\n escapes into literal
# source line breaks. Restore legal Java escaped newlines.
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
ledger_path.write_text(ledger, encoding='utf-8')
