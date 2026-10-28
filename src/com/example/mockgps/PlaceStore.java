package com.example.mockgps;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 本地收藏地点（SharedPreferences 存 JSON），离线可用、可长期保存。 */
public class PlaceStore {

    public static class Place {
        public final String name;
        public final double lonBd;   // 存 BD-09，与输入框一致
        public final double latBd;

        public Place(String name, double lonBd, double latBd) {
            this.name = name;
            this.lonBd = lonBd;
            this.latBd = latBd;
        }
    }

    private static final String KEY = "places_json";
    private final SharedPreferences sp;

    public PlaceStore(Context ctx) {
        sp = ctx.getSharedPreferences("tuopao_places", Context.MODE_PRIVATE);
    }

    public List<Place> list() {
        List<Place> out = new ArrayList<>();
        String s = sp.getString(KEY, "");
        if (s == null || s.isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Place(o.optString("name", "未命名"),
                        o.optDouble("lon", 0), o.optDouble("lat", 0)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 添加或覆盖同名地点（最新在最前）。 */
    public void put(String name, double lonBd, double latBd) {
        List<Place> cur = list();
        List<Place> out = new ArrayList<>();
        out.add(new Place(name, lonBd, latBd));
        for (Place p : cur) {
            if (!p.name.equals(name)) {
                out.add(p);
            }
        }
        save(out);
    }

    public void remove(String name) {
        List<Place> out = new ArrayList<>();
        for (Place p : list()) {
            if (!p.name.equals(name)) {
                out.add(p);
            }
        }
        save(out);
    }

    private void save(List<Place> items) {
        JSONArray arr = new JSONArray();
        try {
            for (Place p : items) {
                JSONObject o = new JSONObject();
                o.put("name", p.name);
                o.put("lon", p.lonBd);
                o.put("lat", p.latBd);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        sp.edit().putString(KEY, arr.toString()).apply();
    }
}
