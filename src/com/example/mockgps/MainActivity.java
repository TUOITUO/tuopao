package com.example.mockgps;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 主界面（深色 Material 风格，配色参考 DeepSeek）：
 *   ① 地点搜索（在线 Photon + 本地收藏 + 拖动地图定位）
 *   ② 地图可视化（高德瓦片）：拖动移动椭圆、拖 A/B 手柄调角度与大小
 *   ③ 跑道参数（BD-09 中心 / 半轴或周长 / 方位角 / 速度 / 圈数）
 *   ④ 播放控制（预览 / 开始 / 停止 / 重新开始 / 实时倍速）
 *
 * 无系统 ActionBar（主题为 Theme.Material.NoActionBar），页面顶部不放标题栏以节省空间。
 */
public class MainActivity extends Activity implements TrackView.OnShapeChanged {

    // ---- DeepSeek 风格深色配色 ----
    private static final int C_BG = 0xFF0F1115;        // 页面底色
    private static final int C_CARD = 0xFF171A21;      // 卡片底色
    private static final int C_BORDER = 0xFF272C37;    // 卡片描边
    private static final int C_PRIMARY = 0xFF2F52D9;   // 主按钮底（深蓝）
    private static final int C_TEXT = 0xFFE6E8EC;      // 主文字
    private static final int C_TEXT2 = 0xFF9AA3B2;     // 次要文字
    private static final int C_HINT = 0xFF6B7280;      // 输入提示
    private static final int C_TITLE = 0xFF8FA6FF;     // 卡片标题
    // 按钮统一深蓝
    private static final int C_BTN = 0xFF1B2540;        // 次级按钮底（深蓝）
    private static final int C_BTN_BORDER = 0xFF31406B;
    private static final int C_BTN_TEXT = 0xFFC3D2FF;   // 次级按钮文字
    private static final String PREF = "tuopao_cfg";

    private EditText etLon, etLat, etA, etB, etAngle, etSpeed, etLaps, etPerimTarget;
    private EditText etSearch;
    private EditText etAmapKey;
    private TextView tvStatus, tvPerim, searchStatus;
    private LinearLayout resultsBox, placesBox;
    private TrackView trackView;
    private TileMap tileMap;
    private PlaceStore placeStore;
    private PresetStore presetStore;
    private LinearLayout presetsBox;
    private TextView tvStart;
    private Button btnLock;
    private Button btnLayer;
    private boolean uiLocked = false;
    private final Button[] realismBtns = new Button[4];
    private int realism = 1;      // 0=平滑 1=标准 2=颠簸
    private FireworksView fireworks;
    /** 长按「拖拖」延时触发烟花。 */
    private final Runnable fireworksStart = new Runnable() {
        @Override
        public void run() {
            if (fireworks != null) {
                fireworks.start();
                toast("🎆 彩蛋触发：拖拖放烟花啦");
            }
        }
    };
    private PlaceSearch searcher;
    private BroadcastReceiver progressReceiver;
    private int lastLap = 0;
    private EllipseTrack.Params activeParams;

    // ---- 原生风格小工具 ----

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int themeColor(int attr, int fallback) {
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(attr, tv, true)) {
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            }
        }
        return fallback;
    }

    private void applyRipple(View v) {
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                && tv.resourceId != 0) {
            v.setBackgroundResource(tv.resourceId);
        }
    }

    /** 深色卡片容器：圆角 + 描边 + 阴影。 */
    private LinearLayout card(String title) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(13);
        box.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_CARD);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), C_BORDER);
        box.setBackground(bg);
        box.setElevation(dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(10), dp(5), dp(10), dp(5));
        box.setLayoutParams(lp);
        if (title != null) {
            TextView t = new TextView(this);
            t.setText(title);
            t.setTextSize(14);
            t.setPadding(0, 0, 0, dp(6));
            t.setTextColor(C_TITLE);
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
            box.addView(t);
        }
        return box;
    }

    /** 带左侧说明文字的输入项：一行 = [说明标签][输入框]。 */
    private EditText field(LinearLayout parent, String labelText, String def, int inputType) {
        LinearLayout r = row();
        r.setPadding(0, dp(2), 0, dp(2));
        TextView lb = label(labelText, C_TEXT2, 12);
        r.addView(lb, new LinearLayout.LayoutParams(dp(132),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        EditText et = new EditText(this);
        et.setHintTextColor(C_HINT);
        et.setTextColor(C_TEXT);
        if (def != null && !def.isEmpty()) {
            et.setText(def);
        }
        et.setTextSize(16);
        et.setSingleLine(true);
        et.setInputType(inputType);
        r.addView(et, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        parent.addView(r, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return et;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        styleButton(b, false);
        return b;
    }

    /** 主操作按钮（深蓝底 + 白字）。 */
    private Button primaryButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        styleButton(b, true);
        return b;
    }

    /** 统一按钮配色：深蓝底 + 浅色字 + 原生水波纹。 */
    private void styleButton(Button b, boolean primary) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? C_PRIMARY : C_BTN);
        g.setCornerRadius(dp(10));
        if (!primary) {
            g.setStroke(dp(1), C_BTN_BORDER);
        }
        android.content.res.ColorStateList ripple =
                android.content.res.ColorStateList.valueOf(
                        primary ? 0x44FFFFFF : 0x33FFFFFF);
        b.setBackground(new android.graphics.drawable.RippleDrawable(ripple, g, null));
        b.setTextColor(primary ? Color.WHITE : C_BTN_TEXT);
        b.setAllCaps(false);
    }

    private TextView label(String text, int color, float size) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    /** 使用说明里的一条步骤：加粗标题 + 说明正文。 */
    private void addStep(LinearLayout parent, String title, String body) {
        TextView t = label(title, C_TEXT, 13);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        t.setPadding(0, dp(9), 0, 0);
        parent.addView(t);
        TextView b = label(body, C_TEXT2, 12);
        b.setLineSpacing(dp(3), 1f);
        b.setPadding(dp(4), dp(3), 0, 0);
        parent.addView(b);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private void addToRow(LinearLayout r, View v, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        if (r.getChildCount() > 0) {
            lp.leftMargin = dp(8);      // 只在中间加间距 → 等宽对齐
        }
        r.addView(v, lp);
    }

    /** 弹窗统一成深色卡片风格（与页面一致）。需在 show() 之后调用。 */
    private void styleDialog(AlertDialog d) {
        if (d.getWindow() != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(C_CARD);
            bg.setCornerRadius(dp(16));
            bg.setStroke(dp(1), C_BORDER);
            d.getWindow().setBackgroundDrawable(bg);
            d.getWindow().setDimAmount(0.6f);
            tintDialogText(d.getWindow().getDecorView());
            // 按钮文字在 show() 后才由主题最终赋色，可能盖回黑色 → 过 50ms 再染一次
            d.getWindow().getDecorView().postDelayed(() -> {
                tintButtons(d.getWindow().getDecorView());
            }, 50);
        }
    }

    /** 只染按钮（positive/negative/neutral）：其余文字第一次已染好。 */
    private void tintButtons(View v) {
        if (v instanceof TextView) {
            int id = v.getId();
            if (id == android.R.id.button1 || id == android.R.id.button2
                    || id == android.R.id.button3) {
                ((TextView) v).setTextColor(C_TITLE);
            }
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                tintButtons(g.getChildAt(i));
            }
        }
    }

    /** 递归把弹窗里的文字染成深色主题配色（系统默认是黑字，落在深底上看不清）。 */
    private void tintDialogText(View v) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            int id = t.getId();
            if (id == android.R.id.button1 || id == android.R.id.button2
                    || id == android.R.id.button3) {
                t.setTextColor(C_TITLE);        // 按钮：亮蓝
            } else {
                t.setTextColor(C_TEXT);         // 标题/正文/列表项：近白
            }
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                tintDialogText(g.getChildAt(i));
            }
        }
    }

    // ---- 生命周期 ----

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(C_BG);
        placeStore = new PlaceStore(this);
        presetStore = new PresetStore(this);
        searcher = new PlaceSearch();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(C_BG);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(10), 0, dp(24));
        scroll.addView(content);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 彩蛋层：满屏烟花（平时隐藏，不挡触摸）
        fireworks = new FireworksView(this);
        fireworks.setVisibility(View.GONE);
        root.addView(fireworks, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // ① 地点搜索
        LinearLayout searchCard = card(null);        // 顶部不放标题，省空间
        LinearLayout searchRow = row();
        etSearch = new EditText(this);
        etSearch.setHint("输入地名，如 常州大学");
        etSearch.setHintTextColor(C_HINT);
        etSearch.setTextColor(C_TEXT);
        etSearch.setSingleLine(true);
        etSearch.setTextSize(16);
        etSearch.setInputType(InputType.TYPE_CLASS_TEXT);
        searchRow.addView(etSearch, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(6);
        searchRow.addView(primaryButton("搜索", v -> doSearch()), slp);
        searchCard.addView(searchRow);
        searchStatus = label("", C_TEXT2, 12);
        searchStatus.setPadding(0, dp(4), 0, 0);
        searchCard.addView(searchStatus);
        resultsBox = new LinearLayout(this);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        searchCard.addView(resultsBox);
        LinearLayout saveRow = row();
        saveRow.setPadding(0, dp(4), 0, 0);
        saveRow.addView(button("收藏当前中心", v -> saveCurrentPlace()),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        searchCard.addView(saveRow);
        // 高德 key（可选）：填了走官方接口，中文 POI 最准
        etAmapKey = field(searchCard, "高德 Key (可选)", "",
                InputType.TYPE_CLASS_TEXT);
        etAmapKey.setTextSize(13);
        etAmapKey.setHint("不填也可用（内置城市库 + OSM）");
        etAmapKey.setHintTextColor(C_HINT);
        etAmapKey.setText(cfgGet("amap_key", ""));
        searchCard.addView(label("申请：lbs.amap.com → 应用管理 → 创建应用 → 添加 Key（选 Web 服务）",
                C_HINT, 11));
        content.addView(searchCard);

        // ② 地图
        LinearLayout mapCard = card("地图 · 拖动移动椭圆 / 拖 A B 手柄调形状");
        trackView = new TrackView(this);
        trackView.setListener(this);
        mapCard.addView(trackView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(330)));
        LinearLayout mapRow = row();
        mapRow.setPadding(0, dp(4), 0, 0);
        addToRow(mapRow, button("放大", v -> {
            tileMap.setZoom(tileMap.getZoom() + 1);
            trackView.invalidate();
            tvStatus.setText("地图缩放 Z" + tileMap.getZoom());
        }), 1);
        addToRow(mapRow, button("缩小", v -> {
            tileMap.setZoom(tileMap.getZoom() - 1);
            trackView.invalidate();
            tvStatus.setText("地图缩放 Z" + tileMap.getZoom());
        }), 1);
        addToRow(mapRow, button("回到中心", v -> {
            applyCenterFromFields();
            tvStatus.setText("地图与椭圆已回到输入的中心坐标");
        }), 1);
        addToRow(mapRow, btnLayer = button("卫星图", v -> toggleLayer()), 1);
        mapCard.addView(mapRow);
        content.addView(mapCard);

        tileMap = new TileMap(getCacheDir());
        tileMap.setOnTileLoaded(() -> trackView.postInvalidate());
        tileMap.setZoom(18);
        trackView.setMap(tileMap);

        // ③ 跑道参数
        LinearLayout paramCard = card("跑道参数");
        etLon = field(paramCard, "中心经度 BD-09", "119.93700271151",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | InputType.TYPE_NUMBER_FLAG_SIGNED);
        etLat = field(paramCard, "中心纬度 BD-09", "31.87580283148",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | InputType.TYPE_NUMBER_FLAG_SIGNED);
        etA = field(paramCard, "长轴半径 a (m)", "78",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etB = field(paramCard, "短轴半径 b (m)", "37",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etAngle = field(paramCard, "方位角 °(0=南北)", "0",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | InputType.TYPE_NUMBER_FLAG_SIGNED);
        etSpeed = field(paramCard, "速度 (m/s)", "2.7",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etLaps = field(paramCard, "圈数", "5", InputType.TYPE_CLASS_NUMBER);
        etPerimTarget = field(paramCard, "目标周长 (m)", "",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        paramCard.addView(label("真实度（轨迹抖动强度）", C_TEXT2, 12));
        LinearLayout realismRow = row();
        realismRow.setPadding(0, dp(4), 0, 0);
        String[] rNames = {"平滑", "标准", "颠簸", "狂野"};
        for (int i = 0; i < rNames.length; i++) {
            final int idx = i;
            realismBtns[i] = button(rNames[i], v -> setRealism(idx));
            addToRow(realismRow, realismBtns[i], 1);
        }
        paramCard.addView(realismRow);
        applyRealismToButtons();

        LinearLayout perimRow = row();
        perimRow.setPadding(0, dp(4), 0, 0);
        addToRow(perimRow, button("按周长调整 a/b", v -> applyPerimeter()), 1);
        addToRow(perimRow, button("预览跑道", v -> previewTrack()), 1);
        paramCard.addView(perimRow);
        tvPerim = label("", C_TEXT2, 12);
        tvPerim.setPadding(0, dp(6), 0, 0);
        paramCard.addView(tvPerim);
        TextWatcher w = new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                updatePerimHint();
            }
        };
        etA.addTextChangedListener(w);
        etB.addTextChangedListener(w);
        etSpeed.addTextChangedListener(w);
        content.addView(paramCard);

        // ④ 播放控制
        LinearLayout playCard = card("播放控制");
        LinearLayout r1 = row();
        addToRow(r1, primaryButton("开始回放", v -> play()), 1);
        addToRow(r1, button("停止", v -> {
            startService(new Intent(this, PlayerService.class)
                    .setAction(PlayerService.ACTION_STOP));
            tvStatus.setText("状态：已停止");
        }), 1);
        playCard.addView(r1);
        LinearLayout r2 = row();
        r2.setPadding(0, dp(4), 0, 0);
        addToRow(r2, button("重新开始", v -> restart()), 1);
        playCard.addView(r2);
        LinearLayout r3 = row();
        r3.setPadding(0, dp(4), 0, 0);
        r3.addView(label("倍速", C_TEXT2, 13));
        for (final double m : new double[]{0.5, 1.0, 2.0, 4.0}) {
            addToRow(r3, button(m == Math.floor(m) ? ((int) m) + "x" : m + "x", v -> {
                PlayerService.speedMultiplier = m;
                tvStatus.setText(String.format(Locale.CHINA,
                        "倍速已切换为 %.1fx（立即生效）", m));
            }), 1);
        }
        playCard.addView(r3);
        tvStatus = label("状态：已停止", C_TEXT2, 12);
        tvStatus.setPadding(0, dp(8), 0, 0);
        playCard.addView(tvStatus);
        content.addView(playCard);

        // ⑤ 预设 / 锁定 / 悬浮窗
        LinearLayout presetCard = card("预设与锁定（防误触）");
        tvStart = label("", C_TEXT2, 12);
        presetCard.addView(tvStart);
        LinearLayout pRow1 = row();
        pRow1.setPadding(0, dp(4), 0, 0);
        addToRow(pRow1, button("保存为预设", v -> savePreset()), 1);
        addToRow(pRow1, btnLock = button("锁定防误触", v -> toggleLock()), 1);
        presetCard.addView(pRow1);
        LinearLayout pRow2 = row();
        pRow2.setPadding(0, dp(4), 0, 0);
        addToRow(pRow2, button("开启悬浮窗", v -> startOverlay()), 1);
        addToRow(pRow2, button("关闭悬浮窗", v -> {
            stopService(new Intent(this, OverlayService.class));
            tvStatus.setText("悬浮窗已关闭");
        }), 1);
        presetCard.addView(pRow2);
        presetsBox = new LinearLayout(this);
        presetsBox.setOrientation(LinearLayout.VERTICAL);
        presetCard.addView(presetsBox);
        content.addView(presetCard);

        // ⑥ 收藏地点
        LinearLayout favCard = card("收藏地点（离线可用 · 长按删除）");
        placesBox = new LinearLayout(this);
        placesBox.setOrientation(LinearLayout.VERTICAL);
        favCard.addView(placesBox);
        content.addView(favCard);

        // ⑦ 使用说明（点按标题展开/收起，默认收起省空间）
        final LinearLayout helpCard = card(null);
        final TextView helpHead = label("▸ 使用说明（点按展开 · 首次使用请看）", C_TITLE, 14);
        helpHead.setTypeface(helpHead.getTypeface(), Typeface.BOLD);
        helpHead.setPadding(dp(2), dp(4), dp(2), dp(4));
        applyRipple(helpHead);
        helpCard.addView(helpHead);
        final LinearLayout helpBody = new LinearLayout(this);
        helpBody.setOrientation(LinearLayout.VERTICAL);
        helpBody.setVisibility(View.GONE);
        helpCard.addView(helpBody);
        helpHead.setOnClickListener(v -> {
            boolean open = helpBody.getVisibility() == View.GONE;
            helpBody.setVisibility(open ? View.VISIBLE : View.GONE);
            helpHead.setText(open ? "▾ 使用说明（点按收起）"
                    : "▸ 使用说明（点按展开 · 首次使用请看）");
        });
        addStep(helpBody, "① 打开开发者选项",
                "设置 → 我的设备 → 全部参数与信息 → 连点「MIUI 版本」7 次，"
                        + "直到提示「您已处于开发者模式」。（部分机型在：设置 → 关于手机）");
        addStep(helpBody, "② 授予模拟位置权限",
                "设置 → 更多设置 → 开发者选项 → 找到「选择模拟位置应用」→ 选中「拖跑」。"
                        + "若列表里没有本应用，先打开一次本应用再回来找。"
                        + "（部分机型在：设置 → 系统 → 开发者选项）");
        addStep(helpBody, "③ 调好跑道",
                "用「搜索地点」或直接拖动地图定位到操场，再调长轴/短轴/方位角/起点，"
                        + "点「预览跑道」确认形状贴住跑道。");
        addStep(helpBody, "④ 开始回放",
                "点「开始回放」，下拉通知栏看到「拖跑 · 轨迹回放中」，"
                        + "即已开始按 1 秒一个点注入位置。");
        addStep(helpBody, "⑤ 切到校园跑 App",
                "位置会实时跟着椭圆移动。建议先点「开始回放」再切过去，"
                        + "这样一进去位置就已经在跑道上了。");
        addStep(helpBody, "⑥ 悬浮窗（可选）",
                "点「开启悬浮窗」，首次会跳到系统授权页，打开「显示在其他应用上层」"
                        + "后返回再点一次，即可在其他 App 上层看小地图。");
        addStep(helpBody, "⑦ 防误触",
                "调好后点「保存为预设」，再长按该预设选「设为启动锁定」——"
                        + "以后每次打开都会自动恢复并锁定参数。");
        addStep(helpBody, "小提示",
                "界面显示的是 BD-09（百度）坐标，实际注入用的是 WGS-84，两者自动换算；"
                        + "地图为高德底图（GCJ-02）。彩蛋：按住页面最底部的「拖拖」不放 🎆");
        content.addView(helpCard);

        // ⑧ 免责声明（最底部）+ 彩蛋
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(18), dp(14), dp(18), dp(22));

        TextView dTitle = label("免责声明", C_TEXT2, 13);
        dTitle.setTypeface(dTitle.getTypeface(), Typeface.BOLD);
        footer.addView(dTitle);

        TextView dBody = label("本软件仅供学习研究、轨迹算法调试与虚拟位置测试使用，"
                + "严禁用于伪造或提交任何真实跑步记录（如校园跑成绩）等作弊行为，"
                + "亦不得用于任何违法用途。使用本软件所产生的一切后果由使用者自行承担。",
                C_HINT, 12);
        dBody.setLineSpacing(dp(3), 1f);
        dBody.setPadding(0, dp(6), 0, dp(12));
        footer.addView(dBody);

        LinearLayout authorRow = row();
        authorRow.addView(label("软件作者：", C_TEXT2, 13));
        final TextView author = label("拖拖", C_TITLE, 16);
        author.setTypeface(author.getTypeface(), Typeface.BOLD);
        author.setPadding(dp(8), dp(6), dp(8), dp(6));
        applyRipple(author);
        authorRow.addView(author);
        authorRow.addView(label("（长按有惊喜）", C_HINT, 11));
        footer.addView(authorRow);

        // 彩蛋：长按「拖拖」→ 一直放烟花，松手停止
        author.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(1.2f).scaleY(1.2f).setDuration(120).start();
                    v.postDelayed(fireworksStart, 350);      // 按住 350ms 起爆
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    v.removeCallbacks(fireworksStart);
                    if (fireworks != null && fireworks.isRunning()) {
                        fireworks.stop();
                    }
                    return true;
                default:
                    return false;
            }
        });
        content.addView(footer);

        progressReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (PlayerService.ACTION_PROGRESS.equals(intent.getAction())) {
                    onProgress(intent);
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(PlayerService.ACTION_PROGRESS);
        registerReceiver(progressReceiver, f);

        updatePerimHint();
        applyCenterFromFields();
        trackView.setEllipse(78, 37, 0);
        refreshPlaces();
        refreshPresets();
        updateStartLabel();
        syncSimState();

        // 启动时若设置了"锁定预设"，则自动加载并进入锁定状态（防误触）
        String lockName = presetStore.getLockedName();
        if (lockName != null && !lockName.isEmpty()) {
            PresetStore.Preset p = presetStore.get(lockName);
            if (p != null) {
                applyPreset(p);
                setUiLocked(true);
                tvStatus.setText("已加载并锁定预设：「" + p.name + "」");
            }
        }
    }

    // ---- 预设 / 锁定 / 悬浮窗 ----

    private void syncSimState() {
        double[] gcj = GeoTransform.bd09ToGcj02(getD(etLon, 119.93700271151),
                getD(etLat, 31.87580283148));
        SimState.centerGcjLon = gcj[0];
        SimState.centerGcjLat = gcj[1];
        SimState.aMeters = getD(etA, 78);
        SimState.bMeters = getD(etB, 37);
        SimState.angleDeg = getD(etAngle, 0);
        SimState.startAngleDeg = startAngleDeg;
        SimState.speedMps = getD(etSpeed, 2.7);
        SimState.laps = Math.max(1, getI(etLaps, 5));
        SimState.zoom = tileMap.getZoom();
        SimState.layer = tileMap.getLayer();
    }

    private double startAngleDeg = 0;

    private void updateStartLabel() {
        if (tvStart != null) {
            tvStart.setText(String.format(Locale.CHINA,
                    "起点：椭圆参数角 %.0f°（拖地图上的绿色「起」手柄可改）", startAngleDeg));
        }
    }

    /** 拖动起点手柄回调。 */
    @Override
    public void onStartChanged(double deg) {
        releaseFieldFocus();
        startAngleDeg = deg;
        SimState.startAngleDeg = deg;
        updateStartLabel();
        tvStatus.setText(String.format(Locale.CHINA, "起点已设为 %.0f°", deg));
    }

    private void toggleLayer() {
        boolean toSat = tileMap.getLayer() == TileMap.LAYER_STANDARD;
        tileMap.setLayer(toSat ? TileMap.LAYER_SATELLITE : TileMap.LAYER_STANDARD);
        SimState.layer = tileMap.getLayer();
        btnLayer.setText(toSat ? "标准图" : "卫星图");
        trackView.invalidate();
        tvStatus.setText(toSat ? "已切换卫星影像（含路网叠加）" : "已切换标准地图");
    }

    private void setUiLocked(boolean locked) {
        uiLocked = locked;
        trackView.setLocked(locked);
        for (EditText et : new EditText[]{etLon, etLat, etA, etB, etAngle, etSpeed,
                etLaps, etPerimTarget}) {
            et.setEnabled(!locked);
            et.setAlpha(locked ? 0.6f : 1f);
        }
        btnLock.setText(locked ? "已锁定（点此解锁）" : "锁定防误触");
        btnLock.setTextColor(locked ? 0xFFFFC107 : C_BTN_TEXT);
    }

    private void toggleLock() {
        setUiLocked(!uiLocked);
        tvStatus.setText(uiLocked ? "已锁定：地图与参数不可编辑（防误触）" : "已解锁：可以继续编辑");
    }

    private void savePreset() {
        final EditText input = new EditText(this);
        input.setHint("预设名称，如 学校操场");
        input.setTextColor(C_TEXT);
        input.setHintTextColor(C_HINT);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("保存为预设")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast("请填一个名称");
                        return;
                    }
                    presetStore.put(currentPreset(name));
                    refreshPresets();
                    toast("已保存预设：" + name);
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        styleDialog(dlg);
    }

    private PresetStore.Preset currentPreset(String name) {
        PresetStore.Preset p = new PresetStore.Preset();
        p.name = name;
        p.lonBd = getD(etLon, 0);
        p.latBd = getD(etLat, 0);
        p.a = getD(etA, 78);
        p.b = getD(etB, 37);
        p.angle = getD(etAngle, 0);
        p.startAngle = startAngleDeg;
        p.speed = getD(etSpeed, 2.7);
        p.laps = Math.max(1, getI(etLaps, 5));
        p.layer = tileMap.getLayer();
        p.zoom = tileMap.getZoom();
        p.realism = realism;
        return p;
    }

    private void applyPreset(PresetStore.Preset p) {
        etLon.setText(String.format(Locale.CHINA, "%.8f", p.lonBd));
        etLat.setText(String.format(Locale.CHINA, "%.8f", p.latBd));
        etA.setText(String.format(Locale.CHINA, "%.1f", p.a));
        etB.setText(String.format(Locale.CHINA, "%.1f", p.b));
        etAngle.setText(String.format(Locale.CHINA, "%.1f", p.angle));
        etSpeed.setText(String.format(Locale.CHINA, "%.2f", p.speed));
        etLaps.setText(String.valueOf(p.laps));
        startAngleDeg = p.startAngle;
        realism = p.realism;
        applyRealismToButtons();
        tileMap.setLayer(p.layer);
        tileMap.setZoom(p.zoom);
        btnLayer.setText(p.layer == TileMap.LAYER_SATELLITE ? "标准图" : "卫星图");
        trackView.setStartAngle(p.startAngle);
        updatePerimHint();
        updateStartLabel();
        applyCenterFromFields();
        trackView.setEllipse(p.a, p.b, p.angle);
        syncSimState();
    }

    private void refreshPresets() {
        if (presetsBox == null) {
            return;
        }
        presetsBox.removeAllViews();
        List<PresetStore.Preset> list = presetStore.list();
        String lockedName = presetStore.getLockedName();
        if (list.isEmpty()) {
            TextView t = label("还没有预设。调好位置/形状/速度后点「保存为预设」。",
                    C_TEXT2, 12);
            t.setPadding(dp(4), dp(6), dp(4), dp(6));
            presetsBox.addView(t);
            return;
        }
        for (final PresetStore.Preset p : list) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(8), dp(10), dp(8), dp(10));
            applyRipple(item);
            boolean isLocked = p.name.equals(lockedName);
            item.addView(label((isLocked ? "🔒 " : "") + p.name,
                    isLocked ? 0xFFFFC107 : C_TEXT, 16));
            item.addView(label(p.summary(), C_TEXT2, 12));
            item.setOnClickListener(v -> {
                applyPreset(p);
                tvStatus.setText("已载入预设：" + p.name);
            });
            item.setOnLongClickListener(v -> {
                final String[] actions = {"设为启动锁定", "取消启动锁定", "删除"};
                AlertDialog dlg = new AlertDialog.Builder(this)
                        .setTitle(p.name)
                        .setItems(actions, (d, which) -> {
                            if (which == 0) {
                                presetStore.setLockedName(p.name);
                                tvStatus.setText("下次打开将自动加载并锁定：" + p.name);
                            } else if (which == 1) {
                                presetStore.setLockedName("");
                                tvStatus.setText("已取消启动锁定");
                            } else {
                                presetStore.remove(p.name);
                                tvStatus.setText("已删除预设：" + p.name);
                            }
                            refreshPresets();
                        })
                        .create();
                dlg.show();
                styleDialog(dlg);
                return true;
            });
            presetsBox.addView(item);
        }
    }

    /** 开启悬浮窗（先检查"显示在其他应用上层"权限）。 */
    private void startOverlay() {
        if (android.os.Build.VERSION.SDK_INT >= 23
                && !android.provider.Settings.canDrawOverlays(this)) {
            toast("请先允许「显示在其他应用上层」");
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                toast("无法打开授权页，请到 设置→应用→拖跑→其他权限 里开启");
            }
            return;
        }
        syncSimState();
        startService(new Intent(this, OverlayService.class));
        tvStatus.setText("悬浮窗已开启（可拖动，✕ 关闭）");
    }

    // ---- 地点搜索 / 收藏 ----

    private void doSearch() {
        if (etSearch == null) {
            return;
        }
        String kw = etSearch.getText().toString().trim();
        if (kw.isEmpty()) {
            toast("请先输入地名");
            return;
        }
        String key = etAmapKey == null ? "" : etAmapKey.getText().toString().trim();
        cfgPut("amap_key", key);

        resultsBox.removeAllViews();
        // 1) 内置城市库：离线、必定有反馈
        final List<PlaceSearch.Result> offline = OfflineCities.search(kw);
        renderResults(offline, null);
        searchStatus.setText(String.format(Locale.CHINA,
                "内置库命中 %d 条，正在联网搜索…", offline.size()));
        tvStatus.setText("搜索中：" + kw);

        // 2) 联网补充（高德 key 优先，否则 OSM）
        searcher.search(kw, key, (list, error) -> {
            List<PlaceSearch.Result> merged = new ArrayList<>(offline);
            if (list != null) {
                for (PlaceSearch.Result r : list) {
                    boolean dup = false;
                    for (PlaceSearch.Result o : merged) {
                        if (Math.abs(o.lon - r.lon) < 1e-4
                                && Math.abs(o.lat - r.lat) < 1e-4) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) {
                        merged.add(r);
                    }
                }
            }
            renderResults(merged, error);
        });
    }

    /** 渲染结果列表；note 为在线搜索的失败说明（可为空）。 */
    private void renderResults(List<PlaceSearch.Result> list, String note) {
        resultsBox.removeAllViews();
        if (list.isEmpty()) {
            searchStatus.setText(note != null ? note
                    : "没有匹配结果。可直接拖动地图定位，或填经纬度。");
            return;
        }
        StringBuilder sb = new StringBuilder("共 " + list.size() + " 条，点击即可定位");
        if (note != null) {
            sb.append("（").append(note).append("）");
        }
        searchStatus.setText(sb.toString());
        tvStatus.setText(sb.toString());
        for (final PlaceSearch.Result r : list) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(8), dp(10), dp(8), dp(10));
            applyRipple(item);
            item.addView(label(r.name, C_TEXT, 16));
            item.addView(label(String.format(Locale.CHINA, "%s  [%s]  %.5f, %.5f",
                    r.detail, r.source, r.lat, r.lon), C_TEXT2, 12));
            item.setOnClickListener(v -> applySearchResult(r));
            resultsBox.addView(item);
        }
    }

    private String cfgGet(String k, String def) {
        return getSharedPreferences(PREF, MODE_PRIVATE).getString(k, def);
    }

    private void cfgPut(String k, String v) {
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putString(k, v).apply();
    }

    /** 选中搜索结果：WGS-84 -> 地图用 GCJ-02、输入框用 BD-09，并在图上打地名标签。 */
    private void applySearchResult(PlaceSearch.Result r) {
        double[] gcj = GeoTransform.wgs84ToGcj02(r.lon, r.lat);
        double[] bd = GeoTransform.gcj02ToBd09(gcj[0], gcj[1]);
        etLon.setText(String.format(Locale.CHINA, "%.8f", bd[0]));
        etLat.setText(String.format(Locale.CHINA, "%.8f", bd[1]));
        tileMap.setCenter(gcj[0], gcj[1]);
        if (tileMap.getZoom() < 16) {
            tileMap.setZoom(17);
        }
        trackView.setCenterLabel(r.name);      // 地图上给出"已跳转"的直观反馈
        trackView.invalidate();
        // 自动退出搜索框：收起结果列表 + 隐藏键盘 + 取消输入焦点，露出地图
        resultsBox.removeAllViews();
        etSearch.clearFocus();
        hideKeyboard();
        searchStatus.setText(String.format(Locale.CHINA,
                "已定位到「%s」。点「搜索」可重新列出结果。", r.name));
        tvStatus.setText(String.format(Locale.CHINA,
                "已定位到「%s」→ 中心 BD-09(%.7f, %.7f)", r.name, bd[0], bd[1]));
        toast("已定位：" + r.name);
    }

    private void saveCurrentPlace() {
        final EditText input = new EditText(this);
        input.setHint("地点名称，如 学校操场");
        input.setTextColor(C_TEXT);
        input.setHintTextColor(C_HINT);
        input.setText(etSearch.getText().toString().trim());
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("收藏当前中心")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = String.format(Locale.CHINA, "点 %.5f,%.5f",
                                getD(etLat, 0), getD(etLon, 0));
                    }
                    placeStore.put(name, getD(etLon, 0), getD(etLat, 0));
                    refreshPlaces();
                    toast("已收藏：" + name);
                })
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        styleDialog(dlg);
    }

    private void refreshPlaces() {
        if (placesBox == null) {
            return;
        }
        placesBox.removeAllViews();
        List<PlaceStore.Place> list = placeStore.list();
        if (list.isEmpty()) {
            TextView t = label("还没有收藏。拖地图选好位置后，点上面「收藏当前中心」。", C_TEXT2, 12);
            t.setPadding(dp(4), dp(6), dp(4), dp(6));
            placesBox.addView(t);
            return;
        }
        for (final PlaceStore.Place p : list) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(8), dp(10), dp(8), dp(10));
            applyRipple(item);
            item.addView(label(p.name, C_TEXT, 16));
            item.addView(label(String.format(Locale.CHINA, "BD-09 %.7f, %.7f",
                    p.lonBd, p.latBd), C_TEXT2, 12));
            item.setOnClickListener(v -> {
                etLon.setText(String.format(Locale.CHINA, "%.8f", p.lonBd));
                etLat.setText(String.format(Locale.CHINA, "%.8f", p.latBd));
                applyCenterFromFields();
                trackView.setCenterLabel(p.name);
                tvStatus.setText("已定位到收藏地点：" + p.name);
            });
            item.setOnLongClickListener(v -> {
                AlertDialog d2 = new AlertDialog.Builder(this)
                        .setTitle("删除收藏")
                        .setMessage("确定删除「" + p.name + "」？")
                        .setPositiveButton("删除", (d, w) -> {
                            placeStore.remove(p.name);
                            refreshPlaces();
                        })
                        .setNegativeButton("取消", null)
                        .create();
                d2.show();
                styleDialog(d2);
                return true;
            });
            placesBox.addView(item);
        }
    }

    /** 拖动地图改参数时，先让输入框失焦：否则 setText 会让 ScrollView 自动滚到光标处，整页跳动。 */
    private void releaseFieldFocus() {
        View f = getCurrentFocus();
        if (f instanceof EditText) {
            f.clearFocus();
            hideKeyboard();
        }
    }

    /** 收起软键盘。 */
    private void hideKeyboard() {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
            View focus = getCurrentFocus();
            if (imm != null && focus != null) {
                imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            }
        } catch (Exception ignored) {
            // 没有键盘就算了
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---- 参数读取与校验 ----

    private double getD(EditText et, double def) {
        try {
            return Double.parseDouble(et.getText().toString().trim());
        } catch (Exception e) {
            return def;
        }
    }

    private int getI(EditText et, int def) {
        try {
            return Integer.parseInt(et.getText().toString().trim());
        } catch (Exception e) {
            return def;
        }
    }

    private boolean validate() {
        if (getD(etLon, 0) == 0 && getD(etLat, 0) == 0) {
            tvStatus.setText("错误：请填写中心点 BD-09 坐标（或用搜索/地图选点）");
            return false;
        }
        if (getD(etA, 0) <= 0 || getD(etB, 0) <= 0) {
            tvStatus.setText("错误：半轴半径必须为正数");
            return false;
        }
        return true;
    }

    private void setRealism(int idx) {
        realism = Math.max(0, Math.min(3, idx));
        applyRealismToButtons();
        if (tvStatus != null) {
            tvStatus.setText("真实度：" + new String[]{"平滑", "标准", "颠簸", "狂野"}[realism]
                    + "（下一轮回放生效）");
        }
    }

    private void applyRealismToButtons() {
        for (int i = 0; i < realismBtns.length; i++) {
            if (realismBtns[i] != null) {
                styleButton(realismBtns[i], i == realism);
            }
        }
    }

    /** 把真实度档位映射到轨迹生成参数（映射表在 EllipseTrack，避免两处不一致）。 */
    private void applyRealism(EllipseTrack.Params p) {
        EllipseTrack.applyRealism(p, realism);
    }

    private EllipseTrack.Params buildParams() {
        EllipseTrack.Params p = new EllipseTrack.Params();
        double[] wgs = GeoTransform.bd09ToWgs84(getD(etLon, 119.93700271151),
                getD(etLat, 31.87580283148));
        p.lon = wgs[0];
        p.lat = wgs[1];
        p.aMeters = getD(etA, 78);
        p.bMeters = getD(etB, 37);
        p.angleDeg = getD(etAngle, 0);
        p.speedMps = getD(etSpeed, 2.7);
        p.laps = Math.max(1, getI(etLaps, 5));
        p.ccw = true;
        p.startAngleDeg = startAngleDeg;
        applyRealism(p);
        return p;
    }

    private static double ramanujanPerim(double a, double b) {
        double h = Math.pow(a - b, 2) / Math.pow(a + b, 2);
        return Math.PI * (a + b) * (1 + 3 * h / (10 + Math.sqrt(4 - 3 * h)));
    }

    private void updatePerimHint() {
        double a = getD(etA, 0), b = getD(etB, 0);
        if (a > 0 && b > 0) {
            double perim = ramanujanPerim(a, b);
            double lapT = perim / Math.max(0.1, getD(etSpeed, 2.7));
            tvPerim.setText(String.format(Locale.CHINA,
                    "周长 ≈ %.1f 米   圈速 ≈ %.0f 秒（%.1f 分钟）",
                    perim, lapT, lapT / 60));
        } else {
            tvPerim.setText("周长: -");
        }
    }

    private void applyPerimeter() {
        double target = getD(etPerimTarget, 0);
        if (target <= 0) {
            toast("请先填写目标周长（米）");
            return;
        }
        double a0 = getD(etA, 78), b0 = getD(etB, 37);
        if (a0 <= 0 || b0 <= 0) {
            a0 = 78;
            b0 = 37;
        }
        final double ratio = a0 / b0;
        double lo = 0.01, hi = Math.max(10.0, target);
        for (int i = 0; i < 80; i++) {
            double s = (lo + hi) / 2;
            if (ramanujanPerim(s * ratio, s) < target) {
                lo = s;
            } else {
                hi = s;
            }
        }
        double s = (lo + hi) / 2;
        etA.setText(String.format(Locale.CHINA, "%.1f", s * ratio));
        etB.setText(String.format(Locale.CHINA, "%.1f", s));
        updatePerimHint();
        trackView.setEllipse(s * ratio, s, getD(etAngle, 0));
        tvStatus.setText(String.format(Locale.CHINA,
                "已按周长 %.0f 米设置：a=%.1f, b=%.1f（比例 %.2f）", target, s * ratio, s, ratio));
    }

    private void applyToView(EllipseTrack.Params p) {
        trackView.setEllipse(p.aMeters, p.bMeters, p.angleDeg);
        trackView.setStartAngle(startAngleDeg);
        applyCenterFromFields();
        syncSimState();
    }

    private void applyCenterFromFields() {
        double[] gcj = GeoTransform.bd09ToGcj02(getD(etLon, 119.93700271151),
                getD(etLat, 31.87580283148));
        tileMap.setCenter(gcj[0], gcj[1]);
        trackView.invalidate();
    }

    @Override
    public void onShapeChanged(double aMeters, double bMeters, double angleDeg) {
        releaseFieldFocus();      // 防止 setText 触发 ScrollView 滚到光标（页面/地图跳动）
        etA.setText(String.format(Locale.CHINA, "%.1f", aMeters));
        etB.setText(String.format(Locale.CHINA, "%.1f", bMeters));
        etAngle.setText(String.format(Locale.CHINA, "%.1f", angleDeg));
        updatePerimHint();
        tvStatus.setText(String.format(Locale.CHINA,
                "拖动中：a=%.0f  b=%.0f  方位角=%.0f°（周长≈%.0f）",
                aMeters, bMeters, angleDeg, ramanujanPerim(aMeters, bMeters)));
    }

    @Override
    public void onCenterChanged(double gcjLon, double gcjLat) {
        releaseFieldFocus();
        double[] bd = GeoTransform.gcj02ToBd09(gcjLon, gcjLat);
        etLon.setText(String.format(Locale.CHINA, "%.8f", bd[0]));
        etLat.setText(String.format(Locale.CHINA, "%.8f", bd[1]));
        trackView.setCenterLabel("");       // 手动移动后，地点标签失效
        tvStatus.setText(String.format(Locale.CHINA,
                "椭圆已移动：中心 BD-09(%.7f, %.7f)", bd[0], bd[1]));
    }

    // ---- 动作 ----

    private void previewTrack() {
        if (!validate()) {
            return;
        }
        EllipseTrack.Params p = buildParams();
        applyToView(p);
        trackView.resetRan();
        double[] wgs = GeoTransform.bd09ToWgs84(getD(etLon, 0), getD(etLat, 0));
        tvStatus.setText(String.format(Locale.CHINA,
                "预览完成：中心 BD-09(%.6f,%.6f) → 注入用 WGS-84(%.6f,%.6f)",
                getD(etLon, 0), getD(etLat, 0), wgs[0], wgs[1]));
    }

    private void play() {
        play(true);      // 默认续跑：参数没变就从暂停处接着跑
    }

    private void play(boolean resume) {
        if (!validate()) {
            return;
        }
        EllipseTrack.Params p = buildParams();
        activeParams = p;
        applyToView(p);
        boolean willResume = resume && PlayerService.willResume(p, realism);
        if (!willResume) {
            trackView.resetRan();     // 只有全新一轮才清轨迹
            lastLap = 0;
        }
        // 记录完整参数，供悬浮窗"开始回放"复用它
        SimState.lastParams = p;
        SimState.realism = realism;
        startService(PlayerService.playIntent(this, p, realism, resume));
        tvStatus.setText(willResume ? "状态：从暂停处继续回放" : "状态：回放中（参数已提交）");
    }

    private void restart() {
        if (!validate()) {
            return;
        }
        trackView.resetRan();
        lastLap = 0;
        PlayerService.speedMultiplier = 1.0;
        play(false);              // 重新开始 = 从第 1 圈重跑
        tvStatus.setText("状态：已重新开始（从第 1 圈注入）");
    }

    // ---- 进度回调 ----

    private void onProgress(Intent intent) {
        int lap = intent.getIntExtra(PlayerService.EXTRA_LAP, 0);
        int total = intent.getIntExtra(PlayerService.EXTRA_TOTAL_LAPS, 5);
        double lon = intent.getDoubleExtra(PlayerService.EXTRA_LON, 0);
        double lat = intent.getDoubleExtra(PlayerService.EXTRA_LAT, 0);
        double speed = intent.getDoubleExtra(PlayerService.EXTRA_SPEED, 0);
        if (lap <= 0) {
            trackView.setHud(1, total, "");
            return;
        }
        double[] gcj = GeoTransform.wgs84ToGcj02(lon, lat);
        boolean newLap = lap != lastLap;
        lastLap = lap;
        trackView.addRanPointGcj(gcj[0], gcj[1], newLap);
        trackView.setHud(lap, total,
                String.format(Locale.CHINA, "%.1f m/s  %.1f km/h", speed, speed * 3.6));
        tvStatus.setText(String.format(Locale.CHINA,
                "回放中 — 第 %d/%d 圈  WGS(%.6f, %.6f)", lap, total, lon, lat));
    }

    @Override
    public void onBackPressed() {
        // 按返回退出时自动打开悬浮窗（前提：已授权"显示在其他应用上层"）
        autoShowOverlay();
        super.onBackPressed();
    }

    /** 退出 App 时若已授权悬浮窗权限则自动开启；未授权则跳过（不打扰）。 */
    private void autoShowOverlay() {
        if (android.os.Build.VERSION.SDK_INT < 23
                || android.provider.Settings.canDrawOverlays(this)) {
            syncSimState();
            startService(new Intent(this, OverlayService.class));
        }
    }

    @Override
    protected void onDestroy() {
        // 其余finish路径（开发者选项"不保留活动"、切后台被杀前的finish等）同样触发
        if (isFinishing()) {
            autoShowOverlay();
        }
        super.onDestroy();
        if (progressReceiver != null) {
            unregisterReceiver(progressReceiver);
        }
        if (fireworks != null) {
            fireworks.stop();
        }
    }

    /** 简化 TextWatcher。 */
    abstract static class SimpleWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int st, int c, int a) {
        }

        @Override
        public void onTextChanged(CharSequence s, int st, int b, int c) {
        }

        @Override
        public abstract void afterTextChanged(Editable s);
    }
}
