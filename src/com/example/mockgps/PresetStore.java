package com.example.mockgps;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 预设（位置 + 椭圆形状 + 起点 + 速度 + 圈数 + 地图图层）的本地存储。 */
public class PresetStore {

    public static class Preset {
        public String name = "";
        public double lonBd, latBd;      // 中心（BD-09，与输入框一致）
        public double a = 78, b = 37;
        public double angle = 0;
        public double startAngle = 0;
        public double speed = 2.7;
        public int laps = 5;
        public int layer = TileMap.LAYER_STANDARD;
        public int zoom = 18;
        /** 真实度档位：0=平滑 1=标准 2=颠簸。 */
        public int realism = 1;

        public String summary() {
            return String.format(java.util.Locale.CHINA,
                    "%.0f×%.0f m · 方位角 %.0f° · %.1f m/s · %d 圈",
                    a, b, angle, speed, laps);
        }
    }

    private static final String KEY = "presets_json";
    private static final String KEY_LOCK = "locked_preset";
    private final SharedPreferences sp;

    public PresetStore(Context ctx) {
        sp = ctx.getSharedPreferences("tuopao_presets", Context.MODE_PRIVATE);
    }

    public List<Preset> list() {
        List<Preset> out = new ArrayList<>();
        String s = sp.getString(KEY, "");
        if (s == null || s.isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Preset p = new Preset();
                p.name = o.optString("name", "未命名");
                p.lonBd = o.optDouble("lon", 0);
                p.latBd = o.optDouble("lat", 0);
                p.a = o.optDouble("a", 78);
                p.b = o.optDouble("b", 37);
                p.angle = o.optDouble("angle", 0);
                p.startAngle = o.optDouble("startAngle", 0);
                p.speed = o.optDouble("speed", 2.7);
                p.laps = o.optInt("laps", 5);
                p.layer = o.optInt("layer", TileMap.LAYER_STANDARD);
                p.zoom = o.optInt("zoom", 18);
                p.realism = o.optInt("realism", 1);
                out.add(p);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public Preset get(String name) {
        for (Preset p : list()) {
            if (p.name.equals(name)) {
                return p;
            }
        }
        return null;
    }

    /** 保存或覆盖同名预设。 */
    public void put(Preset p) {
        List<Preset> out = new ArrayList<>();
        out.add(p);
        for (Preset q : list()) {
            if (!q.name.equals(p.name)) {
                out.add(q);
            }
        }
        save(out);
    }

    public void remove(String name) {
        List<Preset> out = new ArrayList<>();
        for (Preset p : list()) {
            if (!p.name.equals(name)) {
                out.add(p);
            }
        }
        save(out);
        if (name.equals(getLockedName())) {
            setLockedName("");
        }
    }

    /** 启动时自动加载并锁定的预设名（空 = 不锁定）。 */
    public String getLockedName() {
        return sp.getString(KEY_LOCK, "");
    }

    public void setLockedName(String name) {
        sp.edit().putString(KEY_LOCK, name == null ? "" : name).apply();
    }

    private void save(List<Preset> items) {
        JSONArray arr = new JSONArray();
        try {
            for (Preset p : items) {
                JSONObject o = new JSONObject();
                o.put("name", p.name);
                o.put("lon", p.lonBd);
                o.put("lat", p.latBd);
                o.put("a", p.a);
                o.put("b", p.b);
                o.put("angle", p.angle);
                o.put("startAngle", p.startAngle);
                o.put("speed", p.speed);
                o.put("laps", p.laps);
                o.put("layer", p.layer);
                o.put("zoom", p.zoom);
                o.put("realism", p.realism);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        sp.edit().putString(KEY, arr.toString()).apply();
    }
}
