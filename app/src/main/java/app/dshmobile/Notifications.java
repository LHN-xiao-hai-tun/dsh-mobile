package app.dshmobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 下载完成 / 失败的通知（D1 · v1.3.9）。
 *
 * ── 口径（用户 2026-09-23 定，见 PRIVACY.md §四）
 *   **默认关**：默认不发任何通知、也**不申请** `POST_NOTIFICATIONS`。
 *   用户在设置里**主动打开开关**时，才在 Android 13+ 上申请那一条运行时权限。
 *   ⇒ 「关着的时候不弹权限窗、下载照样能用」是这个功能的一部分，别优化掉。
 *
 * ── 为什么不用前台服务
 *   前台服务要 `FOREGROUND_SERVICE` + `foregroundServiceType`（Android 14 起还要对应的
 *   `FOREGROUND_SERVICE_DATA_SYNC`），而本功能的诉求只是"切后台也能看到结果"。
 *   普通通知在**下载线程仍活着**时就能发出去（下载本来就在本进程的线程里跑）。
 *   ⚠️ 已知边界（如实记）：进程若被系统回收，长下载的结果就发不出来了 ——
 *   要彻底解决得上前台服务，留作后续（届时权限清单还要再加一条）。
 */
final class Notifications {

    private Notifications() {
    }

    /** 通知渠道 id（Android 8+ 必须建渠道才发得出通知） */
    static final String CHANNEL = "dsh-download";
    /** 通知 id（固定 = 新的一条覆盖旧的，不刷屏） */
    static final int ID_DOWNLOAD = 0x4401;

    /** 建渠道（幂等；每次发之前调一次最省心 —— 渠道不存在时创建是空操作） */
    static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    ctx.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription(ctx.getString(R.string.notif_channel_desc));
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        } catch (Exception ignored) {
        }
    }

    /**
     * 发一条下载结果通知（成功或失败）。调用方负责先判断"用户开关是否打开"。
     *
     * ⚠️ 权限：Android 13+ 未授权时 `notify()` 会被系统**静默丢弃**（不抛异常）
     *    ⇒ 这里只管发，是否真的显示由系统决定；不要让下载流程依赖它的结果。
     */
    static void postDownloadResult(Context ctx, boolean ok, String text) {
        try {
            ensureChannel(ctx);
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            Intent open = new Intent(ctx, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, flags);

            Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new Notification.Builder(ctx, CHANNEL)
                    : new Notification.Builder(ctx);
            b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(ctx.getString(ok ? R.string.notif_dl_done_title
                            : R.string.notif_dl_fail_title))
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            if (!ok) {
                b.setSmallIcon(android.R.drawable.stat_notify_error);
            }
            nm.notify(ID_DOWNLOAD, b.build());
        } catch (Exception ignored) {
            // 通知只是"锦上添花"：发不出去也绝不能影响下载本身
        }
    }
}
