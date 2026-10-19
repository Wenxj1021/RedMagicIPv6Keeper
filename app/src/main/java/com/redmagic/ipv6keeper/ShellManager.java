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

    public static boolean isShizukuAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasPermission() {
        if (!isShizukuAvailable()) return false;
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
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
            if (hasPermission()) {
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
