package com.redmagic.ipv6keeper;

import android.content.pm.PackageManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class ShellManager {
    private static final String TAG = "ShellManager";
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    public enum AuthMode {
        ROOT,       // KernelSU / Magisk / APatch (UID 0)
        SHIZUKU,    // Shizuku ADB (UID 2000)
        NONE        // No special privileges
    }

    private static Boolean sRootAvailable = null;

    public interface Callback {
        void onOutput(String line);
        void onComplete(int exitCode, String fullOutput);
    }

    public interface LineConsumer {
        void consume(String line);
    }

    public static class CommandResult {
        public final int exitCode;
        public final String output;

        public CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    /**
     * Actively checks if Root (su) is available and granted.
     */
    public static boolean checkRoot() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            int code = p.waitFor();
            boolean ok = (code == 0 && line != null && line.contains("uid=0"));
            sRootAvailable = ok;
            return ok;
        } catch (Throwable t) {
            sRootAvailable = false;
            return false;
        }
    }

    public static boolean isRootGranted() {
        if (sRootAvailable == null) {
            return checkRoot();
        }
        return sRootAvailable;
    }

    public static void setRootGranted(boolean granted) {
        sRootAvailable = granted;
    }

    public static boolean isShizukuAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isShizukuGranted() {
        if (!isShizukuAvailable()) return false;
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static AuthMode getAuthMode() {
        if (isRootGranted()) {
            return AuthMode.ROOT;
        }
        if (isShizukuGranted()) {
            return AuthMode.SHIZUKU;
        }
        return AuthMode.NONE;
    }

    public static boolean hasPermission() {
        return getAuthMode() != AuthMode.NONE;
    }

    public static void execAsync(String command, Callback callback) {
        EXECUTOR.execute(() -> {
            CommandResult result = execSync(command, line -> {
                if (callback != null) {
                    callback.onOutput(line);
                }
            });
            if (callback != null) {
                callback.onComplete(result.exitCode, result.output);
            }
        });
    }

    public static CommandResult execSync(String command, LineConsumer lineConsumer) {
        StringBuilder fullOutput = new StringBuilder();
        int exitCode = -1;
        try {
            Process process;
            AuthMode mode = getAuthMode();

            if (mode == AuthMode.ROOT) {
                // Execute directly via Root (su)
                process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            } else if (mode == AuthMode.SHIZUKU) {
                // Execute through Shizuku with shell (ADB) privileges
                java.lang.reflect.Method method = Shizuku.class.getDeclaredMethod(
                        "newProcess",
                        String[].class,
                        String[].class,
                        String.class
                );
                method.setAccessible(true);
                process = (Process) method.invoke(null, new String[]{"sh", "-c", command}, null, null);
            } else {
                // Fallback to local runtime
                process = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                fullOutput.append(line).append("\n");
                if (lineConsumer != null) {
                    lineConsumer.consume(line);
                }
            }
            while ((line = errorReader.readLine()) != null) {
                fullOutput.append(line).append("\n");
                if (lineConsumer != null) {
                    lineConsumer.consume(line);
                }
            }

            exitCode = process.waitFor();
            reader.close();
            errorReader.close();
        } catch (Exception e) {
            Log.e(TAG, "Error executing command: " + command, e);
            String err = "[Error] " + e.getMessage();
            fullOutput.append(err).append("\n");
            if (lineConsumer != null) {
                lineConsumer.consume(err);
            }
        }
        return new CommandResult(exitCode, fullOutput.toString().trim());
    }
}
