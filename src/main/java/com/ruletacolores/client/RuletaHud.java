package com.ruletacolores.client;

import com.ruletacolores.RuletaColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Estado y dibujo de la ruleta en pantalla (la ven todos los jugadores) + notificacion. */
final class RuletaHud {
    static final RuletaHud INSTANCE = new RuletaHud();

    // duraciones (segundos)
    private static final double ENTER = 1.35;
    private static final double FLOAT = 1.7;
    private static final double SPIN = 5.2;
    private static final double HOLD = 7.5;
    private static final double EXIT = 1.2;
    private static final long NOTE_MS = 120_000L; // 2 minutos

    /** Angulo (en grados, sentido horario desde arriba) donde esta la flecha: 270 = a la izquierda. */
    private static final float POINTER_ANGLE = 270f;
    private static final float[] LAND_PITCH = {0.65f, 1.0f, 1.2f, 0.8f, 1.4f, 1.1f, 1.25f, 0.9f};

    private record Req(int color, String title, String text) {}

    private enum Phase { IDLE, ENTER, FLOAT, SPIN, LANDED, EXIT }

    private final Random rnd = new Random();
    private final ArrayDeque<Req> queue = new ArrayDeque<>();
    private final ColorFx fx = new ColorFx();

    private Phase phase = Phase.IDLE;
    private double t;
    private Req cur;
    private float theta, lastTheta, vel, spinFrom, spinTotal, kick, flash;
    private int flashRgb;
    private int winner, target, lastSec;
    private long lastTickMs;
    private long lastNano;
    private float gcx, gcy, gR, gsw, gsh;
    private float clock, frameDt;

    private final int[] prev = new int[8];
    private final int[] shown = new int[8];
    private final float[] fade = new float[8];
    private final boolean[] recolored = new boolean[8];

    // notificacion
    private boolean nActive, nHidden;
    private String nTitle = "", nText = "";
    private int nRgb;
    private long nAt;

    private RuletaHud() {
        for (int k = 0; k < 8; k++) {
            shown[k] = prev[k] = (k + 2) % 8;
            fade[k] = 1f;
        }
    }

    static int sectorOf(int colorId) {
        return Math.floorMod(colorId - 2, 8);
    }

    void enqueue(int color, String title, String text) {
        queue.add(new Req(Math.floorMod(color, 8), title == null ? "" : title, text == null ? "" : text));
    }

    void reset() {
        queue.clear();
        phase = Phase.IDLE;
        cur = null;
        nActive = false;
        fx.clear();
    }

    void hideNotification() {
        if (nActive) nHidden = true;
    }

    // ------------------------------------------------------------------ logica

    private void begin(Req r) {
        cur = r;
        target = r.color();
        winner = sectorOf(target);
        for (int k = 0; k < 8; k++) {
            shown[k] = prev[k] = (k + 2) % 8;
            fade[k] = 1f;
            recolored[k] = false;
        }
        theta = lastTheta = 0f;
        vel = 0f;
        kick = 0f;
        lastSec = Integer.MIN_VALUE;
        phase = Phase.ENTER;
        t = 0;
        Sfx.play("enter", 1f, 0.9f);
    }

    private void startSpin() {
        phase = Phase.SPIN;
        t = 0;
        float off = 22.5f + (rnd.nextFloat() - 0.5f) * 26f;
        float aw = 45f * winner + off;
        float need = (((POINTER_ANGLE - aw - theta) % 360f) + 360f) % 360f;
        spinFrom = theta;
        spinTotal = need + 360f * (5 + rnd.nextInt(2));
        lastSec = (int) Math.floor((POINTER_ANGLE - theta) / 45f);
        Sfx.play("spin_start", 1f, 0.9f);
    }

    private void land() {
        theta = spinFrom + spinTotal;
        vel = 0f;
        phase = Phase.LANDED;
        t = 0;
        Sfx.play("land", LAND_PITCH[target], 1f);
        flash = 1f;
        flashRgb = RuletaColor.byId(target).rgb;
        fx.start(target);
        fx.burst(gcx, gcy, gR);
        postMessage(cur);
    }

    private void postMessage(Req r) {
        MinecraftClient mc = MinecraftClient.getInstance();
        RuletaColor c = RuletaColor.byId(r.color());
        int rgb = c.rgb;
        String title = r.title().toUpperCase(Locale.ROOT);

        if (mc.inGameHud != null) {
            Style lineStyle = Style.EMPTY.withColor(rgb).withStrikethrough(true);
            Style textStyle = Style.EMPTY.withColor(rgb);
            Text line = Text.literal(" ".repeat(60)).setStyle(lineStyle);
            mc.inGameHud.getChatHud().addMessage(line);
            if (!title.isEmpty()) {
                mc.inGameHud.getChatHud().addMessage(Text.literal(title).setStyle(textStyle.withBold(true)));
            }
            if (!r.text().isEmpty()) {
                for (String s : r.text().split("\n")) {
                    if (!s.isBlank()) {
                        mc.inGameHud.getChatHud().addMessage(Text.literal(s).setStyle(textStyle));
                    }
                }
            }
            mc.inGameHud.getChatHud().addMessage(line);
        }

        nTitle = title.isEmpty() ? c.label.toUpperCase(Locale.ROOT) : title;
        nText = r.text();
        nRgb = rgb;
        nAt = System.currentTimeMillis();
        nHidden = false;
        nActive = true;
    }

    private void update(float dt) {
        clock += dt;
        flash = Math.max(0f, flash - dt * 1.6f);
        kick *= (float) Math.exp(-dt * 14f);
        for (int k = 0; k < 8; k++) {
            if (fade[k] < 1f) fade[k] = Math.min(1f, fade[k] + dt / 0.35f);
        }
        switch (phase) {
            case IDLE -> {
                Req r = queue.poll();
                if (r != null) begin(r);
            }
            case ENTER -> {
                t += dt;
                if (t >= ENTER) {
                    phase = Phase.FLOAT;
                    t = 0;
                }
            }
            case FLOAT -> {
                t += dt;
                if (t >= FLOAT) startSpin();
            }
            case SPIN -> {
                t += dt;
                float u = (float) Math.min(1.0, t / SPIN);
                float f = 1f - (float) Math.pow(1f - u, 4);
                theta = spinFrom + spinTotal * f;
                int sec = (int) Math.floor((POINTER_ANGLE - theta) / 45f);
                if (sec != lastSec) {
                    lastSec = sec;
                    kick = 1f;
                    long now = System.currentTimeMillis();
                    if (now - lastTickMs >= 45) {
                        lastTickMs = now;
                        float sp = Math.min(1f, Math.abs(vel) / 1200f);
                        Sfx.play("tick", 0.75f + 0.5f * sp, 0.6f);
                    }
                }
                if (u >= 1f) land();
            }
            case LANDED -> {
                t += dt;
                for (int j = 1; j <= 7; j++) {
                    int k = (winner + j) % 8;
                    double at = 0.7 + (j - 1) * 0.35;
                    if (!recolored[k] && t >= at) {
                        recolored[k] = true;
                        prev[k] = shown[k];
                        shown[k] = target;
                        fade[k] = 0f;
                        Sfx.play("recolor", 0.7f + 0.09f * j, 0.8f);
                    }
                }
                if (t >= HOLD) {
                    phase = Phase.EXIT;
                    t = 0;
                    Sfx.play("exit", 1f, 0.9f);
                }
            }
            case EXIT -> {
                t += dt;
                if (t >= EXIT) {
                    phase = Phase.IDLE;
                    t = 0;
                    cur = null;
                    fx.clear();
                }
            }
        }
        if (dt > 0f) {
            vel = vel * 0.6f + 0.4f * (theta - lastTheta) / dt;
        }
        lastTheta = theta;
    }

    // ------------------------------------------------------------------ dibujo

    void render(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;

        long now = System.nanoTime();
        float dt = lastNano == 0 ? 0f : Math.min(0.1f, (now - lastNano) / 1e9f);
        lastNano = now;
        frameDt = dt;

        gsw = mc.getWindow().getScaledWidth();
        gsh = mc.getWindow().getScaledHeight();
        gcx = gsw / 2f;
        gcy = gsh / 2f;
        float Rv = Math.min(gsh * 0.30f, gsw * 0.22f);
        gR = Rv;

        update(dt);

        MatrixStack ms = ctx.getMatrices();
        Gfx.begin();
        if (phase != Phase.IDLE) {
            drawWheelScene(ms, Rv);
        }
        if (flash > 0.001f) {
            Gfx.fill(ms.peek().getPositionMatrix(), 0, 0, gsw, gsh, ((int) (flash * 0.35f * 255f) << 24) | (flashRgb & 0xFFFFFF));
        }
        drawNotification(ctx, mc);
        Gfx.end();
    }

    private void drawWheelScene(MatrixStack ms, float Rv) {
        float R = Rv / Gfx.TEX_RATIO;
        float alpha = 1f, offY = 0f, sx = 1f, sy = 1f, amp = 0f, shear = 0f;
        float wavePhase = clock * 14f;
        float fxK = 1f;

        switch (phase) {
            case ENTER -> {
                float u = (float) (t / ENTER);
                float c1 = 1.4f, c3 = c1 + 1f;
                float p = 1f + c3 * (float) Math.pow(u - 1f, 3) + c1 * (float) Math.pow(u - 1f, 2);
                float start = -(gcy + Rv * 1.7f);
                offY = start * (1f - p);
                float s = (1f - u);
                amp = 0.07f * s * s;
                sy = 1f + 0.30f * s * s;
                if (u > 0.78f) sy *= 1f - 0.10f * (float) Math.sin((u - 0.78f) / 0.22f * Math.PI);
                sx = 1f / (float) Math.sqrt(sy);
                shear = 0.16f * (float) Math.sin(t * 10.0) * s;
                alpha = Math.min(1f, u * 4f);
            }
            case FLOAT -> {
                float k = 1f - (float) (t / FLOAT);
                offY = (float) Math.sin(t * Math.PI * 2.0) * Rv * 0.05f * k;
            }
            case LANDED -> {
                float pun = Math.max(0f, 1f - (float) t * 3f);
                sx = sy = 1f + 0.07f * pun;
            }
            case EXIT -> {
                float u = (float) (t / EXIT);
                offY = u * u * (gsh - gcy + Rv * 1.7f);
                amp = 0.07f * u;
                sy = 1f + 0.30f * u * u;
                sx = 1f / (float) Math.sqrt(sy);
                shear = 0.16f * (float) Math.sin(t * 10.0) * u;
                fxK = Math.max(0f, 1f - u * 1.4f);
            }
            default -> {}
        }

        float cx = gcx, cy = gcy + offY;
        fx.update(frameDt, gcx, gcy, Rv, gsw, gsh, fxK);

        Matrix4f scr = ms.peek().getPositionMatrix();

        // aura y rayos detras
        if (phase == Phase.LANDED || phase == Phase.EXIT) {
            RuletaColor c = RuletaColor.byId(target);
            float r = ((c.rgb >> 16) & 255) / 255f, g = ((c.rgb >> 8) & 255) / 255f, b = (c.rgb & 255) / 255f;
            float pulse = 0.55f + 0.2f * (float) Math.sin(clock * 3f);
            Gfx.additive(true);
            Gfx.quad(scr, Gfx.GLOW, cx - Rv * 2.1f, cy - Rv * 2.1f, cx + Rv * 2.1f, cy + Rv * 2.1f, r, g, b, pulse * fxK);
            Gfx.additive(false);
        }
        fx.drawBack(ms, cx, cy, Rv, fxK);

        // ruleta (con desenfoque de movimiento al girar)
        ms.push();
        ms.translate(cx, cy, 0f);
        ms.scale(sx, sy, 1f);
        float spd = Math.abs(vel);
        float spread = Math.min(30f, spd * 0.011f);
        int copies = phase == Phase.SPIN ? 1 + Math.min(7, (int) (spd / 170f)) : 1;
        float dir = vel >= 0f ? 1f : -1f;
        for (int c = 0; c < copies; c++) {
            float ang = theta - dir * spread * c / copies;
            Gfx.wheel(ms, R, ang, prev, shown, fade, alpha / (c + 1), amp, wavePhase, shear);
        }
        ms.pop();

        // flecha dorada (a la izquierda, apuntando al centro; rebota al pasar cada gajo)
        ms.push();
        ms.translate(cx, cy, 0f);
        ms.scale(sx, sy, 1f);
        ms.translate(-Rv * 0.97f, 0f, 0f);
        ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-90f - kick * 20f));
        float ph = Rv * 0.62f, pw = ph * 0.8f;
        Matrix4f pm = ms.peek().getPositionMatrix();
        Gfx.quad(pm, Gfx.POINTER, -pw / 2f + 2f, -ph + 3f, pw / 2f + 2f, 3f, 0f, 0f, 0f, 0.30f * alpha);
        Gfx.quad(pm, Gfx.POINTER, -pw / 2f, -ph, pw / 2f, 0f, 1f, 1f, 1f, alpha);
        ms.pop();

        fx.drawFront(ms, gsw, gsh, fxK);
    }

    private void drawNotification(DrawContext ctx, MinecraftClient mc) {
        if (!nActive) return;
        long age = System.currentTimeMillis() - nAt;
        if (age >= NOTE_MS) {
            nActive = false;
            return;
        }
        if (nHidden) return;

        TextRenderer tr = mc.textRenderer;
        int w = 236;
        List<OrderedText> lines = tr.wrapLines(Text.literal(nText), w - 16);
        if (lines.size() > 5) lines = lines.subList(0, 5);

        float slide = (float) Math.min(1.0, age / 350.0);
        slide = 1f - (float) Math.pow(1f - slide, 3);
        float x = 4f - (w + 8f) * (1f - slide);
        float y = 4f;
        int titleH = 24;
        int bodyH = Math.max(1, lines.size()) * 10 + 8;
        int footH = 15;
        int total = titleH + bodyH + footH;
        int col = 0xFF000000 | nRgb;

        Matrix4f m = ctx.getMatrices().peek().getPositionMatrix();
        Gfx.fill(m, x, y, x + w, y + titleH, 0xE6000000);
        Gfx.fill(m, x, y + titleH, x + w, y + total, 0xD90F1420);
        Gfx.fill(m, x, y, x + 3, y + total, col);
        Gfx.quad(m, Gfx.ICON, x + w - 21, y + 4, x + w - 5, y + 20, 1f, 1f, 1f, 1f);

        Text title = Text.literal(nTitle).formatted(Formatting.BOLD);
        float s = Math.min(1.35f, (w - 40f) / Math.max(1, tr.getWidth(title)));
        MatrixStack ms = ctx.getMatrices();
        ms.push();
        ms.translate(x + 9f, y + 12f - 4.5f * s, 0f);
        ms.scale(s, s, 1f);
        ctx.drawText(tr, title, 0, 0, col, false);
        ms.pop();

        for (int i = 0; i < lines.size(); i++) {
            ctx.drawText(tr, lines.get(i), (int) x + 9, (int) y + titleH + 5 + i * 10, col, true);
        }

        MutableText foot = Text.literal("Presiona ");
        if (RuletaClient.hideKey != null) foot.append(RuletaClient.hideKey.getBoundKeyLocalizedText());
        foot.append(" para ocultar este mensaje");
        ctx.drawText(tr, foot, (int) x + 9, (int) y + titleH + bodyH + 3, 0xFFFFFFFF, false);
    }
}
