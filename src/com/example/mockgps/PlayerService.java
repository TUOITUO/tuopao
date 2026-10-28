package com.example.mockgps;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.util.List;

/**
 * 轨迹回放前台服务：
 *   - 参数化生成椭圆跑道轨迹（或播放 assets 内置轨迹作为兜底）；
 *   - 按时间戳逐点向 GPS_TEST_PROVIDER 注入虚拟位置；
 *   - 通过广播把进度（当前点/圈数/经纬度/速度）发给主界面做可视化；
 *   - 播完自动循环。
 */
public class PlayerService extends Service {

    public static final String ACTION_PLAY = "com.example.mockgps.PLAY";
    public static final String ACTION_STOP = "com.example.mockgps.STOP";
    /** 进度广播（主界面接收）。 */
    public static final String ACTION_PROGRESS = "com.example.mockgps.PROGRESS";
    public static final String EXTRA_LAP = "lap";
    public static final String EXTRA_LON = "lon";
    public static final String EXTRA_LAT = "lat";
    public static final String EXTRA_SPEED = "speed";
    public static final String EXTRA_T = "t";
    public static final String EXTRA_TOTAL_LAPS = "total_laps";
    /** 生成的轨迹形状（给 TrackView 画跑道，JSON 数组 [x1,y1,x2,y2,...] 米制局部坐标）。 */
    public static final String ACTION_SHAPE = "com.example.mockgps.SHAPE";
    public static final String EXTRA_SHAPE_JSON = "shape";

    private static final String CHANNEL_ID = "mockgps_play";
    private static final String TAG = "MockGPS";

    private static volatile boolean running = false;
    /** 播放代次：每次开始/停止都自增，旧线程据此自动退出（防"重新开始"时双线程注入）。 */
    private static volatile int epoch = 0;
    /** 播放倍速（1.0 = 实际速度；可在播放中实时调整）。 */
    public static volatile double speedMultiplier = 1.0;

    private Thread worker;
    private LocationManager lm;

    public static boolean isRunning() {
        return running;
    }

    static class P {
        double lon, lat, t, v;
        int lap;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "轨迹回放", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(act)) {
            stopPlayback();
            return START_NOT_STICKY;
        }
        if (ACTION_PLAY.equals(act)) {
            EllipseTrack.Params params = null;
            if (intent.hasExtra("lon")) {
                params = new EllipseTrack.Params();
                params.lon = intent.getDoubleExtra("lon", 0);
                params.lat = intent.getDoubleExtra("lat", 0);
                params.aMeters = intent.getDoubleExtra("a", 78);
                params.bMeters = intent.getDoubleExtra("b", 37);
                params.angleDeg = intent.getDoubleExtra("angle", 0);
                params.speedMps = intent.getDoubleExtra("speed", 2.7);
                params.laps = intent.getIntExtra("laps", 5);
                params.ccw = intent.getBooleanExtra("ccw", true);
                params.startAngleDeg = intent.getDoubleExtra("start_angle", 0);
                // 真实度档位（此前漏传，导致 平滑/颠簸/狂野 三档实际无效）
                EllipseTrack.applyRealism(params, intent.getIntExtra("realism", 1));
            }
            boolean resume = intent.getBooleanExtra("resume", true);
            String sig = intent.getStringExtra("sig");
            int realism = intent.getIntExtra("realism", 1);
            // 已在播放时先停旧线程，再起新线程
            if (running) {
                stopPlayback();
            }
            startPlayback(params, sig, resume);
            return START_STICKY;
        }
        return START_NOT_STICKY;
    }

    /** 统一的"开始回放"Intent 构造：主界面与悬浮窗共用，保证参数不漏传。 */
    public static Intent playIntent(Context ctx, EllipseTrack.Params p, int realism,
                                    boolean resume) {
        return new Intent(ctx, PlayerService.class)
                .setAction(ACTION_PLAY)
                .putExtra("lon", p.lon)
                .putExtra("lat", p.lat)
                .putExtra("a", p.aMeters)
                .putExtra("b", p.bMeters)
                .putExtra("angle", p.angleDeg)
                .putExtra("speed", p.speedMps)
                .putExtra("laps", p.laps)
                .putExtra("start_angle", p.startAngleDeg)
                .putExtra("ccw", p.ccw)
                .putExtra("realism", realism)
                .putExtra("resume", resume)
                .putExtra("sig", signature(p, realism));
    }

    /** 参数指纹：只要它没变，"开始"就是接着上次暂停的位置续跑。 */
    public static String signature(EllipseTrack.Params p, int realism) {
        return String.format(java.util.Locale.US,
                "%.7f|%.7f|%.3f|%.3f|%.3f|%.3f|%.3f|%d|%b|%d",
                p.lon, p.lat, p.aMeters, p.bMeters, p.angleDeg,
                p.startAngleDeg, p.speedMps, p.laps, p.ccw, realism);
    }

    /** 这次"开始"会不会是续跑（供界面决定要不要清轨迹）。 */
    public static boolean willResume(EllipseTrack.Params p, int realism) {
        return SimState.pausedVt >= 0
                && signature(p, realism).equals(SimState.pausedSig);
    }

    private synchronized void startPlayback(EllipseTrack.Params params, String sig,
                                            boolean resume) {
        if (running) {
            return;
        }
        // 续跑判定：参数没变 + 有暂停进度
        double startVt = 0;
        boolean doResume = resume && sig != null && sig.equals(SimState.pausedSig)
                && SimState.pausedVt >= 0;
        if (doResume) {
            startVt = SimState.pausedVt;
        } else {
            SimState.clearTrail();      // 全新一轮才清轨迹
        }
        SimState.pausedVt = -1;
        SimState.pausedSig = null;
        curSig = sig;
        final double fStartVt = startVt;
        Notification n;
        if (Build.VERSION.SDK_INT >= 26) {
            n = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("MockGPS 轨迹回放中")
                    .setContentText("虚拟位置按轨迹逐点注入")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .build();
        } else {
            //noinspection deprecation
            n = new Notification.Builder(this)
                    .setContentTitle("MockGPS 轨迹回放中")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .build();
        }
        startForeground(1, n);
        epoch++;
        final int myEpoch = epoch;
        running = true;
        SimState.running = true;
        SimState.curVt = fStartVt;
        SimState.totalLaps = params != null ? params.laps : 5;
        final EllipseTrack.Params fp = params;
        worker = new Thread(() -> playLoop(fp, myEpoch, fStartVt), "mockgps-player");
        worker.start();
        Log.i(TAG, "playback started");
    }

    /** 把错误原因显示到前台通知上（防止服务静默停止、用户不知道为什么没效果）。 */
    private void notifyError(String reason) {
        try {
            Notification n;
            if (Build.VERSION.SDK_INT >= 26) {
                n = new Notification.Builder(this, CHANNEL_ID)
                        .setContentTitle("拖跑 · 未生效")
                        .setContentText(reason)
                        .setStyle(new Notification.BigTextStyle().bigText(reason))
                        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                        .build();
            } else {
                //noinspection deprecation
                n = new Notification.Builder(this)
                        .setContentTitle("拖跑 · 未生效")
                        .setContentText(reason)
                        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                        .build();
            }
            startForeground(1, n);
        } catch (Exception ignored) {
            // 通知失败不影响日志
        }
    }

    private void stopPlayback() {
        // 就地暂停：把当前进度存下来，下次"开始"从这里续跑（而不是回到起点）
        SimState.pausedVt = SimState.curVt;
        SimState.pausedSig = curSig;
        running = false;
        epoch++;                  // 使当前工作线程失效
        SimState.running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        stopForeground(true);
        stopSelf();
        Log.i(TAG, "playback stopped");
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 形状 JSON 传递字段（跨方法的小技巧）。 */
    private final String[] shapeJsonRef = new String[1];
    /** 本次回放的参数指纹（停止时连同进度一起存起来）。 */
    private volatile String curSig = null;

    /** 加载轨迹：参数化生成优先，失败回退 assets 内置轨迹。 */
    private List<P> loadTrack(EllipseTrack.Params params, boolean[] usedParam) {
        shapeJsonRef[0] = null;
        if (params != null) {
            try {
                List<EllipseTrack.Point> gen = EllipseTrack.generate(params);
                List<P> out = new java.util.ArrayList<>(gen.size());
                // 跑道形状（局部平面米），供 TrackView 绘制
                double th = Math.toRadians(params.angleDeg);
                StringBuilder sb = new StringBuilder("[");
                int shapeN = 240;
                for (int i = 0; i <= shapeN; i++) {
                    double tt = 2 * Math.PI * i / shapeN;
                    double x0 = params.aMeters * Math.cos(tt);
                    double y0 = params.bMeters * Math.sin(tt);
                    double xr, yr;
                    if (params.ccw) {
                        xr = x0 * Math.cos(th) - y0 * Math.sin(th);
                        yr = x0 * Math.sin(th) + y0 * Math.cos(th);
                    } else {
                        xr = x0 * Math.cos(th) + y0 * Math.sin(th);
                        yr = -x0 * Math.sin(th) + y0 * Math.cos(th);
                    }
                    if (i > 0) sb.append(',');
                    sb.append(xr).append(',').append(yr);
                }
                sb.append(']');
                shapeJsonRef[0] = sb.toString();

                for (EllipseTrack.Point g : gen) {
                    P p = new P();
                    p.lon = g.lon;
                    p.lat = g.lat;
                    p.t = g.t;
                    p.v = g.v;
                    p.lap = g.lap;
                    out.add(p);
                }
                usedParam[0] = true;
                return out;
            } catch (Exception e) {
                Log.e(TAG, "parametric generate failed, fallback to assets: " + e);
            }
        }
        // 回退：assets 内置轨迹
        try {
            java.io.InputStream in = getAssets().open("track.json");
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
            in.close();
            JSONObject doc = new JSONObject(buf.toString("UTF-8"));
            org.json.JSONArray arr = doc.getJSONArray("points");
            List<P> out = new java.util.ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                P p = new P();
                p.lon = o.getDouble("lon");
                p.lat = o.getDouble("lat");
                p.t = o.optDouble("t", i);
                p.v = o.optDouble("speed_mps", 0);
                p.lap = o.optInt("lap", 1);
                out.add(p);
            }
            usedParam[0] = false;
            return out;
        } catch (Exception e) {
            Log.e(TAG, "load assets track failed: " + e);
            return null;
        }
    }

    private void playLoop(EllipseTrack.Params params, int myEpoch, double startVt) {
        boolean[] usedParam = new boolean[1];
        List<P> pts = loadTrack(params, usedParam);
        if (pts == null || pts.isEmpty()) {
            stopPlayback();
            return;
        }
        int totalLaps = params != null ? params.laps : 5;
        // 同步椭圆参数到共享状态（悬浮窗据此绘制，即使主界面已退到后台）
        if (params != null) {
            double[] cGcj = GeoTransform.wgs84ToGcj02(params.lon, params.lat);
            SimState.centerGcjLon = cGcj[0];
            SimState.centerGcjLat = cGcj[1];
            SimState.aMeters = params.aMeters;
            SimState.bMeters = params.bMeters;
            SimState.angleDeg = params.angleDeg;
            SimState.startAngleDeg = params.startAngleDeg;
            SimState.laps = params.laps;
            SimState.speedMps = params.speedMps;
        }

        // 广播形状（参数化时）
        if (shapeJsonRef[0] != null) {
            Intent si = new Intent(ACTION_SHAPE);
            si.setPackage(getPackageName());
            si.putExtra(EXTRA_SHAPE_JSON, shapeJsonRef[0]);
            sendBroadcast(si);
        }
        // 广播起点（HUD 初始状态）
        Intent hi = new Intent(ACTION_PROGRESS);
        hi.setPackage(getPackageName());
        hi.putExtra(EXTRA_TOTAL_LAPS, totalLaps);
        hi.putExtra(EXTRA_LAP, 0);
        hi.putExtra(EXTRA_LON, pts.get(0).lon);
        hi.putExtra(EXTRA_LAT, pts.get(0).lat);
        hi.putExtra(EXTRA_SPEED, 0.0);
        sendBroadcast(hi);

        try {
            ensureTestProvider();
        } catch (SecurityException e) {
            Log.e(TAG, "mock location not granted (select app in developer options): " + e);
            notifyError("虚拟位置未生效：请在 设置→开发者选项→选择模拟位置应用 里选中「拖跑」");
            stopPlayback();
            return;
        }

        while (running && epoch == myEpoch) {
            // 虚拟时钟：按 speedMultiplier 推进，支持播放中实时改倍速/自定义速度。
            double totalT = pts.get(pts.size() - 1).t;
            if (totalT <= 0) {
                totalT = 1;
            }
            double vt = startVt;          // 续跑时从暂停处接着走
            int idx = -1;
            int lastSimLap = 0;
            if (startVt > 0) {
                // 续跑：让圈分界与当前位置对齐，避免轨迹上多出一道断口
                int j = 0;
                while (j + 1 < pts.size() && pts.get(j + 1).t <= startVt) {
                    j++;
                }
                lastSimLap = pts.get(j).lap;
            }
            long lastReal = SystemClock.elapsedRealtime();
            while (running && epoch == myEpoch) {
                long now = SystemClock.elapsedRealtime();
                double dtReal = (now - lastReal) / 1000.0;
                lastReal = now;
                vt += dtReal * speedMultiplier;
                SimState.curVt = vt;
                boolean wrapped = false;
                while (vt >= totalT) {
                    vt -= totalT;
                    wrapped = true;
                }
                if (wrapped) {
                    idx = -1;    // 新一圈：重新定位
                }
                // 前进到 vt 对应的点（时间严格递增，线性推进）
                int next = idx + 1;
                while (next < pts.size() && pts.get(next).t <= vt) {
                    next++;
                }
                next = Math.min(next - 1, pts.size() - 1);
                if (next < 0) {
                    next = 0;
                }
                if (next != idx) {
                    idx = next;
                    P p = pts.get(idx);
                    inject(p);
                    Intent pi = new Intent(ACTION_PROGRESS);
                    pi.setPackage(getPackageName());
                    pi.putExtra(EXTRA_LAP, p.lap);
                    pi.putExtra(EXTRA_LON, p.lon);
                    pi.putExtra(EXTRA_LAT, p.lat);
                    pi.putExtra(EXTRA_SPEED, p.v * speedMultiplier);
                    pi.putExtra(EXTRA_T, vt);
                    pi.putExtra(EXTRA_TOTAL_LAPS, totalLaps);
                    sendBroadcast(pi);
                    // 同步到进程内共享状态（悬浮窗在 Activity 不可见时也能刷新）
                    double[] gcj = GeoTransform.wgs84ToGcj02(p.lon, p.lat);
                    SimState.hasPos = true;
                    SimState.curGcjLon = gcj[0];
                    SimState.curGcjLat = gcj[1];
                    SimState.lap = p.lap;
                    SimState.totalLaps = totalLaps;
                    SimState.curSpeed = p.v * speedMultiplier;
                    SimState.progress = totalT > 0 ? (vt % totalT) / totalT : 0;
                    SimState.running = true;
                    SimState.addTrail(gcj[0], gcj[1], p.lap != lastSimLap);
                    lastSimLap = p.lap;
                }
                try {
                    Thread.sleep(40);
                } catch (InterruptedException e) {
                    return;
                }
            }
            Log.i(TAG, "playback finished");
        }
    }

    private void ensureTestProvider() {
        // 三个 provider 都要接管：
        //   GPS     —— 主轨迹（跑步 App 多数读这个）
        //   NETWORK —— 步道乐跑等用高德/百度融合定位时并行读网络定位，
        //              只 mock GPS 的话融合结果会被真实网络位置拉回去
        //   fused   —— Android 12+ 的系统融合 provider
        addOne(LocationManager.GPS_PROVIDER);
        lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
        addOne(LocationManager.NETWORK_PROVIDER);
        lm.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER, true);
        if (Build.VERSION.SDK_INT >= 31) {
            addOne("fused");
            try {
                lm.setTestProviderEnabled("fused", true);
            } catch (Exception ignored) {
            }
        }
    }

    /** 注册一个测试提供者（已存在则忽略；未授予 mock 权限会抛 SecurityException，必须上抛）。 */
    private void addOne(String provider) {
        try {
            lm.addTestProvider(provider,
                    false, false, false, false,
                    true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
        } catch (IllegalArgumentException ignored) {
            // provider 已存在
        }
    }

    private double lastLon, lastLat;
    private boolean hasLast;
    private float lastBearing;
    private int injectTick;

    private void inject(P p) {
        long nowMs = System.currentTimeMillis();
        long nowNanos = SystemClock.elapsedRealtimeNanos();

        // 航向：由相邻点差分（跑步 App 会校验 speed/bearing 是否与位移一致）
        if (hasLast) {
            double dx = (p.lon - lastLon) * 111320.0 * Math.cos(Math.toRadians(p.lat));
            double dy = (p.lat - lastLat) * 110540.0;
            if (Math.hypot(dx, dy) > 0.4) {
                float bearing = (float) Math.toDegrees(Math.atan2(dx, dy));
                lastBearing = (lastBearing + bearing) / 2f;   // 平滑防跳
            }
        }
        lastLon = p.lon;
        lastLat = p.lat;
        hasLast = true;
        injectTick++;

        // accuracy 围绕 3~6 米抖动（恒定 3.5 米太"完美"，易被合理性校验打分）
        float accGps = 3.0f + (float) ((Math.sin(injectTick * 1.7) + 1) * 1.6f);
        Location loc = new Location(LocationManager.GPS_PROVIDER);
        loc.setLongitude(p.lon);
        loc.setLatitude(p.lat);
        loc.setAccuracy(accGps);
        loc.setVerticalAccuracyMeters(5f + (injectTick % 3));
        loc.setSpeed((float) Math.max(0, p.v));
        loc.setBearing(lastBearing);
        loc.setAltitude(3.2 + (injectTick % 5) * 0.3);
        // 卫星数：很多跑步 App 丢弃"卫星数为 0"的 GPS 样本，mock 不带 extras 就会被当无效帧
        android.os.Bundle ex = new android.os.Bundle();
        ex.putInt("satellites", 8 + (injectTick % 4));
        ex.putInt("maxsatellites", 32);
        loc.setExtras(ex);
        loc.setTime(nowMs);
        loc.setElapsedRealtimeNanos(nowNanos);

        boolean ok = true;
        try {
            lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc);
        } catch (Exception e) {
            Log.e(TAG, "inject gps failed: " + e);
            ok = false;
        }

        // 网络定位：同一位置、更大的精度圆（融合定位看到的也全是虚拟位置）
        Location net = new Location(LocationManager.NETWORK_PROVIDER);
        net.setLongitude(p.lon);
        net.setLatitude(p.lat);
        net.setAccuracy(18f + (injectTick % 5) * 3f);
        net.setSpeed(loc.getSpeed());
        net.setBearing(lastBearing);
        net.setTime(nowMs);
        net.setElapsedRealtimeNanos(nowNanos);
        try {
            lm.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, net);
        } catch (Exception e) {
            Log.e(TAG, "inject network failed: " + e);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                Location fused = new Location("fused");
                fused.set(loc);
                fused.setAccuracy(accGps + 2f);
                lm.setTestProviderLocation("fused", fused);
            } catch (Exception ignored) {
                // fused 不可 mock 就算了，GPS+NETWORK 已覆盖主要通路
            }
        }
        if (!ok) {
            stopPlayback();
        }
    }
}
