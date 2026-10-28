package com.example.mockgps;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 高德瓦片底图（GCJ-02 坐标 / Web Mercator 投影，无需 API key）。
 *
 * - 内存 LRU + 磁盘缓存（{@code cacheDir/tiles}），第二次打开同一区域不再联网；
 * - 后台线程池下载，下载完成回调 invalidate；
 * - 提供经纬度 <-> 屏幕像素、米/像素 换算，供椭圆绘制使用。
 */
public class TileMap {

    public static final int TILE = 256;
    public static final int MIN_ZOOM = 15;
    public static final int MAX_ZOOM = 19;
    /** 图层：标准路网 / 卫星影像。 */
    public static final int LAYER_STANDARD = 0;
    public static final int LAYER_SATELLITE = 1;

    private static final String[] HOSTS = {
            "https://webrd01.is.autonavi.com/appmaptile",
            "https://webrd02.is.autonavi.com/appmaptile",
            "https://webrd03.is.autonavi.com/appmaptile",
            "https://webrd04.is.autonavi.com/appmaptile",
    };
    private static final String[] SAT_HOSTS = {
            "https://webst01.is.autonavi.com/appmaptile",
            "https://webst02.is.autonavi.com/appmaptile",
            "https://webst03.is.autonavi.com/appmaptile",
            "https://webst04.is.autonavi.com/appmaptile",
    };
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    private double centerLon, centerLat;      // GCJ-02
    private int zoom = 18;
    /** 当前图层（标准 / 卫星）。 */
    private int layer = LAYER_STANDARD;

    private final LruCache<String, Bitmap> mem;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Set<String> inFlight =
            Collections.synchronizedSet(new HashSet<String>());
    private final File diskDir;

    private Runnable onTileLoaded;
    /** 统计：成功/失败次数（用于界面提示）。 */
    public volatile int okCount = 0, failCount = 0, pendingCount = 0;

    public TileMap(File cacheDir) {
        mem = new LruCache<String, Bitmap>(96) {   // ≈24MB
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        diskDir = new File(cacheDir, "tiles");
        if (!diskDir.exists() && !diskDir.mkdirs()) {
            // 忽略：无磁盘缓存也能用
        }
    }

    public void setOnTileLoaded(Runnable r) {
        this.onTileLoaded = r;
    }

    public void setCenter(double lon, double lat) {
        this.centerLon = lon;
        this.centerLat = lat;
    }

    public double getCenterLon() {
        return centerLon;
    }

    public double getCenterLat() {
        return centerLat;
    }

    public int getZoom() {
        return zoom;
    }

    public void setZoom(int z) {
        this.zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
    }

    public int getLayer() {
        return layer;
    }

    public void setLayer(int l) {
        this.layer = l;
    }

    /** 当前中心纬度处的米/像素。 */
    public double metersPerPixel() {
        return 156543.03392 * Math.cos(Math.toRadians(centerLat))
                / Math.pow(2, zoom);
    }

    // ---- 投影换算 ----

    public double[] lonLatToWorld(double lon, double lat, int z) {
        double n = Math.pow(2, z) * TILE;
        double x = (lon + 180.0) / 360.0 * n;
        double latR = Math.toRadians(lat);
        double y = (1.0 - Math.log(Math.tan(latR) + 1.0 / Math.cos(latR)) / Math.PI)
                / 2.0 * n;
        return new double[]{x, y};
    }

    public double[] worldToLonLat(double x, double y, int z) {
        double n = Math.pow(2, z) * TILE;
        double lon = x / n * 360.0 - 180.0;
        double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y / n))));
        return new double[]{lon, lat};
    }

    /** 经纬度 -> 视图内像素坐标。 */
    public float[] lonLatToScreen(double lon, double lat, int w, int h) {
        double[] c = lonLatToWorld(centerLon, centerLat, zoom);
        double[] p = lonLatToWorld(lon, lat, zoom);
        return new float[]{(float) (p[0] - c[0] + w / 2.0),
                (float) (p[1] - c[1] + h / 2.0)};
    }

    /** 视图内像素坐标 -> 经纬度。 */
    public double[] screenToLonLat(float sx, float sy, int w, int h) {
        double[] c = lonLatToWorld(centerLon, centerLat, zoom);
        return worldToLonLat(c[0] + sx - w / 2.0, c[1] + sy - h / 2.0, zoom);
    }

    /** 按像素平移地图（拖动时用；dx>0 表示地图内容右移 = 中心西移）。 */
    public void panByPixels(double dx, double dy) {
        double[] c = lonLatToWorld(centerLon, centerLat, zoom);
        double[] ll = worldToLonLat(c[0] - dx, c[1] - dy, zoom);
        centerLon = ll[0];
        centerLat = ll[1];
    }

    // ---- 绘制 ----

    public void draw(Canvas canvas, int w, int h, Paint placeholder) {
        if (layer == LAYER_SATELLITE) {
            // 卫星影像 + 路网叠加。
            // 关键：用 MULTIPLY（正片叠底）而不是半透明覆盖 ——
            // 路网瓦片的米白底色乘上去 ≈ 不变（不会洗白影像），
            // 只有深色的道路与文字会把影像压暗，从而"浮"在影像上。
            drawLayer(canvas, w, h, placeholder, LAYER_SATELLITE, null);
            Paint road = new Paint();
            //noinspection deprecation
            road.setXfermode(new android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.MULTIPLY));
            drawLayer(canvas, w, h, null, LAYER_STANDARD, road);
        } else {
            drawLayer(canvas, w, h, placeholder, LAYER_STANDARD, null);
        }
    }

    private void drawLayer(Canvas canvas, int w, int h, Paint placeholder,
                           int lay, Paint overlayPaint) {
        double[] c = lonLatToWorld(centerLon, centerLat, zoom);
        double originX = c[0] - w / 2.0;
        double originY = c[1] - h / 2.0;
        int tx0 = (int) Math.floor(originX / TILE);
        int ty0 = (int) Math.floor(originY / TILE);
        int tx1 = (int) Math.floor((originX + w) / TILE);
        int ty1 = (int) Math.floor((originY + h) / TILE);
        int maxTile = 1 << zoom;
        for (int ty = ty0; ty <= ty1; ty++) {
            if (ty < 0 || ty >= maxTile) {
                continue;
            }
            for (int tx = tx0; tx <= tx1; tx++) {
                int wx = ((tx % maxTile) + maxTile) % maxTile;
                float left = (float) (tx * TILE - originX);
                float top = (float) (ty * TILE - originY);
                Bitmap bm = bitmapFor(lay, zoom, wx, ty);
                if (bm != null) {
                    canvas.drawBitmap(bm, left, top, overlayPaint);
                } else if (placeholder != null) {
                    canvas.drawRect(left, top, left + TILE, top + TILE, placeholder);
                }
            }
        }
    }

    private String key(int lay, int z, int x, int y) {
        return lay + "/" + z + "/" + x + "/" + y;
    }

    private Bitmap bitmapFor(int lay, int z, int x, int y) {
        String k = key(lay, z, x, y);
        Bitmap bm = mem.get(k);
        if (bm != null) {
            return bm;
        }
        File f = new File(diskDir, lay + "_" + z + "_" + x + "_" + y + ".png");
        if (f.isFile()) {
            Bitmap d = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (d != null) {
                mem.put(k, d);
                return d;
            }
        }
        request(k, lay, z, x, y, f);
        return null;
    }

    private void request(final String k, final int lay, final int z, final int x,
                         final int y, final File f) {
        if (!inFlight.add(k)) {
            return;
        }
        pendingCount++;
        pool.execute(() -> {
            Bitmap bm = null;
            try {
                String url = lay == LAYER_SATELLITE
                        ? SAT_HOSTS[(x + y) & 3] + "?style=6&x=" + x + "&y=" + y + "&z=" + z
                        : HOSTS[(x + y) & 3] + "?lang=zh_cn&size=1&scale=1&style=8"
                        + "&x=" + x + "&y=" + y + "&z=" + z;
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", UA);
                conn.setRequestProperty("Referer", "https://www.amap.com/");
                conn.connect();
                int code = conn.getResponseCode();
                if (code == 200) {
                    InputStream in = conn.getInputStream();
                    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                    byte[] tmp = new byte[8192];
                    int n;
                    while ((n = in.read(tmp)) > 0) {
                        buf.write(tmp, 0, n);
                    }
                    in.close();
                    byte[] data = buf.toByteArray();
                    bm = BitmapFactory.decodeByteArray(data, 0, data.length);
                    if (bm != null) {
                        try (FileOutputStream out = new FileOutputStream(f)) {
                            out.write(data);
                        }
                    }
                }
                conn.disconnect();
            } catch (Exception ignored) {
                // 网络问题：保持占位
            } finally {
                inFlight.remove(k);
                pendingCount--;
                if (bm != null) {
                    mem.put(k, bm);
                    okCount++;
                } else {
                    failCount++;
                }
                if (onTileLoaded != null) {
                    onTileLoaded.run();
                }
            }
        });
    }

    /** 占位底色（深色，与暗色界面统一；瓦片没加载时不会出现刺眼的浅色块）。 */
    public static Paint placeholderPaint() {
        Paint p = new Paint();
        p.setColor(Color.parseColor("#1A1F2A"));
        return p;
    }
}
