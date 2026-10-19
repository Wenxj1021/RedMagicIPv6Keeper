package com.redmagic.ipv6keeper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class KeepaliveService extends Service {
    private static final String TAG = "KeepaliveService";
    private static final String CHANNEL_ID = "ipv6_keepalive_service_channel";
    private static final int NOTIF_ID = 1001;

    public static final String ACTION_START = "com.redmagic.ipv6keeper.START";
    public static final String ACTION_STOP = "com.redmagic.ipv6keeper.STOP";
    public static final String EXTRA_INTERVAL = "extra_interval_seconds";

    private static boolean isRunning = false;
    private static KeepaliveListener listener;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> keepaliveTask;
    private PowerManager.WakeLock wakeLock;

    private int intervalSeconds = 45;
    private int pingCount = 0;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    public interface KeepaliveListener {
        void onTick(int count, boolean success, String timestamp, String message);
        void onStatusChanged(boolean running);
    }

    public static void setListener(KeepaliveListener l) {
        listener = l;
        if (listener != null) {
            listener.onStatusChanged(isRunning);
        }
    }

    public static boolean isRunning() {
        return isRunning;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RedMagicIPv6:KeepaliveLock");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null) {
            intervalSeconds = intent.getIntExtra(EXTRA_INTERVAL, 45);
        }

        startForeground(NOTIF_ID, buildNotification("IPv6 保活守护已启动", "探测周期: " + intervalSeconds + " 秒"));
        isRunning = true;
        if (listener != null) {
            listener.onStatusChanged(true);
        }

        startKeepaliveLoop();

        return START_STICKY;
    }

    private void startKeepaliveLoop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        scheduler = Executors.newSingleThreadScheduledExecutor();

        keepaliveTask = scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (wakeLock != null && !wakeLock.isHeld()) {
                    wakeLock.acquire(3000);
                }

                pingCount++;
                String now = timeFormat.format(new Date());

                // Execute light NDP solicit
                ShellManager.CommandResult res = ShellManager.execSync(
                        "ping6 -c 1 -W 2 'ff02::2%wlan0'",
                        null
                );

                boolean success = (res.exitCode == 0);
                String msg = success ? "NDP 探针成功 (状态: REACHABLE)" : "NDP 探针无响应 (正在自动唤醒)";

                if (!success) {
                    // Try flush neighbour cache and re-probe
                    ShellManager.execSync("ip -6 neigh flush dev wlan0; ping6 -c 1 -W 2 'ff02::2%wlan0'", null);
                }

                updateNotification("IPv6 守护中 · 第 " + pingCount + " 次探测", "上次探测: " + now + " (" + (success ? "正常" : "告警") + ")");

                if (listener != null) {
                    listener.onTick(pingCount, success, now, msg);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error in keepalive tick", e);
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                }
            }
        }, 1, intervalSeconds, TimeUnit.SECONDS);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "红魔 IPv6 持续保活守护",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("后台轻量保活 NDP 邻居状态，彻底阻断 Wi-Fi 休眠掉线");
            channel.setShowBadge(false);

            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String title, String content) {
        Intent notifIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, notifIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent stopIntent = new Intent(this, KeepaliveService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止守护", stopPi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String title, String content) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification(title, content));
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        if (keepaliveTask != null) {
            keepaliveTask.cancel(true);
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (listener != null) {
            listener.onStatusChanged(false);
        }
        stopForeground(true);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
