package com.example.mockgps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 彩蛋：满屏烟花。长按免责声明里的「拖拖」时持续燃放，松手即停。
 *
 * 花型：球形 / 环形 / 双色环 / 柳树（长垂尾） / 棕榈（粗长尾）+ 随机十字星。
 * 每颗火花带拖尾（上一帧位置连线）与辉光，爆炸瞬间有闪光。
 */
public class FireworksView extends View {

    private static class Spark {
        float x, y, px, py, vx, vy, life, maxLife, size;
        int color;
        float gravity, drag;
    }

    private static class Rocket {
        float x, y, vy, targetY;
        int color;
    }

    private static class Flash {
        float x, y, life, maxLife, radius;
        int color;
    }

    private static final int MAX_SPARKS = 2200;

    private final List<Spark> sparks = new ArrayList<>();
    private final List<Rocket> rockets = new ArrayList<>();
    private final List<Flash> flashes = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trail = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;
    private long lastMs;
    private long lastLaunchMs;

    public FireworksView(Context c) {
        super(c);
        paint.setStyle(Paint.Style.FILL);
        glow.setStyle(Paint.Style.FILL);
        trail.setStyle(Paint.Style.STROKE);
        setClickable(false);
        setFocusable(false);
    }

    public boolean isRunning() {
        return running;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        sparks.clear();
        rockets.clear();
        flashes.clear();
        lastMs = System.currentTimeMillis();
        lastLaunchMs = 0;
        setVisibility(VISIBLE);
        handler.post(loop);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(loop);
        sparks.clear();
        rockets.clear();
        flashes.clear();
        setVisibility(GONE);
        invalidate();
    }

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            long now = System.currentTimeMillis();
            float dt = Math.min(40, now - lastMs) / 1000f;
            lastMs = now;
            step(dt, now);
            invalidate();
            handler.postDelayed(this, 16);
        }
    };

    private void step(float dt, long now) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        // 更密集地发射（有时一次两发）
        if (now - lastLaunchMs > 180 + rnd.nextInt(220)) {
            lastLaunchMs = now;
            launch(w, h);
            if (rnd.nextInt(3) == 0) {
                launch(w, h);
            }
        }
        for (int i = rockets.size() - 1; i >= 0; i--) {
            Rocket r = rockets.get(i);
            r.y += r.vy * dt;
            if (r.y <= r.targetY) {
                explode(r.x, r.y, r.color);
                rockets.remove(i);
            }
        }
        for (int i = flashes.size() - 1; i >= 0; i--) {
            Flash f = flashes.get(i);
            f.life -= dt;
            if (f.life <= 0) {
                flashes.remove(i);
            }
        }
        for (int i = sparks.size() - 1; i >= 0; i--) {
            Spark s = sparks.get(i);
            s.life -= dt;
            if (s.life <= 0 || s.y > h + 60) {
                sparks.remove(i);
                continue;
            }
            s.px = s.x;
            s.py = s.y;
            s.vy += s.gravity * dt;
            s.vx *= (1 - s.drag * dt);
            s.vy *= (1 - s.drag * 0.85f * dt);
            s.x += s.vx * dt;
            s.y += s.vy * dt;
        }
    }

    private void launch(int w, int h) {
        Rocket r = new Rocket();
        r.x = w * (0.08f + rnd.nextFloat() * 0.84f);
        r.y = h + 8;
        r.vy = -(h * (0.9f + rnd.nextFloat() * 0.6f));
        r.targetY = h * (0.06f + rnd.nextFloat() * 0.42f);
        r.color = randomColor();
        rockets.add(r);
    }

    /** 爆炸：随机花型。 */
    private void explode(float x, float y, int baseColor) {
        flashes.add(newFlash(x, y, baseColor));
        int type = rnd.nextInt(5);
        switch (type) {
            case 1:
                ring(x, y, baseColor, 1);
                break;
            case 2:
                ring(x, y, baseColor, 2);
                break;
            case 3:
                willow(x, y, baseColor);
                break;
            case 4:
                palm(x, y, baseColor);
                break;
            default:
                sphere(x, y, baseColor);
        }
    }

    /** 球形：各向同性。 */
    private void sphere(float x, float y, int color) {
        int n = 120 + rnd.nextInt(70);
        for (int i = 0; i < n; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            double sp = 60 + Math.pow(rnd.nextDouble(), 0.5) * 320;
            addSpark(x, y, a, sp, color, 0.9f + rnd.nextFloat() * 1.0f, 300, 1.05f,
                    2.5f + rnd.nextFloat() * 3.5f);
        }
    }

    /** 环形：速度一致，形成清晰的圆环。 */
    private void ring(float x, float y, int color, int rings) {
        int per = 66 + rnd.nextInt(28);
        for (int k = 0; k < rings; k++) {
            int c = k == 0 ? color : randomColor();
            double sp = 190 + k * 110 + rnd.nextInt(40);
            for (int i = 0; i < per; i++) {
                double a = Math.PI * 2 * i / per + rnd.nextDouble() * 0.05;
                addSpark(x, y, a, sp * (0.95 + rnd.nextDouble() * 0.1), c,
                        1.0f + rnd.nextFloat() * 0.7f, 260, 0.9f,
                        2.5f + rnd.nextFloat() * 3f);
            }
        }
    }

    /** 柳树：寿命长、重力大，慢慢垂下。 */
    private void willow(float x, float y, int color) {
        int n = 70 + rnd.nextInt(40);
        for (int i = 0; i < n; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            double sp = 50 + rnd.nextDouble() * 180;
            addSpark(x, y, a, sp, rnd.nextInt(4) == 0 ? randomColor() : color,
                    1.8f + rnd.nextFloat() * 1.2f, 420, 0.55f,
                    3f + rnd.nextFloat() * 3f);
        }
    }

    /** 棕榈：条数少、粗、尾长。 */
    private void palm(float x, float y, int color) {
        int n = 22 + rnd.nextInt(14);
        for (int i = 0; i < n; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            double sp = 260 + rnd.nextDouble() * 220;
            addSpark(x, y, a, sp, rnd.nextInt(3) == 0 ? randomColor() : color,
                    1.5f + rnd.nextFloat() * 0.9f, 300, 0.8f,
                    6f + rnd.nextFloat() * 5f);
        }
    }

    private void addSpark(float x, float y, double angle, double speed, int color,
                          float life, float gravity, float drag, float size) {
        if (sparks.size() >= MAX_SPARKS) {
            return;
        }
        Spark s = new Spark();
        s.x = x;
        s.y = y;
        s.px = x;
        s.py = y;
        s.vx = (float) (Math.cos(angle) * speed);
        s.vy = (float) (Math.sin(angle) * speed);
        s.maxLife = life;
        s.life = life;
        s.color = color;
        s.size = size;
        s.gravity = gravity;
        s.drag = drag;
        sparks.add(s);
    }

    private Flash newFlash(float x, float y, int color) {
        Flash f = new Flash();
        f.x = x;
        f.y = y;
        f.color = color;
        f.maxLife = 0.28f;
        f.life = f.maxLife;
        f.radius = 60 + rnd.nextFloat() * 60;
        return f;
    }

    private int randomColor() {
        float[] hsv = {rnd.nextInt(360), 0.9f, 1f};
        return Color.HSVToColor(hsv);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);

        // 爆炸闪光
        for (Flash f : flashes) {
            float t = Math.max(0f, f.life / f.maxLife);
            glow.setColor(f.color);
            glow.setAlpha((int) (90 * t));
            float r = f.radius * (1.6f - t * 0.6f);
            c.drawCircle(f.x, f.y, r, glow);
            glow.setAlpha((int) (170 * t));
            c.drawCircle(f.x, f.y, r * 0.45f, glow);
        }

        // 上升中的火箭（带尾迹）
        for (Rocket r : rockets) {
            trail.setColor(r.color);
            trail.setStrokeWidth(4);
            trail.setAlpha(220);
            c.drawLine(r.x, r.y, r.x, r.y + 26, trail);
            paint.setColor(Color.WHITE);
            paint.setAlpha(255);
            c.drawCircle(r.x, r.y, 4.5f, paint);
        }

        // 火花：拖尾 + 辉光 + 芯
        for (Spark s : sparks) {
            float f = Math.max(0f, s.life / s.maxLife);
            int alpha = (int) (255 * Math.min(1f, f * 1.8f));
            trail.setColor(s.color);
            trail.setStrokeWidth(s.size * 0.75f);
            trail.setAlpha(Math.max(0, alpha - 40));
            c.drawLine(s.px, s.py, s.x, s.y, trail);

            glow.setColor(s.color);
            glow.setAlpha(alpha / 4);
            c.drawCircle(s.x, s.y, s.size * (1.6f + (1 - f)), glow);

            paint.setColor(s.color);
            paint.setAlpha(alpha);
            c.drawCircle(s.x, s.y, s.size * (0.35f + f * 0.75f), paint);
        }
    }

    /** 供外部判断是否有烟花在跑（例如自适应帧率）。 */
    public int particleCount() {
        return sparks.size();
    }
}
