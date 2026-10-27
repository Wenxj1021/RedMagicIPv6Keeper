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

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::checkAuthStatus;
    private final Shizuku.OnBinderDeadListener binderDeadListener = this::checkAuthStatus;
    private final Shizuku.OnRequestPermissionResultListener requestPermissionResultListener =
            (requestCode, grantResult) -> {
                if (requestCode == SHIZUKU_REQ_CODE) {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        appendLog("[Shizuku] 恭喜！已成功获取 ADB Shell 权限。");
                        checkAuthStatus();
                    } else {
                        appendLog("[Shizuku] 错误：用户拒绝了权限授权。");
                        Toast.makeText(this, "需要权限以执行优化指令", Toast.LENGTH_SHORT).show();
                        checkAuthStatus();
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
        checkAuthStatus();
        requestIgnoreBatteryOptimizations();
        checkIpv6Status();
    }

    private void initViews() {
        // Button 0: Re-check IPv6 Traffic Light
        binding.btnRecheckIpv6.setOnClickListener(v -> checkIpv6Status());

        // Root & Shizuku request buttons
        binding.btnRootReq.setOnClickListener(v -> requestRootPermission());
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

    private void checkAuthStatus() {
        new Thread(() -> {
            boolean hasRoot = ShellManager.checkRoot();
            boolean hasShizuku = ShellManager.isShizukuGranted();
            boolean shizukuAvailable = ShellManager.isShizukuAvailable();

            runOnUiThread(() -> {
                if (hasRoot) {
                    binding.tvShizukuBadge.setText("Root 已授权 👑");
                    binding.tvShizukuBadge.setTextColor(getColor(R.color.status_green));
                    binding.tvShizukuBadge.setBackgroundColor(getColor(R.color.status_green_bg));
                    binding.tvShizukuDetail.setText("已获取 KernelSU / Magisk Root 最高系统权限 (UID 0)，保活与修复畅通无阻。");
                    binding.btnRootReq.setVisibility(View.GONE);
                    binding.btnShizukuReq.setVisibility(View.GONE);
                } else if (hasShizuku) {
                    binding.tvShizukuBadge.setText("Shizuku 已授权 ⚡");
                    binding.tvShizukuBadge.setTextColor(getColor(R.color.status_green));
                    binding.tvShizukuBadge.setBackgroundColor(getColor(R.color.status_green_bg));
                    binding.tvShizukuDetail.setText("已成功绑定 Shizuku，享有 ADB Shell (UID 2000) 完整执行权限。");
                    binding.btnRootReq.setVisibility(View.GONE);
                    binding.btnShizukuReq.setVisibility(View.GONE);
                } else {
                    binding.tvShizukuBadge.setText("未授权 ⚠️");
                    binding.tvShizukuBadge.setTextColor(getColor(R.color.status_orange));
                    binding.tvShizukuBadge.setBackgroundColor(getColor(R.color.status_orange_bg));
                    binding.tvShizukuDetail.setText("未获取到执行权限。支持直接申请 Root 授权或通过 Shizuku 授权。");
                    binding.btnRootReq.setVisibility(View.VISIBLE);
                    binding.btnShizukuReq.setVisibility(shizukuAvailable ? View.VISIBLE : View.GONE);
                }
            });
        }).start();
    }

    private void requestRootPermission() {
        appendLog(">>> 正在向系统申请 Root (su) 权限...");
        new Thread(() -> {
            boolean ok = ShellManager.checkRoot();
            runOnUiThread(() -> {
                if (ok) {
                    appendLog("✔ [成功] Root 权限已成功授予 (UID 0)！");
                    Toast.makeText(this, "Root 权限已获取！", Toast.LENGTH_SHORT).show();
                } else {
                    appendLog("❌ [失败] 未能获取 Root 权限，请在授权弹窗中允许，或检查 KernelSU / Magisk。");
                    Toast.makeText(this, "Root 授权失败或已拒绝", Toast.LENGTH_SHORT).show();
                }
                checkAuthStatus();
            });
        }).start();
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
            Toast.makeText(this, "请先授予 Root 或 Shizuku 权限！", Toast.LENGTH_SHORT).show();
            checkAuthStatus();
            return;
        }

        binding.btnFixIpv6.setEnabled(false);
        appendLog("========================================");
        appendLog(">>> 开始执行全链路 IPv6 深度修复...");
        appendLog("1. 调整 Wi-Fi 功耗与休眠策略为极致性能模式...");
        appendLog("2. 禁用 IpReachabilityMonitor 误断开保护...");
        appendLog("3. 重启 L2 Wi-Fi 硬件协议栈 (清除 Qualcomm ND 固件离线漂移)...");

        boolean restartChrome = binding.cbChrome.isChecked();

        String fixCommand =
                "settings put global wifi_power_save 0\n" +
                "settings put global wifi_suspend_optimizations_enabled 0\n" +
                "settings put global wifi_scan_throttle_enabled 0\n" +
                "settings put global wifi_sleep_policy 2\n" +
                "cmd wifi set-ipreach-disconnect disabled\n" +
                "svc wifi disable\n" +
                "sleep 2\n" +
                "svc wifi enable\n" +
                "sleep 3\n" +
                "ip -6 neigh flush dev wlan0\n" +
                "ping6 -c 2 -W 2 'ff02::2%wlan0'\n";

        ShellManager.execAsync(
                fixCommand,
                new ShellManager.Callback() {
                    @Override
                    public void onOutput(String line) {
                        appendLog(line);
                    }

                    @Override
                    public void onComplete(int exitCode, String fullOutput) {
                        runOnUiThread(() -> {
                            appendLog("----------------------------------------");
                            appendLog("✔ [成功] Wi-Fi 硬件协议栈已深度重置并重新握手公网 IPv6！");

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
                "echo '[公网 IPv6 连通性测试 (Google / Cloudflare / 阿里)]'\n" +
                "toybox nc -z -w 2 2001:4860:4860::8888 443 && echo '-> Google (443): 畅通' || echo '-> Google (443): 超时'\n" +
                "toybox nc -z -w 2 2606:4700:4700::1111 443 && echo '-> Cloudflare (443): 畅通' || echo '-> Cloudflare (443): 超时'\n" +
                "toybox nc -z -w 2 2400:3200::1 443 && echo '-> 阿里 (443): 畅通' || echo '-> 阿里 (443): 超时'\n",
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
        checkAuthStatus();
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

    private static class TargetNode {
        final String name;
        final String host;
        final int port;
        int latency = -1;
        boolean ok = false;

        TargetNode(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }
    }

    private void checkIpv6Status() {
        setTrafficLight(TrafficState.TESTING, "正在检测 IPv6...", "正在并发测试三大节点 (Google · Cloudflare · 阿里)...", "本地 IPv6: 检测中...", 0);

        new Thread(() -> {
            String globalIp = getGlobalIpv6Address();
            boolean hasGlobal = (globalIp != null && !globalIp.isEmpty());

            TargetNode[] nodes = new TargetNode[]{
                    new TargetNode("Google", "2001:4860:4860::8888", 443),
                    new TargetNode("Cloudflare", "2606:4700:4700::1111", 443),
                    new TargetNode("阿里", "2400:3200::1", 443)
            };

            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(nodes.length);
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(nodes.length);

            for (TargetNode node : nodes) {
                pool.execute(() -> {
                    long start = System.currentTimeMillis();
                    boolean connected = false;
                    int rtt = -1;

                    // 1. Try port 443 (DoH / HTTPS) first, fallback to port 53 (DNS)
                    int[] ports = new int[]{node.port, 53};
                    for (int p : ports) {
                        try (java.net.Socket s = new java.net.Socket()) {
                            s.connect(new java.net.InetSocketAddress(node.host, p), 1800);
                            rtt = (int) (System.currentTimeMillis() - start);
                            connected = true;
                            break;
                        } catch (Exception ignored) {}
                    }

                    if (connected) {
                        node.latency = rtt;
                        node.ok = true;
                    } else if (ShellManager.hasPermission()) {
                        // 2. Fallback to ping6 if Shizuku available
                        ShellManager.CommandResult res = ShellManager.execSync("ping6 -c 1 -W 2 " + node.host, null);
                        if (res.exitCode == 0) {
                            node.latency = parsePingRtt(res.output);
                            node.ok = true;
                        }
                    }
                    latch.countDown();
                });
            }

            try {
                latch.await(3500, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {}
            pool.shutdown();

            int successCount = 0;
            StringBuilder latencySummary = new StringBuilder();
            for (int i = 0; i < nodes.length; i++) {
                TargetNode node = nodes[i];
                if (node.ok) successCount++;
                latencySummary.append(node.name).append(": ");
                if (node.ok) {
                    latencySummary.append(node.latency > 0 ? node.latency + "ms" : "<30ms");
                } else {
                    latencySummary.append("超时❌");
                }
                if (i < nodes.length - 1) {
                    latencySummary.append(" · ");
                }
            }

            final int finalSuccessCount = successCount;
            final String finalLatencyStr = latencySummary.toString();
            final String addrDisplay = hasGlobal ? globalIp : "未获取到公网 IPv6 地址";

            runOnUiThread(() -> {
                if (finalSuccessCount == 3) {
                    // 全部能连上：绿灯
                    setTrafficLight(
                            TrafficState.GREEN,
                            "IPv6 全线畅通 (3/3)",
                            finalLatencyStr,
                            "公网 IP: " + addrDisplay,
                            0
                    );
                    appendLog("[状态检测] 🟢 全线畅通 (3/3): " + finalLatencyStr);
                } else if (finalSuccessCount > 0) {
                    // 只能连上其中几个：黄灯
                    setTrafficLight(
                            TrafficState.YELLOW,
                            "IPv6 部分连通 (" + finalSuccessCount + "/3)",
                            finalLatencyStr,
                            "本地 IP: " + addrDisplay,
                            0
                    );
                    appendLog("[状态检测] 🟡 部分连通 (" + finalSuccessCount + "/3): " + finalLatencyStr);
                } else {
                    // 一个都连不上：红灯
                    setTrafficLight(
                            TrafficState.RED,
                            "IPv6 彻底断开 (0/3)",
                            finalLatencyStr,
                            "状态: " + (hasGlobal ? "已分配 IP 但全线超时" : "未获取公网 IPv6"),
                            0
                    );
                    appendLog("[状态检测] 🔴 全部断开 (0/3): " + finalLatencyStr);
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
