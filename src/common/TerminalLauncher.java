package common;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TerminalLauncher {
    private static final List<List<String>> LINUX_TERMINALS = List.of(
            List.of("x-terminal-emulator"),
            List.of("ptyxis", "-e"),
            List.of("gnome-terminal", "--"),
            List.of("konsole", "-e"),
            List.of("xfce4-terminal", "-e"),
            List.of("xterm", "-e"),
            List.of("flatpak-spawn", "--host")
        );

    public static Process startInTerminal(List<String> command) throws IOException {
        String os = System.getProperty("os.name").toLowerCase();

        if (!os.contains("linux")) {
            throw new UnsupportedOperationException("Only Linux is supported for terminal launching");
        }

        IOException last = null;
        for (List<String> term : LINUX_TERMINALS) {
            List<String> full = new ArrayList<>(term);
            full.addAll(command);
            try {
                return new ProcessBuilder(full).start();
            } catch (IOException e) {
                last = e; // try the next terminal
            }
        }
        throw new IOException("No terminal emulator found", last);
    }
}
