package me.countergd.launcher;

import me.countergd.launcher.ui.LauncherWindow;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // Keep Swing's default look and feel if the platform one is unavailable.
        }
        SwingUtilities.invokeLater(() -> new LauncherWindow().setVisible(true));
    }
}
