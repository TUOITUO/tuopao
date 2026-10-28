package com.example.mockgps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 进程内共享的模拟状态：主界面、播放服务、悬浮窗都读写这里。
 * 用静态字段是为了让悬浮窗（另一个组件）在 Activity 不可见时也能持续刷新。
 */
public final class SimState {

    private SimState() {
    }

    // ---- 椭圆参数（GCJ-02 中心 + 形状）----
    public static volatile double centerGcjLon = 119.930428;
    public static volatile double centerGcjLat = 31.870122;
    public static volatile double aMeters = 78;
    public static volatile double bMeters = 37;
    public static volatile double angleDeg = 0;     // 长轴方位角：0=南北
    public static volatile double startAngleDeg = 0; // 起点在椭圆上的参数角
    public static volatile int laps = 5;
    public static volatile double speedMps = 2.7;

    // ---- 最近一次回放用的完整参数（悬浮窗据此一键启动）----
    public static volatile EllipseTrack.Params lastParams;
    public static volatile int realism = 1;
    /** 虚拟时钟当前进度（秒），停止时记下来用于"原地暂停"。 */
    public static volatile double curVt = 0;
    /** 暂停时的进度；<0 表示没有暂停中的回放。 */
    public static volatile double pausedVt = -1;
    /** 暂停那次回放的参数指纹，用于判断"再次开始"是续跑还是新跑。 */
    public static volatile String pausedSig = null;

    // ---- 地图显示 ----
    public static volatile int zoom = 18;
    public static volatile int layer = TileMap.LAYER_STANDARD;

    // ---- 运行进度 ----
    public static volatile boolean running = false;
    public static volatile boolean hasPos = false;
    public static volatile double curGcjLon, curGcjLat;
    public static volatile int lap = 0;
    public static volatile int totalLaps = 5;
    public static volatile double curSpeed = 0;
    public static volatile double progress = 0;     // 0..1（本圈进度）

    // ---- 已跑轨迹（GCJ-02），null 表示圈分界 ----
    private static final List<double[]> TRAIL =
            Collections.synchronizedList(new ArrayList<double[]>());

    public static void addTrail(double lonGcj, double latGcj, boolean newLap) {
        synchronized (TRAIL) {
            if (newLap) {
                TRAIL.add(null);
            }
            TRAIL.add(new double[]{lonGcj, latGcj});
            while (TRAIL.size() > 4000) {
                TRAIL.remove(0);
            }
        }
    }

    public static List<double[]> trailSnapshot() {
        synchronized (TRAIL) {
            return new ArrayList<>(TRAIL);
        }
    }

    public static void clearTrail() {
        synchronized (TRAIL) {
            TRAIL.clear();
        }
        hasPos = false;
    }
}
