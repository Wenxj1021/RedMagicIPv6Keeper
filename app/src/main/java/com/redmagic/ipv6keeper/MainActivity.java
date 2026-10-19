package com.redmagic.ipv6keeper;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.redmagic.ipv6keeper.databinding.ActivityMainBinding;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity implements KeepaliveService.KeepaliveListener {
    private static final int SHIZUKU_REQ_CODE = 5001;
    private ActivityMainBinding binding;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::checkShizukuStatus;
    private final Shizuku.OnBinderDeadListener binderDeadListener = this::checkShizukuStatus;
    private final Shizuku.OnRequestPermissionResultListener requestPermissionResultListener =
            (requestCode, grantResult) -> {
                if (requestCode == SHIZUKU_REQ_CODE) {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        appendLog("[Shizuku] 恭喜！已成功获取 ADB Shell 权限。");
                        checkShizukuStatus();
                    } else {
                        appendLog("[Shizuku] 错误：用户拒绝了权限授权。");
                        Toast.makeText(this, "需要 Shizuku 权限以执行优化指令", Toast.LENGTH_SHORT).show();
                        checkShizukuStatus();
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        initViews();
        registerShizuku();
        checkShizukuStatus();
        requestIgnoreBatteryOptimizations();
        checkIpv6Status();
    }

    private void initViews() {
        // Button 0: Re-check IPv6 Traffic Light
        binding.btnRecheckIpv6.setOnClickListener(v -> checkIpv6Status());

        // Shizuku request button
        binding.btnShizukuReq.setOnClickListener(v -> requestShizukuPermission());

        // Button 1: One-click IPv6 fix
        binding.btnFixIpv6.setOnClickListener(v -> executeOneClickFix());

        // Switch: Keepalive Daemon
        binding.switchDaemon.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (!ShellManager.hasPermission()) {
                    Toast.makeText(this, "请先授予 Shizuku 权限！", Toast.LENGTH_SHORT).show();
                    binding.switchDaemon.setChecked(false);
                    return;
                }
                startKeepalive();
            } else {
                stopKeepalive();
            }
        });

        // Diagnostic button
        binding.btnDiag.setOnClickListener(v -> executeDiagnostics());

        // Clear log button
        binding.btnClearLog.setOnClickListener(v -> binding.tvConsole.setText(""));
    }

    private void registerShizuku() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener);
    }

    private void checkShizukuStatus() {
        runOnUiThread(() -> {
            if (!ShellManager.isShizukuAvailable()) {
                binding.tvShizukuBadge.setText("未运行");
                binding.tvShizukuBadge.setTextColor(getColor(R.color.status_red));
                binding.tvShizukuBadge.setBackgroundColor(getColor(R.color.status_red_bg));
                binding.tvShizukuDetail.setText("未检测到 Shizuku 服务。请在平板上打开 Shizuku App 并启动服务。");
                binding.btnShizukuReq.setVisibility(View.GONE);
                return;
            }

            if (ShellManager.hasPermission()) {
                binding.tvShizukuBadge.setText("已授权");
                binding.tvShizukuBadge.setTextColor(getColor(R.color.status_green));
                binding.tvShizukuBadge.setBackgroundColor(getColor(R.color.status_green_bg));
                binding.tvShizukuDetail.setText("已成功绑定 Shizuku，享有 ADB Shell (UID 2000) 完整执行权限。");
                binding.btnShizukuReq.setVisibility(View.GONE);
            } else {
                binding.tvShizukuBadge.setText("未授权");
                binding.tvShizukuBadge.setTextColor(getColor(R.color.status_orange));
                binding.tvShizukuBadge.setBackgroundColor(getColor(R.color.status_orange_bg));
                binding.tvShizukuDetail.setText("Shizuku 正在运行，但本应用尚未获得授权。请点击下方按钮申请。");
                binding.btnShizukuReq.setVisibility(View.VISIBLE);
            }
        });
    }

    private void requestShizukuPermission() {
        if (!ShellManager.isShizukuAvailable()) {
            Toast.makeText(this, "Shizuku 服务未运行，请先在 Shizuku 应用中启动", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Shizuku.requestPermission(SHIZUKU_REQ_CODE);
        } catch (Exception e) {
            appendLog("[错误] 请求 Shizuku 权限失败: " + e.getMessage());
        }
    }

    private void executeOneClickFix() {
        if (!ShellManager.hasPermission()) {
            Toast.makeText(this, "请先授予 Shizuku 权限！", Toast.LENGTH_SHORT).show();
            requestShizukuPermission();
            return;
        }

        binding.btnFixIpv6.setEnabled(false);
        appendLog("========================================");
        appendLog(">>> 开始执行 IPv6 深度修复流程...");

        boolean restartChrome = binding.cbChrome.isChecked();

        ShellManager.execAsync(
                "settings put global wifi_power_save 0\n" +
                "settings put global wifi_suspend_optimizations_enabled 0\n" +
                "settings put global wifi_scan_throttle_enabled 0\n" +
                "settings put global wifi_sleep_policy 2\n" +
                "ip -6 neigh flush dev wlan0\n" +
                "ping6 -c 2 -W 2 'ff02::2%wlan0'\n" +
                "ping6 -c 2 -W 2 2001:4860:4860::8888\n",
                new ShellManager.Callback() {
                    @Override
                    public void onOutput(String line) {
                        appendLog(line);
                    }

                    @Override
                    public void onComplete(int exitCode, String fullOutput) {
                        runOnUiThread(() -> {
                            appendLog("----------------------------------------");
                            if (exitCode == 0) {
                                appendLog("✔ [成功] Wi-Fi 功耗优化与 IPv6 NDP 唤醒完成！");
                            } else {
                                appendLog("! [提示] 唤醒命令执行完毕 (代码: " + exitCode + ")");
                            }

                            if (restartChrome) {
                                appendLog(">>> 正在重启 Chrome 浏览器以重置 Happy Eyeballs 缓存...");
                                ShellManager.execAsync(
                                        "am force-stop com.android.chrome\n" +
                                        "sleep 1\n" +
                                        "am start -n com.android.chrome/com.google.android.apps.chrome.Main\n",
                                        new ShellManager.Callback() {
                                            @Override
                                            public void onOutput(String line) {
                                                appendLog(line);
                                            }

                                            @Override
                                            public void onComplete(int exitCode, String fullOutput) {
                                                runOnUiThread(() -> {
                                                    appendLog("✔ [完成] Chrome 浏览器已重启并恢复公网 IPv6 优先。");
                                                    binding.btnFixIpv6.setEnabled(true);
                                                    checkIpv6Status();
                                                });
                                            }
                                        }
                                );
                            } else {
                                binding.btnFixIpv6.setEnabled(true);
                                checkIpv6Status();
                            }
                        });
                    }
                }
        );
    }

    private void startKeepalive() {
        int interval = 45;
        if (binding.rb30s.isChecked()) interval = 30;
        else if (binding.rb60s.isChecked()) interval = 60;

        appendLog(">>> 正在启动后台持续保活守护服务 (周期: " + interval + " 秒)...");

        Intent serviceIntent = new Intent(this, KeepaliveService.class);
        serviceIntent.setAction(KeepaliveService.ACTION_START);
        serviceIntent.putExtra(KeepaliveService.EXTRA_INTERVAL, interval);

        ContextCompat.startForegroundService(this, serviceIntent);
    }

    private void stopKeepalive() {
        appendLog(">>> 正在停止保活守护服务...");
        Intent serviceIntent = new Intent(this, KeepaliveService.class);
        serviceIntent.setAction(KeepaliveService.ACTION_STOP);
        startService(serviceIntent);
    }

    private void executeDiagnostics() {
        appendLog("========================================");
        appendLog(">>> 正在检测当前网络与 IPv6 状态...");
        ShellManager.execAsync(
                "echo '[IPv6 地址列表]'\n" +
                "ip -6 addr show dev wlan0 | grep inet6\n" +
                "echo '[NDP 邻居网关状态]'\n" +
                "ip -6 neigh show dev wlan0\n" +
                "echo '[公网 IPv6 连通性测试 (Google DNS)]'\n" +
                "ping6 -c 2 -W 2 2001:4860:4860::8888\n",
                new ShellManager.Callback() {
                    @Override
                    public void onOutput(String line) {
                        appendLog(line);
                    }

                    @Override
                    public void onComplete(int exitCode, String fullOutput) {
                        runOnUiThread(() -> appendLog(">>> 状态检测完毕。"));
                    }
                }
        );
    }

    private void appendLog(String text) {
        runOnUiThread(() -> {
            String now = timeFormat.format(new Date());
            binding.tvConsole.append("[" + now + "] " + text + "\n");
            binding.svLog.post(() -> binding.svLog.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    public void onTick(int count, boolean success, String timestamp, String message) {
        runOnUiThread(() -> {
            String status = "第 " + count + " 次保活 · " + timestamp + " · " + (success ? "正常" : "异常");
            binding.tvDaemonStatus.setText(status);
            appendLog("[保活守护] " + status + ": " + message);
        });
    }

    @Override
    public void onStatusChanged(boolean running) {
        runOnUiThread(() -> {
            binding.switchDaemon.setChecked(running);
            if (running) {
                binding.tvDaemonStatus.setText("守护服务运行中 🟢");
                binding.tvDaemonStatus.setTextColor(getColor(R.color.status_green));
            } else {
                binding.tvDaemonStatus.setText("守护已停止 ⚪");
                binding.tvDaemonStatus.setTextColor(getColor(R.color.text_secondary));
            }
        });
    }

    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        KeepaliveService.setListener(this);
        checkShizukuStatus();
        checkIpv6Status();
    }

    private enum TrafficState {
        TESTING,
        GREEN,
        YELLOW,
        RED
    }

    private void setTrafficLight(TrafficState state, String title, String desc, String address, int rtt) {
        runOnUiThread(() -> {
            binding.tvTlTitle.setText(title);
            binding.tvTlDesc.setText(desc);
            binding.tvTlAddress.setText(address);

            switch (state) {
                case GREEN:
                    binding.viewLightRed.animate().alpha(0.18f).setDuration(250).start();
                    binding.viewLightYellow.animate().alpha(0.18f).setDuration(250).start();
                    binding.viewLightGreen.animate().alpha(1.0f).setDuration(250).start();

                    binding.tvTlTitle.setTextColor(getColor(R.color.tl_green_bright));
                    binding.tvTlBadge.setText("畅通 🟢");
                    binding.tvTlBadge.setTextColor(getColor(R.color.tl_green_bright));
                    binding.cardTrafficLight.setStrokeColor(getColor(R.color.tl_green_bright));
                    break;

                case YELLOW:
                    binding.viewLightRed.animate().alpha(0.18f).setDuration(250).start();
                    binding.viewLightYellow.animate().alpha(1.0f).setDuration(250).start();
                    binding.viewLightGreen.animate().alpha(0.18f).setDuration(250).start();

                    binding.tvTlTitle.setTextColor(getColor(R.color.tl_yellow_bright));
                    binding.tvTlBadge.setText("受阻 🟡");
                    binding.tvTlBadge.setTextColor(getColor(R.color.tl_yellow_bright));
                    binding.cardTrafficLight.setStrokeColor(getColor(R.color.tl_yellow_bright));
                    break;

                case RED:
                    binding.viewLightRed.animate().alpha(1.0f).setDuration(250).start();
                    binding.viewLightYellow.animate().alpha(0.18f).setDuration(250).start();
                    binding.viewLightGreen.animate().alpha(0.18f).setDuration(250).start();

                    binding.tvTlTitle.setTextColor(getColor(R.color.tl_red_bright));
                    binding.tvTlBadge.setText("断开 🔴");
                    binding.tvTlBadge.setTextColor(getColor(R.color.tl_red_bright));
                    binding.cardTrafficLight.setStrokeColor(getColor(R.color.tl_red_bright));
                    break;

                case TESTING:
                default:
                    binding.viewLightRed.animate().alpha(0.18f).setDuration(200).start();
                    binding.viewLightYellow.animate().alpha(1.0f).setDuration(200).start();
                    binding.viewLightGreen.animate().alpha(0.18f).setDuration(200).start();

                    binding.tvTlTitle.setTextColor(getColor(R.color.tl_yellow_bright));
                    binding.tvTlBadge.setText("检测中");
                    binding.tvTlBadge.setTextColor(getColor(R.color.tl_yellow_bright));
                    binding.cardTrafficLight.setStrokeColor(getColor(R.color.tl_housing_stroke));
                    break;
            }
        });
    }

    private void checkIpv6Status() {
        setTrafficLight(TrafficState.TESTING, "正在检测 IPv6...", "正在探测公网连通性与网关状态...", "本地 IPv6: 检测中...", 0);

        new Thread(() -> {
            String globalIp = getGlobalIpv6Address();
            boolean hasGlobal = (globalIp != null && !globalIp.isEmpty());

            boolean connected = false;
            int latency = 0;
            long start = System.currentTimeMillis();

            // 1. Direct TCP Socket test to Google Public DNS IPv6 (fastest user-space check)
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("2001:4860:4860::8888", 53), 1500);
                connected = true;
                latency = (int) (System.currentTimeMillis() - start);
            } catch (Exception ignored) {
                // Secondary check: Cloudflare DNS IPv6
                try (java.net.Socket s = new java.net.Socket()) {
                    start = System.currentTimeMillis();
                    s.connect(new java.net.InetSocketAddress("2606:4700:4700::1111", 53), 1500);
                    connected = true;
                    latency = (int) (System.currentTimeMillis() - start);
                } catch (Exception ignored2) {}
            }

            // 2. If socket check failed, attempt ping6 with Shizuku if available
            if (!connected && ShellManager.hasPermission()) {
                ShellManager.CommandResult res = ShellManager.execSync("ping6 -c 1 -W 2 2001:4860:4860::8888", null);
                if (res.exitCode == 0) {
                    connected = true;
                    latency = parsePingRtt(res.output);
                }
            }

            final boolean isConnected = connected;
            final int finalLatency = latency;
            final String addrDisplay = hasGlobal ? globalIp : "未获取到公网 IPv6 地址";

            runOnUiThread(() -> {
                if (isConnected && hasGlobal) {
                    String rttStr = (finalLatency > 0) ? (finalLatency + " ms") : "<30 ms";
                    setTrafficLight(
                            TrafficState.GREEN,
                            "IPv6 畅通无阻",
                            "公网已畅通 · 延迟: " + rttStr + " · 邻居状态正常",
                            "公网 IP: " + addrDisplay,
                            finalLatency
                    );
                    appendLog("[状态检测] 🟢 IPv6 畅通无阻 (" + rttStr + ")");
                } else if (hasGlobal && !isConnected) {
                    setTrafficLight(
                            TrafficState.YELLOW,
                            "IPv6 受阻 / 握手超时",
                            "已获取公网 IP 但公网探针超时，建议点击下方「一键修复」",
                            "本地 IP: " + addrDisplay,
                            0
                    );
                    appendLog("[状态检测] 🟡 IPv6 受阻: 已分配 IP 但公网握手超时");
                } else {
                    setTrafficLight(
                            TrafficState.RED,
                            "IPv6 掉线 / 未连接",
                            "未检测到可用公网 IPv6 地址，请检查 Wi-Fi 或点击「一键修复」",
                            "状态: 处于 IPv4 单栈模式",
                            0
                    );
                    appendLog("[状态检测] 🔴 IPv6 掉线: 未获取公网 IPv6");
                }
            });
        }).start();
    }

    private String getGlobalIpv6Address() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                java.net.NetworkInterface nif = interfaces.nextElement();
                if (nif.isLoopback() || !nif.isUp()) continue;
                java.util.Enumeration<java.net.InetAddress> addresses = nif.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    if (addr instanceof java.net.Inet6Address) {
                        java.net.Inet6Address v6 = (java.net.Inet6Address) addr;
                        if (!v6.isLinkLocalAddress() && !v6.isLoopbackAddress() && !v6.isMulticastAddress()) {
                            String host = v6.getHostAddress();
                            if (host != null) {
                                int percentIdx = host.indexOf('%');
                                if (percentIdx != -1) host = host.substring(0, percentIdx);
                                if (host.startsWith("2") || host.startsWith("3")) {
                                    return host;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private int parsePingRtt(String pingOutput) {
        try {
            if (pingOutput != null && pingOutput.contains("min/avg/max")) {
                String[] parts = pingOutput.split("=");
                if (parts.length > 1) {
                    String[] metrics = parts[1].trim().split("/");
                    if (metrics.length > 1) {
                        return (int) Float.parseFloat(metrics[1].trim());
                    }
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    @Override
    protected void onPause() {
        super.onPause();
        KeepaliveService.setListener(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener);
    }
}
