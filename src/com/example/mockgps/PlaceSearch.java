package com.example.mockgps;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 在线地点搜索。两条通路（结果统一转成 WGS-84 交给上层）：
 *   1) 高德 Web 服务（需在界面里填一个免费 key）：中文 POI 覆盖最好、国内直连；
 *   2) photon.komoot.io（OpenStreetMap，免 key）：无需配置，但境外服务在部分网络下不稳定。
 */
public class PlaceSearch {

    public static class Result {
        public final String name;
        public final String detail;
        public final double lon;   // WGS-84
        public final double lat;
        public final String source;

        public Result(String name, String detail, double lon, double lat, String source) {
            this.name = name;
            this.detail = detail;
            this.lon = lon;
            this.lat = lat;
            this.source = source;
        }
    }

    public interface Callback {
        void onResult(List<Result> list, String error);
    }

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile int seq = 0;

    public void search(final String keyword, final String amapKey, final Callback cb) {
        final int my = ++seq;
        pool.execute(() -> {
            List<Result> out = new ArrayList<>();
            List<String> errs = new ArrayList<>();

            if (amapKey != null && !amapKey.trim().isEmpty()) {
                try {
                    out.addAll(searchAmap(keyword, amapKey.trim()));
                } catch (Exception e) {
                    errs.add("高德：" + shortErr(e));
                }
            }
            if (out.isEmpty()) {
                try {
                    out.addAll(searchPhoton(keyword));
                } catch (Exception e) {
                    errs.add("OSM：" + shortErr(e));
                }
            }

            final List<Result> fr = out;
            final String fe = fr.isEmpty() && !errs.isEmpty()
                    ? "在线搜索失败（" + String.join("；", errs) + "）"
                    : null;
            if (my != seq) {
                return;      // 已有更新的搜索
            }
            main.post(() -> cb.onResult(fr, fe));
        });
    }

    private static String shortErr(Exception e) {
        String n = e.getClass().getSimpleName();
        if (n.contains("SocketTimeout")) {
            return "连接超时";
        }
        if (n.contains("UnknownHost")) {
            return "域名解析失败";
        }
        return n;
    }

    // ---------- 高德（需 key）----------
    private List<Result> searchAmap(String keyword, String key) throws Exception {
        String url = "https://restapi.amap.com/v3/place/text?key="
                + URLEncoder.encode(key, "UTF-8")
                + "&keywords=" + URLEncoder.encode(keyword, "UTF-8")
                + "&offset=10&page=1&extensions=base";
        String body = httpGet(url, 10000, 12000, 1);
        JSONObject doc = new JSONObject(body);
        List<Result> out = new ArrayList<>();
        if (!"1".equals(doc.optString("status"))) {
            throw new IllegalStateException(doc.optString("info", "高德返回错误"));
        }
        JSONArray pois = doc.optJSONArray("pois");
        if (pois == null) {
            return out;
        }
        for (int i = 0; i < pois.length(); i++) {
            JSONObject p = pois.getJSONObject(i);
            String loc = p.optString("location", "");
            int comma = loc.indexOf(',');
            if (comma <= 0) {
                continue;
            }
            double gLon = Double.parseDouble(loc.substring(0, comma));
            double gLat = Double.parseDouble(loc.substring(comma + 1));
            // 高德返回 GCJ-02 -> 转 WGS-84 统一
            double[] wgs = GeoTransform.gcj02ToWgs84(gLon, gLat);
            String detail = join(p.optString("cityname", ""), p.optString("adname", ""),
                    p.optString("address", ""));
            out.add(new Result(p.optString("name", "未命名"), detail,
                    wgs[0], wgs[1], "高德"));
        }
        return out;
    }

    // ---------- photon / OSM（免 key）----------
    private List<Result> searchPhoton(String keyword) throws Exception {
        String url = "https://photon.komoot.io/api/?limit=8&q="
                + URLEncoder.encode(keyword, "UTF-8");
        String body = httpGet(url, 12000, 15000, 2);   // 境外服务：超时放宽 + 重试
        JSONObject doc = new JSONObject(body);
        JSONArray fs = doc.optJSONArray("features");
        List<Result> out = new ArrayList<>();
        if (fs == null) {
            return out;
        }
        for (int i = 0; i < fs.length(); i++) {
            JSONObject f = fs.optJSONObject(i);
            if (f == null) {
                continue;
            }
            JSONObject p = f.optJSONObject("properties");
            JSONObject g = f.optJSONObject("geometry");
            if (p == null || g == null) {
                continue;
            }
            JSONArray co = g.optJSONArray("coordinates");
            if (co == null || co.length() < 2) {
                continue;
            }
            String name = p.optString("name", "");
            if (name.isEmpty()) {
                name = p.optString("street", "");
            }
            if (name.isEmpty()) {
                name = p.optString("city", "");
            }
            if (name.isEmpty()) {
                continue;
            }
            String detail = join(p.optString("city", ""), p.optString("county", ""),
                    p.optString("state", ""), p.optString("country", ""));
            out.add(new Result(name, detail, co.getDouble(0), co.getDouble(1), "OSM"));
        }
        return out;
    }

    /** 简易 HTTP GET（含重试）。 */
    private String httpGet(String url, int connectMs, int readMs, int attempts)
            throws Exception {
        Exception last = null;
        for (int i = 0; i < attempts; i++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(connectMs);
                c.setReadTimeout(readMs);
                c.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 13; Mobile) tuopao/1.2");
                c.setRequestProperty("Accept", "application/json");
                int code = c.getResponseCode();
                if (code != 200) {
                    throw new IllegalStateException("HTTP " + code);
                }
                InputStream in = c.getInputStream();
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] tmp = new byte[8192];
                int n;
                while ((n = in.read(tmp)) > 0) {
                    buf.write(tmp, 0, n);
                }
                in.close();
                return buf.toString("UTF-8");
            } catch (Exception e) {
                last = e;
                try {
                    Thread.sleep(400L * (i + 1));
                } catch (InterruptedException ignored) {
                    break;
                }
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        }
        throw last != null ? last : new IllegalStateException("未知错误");
    }

    private static String join(String... vals) {
        StringBuilder sb = new StringBuilder();
        for (String v : vals) {
            if (v != null && !v.isEmpty() && !"null".equals(v) && !"[]".equals(v)) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(v);
            }
        }
        return sb.toString();
    }
}
