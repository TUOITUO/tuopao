package com.example.mockgps;

/**
 * 坐标系转换：BD-09（百度）→ GCJ-02（火星）→ WGS-84（GPS 实际使用）。
 * 注入 mock location 必须用 WGS-84；用户在百度地图上复制的坐标是 BD-09。
 */
public final class GeoTransform {

    private static final double PI = Math.PI;
    private static final double X_PI = PI * 3000.0 / 180.0;
    // 克拉索夫斯基椭球参数（GCJ-02 加密用）
    private static final double A = 6378245.0;
    private static final double EE = 0.00669342162296594323;

    private GeoTransform() {
    }

    /** BD-09 → GCJ-02。输入输出均为 [lng, lat]。 */
    public static double[] bd09ToGcj02(double lng, double lat) {
        double x = lng - 0.0065;
        double y = lat - 0.006;
        double z = Math.sqrt(x * x + y * y) - 0.00002 * Math.sin(y * X_PI);
        double theta = Math.atan2(y, x) - 0.000003 * Math.cos(x * X_PI);
        return new double[]{z * Math.cos(theta), z * Math.sin(theta)};
    }

    /** GCJ-02 → BD-09。 */
    public static double[] gcj02ToBd09(double lng, double lat) {
        double z = Math.sqrt(lng * lng + lat * lat) + 0.00002 * Math.sin(lat * X_PI);
        double theta = Math.atan2(lat, lng) + 0.000003 * Math.cos(lng * X_PI);
        return new double[]{z * Math.cos(theta) + 0.0065, z * Math.sin(theta) + 0.006};
    }

    /** GCJ-02 正向加密（WGS-84 → GCJ-02）。 */
    private static double[] gcj02Encrypt(double lng, double lat) {
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
        return new double[]{lng + dLng, lat + dLat};
    }

    /** GCJ-02 → WGS-84（迭代反解，3 轮后误差 <1m）。 */
    public static double[] gcj02ToWgs84(double lng, double lat) {
        double wLng = lng;
        double wLat = lat;
        for (int i = 0; i < 3; i++) {
            double[] enc = gcj02Encrypt(wLng, wLat);
            wLng += lng - enc[0];
            wLat += lat - enc[1];
        }
        return new double[]{wLng, wLat};
    }

    /** BD-09 → WGS-84（注入 GPS 用）。 */
    public static double[] bd09ToWgs84(double lng, double lat) {
        double[] g = bd09ToGcj02(lng, lat);
        return gcj02ToWgs84(g[0], g[1]);
    }

    /** WGS-84 → GCJ-02（把注入坐标画到高德地图上时用）。 */
    public static double[] wgs84ToGcj02(double lng, double lat) {
        return gcj02Encrypt(lng, lat);
    }

    /** WGS-84 → BD-09（显示用）。 */
    public static double[] wgs84ToBd09(double lng, double lat) {
        double[] g = gcj02Encrypt(lng, lat);
        return gcj02ToBd09(g[0], g[1]);
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320.0 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y
                + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }
}
