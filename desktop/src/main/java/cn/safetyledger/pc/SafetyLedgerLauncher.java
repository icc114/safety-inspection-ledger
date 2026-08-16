package cn.safetyledger.pc;

import javax.swing.*;

/** Entry point for PC 0.2.1. Keeps the proven 0.2.0 workflow and applies the compact calendar UI layer. */
public final class SafetyLedgerLauncher {
    private SafetyLedgerLauncher() {}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            SafetyLedgerDesktop frame = new SafetyLedgerDesktop();
            DesktopUiPatch.apply(frame);
            frame.setVisible(true);
        });
    }
}
