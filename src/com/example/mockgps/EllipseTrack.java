package com.example.mockgps;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 椭圆跑道轨迹生成器（参数化，替代固定 assets 轨迹）。
 *
 * 真实感模型（令轨迹"像真人跑的"）分三层叠加：
 *   ① 几何层：每圈独立微调长短轴（±1.2%）、方位角（±1.2°）、中心（±0.9m）；
 *   ② 跑姿层：沿切线法向叠加横向偏移 = 慢漂移(0.6~1.5m/50~120m) + 身体摆动(0.12~0.37m/7~15m)
 *              + 带回归的随机游走(±0.7m)，模拟"跑者不是贴着一条线跑"；
 *   ③ 接收机层：AR(1) 相关 GPS 噪声（σ≈1.2m、ρ≈0.78），位置误差不再逐点跳变而是缓慢漂移。
 * 速度层：弯道曲率减速 + 每圈独立快慢(±3.5%) + 沿圈慢调制(±4~7%) + 全程疲劳衰减(-2%) + 微噪声。
 *
 * 参数：
 *   lon/lat    跑道中心（WGS-84）
 *   aMeters    长轴半径（米）
 *   bMeters    短轴半径（米）
 *   angleDeg   长轴方位角（度，0=正南北，顺时针为正）
 *   speedMps   直道速度（米/秒），弯道按 slowdown 比例自动减速
 *   laps       圈数
 *   interval   GPS 采样间隔（秒）
 *   ccw        是否逆时针
 *   jitter     圈间速度差异（0.035 = ±3.5%）
 *
 * 输出点：lon/lat/t(相对秒)/v(米每秒)/lap——与 assets/track.json 同构。
 */
public class EllipseTrack {

    public static class Point {
        public double lon, lat, t, v;
        public int lap;
    }

    public static class Params {
        public double lon;
        public double lat;
        public double aMeters = 78;
        public double bMeters = 37;
        public double angleDeg = 0;
        /** 起点在椭圆上的参数角（度，0=长轴正向端）；决定从哪开始跑。 */
        public double startAngleDeg = 0;
        public double speedMps = 2.7;
        public int laps = 5;
        public double interval = 1.0;
        public boolean ccw = true;
        /** 圈间速度差异（±比例）。 */
        public double jitter = 0.035;
        /** 弯道减速比例。 */
        public double slowdown = 0.12;

        // ---- 真实感参数 ----
        /** GPS 位置噪声标准差（米）。0 = 完全理想轨迹。 */
        public double gpsSigma = 0.75;
        /** GPS 噪声相关性（0=白噪声，越大越"缓慢漂移"）。 */
        public double gpsRho = 0.90;
        /** 横向慢漂移幅度（米）：跑者左右换线。 */
        public double wobbleAmp = 0.9;
        /** 身体摆动幅度（米）：步态引起的快速摆动。 */
        public double wobbleFast = 0.18;
        /** 每圈形状差异（长短轴相对比例）。 */
        public double shapeJitter = 0.010;
        /** 每圈方位角差异（度）。 */
        public double angleJitter = 1.0;
        /** 每圈中心偏移（米）。 */
        public double centerJitter = 0.7;
        /** 全程疲劳衰减比例。 */
        public double fatigue = 0.02;
        /**
         * 每圈形状指数变化范围（超椭圆 |x/a|^n + |y/b|^n = 1）：
         * 0 = 每圈都是椭圆；4.0 = 每圈 n 在 [2,6] 间随机取值
         * （n=2 趋近椭圆，n≥4 趋近圆角长方形）。
         */
        public double shapeVariety = 0;
        /** 慢速二维游走幅度（米）：让整圈基线本身漂移，避免规则。 */
        public double wanderAmp = 0;
        /** 形状起伏（半径慢变调制，0~0.1）：让形状"鼓起/凹下"，更随机。 */
        public double shapeBump = 0;

        public long seed = 42;
    }

    private static final double TWO_PI = Math.PI * 2;

    /**
     * 把"真实度"档位映射到生成参数。主界面与播放服务共用，避免两处各写一份导致不一致。
     * 0=平滑 1=标准 2=颠簸 3=狂野（狂野档每圈形状指数不同，有的椭圆有的近长方形）
     */
    public static void applyRealism(Params p, int level) {
        switch (level) {
            case 0:
                p.gpsSigma = 0.25;
                p.wobbleAmp = 0.25;
                p.wobbleFast = 0.05;
                p.shapeJitter = 0.003;
                p.angleJitter = 0.3;
                p.centerJitter = 0.2;
                p.shapeVariety = 0;
                p.wanderAmp = 0;
                p.shapeBump = 0;
                p.gpsRho = 0.90;
                p.jitter = 0.035;
                break;
            case 2:
                p.gpsSigma = 1.35;
                p.wobbleAmp = 1.5;
                p.wobbleFast = 0.35;
                p.shapeJitter = 0.020;
                p.angleJitter = 2.0;
                p.centerJitter = 1.5;
                p.shapeVariety = 0;
                p.wanderAmp = 0;
                p.shapeBump = 0;
                p.gpsRho = 0.90;
                p.jitter = 0.035;
                break;
            case 3:
                // 狂野：每圈朝向/位置/形状/快慢都不同，路径本身还会游走
                p.gpsSigma = 2.4;
                p.gpsRho = 0.72;        // 相关性降低 → 位置更"跳"
                p.wobbleAmp = 3.2;
                p.wobbleFast = 0.9;
                p.shapeJitter = 0.070;
                p.angleJitter = 10.0;
                p.centerJitter = 5.0;
                p.shapeVariety = 10.0;  // 形状指数 n ∈ [2,12]
                p.wanderAmp = 2.0;
                p.shapeBump = 0.06;
                p.jitter = 0.10;        // 每圈快慢 ±10%
                break;
            default:
                p.gpsSigma = 0.75;
                p.wobbleAmp = 0.9;
                p.wobbleFast = 0.18;
                p.shapeJitter = 0.010;
                p.angleJitter = 1.0;
                p.centerJitter = 0.7;
                p.shapeVariety = 0;
                p.wanderAmp = 0;
                p.shapeBump = 0;
                p.gpsRho = 0.90;
                p.jitter = 0.035;
        }
    }

    /** 每米度数换算（东西向）。 */
    private static double mxPerDeg(double lat) {
        return Math.PI / 180.0 * 6371000.0 * Math.cos(Math.toRadians(lat));
    }

    /** 每米度数换算（南北向）。 */
    private static double myPerDeg() {
        return Math.PI / 180.0 * 6371000.0;
    }

    /** 标准正态随机数（Box-Muller）。 */
    private static double gauss(java.util.Random r) {
        double u1 = Math.max(1e-9, r.nextDouble());
        double u2 = r.nextDouble();
        return Math.sqrt(-2 * Math.log(u1)) * Math.cos(TWO_PI * u2);
    }

    /** 超椭圆 x 分量：n=2 时即 cos t。 */
    private static double sCos(double t, double n) {
        double c = Math.cos(t);
        return Math.signum(c) * Math.pow(Math.abs(c), 2.0 / n);
    }

    /** 超椭圆 y 分量：n=2 时即 sin t。 */
    private static double sSin(double t, double n) {
        double s = Math.sin(t);
        return Math.signum(s) * Math.pow(Math.abs(s), 2.0 / n);
    }

    /** 生成某条超椭圆（给定形状指数 n）的累计弧长表，返回长度 n+1 的数组。 */
    private static double[] arcTable(double a, double b, int n, double exp) {
        double[] s = new double[n + 1];
        double acc = 0;
        for (int i = 0; i <= n; i++) {
            s[i] = acc;
            if (i < n) {
                double t0 = TWO_PI * i / n;
                double t1 = TWO_PI * (i + 1) / n;
                double dx = a * (sCos(t1, exp) - sCos(t0, exp));
                double dy = b * (sSin(t1, exp) - sSin(t0, exp));
                acc += Math.sqrt(dx * dx + dy * dy);
            }
        }
        return s;
    }

    /** 椭圆弧长数值积分。 */
    private static double ellipsePerimeter(double a, double b) {
        int n = 2000;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double t0 = TWO_PI * i / n;
            double t1 = TWO_PI * (i + 1) / n;
            double dx = a * (Math.cos(t1) - Math.cos(t0));
            double dy = b * (Math.sin(t1) - Math.sin(t0));
            sum += Math.sqrt(dx * dx + dy * dy);
        }
        return sum;
    }

    /**
     * 生成轨迹。t 从 0 开始严格递增，最后一圈结束即返回。
     */
    public static List<Point> generate(Params p) {
        double a = Math.max(10, p.aMeters);
        double b = Math.max(5, Math.min(p.bMeters, a));
        double mx = mxPerDeg(p.lat), my = myPerDeg();
        double perimBase = ellipsePerimeter(a, b);

        // 采样密度（按基准周长）
        int n = Math.max(500, (int) perimBase);
        double[] sBase = arcTable(a, b, n, 2.0);

        // 基准圈速：∮ ds/v(t) = T，用于把"速度"换算成每圈用时
        double kMax = Math.max(a, b) / (Math.min(a, b) * Math.min(a, b));
        double tLapBase = 0;
        for (int i = 0; i < n; i++) {
            double tMid = TWO_PI * (i + 0.5) / n;
            double vMid = speedAtAngle(tMid, a, b, p.speedMps, p.slowdown, kMax);
            tLapBase += (sBase[i + 1] - sBase[i]) / vMid;
        }

        java.util.Random rng = new java.util.Random(p.seed);
        List<Point> out = new ArrayList<>();
        double tCursor = 0;

        // 起点：按参数角比例定位（每圈形状不同，用各自弧长表换算）
        double startNorm = ((p.startAngleDeg % 360) + 360) % 360 / 360.0;
        int sIdx = Math.max(0, Math.min(n - 1, (int) Math.round(startNorm * n)));

        // 接收机噪声状态（AR(1)，米）
        final double rho = Math.max(0, Math.min(0.98, p.gpsRho));
        final double sqrtTerm = Math.sqrt(1 - rho * rho);
        double arX = 0, arY = 0;
        // 慢速二维游走状态（跨圈连续，制造"基线漂移"）
        double wanX = 0, wanY = 0;

        for (int lap = 1; lap <= p.laps; lap++) {
            // ---------- ① 几何层：每圈独立的形状 / 朝向 / 位置 ----------
            double lapSpeed = 1 + gauss(rng) * p.jitter;
            double aL = a * (1 + gauss(rng) * p.shapeJitter);
            double bL = b * (1 + gauss(rng) * p.shapeJitter * 1.4);
            // 形状指数：2=椭圆，越大越接近圆角长方形
            double exp = p.shapeVariety > 0
                    ? 2.0 + p.shapeVariety * rng.nextDouble() : 2.0;
            double[] sLap = arcTable(aL, bL, n, exp);
            double lenLap = sLap[n];
            double s0 = sLap[sIdx];

            double angL = Math.toRadians(90.0 - (p.angleDeg + gauss(rng) * p.angleJitter));
            double cosL = Math.cos(angL), sinL = Math.sin(angL);
            double offX = gauss(rng) * p.centerJitter;
            double offY = gauss(rng) * p.centerJitter;

            // ---------- ② 跑姿层：横向摆动参数 ----------
            double ph1 = rng.nextDouble() * TWO_PI, ph2 = rng.nextDouble() * TWO_PI;
            double lam1 = 50 + rng.nextDouble() * 70;
            double lam2 = 7 + rng.nextDouble() * 8;
            double amp1 = p.wobbleAmp * (0.5 + rng.nextDouble());
            double amp2 = p.wobbleFast * (0.5 + rng.nextDouble());
            double rw = 0;
            // 形状起伏：每圈独立的波长/相位（半径慢变调制）
            double bumpPh = rng.nextDouble() * TWO_PI;
            double bumpLam = 20 + rng.nextDouble() * 45;

            // ---------- 速度慢调制 ----------
            double vPh = rng.nextDouble() * TWO_PI;
            double vLam = 80 + rng.nextDouble() * 70;
            double vAmp = 0.04 + rng.nextDouble() * 0.03;
            double fatigue = p.laps > 1
                    ? 1 - p.fatigue * (lap - 1) / (p.laps - 1.0) : 1.0;

            // 形状变化会改变周长：按比例修正用时，保证速度仍然对
            double tLapEff = tLapBase * (lenLap / perimBase) / (lapSpeed * fatigue);
            int nSamples = Math.max(2, (int) Math.round(tLapEff / p.interval));
            int kStart = (lap == 1) ? 0 : 1;

            for (int k = kStart; k <= nSamples; k++) {
                double fracBase = (double) k / nSamples;
                double frac = (k == 0 || k == nSamples) ? fracBase
                        : fracBase + (rng.nextDouble() - 0.5) * (1.0 / nSamples) * 0.35;
                double sTarget = (s0 + frac * lenLap) % lenLap;

                double tt = paramAngle(sLap, sTarget);
                double tt2 = paramAngle(sLap, (sTarget + 1.0) % lenLap);
                double x0 = aL * sCos(tt, exp);
                double y0 = bL * sSin(tt, exp) * (p.ccw ? 1 : -1);
                double x1 = aL * sCos(tt2, exp);
                double y1 = bL * sSin(tt2, exp) * (p.ccw ? 1 : -1);
                if (p.shapeBump > 0) {
                    // 半径慢变调制：形状会"鼓"出来或"瘪"进去，不是干净的椭圆/长方形
                    double bump = 1 + p.shapeBump
                            * Math.sin(TWO_PI * sTarget / bumpLam + bumpPh);
                    x0 *= bump;
                    y0 *= bump;
                    x1 *= bump;
                    y1 *= bump;
                }
                double tx = x1 - x0, ty = y1 - y0;
                double tl = Math.hypot(tx, ty);
                double nx, ny;
                if (tl < 1e-6) {
                    nx = 0;
                    ny = 1;
                } else {
                    nx = -ty / tl;
                    ny = tx / tl;
                }

                rw = rw * 0.985 + gauss(rng) * 0.09;
                rw = Math.max(-0.7, Math.min(0.7, rw));
                double latOff = amp1 * Math.sin(TWO_PI * sTarget / lam1 + ph1)
                        + amp2 * Math.sin(TWO_PI * sTarget / lam2 + ph2) + rw;

                double xr = x0 * cosL - y0 * sinL;
                double yr = x0 * sinL + y0 * cosL;
                double nrx = nx * cosL - ny * sinL;
                double nry = nx * sinL + ny * cosL;
                xr += latOff * nrx + offX;
                yr += latOff * nry + offY;

                // 慢速二维游走：整圈基线本身在漂，两圈不可能重合
                if (p.wanderAmp > 0) {
                    wanX = wanX * 0.98 + gauss(rng) * p.wanderAmp * 0.10;
                    wanY = wanY * 0.98 + gauss(rng) * p.wanderAmp * 0.10;
                    double wlim = p.wanderAmp * 2.0;
                    wanX = Math.max(-wlim, Math.min(wlim, wanX));
                    wanY = Math.max(-wlim, Math.min(wlim, wanY));
                    xr += wanX;
                    yr += wanY;
                }

                // ---------- ③ 接收机层：AR(1) 相关噪声 ----------
                arX = rho * arX + sqrtTerm * p.gpsSigma * gauss(rng);
                arY = rho * arY + sqrtTerm * p.gpsSigma * gauss(rng);
                xr += arX;
                yr += arY;

                Point pt = new Point();
                pt.lon = p.lon + xr / mx;
                pt.lat = p.lat + yr / my;
                pt.t = tCursor + tLapEff * k / nSamples
                        * (k == 0 ? 1 : 1 + (rng.nextDouble() - 0.5) * 0.02);
                if (pt.t <= (out.isEmpty() ? -1 : out.get(out.size() - 1).t)) {
                    pt.t = out.get(out.size() - 1).t + 0.01;
                }

                double vCurve = speedAtAngle(tt, a, b, p.speedMps, p.slowdown, kMax);
                double vSlow = 1 + vAmp * Math.sin(TWO_PI * sTarget / vLam + vPh);
                pt.v = vCurve * lapSpeed * fatigue * vSlow * (1 + gauss(rng) * 0.012);
                pt.lap = lap;
                out.add(pt);
            }
            tCursor += tLapEff;
        }
        return out;
    }

    /** 给定参数角 t 处的速度：曲率越大（弯道）越慢。 */
    private static double speedAtAngle(double t, double a, double b,
                                       double baseSpeed, double slowdown, double kMax) {
        double den = a * a * Math.sin(t) * Math.sin(t) + b * b * Math.cos(t) * Math.cos(t);
        double k = a * b / Math.pow(den, 1.5);
        double w = Math.min(1.0, k / kMax);
        return baseSpeed * (1 - slowdown * w);
    }

    /** 在累计弧长表上二分找参数角。 */
    private static double paramAngle(double[] sOfT, double s) {
        int lo = 0, hi = sOfT.length - 1;
        if (s <= 0) return 0;
        if (s >= sOfT[hi]) return TWO_PI;
        while (lo < hi - 1) {
            int mid = (lo + hi) >>> 1;
            if (sOfT[mid] <= s) lo = mid;
            else hi = mid;
        }
        double seg = sOfT[hi == lo ? lo + 1 : hi] - sOfT[lo];
        double f = seg > 1e-9 ? (s - sOfT[lo]) / seg : 0;
        double t0 = TWO_PI * lo / (sOfT.length - 1);
        double t1 = TWO_PI * (lo + 1) / (sOfT.length - 1);
        return t0 + (t1 - t0) * f;
    }

    /** 导出为与 assets/track.json 同构的 JSON。 */
    public static String toJson(List<Point> pts) throws Exception {
        JSONObject doc = new JSONObject();
        JSONArray arr = new JSONArray();
        double totalD = 0, lastT = 0;
        for (int i = 0; i < pts.size(); i++) {
            Point p = pts.get(i);
            JSONObject o = new JSONObject();
            o.put("lon", p.lon);
            o.put("lat", p.lat);
            o.put("t", p.t);
            o.put("speed_mps", p.v);
            o.put("lap", p.lap);
            arr.put(o);
            if (i > 0) {
                Point q = pts.get(i - 1);
                double dx = (p.lon - q.lon) * 111320 * Math.cos(Math.toRadians(p.lat));
                double dy = (p.lat - q.lat) * 110540;
                totalD += Math.sqrt(dx * dx + dy * dy);
            }
            lastT = p.t;
        }
        doc.put("points", arr);
        doc.put("point_count", pts.size());
        doc.put("total_distance_m", totalD);
        doc.put("total_duration_s", lastT);
        return doc.toString();
    }
}
