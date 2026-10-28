package com.example.mockgps;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 悬浮窗：在其他应用上层显示一个小地图（椭圆 + 当前位置 + 圈数进度），
 * 底部三个圆形按钮切换速度档位，点击地图进入主界面。
 * 需要 SYSTEM_ALERT_WINDOW 权限。
 */
public class OverlayService extends Service {

    private static final String CHANNEL_ID = "tuopao_overlay";
    /** 步行速度（米/秒），约 5 km/h。 */
    private static final double WALK_MPS = 1.4;
    /** 自行车速度（米/秒）= 18 km/h。 */
    private static final double BIKE_MPS = 18 / 3.6;

    private WindowManager wm;
    private View root;
    private TrackView mini;
    private TileMap map;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager.LayoutParams lp;
    private final TextView[] speedBtns = new TextView[3];
    private int speedMode = 1;          // 0=步行 1=跑步 2=骑行
    private TextView playBtn;
    private boolean lastRunningShown = false;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            if (mini != null) {
                map.setCenter(SimState.centerGcjLon, SimState.centerGcjLat);
                map.setZoom(SimState.zoom);
                map.setLayer(SimState.layer);
                mini.setEllipse(SimState.aMeters, SimState.bMeters, SimState.angleDeg);
                mini.setStartAngle(SimState.startAngleDeg);
                mini.setHud(Math.max(SimState.lap, 1), SimState.totalLaps,
                        String.format(java.util.Locale.CHINA, "%.1f m/s",
                                SimState.curSpeed));
                mini.setExternalState(SimState.trailSnapshot(), SimState.curGcjLon,
                        SimState.curGcjLat, SimState.hasPos);
                mini.invalidate();
            }
            if (SimState.running != lastRunningShown) {
                lastRunningShown = SimState.running;
                updatePlayButton(SimState.running);
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "悬浮窗", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n;
        if (Build.VERSION.SDK_INT >= 26) {
            n = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("拖跑 · 悬浮窗运行中")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .build();
        } else {
            //noinspection deprecation
            n = new Notification.Builder(this)
                    .setContentTitle("拖跑 · 悬浮窗运行中")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .build();
        }
        startForeground(2, n);
        if (root == null) {
            showOverlay();
        }
        return START_STICKY;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void showOverlay() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        final int radius = dp(22);
        final int mapSize = dp(170);
        final int btnSize = dp(30);
        final int btnGap = dp(10);
        final int rowH = dp(46);

        FrameLayout box = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.parseColor("#0F1115"));
        bg.setCornerRadius(radius);
        box.setBackground(bg);
        box.setClipToOutline(true);
        box.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        // ---- 上半：地图 ----
        FrameLayout mapBox = new FrameLayout(this);
        map = new TileMap(getCacheDir());
        map.setOnTileLoaded(() -> {
            if (mini != null) {
                mini.postInvalidate();
            }
        });
        map.setCenter(SimState.centerGcjLon, SimState.centerGcjLat);
        map.setZoom(SimState.zoom);
        map.setLayer(SimState.layer);

        mini = new TrackView(this);
        mini.setCompact(true);
        mini.setInteractive(false);      // 不响应触摸：拖动交给外层窗口
        mini.setMap(map);
        mini.setEllipse(SimState.aMeters, SimState.bMeters, SimState.angleDeg);
        mini.setStartAngle(SimState.startAngleDeg);
        mapBox.addView(mini, new FrameLayout.LayoutParams(mapSize, mapSize));

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(Color.WHITE);
        close.setTextSize(13);
        android.graphics.drawable.GradientDrawable cbg = new android.graphics.drawable.GradientDrawable();
        cbg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        cbg.setColor(Color.parseColor("#CC1B2540"));
        close.setBackground(cbg);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> stopSelf());
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(24), dp(24));
        clp.gravity = Gravity.TOP | Gravity.END;
        clp.topMargin = dp(10);
        clp.rightMargin = dp(10);
        mapBox.addView(close, clp);
        column.addView(mapBox, new LinearLayout.LayoutParams(mapSize, mapSize));

        // ---- 下半：三个圆形速度按钮（总宽 3*32+2*12 = 120dp < 170dp）----
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);

        // 第 1 个圆钮：启动 / 停止回放
        playBtn = new TextView(this);
        playBtn.setTextSize(13);
        playBtn.setGravity(Gravity.CENTER);
        playBtn.setTypeface(playBtn.getTypeface(), Typeface.BOLD);
        playBtn.setOnClickListener(v -> togglePlayback());
        row.addView(playBtn, new LinearLayout.LayoutParams(btnSize, btnSize));

        String[] names = {"步", "跑", "骑"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView b = new TextView(this);
            b.setText(names[i]);
            b.setTextSize(13);
            b.setGravity(Gravity.CENTER);
            b.setTypeface(b.getTypeface(), Typeface.BOLD);
            b.setOnClickListener(v -> applySpeedMode(idx));
            speedBtns[i] = b;
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(btnSize, btnSize);
            blp.leftMargin = btnGap;          // 每个速度钮与左侧留 10dp 间距
            row.addView(b, blp);
        }
        column.addView(row, new LinearLayout.LayoutParams(mapSize, rowH));
        box.addView(column, new FrameLayout.LayoutParams(mapSize, mapSize + rowH));

        // 圆角描边（画在最上层，不拦触摸）
        View border = new View(this);
        android.graphics.drawable.GradientDrawable bd = new android.graphics.drawable.GradientDrawable();
        bd.setColor(0x00000000);
        bd.setCornerRadius(radius);
        bd.setStroke(dp(3), Color.parseColor("#2F52D9"));
        border.setBackground(bd);
        box.addView(border, new FrameLayout.LayoutParams(mapSize, mapSize + rowH));

        // 拖动移动窗口；轻点（没拖动）则打开主界面
        box.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - downX);
                        int dy = (int) (e.getRawY() - downY);
                        if (Math.abs(dx) > 6 || Math.abs(dy) > 6) {
                            moved = true;
                            lp.x = startX + dx;
                            lp.y = startY + dy;
                            if (wm != null && root != null) {
                                wm.updateViewLayout(root, lp);
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            openApp();
                        }
                        return true;
                    default:
                        return moved;
                }
            }
        });

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(
                mapSize, mapSize + rowH, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(16);
        lp.y = dp(120);

        root = box;
        try {
            wm.addView(root, lp);
        } catch (Exception e) {
            root = null;
            stopSelf();
            return;
        }
        initSpeedMode();
        updateSpeedButtons();
        lastRunningShown = SimState.running;
        updatePlayButton(SimState.running);
        handler.post(refresher);
    }

    /** 启动 / 停止回放（不用回到主界面）。 */
    private void togglePlayback() {
        boolean running = SimState.running;
        if (running) {
            startService(new Intent(this, PlayerService.class)
                    .setAction(PlayerService.ACTION_STOP));
            Toast.makeText(this, "已暂停（原地保留位置）", Toast.LENGTH_SHORT).show();
        } else {
            EllipseTrack.Params p = SimState.lastParams;
            if (p == null) {
                p = fallbackParams();     // 主界面还没跑过时，用共享状态兜底
            }
            // 先判断是不是续跑（startService 之后暂停标记会被消费掉，所以必须先算）
            boolean resume = PlayerService.willResume(p, SimState.realism);
            startService(PlayerService.playIntent(this, p, SimState.realism, true));
            Toast.makeText(this, resume ? "继续回放（接着上次位置）" : "开始回放",
                    Toast.LENGTH_SHORT).show();
        }
        lastRunningShown = !running;
        updatePlayButton(!running);       // 立即反馈，不等下一次刷新
    }

    /** 主界面从未启动过时，用共享状态拼一份参数。 */
    private EllipseTrack.Params fallbackParams() {
        EllipseTrack.Params p = new EllipseTrack.Params();
        double[] wgs = GeoTransform.gcj02ToWgs84(SimState.centerGcjLon, SimState.centerGcjLat);
        p.lon = wgs[0];
        p.lat = wgs[1];
        p.aMeters = SimState.aMeters;
        p.bMeters = SimState.bMeters;
        p.angleDeg = SimState.angleDeg;
        p.startAngleDeg = SimState.startAngleDeg;
        p.speedMps = SimState.speedMps;
        p.laps = SimState.laps;
        EllipseTrack.applyRealism(p, SimState.realism);
        return p;
    }

    /** 绿色=可启动，红色=可停止。 */
    private void updatePlayButton(boolean running) {
        if (playBtn == null) {
            return;
        }
        playBtn.setText(running ? "停" : "开");
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(Color.parseColor(running ? "#C0392B" : "#1E9E5A"));
        g.setStroke(dp(2), Color.parseColor(running ? "#E05A4A" : "#35C077"));
        playBtn.setBackground(g);
        playBtn.setTextColor(Color.WHITE);
    }

    /** 点击地图：打开拖跑主界面。 */
    private void openApp() {
        try {
            Intent it = new Intent(this, MainActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(it);
        } catch (Exception ignored) {
            // 打不开就算了
        }
    }

    /** 按当前倍速反推应该高亮哪一档。 */
    private void initSpeedMode() {
        double base = baseSpeed();
        double m = PlayerService.speedMultiplier;
        if (Math.abs(m - WALK_MPS / base) < 0.03) {
            speedMode = 0;
        } else if (Math.abs(m - BIKE_MPS / base) < 0.03) {
            speedMode = 2;
        } else {
            speedMode = 1;
        }
    }

    private double baseSpeed() {
        return SimState.speedMps > 0.2 ? SimState.speedMps : 2.7;
    }

    /** 切换速度档位：用实时倍速实现，不打断回放。 */
    private void applySpeedMode(int mode) {
        double base = baseSpeed();
        double target;
        String label;
        if (mode == 0) {
            target = WALK_MPS;
            label = String.format(java.util.Locale.CHINA, "步行 %.1f m/s（约 %.1f km/h）",
                    WALK_MPS, WALK_MPS * 3.6);
        } else if (mode == 2) {
            target = BIKE_MPS;
            label = String.format(java.util.Locale.CHINA, "骑行 %.1f m/s（18 km/h）",
                    BIKE_MPS);
        } else {
            target = base;
            label = String.format(java.util.Locale.CHINA, "跑步 %.1f m/s（默认速度）", base);
        }
        PlayerService.speedMultiplier = target / base;
        speedMode = mode;
        updateSpeedButtons();
        Toast.makeText(this, "速度档位：" + label, Toast.LENGTH_SHORT).show();
    }

    private void updateSpeedButtons() {
        for (int i = 0; i < speedBtns.length; i++) {
            TextView b = speedBtns[i];
            if (b == null) {
                continue;
            }
            boolean active = i == speedMode;
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(Color.parseColor(active ? "#2F52D9" : "#232E4C"));
            g.setStroke(dp(2), Color.parseColor("#3B4C77"));
            b.setBackground(g);
            b.setTextColor(active ? Color.WHITE : Color.parseColor("#AFBEE6"));
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(refresher);
        if (wm != null && root != null) {
            try {
                wm.removeView(root);
            } catch (Exception ignored) {
            }
        }
        root = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
