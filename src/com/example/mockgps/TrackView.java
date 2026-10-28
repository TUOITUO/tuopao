package com.example.mockgps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 地图式轨迹视图：
 *   - 真实地图底图（高德瓦片，见 {@link TileMap}）；
 *   - 椭圆跑道（长轴方位角：0 = 南北向，顺时针）；
 *   - 单指拖动空白处 = 平移地图（同时移动椭圆位置）；
 *   - 拖动 A 手柄 = 改方位角 + 长轴半径；拖动 B 手柄 = 改短轴半径；
 *   - 已跑轨迹 / 当前位置 / 圈数 HUD / 指北针 / 比例尺。
 */
public class TrackView extends View {

    /** 形状与中心变化回调。 */
    public interface OnShapeChanged {
        void onShapeChanged(double aMeters, double bMeters, double angleDeg);

        void onCenterChanged(double gcjLon, double gcjLat);

        /** 拖动"起"手柄：起点在椭圆上的参数角（度）。 */
        void onStartChanged(double startAngleDeg);
    }

    private OnShapeChanged listener;
    private TileMap map;

    // ---- 椭圆参数（中心 = 地图中心）----
    private double aMeters = 78;
    private double bMeters = 37;
    private double angleDeg = 0;      // 0 = 正南北，顺时针
    /** 起点在椭圆上的参数角（度）。 */
    private double startAngleDeg = 0;
    private boolean hasShape = false;
    /** 锁定：防误触，忽略一切触摸编辑。 */
    private boolean locked = false;
    /** 紧凑模式（悬浮窗用）：只画地图 + 椭圆 + 当前位置 + 圈数。 */
    private boolean compact = false;
    /** 是否响应触摸交互；悬浮窗里置 false，避免吃掉拖动事件导致窗口拖不动。 */
    private boolean interactive = true;

    // ---- 已跑轨迹（GCJ-02 绝对坐标，平移地图时不跟着动）----
    private final ArrayList<double[]> trail = new ArrayList<>();
    private double curLon, curLat;
    private boolean hasPos = false;

    // ---- HUD ----
    private int curLap = 0, totalLaps = 0;
    private String hudSpeed = "";
    private boolean running = false;
    private String mapStatus = "";
    /** 当前定位到的地点名（搜索/收藏选中后显示，作为"已跳转"的直观反馈）。 */
    private String centerLabel = "";

    // ---- 画笔 ----
    private final Paint pTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pRan = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCur = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pStart = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pHud = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pHudBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pHandle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pHandleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pNorth = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCenter = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pPlaceholder = TileMap.placeholderPaint();

    // ---- 触摸 ----
    private static final int MODE_NONE = 0, MODE_PAN = 1, MODE_A = 2, MODE_B = 3,
            MODE_S = 4;
    private int mode = MODE_NONE;
    private float lastX, lastY;
    public TrackView(Context context) {
        super(context);
        pTrack.setStyle(Paint.Style.STROKE);
        pTrack.setStrokeWidth(7);
        pTrack.setColor(0xFF1565FF);
        pTrack.setAlpha(200);
        pRan.setStyle(Paint.Style.STROKE);
        pRan.setStrokeWidth(9);
        pRan.setColor(0xFF00A651);
        pCur.setStyle(Paint.Style.FILL);
        pCur.setColor(0xFF1565FF);
        pStart.setStyle(Paint.Style.FILL);
        pStart.setColor(0xFF00A651);
        pHud.setColor(Color.WHITE);
        pHud.setTextSize(38);
        pHud.setFakeBoldText(true);
        pHudBg.setColor(0xAA101828);
        pHandle.setStyle(Paint.Style.STROKE);
        pHandle.setStrokeWidth(5);
        pHandle.setColor(0xFFFF6B00);
        pHandleFill.setStyle(Paint.Style.FILL);
        pHandleFill.setColor(0x55FF6B00);
        pNorth.setColor(0xFF34495E);
        pNorth.setTextSize(30);
        pNorth.setFakeBoldText(true);
        pCenter.setStyle(Paint.Style.STROKE);
        pCenter.setStrokeWidth(3);
        pCenter.setColor(0xCCFF1744);
        pPlaceholder.setColor(0xFFE8E4DA);
    }

    public void setListener(OnShapeChanged l) {
        this.listener = l;
    }

    public void setMap(TileMap m) {
        this.map = m;
        invalidate();
    }

    public void setEllipse(double a, double b, double angleDeg) {
        this.aMeters = Math.max(10, a);
        this.bMeters = Math.max(5, Math.min(b, this.aMeters));
        this.angleDeg = ((angleDeg % 360) + 360) % 360;
        this.hasShape = true;
        invalidate();
    }

    public double getA() {
        return aMeters;
    }

    public double getB() {
        return bMeters;
    }

    public double getAngleDeg() {
        return angleDeg;
    }

    public void setStartAngle(double deg) {
        this.startAngleDeg = ((deg % 360) + 360) % 360;
        invalidate();
    }

    public double getStartAngleDeg() {
        return startAngleDeg;
    }

    public void setLocked(boolean b) {
        this.locked = b;
        invalidate();
    }

    public boolean isLocked() {
        return locked;
    }

    public void setCompact(boolean b) {
        this.compact = b;
        invalidate();
    }

    public void setInteractive(boolean b) {
        this.interactive = b;
    }

    /** 追加一个已跑点（GCJ-02 绝对坐标）。 */
    public void addRanPointGcj(double lonGcj, double latGcj, boolean newLap) {
        if (newLap) {
            trail.add(null);      // 圈分界标记
        }
        trail.add(new double[]{lonGcj, latGcj});
        if (trail.size() > 6000) {
            trail.remove(0);
        }
        curLon = lonGcj;
        curLat = latGcj;
        hasPos = true;
        invalidate();
    }

    public void resetRan() {
        trail.clear();
        hasPos = false;
        invalidate();
    }

    /** 外部注入轨迹与当前位置（悬浮窗读共享状态用）。 */
    public void setExternalState(java.util.List<double[]> t, double lonGcj,
                                 double latGcj, boolean has) {
        trail.clear();
        if (t != null) {
            trail.addAll(t);
        }
        curLon = lonGcj;
        curLat = latGcj;
        hasPos = has;
    }

    public void setHud(int lap, int total, String speed) {
        this.curLap = lap;
        this.totalLaps = total;
        this.hudSpeed = speed == null ? "" : speed;
        this.running = total > 0;
        invalidate();
    }

    public void setMapStatus(String s) {
        this.mapStatus = s == null ? "" : s;
        invalidate();
    }

    /** 设置中心点标签（地名）；传空串清除。 */
    public void setCenterLabel(String s) {
        this.centerLabel = s == null ? "" : s;
        invalidate();
    }

    // ---- 几何换算 ----

    /** 长轴单位方向（0=北，顺时针）。 */
    private double[] axisU() {
        double t = Math.toRadians(angleDeg);
        return new double[]{Math.sin(t), Math.cos(t)};
    }

    /**
     * 短轴单位方向。必须与 {@link EllipseTrack} 的参数化一致：
     * 生成器里 t=90° 的点是 (-b·cosθ, b·sinθ)，因此短轴方向 = (-cosθ, sinθ)。
     * 若写成 (cosθ, -sinθ)，椭圆形状看不出差别（对称），但参数角映射会被镜像，
     * 导致"起点手柄画在一处、实际从另一处起跑"。
     */
    private double[] axisV() {
        double t = Math.toRadians(angleDeg);
        return new double[]{-Math.cos(t), Math.sin(t)};
    }

    /** 椭圆上参数角 t 处的相对中心米制坐标。 */
    private double[] ellipsePoint(double t) {
        double[] u = axisU();
        double[] v = axisV();
        double ca = aMeters * Math.cos(t);
        double sb = bMeters * Math.sin(t);
        return new double[]{u[0] * ca + v[0] * sb, u[1] * ca + v[1] * sb};
    }

    /** 相对中心的米制偏移 -> 屏幕像素。 */
    private float[] meterOffsetToScreen(double mx, double my) {
        float[] c = centerScreen();
        double mpp = map != null ? map.metersPerPixel() : 1.0;
        return new float[]{(float) (c[0] + mx / mpp), (float) (c[1] - my / mpp)};
    }

    private float[] centerScreen() {
        if (map == null) {
            return new float[]{getWidth() / 2f, getHeight() / 2f};
        }
        return map.lonLatToScreen(map.getCenterLon(), map.getCenterLat(),
                getWidth(), getHeight());
    }

    private float[] handleA() {
        double[] u = axisU();
        return meterOffsetToScreen(u[0] * aMeters, u[1] * aMeters);
    }

    private float[] handleB() {
        double[] v = axisV();
        return meterOffsetToScreen(v[0] * bMeters, v[1] * bMeters);
    }

    /** 起点手柄（"起"）：位于椭圆上参数角 startAngleDeg 处。 */
    private float[] handleS() {
        double[] p = ellipsePoint(Math.toRadians(startAngleDeg));
        return meterOffsetToScreen(p[0], p[1]);
    }

    // ---- 绘制 ----

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        c.drawColor(0xFF0B1018);

        // 1) 地图底图（略压暗，融入深色界面）
        if (map != null) {
            map.draw(c, w, h, pPlaceholder);
            pDim.setColor(0x1A0B1018);
            c.drawRect(0, 0, w, h, pDim);
        }

        // 2) 椭圆跑道
        if (hasShape && map != null) {
            drawEllipse(c);
            drawStartMarker(c);
            if (!compact) {
                drawHandles(c);
                drawCenterLabel(c);
            }
        }

        // 3) 已跑轨迹 + 当前位置
        drawTrail(c);
        drawCurrent(c);

        // 4) HUD
        if (compact) {
            drawMiniHud(c);
        } else {
            drawCompass(c);
            drawScaleBar(c);
            drawHud(c);
        }
        if (locked) {
            Paint lk = new Paint(pHud);
            lk.setTextSize(26);
            lk.setColor(0xFFFFC107);
            lk.setFakeBoldText(true);
            c.drawRoundRect(new android.graphics.RectF(
                    getWidth() - 190, 20, getWidth() - 20, 74), 12, 12, pHudBg);
            c.drawText("已锁定", getWidth() - 176, 58, lk);
        }
    }

    /** 起点标记（绿色圆点 + "起"）。 */
    private void drawStartMarker(Canvas c) {
        float[] sp = handleS();
        Paint halo = new Paint(pStart);
        halo.setColor(0xFFFFFFFF);
        c.drawCircle(sp[0], sp[1], 20, halo);
        c.drawCircle(sp[0], sp[1], 15, pStart);
        Paint t = new Paint(pHud);
        t.setTextSize(24);
        t.setFakeBoldText(true);
        t.setColor(0xFF0B1018);
        c.drawText("起", sp[0] - 12, sp[1] + 8, t);
    }

    /** 紧凑 HUD（悬浮窗）：只显示圈数与速度。 */
    private void drawMiniHud(Canvas c) {
        String hud = "第" + Math.max(curLap, 1) + "/" + Math.max(totalLaps, 1) + "圈";
        if (!hudSpeed.isEmpty()) {
            hud += " " + hudSpeed;
        }
        Paint t = new Paint(pHud);
        t.setTextSize(26);
        float tw = t.measureText(hud);
        c.drawRoundRect(new android.graphics.RectF(10, 10, 30 + tw, 56), 10, 10, pHudBg);
        c.drawText(hud, 22, 43, t);
    }

    private void drawEllipse(Canvas c) {
        Path p = new Path();
        int n = 240;
        double[] u = axisU();
        double[] v = axisV();
        for (int i = 0; i <= n; i++) {
            double t = 2 * Math.PI * i / n;
            double ca = aMeters * Math.cos(t);
            double sb = bMeters * Math.sin(t);
            double mx = u[0] * ca + v[0] * sb;
            double my = u[1] * ca + v[1] * sb;
            float[] s = meterOffsetToScreen(mx, my);
            if (i == 0) {
                p.moveTo(s[0], s[1]);
            } else {
                p.lineTo(s[0], s[1]);
            }
        }
        p.close();
        // 白色描边打底，保证在浅色地图上也清晰
        Paint halo = new Paint(pTrack);
        halo.setColor(0xFFFFFFFF);
        halo.setStrokeWidth(13);
        halo.setAlpha(200);
        c.drawPath(p, halo);
        c.drawPath(p, pTrack);

        // 长轴（焦点连线）虚线 + 中心十字
        Paint ax = new Paint(pTrack);
        ax.setStrokeWidth(4);
        ax.setAlpha(150);
        ax.setPathEffect(new DashPathEffect(new float[]{14, 12}, 0));
        float[] a1 = meterOffsetToScreen(-u[0] * aMeters, -u[1] * aMeters);
        float[] a2 = meterOffsetToScreen(u[0] * aMeters, u[1] * aMeters);
        c.drawLine(a1[0], a1[1], a2[0], a2[1], ax);

        float[] cen = centerScreen();
        c.drawLine(cen[0] - 18, cen[1], cen[0] + 18, cen[1], pCenter);
        c.drawLine(cen[0], cen[1] - 18, cen[0], cen[1] + 18, pCenter);
        c.drawCircle(cen[0], cen[1], 6, pCenter);
    }

    /** 中心点地名标签（搜索/收藏定位后的直观反馈）。 */
    private void drawCenterLabel(Canvas c) {
        if (centerLabel.isEmpty()) {
            return;
        }
        float[] cen = centerScreen();
        Paint t = new Paint(pHud);
        t.setTextSize(28);
        t.setFakeBoldText(true);
        float tw = t.measureText(centerLabel);
        float left = cen[0] - tw / 2 - 16;
        float top = cen[1] - 96;
        Paint bg = new Paint(pHudBg);
        bg.setColor(0xE01565FF);
        c.drawRoundRect(new android.graphics.RectF(left, top, left + tw + 32, top + 52),
                14, 14, bg);
        c.drawText(centerLabel, left + 16, top + 36, t);
        Paint line = new Paint(pCur);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(3);
        line.setColor(0xE01565FF);
        c.drawLine(cen[0], top + 52, cen[0], cen[1] - 20, line);
    }

    private void drawHandles(Canvas c) {
        float[] a = handleA();
        float[] b = handleB();
        c.drawCircle(a[0], a[1], 30, pHandleFill);
        c.drawCircle(a[0], a[1], 30, pHandle);
        c.drawCircle(b[0], b[1], 26, pHandleFill);
        c.drawCircle(b[0], b[1], 26, pHandle);
        Paint t = new Paint(pHandle);
        t.setStyle(Paint.Style.FILL);
        t.setTextSize(26);
        t.setFakeBoldText(true);
        c.drawText("A", a[0] + 34, a[1] + 9, t);
        c.drawText("B", b[0] + 30, b[1] + 9, t);
        // 起点手柄（绿色）
        float[] sp = handleS();
        Paint pS = new Paint(pHandle);
        pS.setColor(0xFF00A651);
        Paint pSFill = new Paint(pHandleFill);
        pSFill.setColor(0x5500A651);
        c.drawCircle(sp[0], sp[1], 26, pSFill);
        c.drawCircle(sp[0], sp[1], 26, pS);
        Paint tS = new Paint(pS);
        tS.setStyle(Paint.Style.FILL);
        tS.setTextSize(24);
        tS.setFakeBoldText(true);
        c.drawText("起", sp[0] + 30, sp[1] + 9, tS);
    }

    private void drawTrail(Canvas c) {
        if (map == null || trail.isEmpty()) {
            return;
        }
        Path p = new Path();
        boolean started = false;
        for (double[] pt : trail) {
            if (pt == null) {            // 圈分界：断开重画
                started = false;
                continue;
            }
            float[] s = map.lonLatToScreen(pt[0], pt[1], getWidth(), getHeight());
            if (!started) {
                p.moveTo(s[0], s[1]);
                started = true;
            } else {
                p.lineTo(s[0], s[1]);
            }
        }
        Paint halo = new Paint(pRan);
        halo.setColor(0xFFFFFFFF);
        halo.setStrokeWidth(15);
        halo.setAlpha(180);
        c.drawPath(p, halo);
        c.drawPath(p, pRan);
    }

    private void drawCurrent(Canvas c) {
        if (map == null || !hasPos) {
            return;
        }
        float[] s = map.lonLatToScreen(curLon, curLat, getWidth(), getHeight());
        Paint glow = new Paint(pCur);
        glow.setAlpha(70);
        c.drawCircle(s[0], s[1], 34, glow);
        c.drawCircle(s[0], s[1], 16, pCur);
        Paint ring = new Paint(pCur);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(4);
        ring.setColor(Color.WHITE);
        c.drawCircle(s[0], s[1], 16, ring);
    }

    private void drawCompass(Canvas c) {
        float x = getWidth() - 54, y = 60;
        Paint bg = new Paint(pHudBg);
        bg.setAlpha(0xCC);
        c.drawCircle(x, y, 40, bg);
        Paint a = new Paint(pNorth);
        a.setStyle(Paint.Style.STROKE);
        a.setStrokeWidth(4);
        a.setColor(0xFFE53935);
        c.drawLine(x, y + 22, x, y - 24, a);
        c.drawLine(x, y - 24, x - 10, y - 8, a);
        c.drawLine(x, y - 24, x + 10, y - 8, a);
        Paint tt = new Paint(pNorth);
        tt.setTextSize(24);
        tt.setFakeBoldText(true);
        c.drawText("N", x - 9, y + 40, tt);
    }

    private void drawScaleBar(Canvas c) {
        if (map == null) {
            return;
        }
        double mpp = map.metersPerPixel();
        double meters = 50;
        if (50 / mpp < 70) {
            meters = 100;
        }
        if (100 / mpp < 70) {
            meters = 200;
        }
        float len = (float) (meters / mpp);
        float x = 34;

        // ---- 最底层：比例尺（距底 14px）----
        float y = getHeight() - 40;
        Paint p = new Paint(pHud);
        p.setTextSize(26);
        p.setFakeBoldText(false);
        Paint bg = new Paint(pHudBg);
        bg.setAlpha(0x99);
        c.drawRoundRect(new android.graphics.RectF(x - 12, y - 30,
                x + len + 96, y + 22), 10, 10, bg);
        c.drawLine(x, y, x + len, y, p);
        c.drawLine(x, y - 10, x, y + 10, p);
        c.drawLine(x + len, y - 10, x + len, y + 10, p);
        c.drawText(((int) meters) + " m", x + len + 14, y + 9, p);

        // ---- 上一段：缩放级别 + 瓦片加载状态（与比例尺留 8px 间隙）----
        Paint sm = new Paint(pHud);
        sm.setTextSize(24);
        sm.setFakeBoldText(false);
        String st = "Z" + map.getZoom() + "  瓦片 ✓" + map.okCount
                + (map.pendingCount > 0 ? " ⏳" + map.pendingCount : "")
                + (map.failCount > 0 ? " ✗" + map.failCount : "");
        float stw = sm.measureText(st);
        float zBottom = y - 30 - 8;              // 比例尺框上沿再留 8px
        c.drawRoundRect(new android.graphics.RectF(x - 12, zBottom - 34,
                x + stw + 20, zBottom), 10, 10, bg);
        c.drawText(st, x, zBottom - 8, sm);
    }

    private void drawHud(Canvas c) {
        String hud = "第 " + Math.max(curLap, 1) + " / " + Math.max(totalLaps, 1) + " 圈";
        if (!hudSpeed.isEmpty()) {
            hud += "   " + hudSpeed;
        }
        float tw = pHud.measureText(hud);
        c.drawRoundRect(new android.graphics.RectF(20, 20, 48 + tw, 88), 14, 14, pHudBg);
        c.drawText(hud, 40, 68, pHud);

        Paint small = new Paint(pHud);
        small.setTextSize(25);
        small.setFakeBoldText(false);
        small.setColor(0xFFE8EAF0);
        // 当前中心（BD-09，与输入框一致）：搜索/收藏定位后这里的数字会立刻变化
        double[] bd = GeoTransform.gcj02ToBd09(map.getCenterLon(), map.getCenterLat());
        String info = String.format(Locale.CHINA,
                "a=%.0f  b=%.0f  方位角=%.0f°   中心BD-09 %.6f, %.6f",
                aMeters, bMeters, angleDeg, bd[0], bd[1]);
        float iw = small.measureText(info);
        // 与下方 Z 状态框留 8px 间隙（Z 框上沿 = 比例尺 y - 30 - 8 - 34）
        float infoBottom = getHeight() - 40 - 30 - 8 - 34 - 8;
        c.drawRoundRect(new android.graphics.RectF(20, infoBottom - 46,
                44 + iw, infoBottom), 12, 12, pHudBg);
        c.drawText(info, 34, infoBottom - 14, small);
    }

    // ---- 触摸：拖动地图 / 拖动手柄 ----

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (map == null || locked) {
            return false;      // 锁定后不响应任何编辑手势（页面可正常滚动）
        }
        if (!interactive) {
            return false;      // 悬浮窗：不拦截触摸，交给外层拖动窗口
        }
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                lastX = x;
                lastY = y;
                float[] a = handleA();
                float[] b = handleB();
                float[] sp = handleS();
                double da = Math.hypot(x - a[0], y - a[1]);
                double db = Math.hypot(x - b[0], y - b[1]);
                double ds = Math.hypot(x - sp[0], y - sp[1]);
                if (ds <= 70 && ds <= da && ds <= db) {
                    mode = MODE_S;
                } else if (da <= 80 && da <= db) {
                    mode = MODE_A;
                } else if (db <= 80) {
                    mode = MODE_B;
                } else {
                    mode = MODE_PAN;
                }
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mode == MODE_PAN) {
                    float dx = x - lastX, dy = y - lastY;
                    if (Math.abs(dx) > 0.5 || Math.abs(dy) > 0.5) {
                        map.panByPixels(dx, dy);
                        if (listener != null) {
                            listener.onCenterChanged(map.getCenterLon(), map.getCenterLat());
                        }
                    }
                }
                if (mode == MODE_S) {
                    float[] cen = centerScreen();
                    double mpp = map.metersPerPixel();
                    double mx = (x - cen[0]) * mpp;
                    double my = -(y - cen[1]) * mpp;
                    double[] u = axisU();
                    double[] v = axisV();
                    double ca = mx * u[0] + my * u[1];
                    double sb = mx * v[0] + my * v[1];
                    double t = Math.toDegrees(Math.atan2(sb / bMeters, ca / aMeters));
                    startAngleDeg = ((t % 360) + 360) % 360;
                    if (listener != null) {
                        listener.onStartChanged(startAngleDeg);
                    }
                } else if (mode == MODE_A || mode == MODE_B) {
                    // 触摸点相对中心的米制偏移（屏幕 y 向下 -> 北向上）
                    float[] cen = centerScreen();
                    double mpp = map.metersPerPixel();
                    double mx = (x - cen[0]) * mpp;
                    double my = -(y - cen[1]) * mpp;
                    if (mode == MODE_A) {
                        double a = Math.hypot(mx, my);
                        a = Math.max(15, Math.min(5000, a));
                        double ang = Math.toDegrees(Math.atan2(mx, my));
                        ang = ((ang % 360) + 360) % 360;
                        aMeters = a;
                        angleDeg = ang;
                        if (bMeters > aMeters) {
                            bMeters = aMeters;
                        }
                    } else {
                        double[] v = axisV();
                        double bb = Math.abs(mx * v[0] + my * v[1]);
                        bMeters = Math.max(5, Math.min(aMeters, bb));
                    }
                    if (listener != null) {
                        listener.onShapeChanged(aMeters, bMeters, angleDeg);
                    }
                }
                lastX = x;
                lastY = y;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mode != MODE_NONE) {
                    mode = MODE_NONE;
                    if (listener != null) {
                        listener.onShapeChanged(aMeters, bMeters, angleDeg);
                        listener.onStartChanged(startAngleDeg);
                        listener.onCenterChanged(map.getCenterLon(), map.getCenterLat());
                    }
                    invalidate();
                }
                return true;
            }
            default:
                return false;
        }
    }
}
