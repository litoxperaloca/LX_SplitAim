package lx.splitaim;

import se.krka.kahlua.integration.annotations.LuaMethod;

/**
 * Estado del aim assist, separado POR JUGADOR (0..3).
 *
 * Fork split-screen de "[B42] Controller Aim Assist" (MIT, "Here to Suffer not for Fun").
 * El original guardaba aimGranted / suppressed / sticky target / curva como campos
 * globales: en split-screen cada OnPlayerUpdate pisaba el estado del otro jugador.
 *
 * Los ajustes de tuning (tamanos de zona, fuerza, lock-on) son globales: los configura
 * el menu de opciones y valen para todos los jugadores.
 */
public final class AimState {
    private AimState() {}

    public static final int MAX_PLAYERS = 4;

    // ---------------- ajustes globales (ModOptions) ----------------
    public static volatile boolean applyOverride = true;
    public static volatile boolean showOverlay = false;
    public static volatile boolean showLockMarker = true;

    public static volatile float slowdownX = 0.35F;
    public static volatile float slowdownY = 0.25F;
    public static volatile float slowdownHead = 0.05F;
    public static volatile float trackingFactor = 1.0F;
    public static volatile float hitboxWidth = 0.40F;
    public static volatile float hitboxHeight = 0.45F;
    public static volatile float hitboxDown = 0.15F;
    public static volatile float headHalfW = 0.03F;
    public static volatile float headHalfH = 0.03F;
    public static volatile float headOffset = 1.0F;
    public static volatile float upResist = 0.7F;
    public static volatile float recenterResist = 0.9F;
    public static volatile float stickCurve = 1.0F;

    // lock-on estilo B41: el stick elige al zombie en esa direccion y el reticulo se
    // posa sobre su cuerpo proyectado en 3D (resuelve solo la altura / nivel / piso).
    public static volatile boolean lockOn = true;
    public static volatile float lockConeDeg = 28.0F;
    public static volatile float lockSnap = 0.35F;
    public static volatile boolean lockHead = false;
    public static volatile float lockMaxLevels = 3.3F;

    // ---------------- por jugador: lo escribe Lua cada tick ----------------
    public static final boolean[] suppressed = new boolean[MAX_PLAYERS];
    public static final boolean[] aimGranted = new boolean[MAX_PLAYERS];

    // ---------------- por jugador: runtime (solo Java) ----------------
    public static final int[] stickyId = filled(-1);
    public static final int[] lockId = filled(-1);
    public static final boolean[] aiming = new boolean[MAX_PLAYERS];
    public static final boolean[] inZone = new boolean[MAX_PLAYERS];
    public static final boolean[] inHead = new boolean[MAX_PLAYERS];
    public static final boolean[] tracking = new boolean[MAX_PLAYERS];
    public static final long[] lastFrameMs = new long[MAX_PLAYERS];

    // calculado en onEnter, consumido en onExit de la MISMA llamada (mismo hilo)
    public static final boolean[] pendingTrack = new boolean[MAX_PLAYERS];
    public static final float[] pendingTrackDX = new float[MAX_PLAYERS];
    public static final float[] pendingTrackDY = new float[MAX_PLAYERS];
    public static final boolean[] pendingLock = new boolean[MAX_PLAYERS];
    public static final float[] lockScreenX = new float[MAX_PLAYERS];
    public static final float[] lockScreenY = new float[MAX_PLAYERS];

    private static int[] filled(int v) {
        int[] a = new int[MAX_PLAYERS];
        java.util.Arrays.fill(a, v);
        return a;
    }

    /** Indice valido 0..3 o -1. */
    public static int idx(double p) {
        int i = (int) p;
        return (i >= 0 && i < MAX_PLAYERS) ? i : -1;
    }

    public static int idx(int p) {
        return (p >= 0 && p < MAX_PLAYERS) ? p : -1;
    }

    public static void resetPlayer(int p) {
        if (p < 0 || p >= MAX_PLAYERS) return;
        stickyId[p] = -1;
        lockId[p] = -1;
        aiming[p] = false;
        inZone[p] = false;
        inHead[p] = false;
        tracking[p] = false;
        pendingTrack[p] = false;
        pendingLock[p] = false;
    }

    // ======================= API Lua (globales) =======================
    // Los numeros llegan desde Lua como double: se castean aca.

    @LuaMethod(name = "lxsa_isLoaded", global = true)
    public static boolean isLoaded() { return true; }

    @LuaMethod(name = "lxsa_setApplyOverride", global = true)
    public static void setApplyOverride(boolean v) { applyOverride = v; }

    @LuaMethod(name = "lxsa_getApplyOverride", global = true)
    public static boolean getApplyOverride() { return applyOverride; }

    @LuaMethod(name = "lxsa_setSuppressed", global = true)
    public static void setSuppressed(double player, boolean v) {
        int p = idx(player);
        if (p >= 0) suppressed[p] = v;
    }

    @LuaMethod(name = "lxsa_setAimGranted", global = true)
    public static void setAimGranted(double player, boolean v) {
        int p = idx(player);
        if (p >= 0) aimGranted[p] = v;
    }

    @LuaMethod(name = "lxsa_resetPlayer", global = true)
    public static void resetPlayerLua(double player) { resetPlayer(idx(player)); }

    @LuaMethod(name = "lxsa_setShowOverlay", global = true)
    public static void setShowOverlay(boolean v) { showOverlay = v; }

    @LuaMethod(name = "lxsa_getShowOverlay", global = true)
    public static boolean getShowOverlay() { return showOverlay; }

    @LuaMethod(name = "lxsa_setShowLockMarker", global = true)
    public static void setShowLockMarker(boolean v) { showLockMarker = v; }

    @LuaMethod(name = "lxsa_getShowLockMarker", global = true)
    public static boolean getShowLockMarker() { return showLockMarker; }

    @LuaMethod(name = "lxsa_isAiming", global = true)
    public static boolean isAiming(double player) {
        int p = idx(player);
        return p >= 0 && aiming[p];
    }

    @LuaMethod(name = "lxsa_isLocked", global = true)
    public static boolean isLocked(double player) {
        int p = idx(player);
        return p >= 0 && aiming[p] && lockId[p] >= 0;
    }

    @LuaMethod(name = "lxsa_getLockScreenX", global = true)
    public static double getLockScreenX(double player) {
        int p = idx(player);
        return p >= 0 ? lockScreenX[p] : Double.NaN;
    }

    @LuaMethod(name = "lxsa_getLockScreenY", global = true)
    public static double getLockScreenY(double player) {
        int p = idx(player);
        return p >= 0 ? lockScreenY[p] : Double.NaN;
    }

    @LuaMethod(name = "lxsa_getStickyId", global = true)
    public static double getStickyId(double player) {
        int p = idx(player);
        return p >= 0 ? stickyId[p] : -1;
    }

    // ---- zonas / fuerza ----
    @LuaMethod(name = "lxsa_setHitboxWidth", global = true)
    public static void setHitboxWidth(double v) { hitboxWidth = (float) v; }
    @LuaMethod(name = "lxsa_getHitboxWidth", global = true)
    public static double getHitboxWidth() { return hitboxWidth; }
    @LuaMethod(name = "lxsa_setHitboxHeight", global = true)
    public static void setHitboxHeight(double v) { hitboxHeight = (float) v; }
    @LuaMethod(name = "lxsa_getHitboxHeight", global = true)
    public static double getHitboxHeight() { return hitboxHeight; }
    @LuaMethod(name = "lxsa_setHitboxDown", global = true)
    public static void setHitboxDown(double v) { hitboxDown = (float) v; }
    @LuaMethod(name = "lxsa_getHitboxDown", global = true)
    public static double getHitboxDown() { return hitboxDown; }
    @LuaMethod(name = "lxsa_setHeadHalfW", global = true)
    public static void setHeadHalfW(double v) { headHalfW = (float) v; }
    @LuaMethod(name = "lxsa_getHeadHalfW", global = true)
    public static double getHeadHalfW() { return headHalfW; }
    @LuaMethod(name = "lxsa_setHeadHalfH", global = true)
    public static void setHeadHalfH(double v) { headHalfH = (float) v; }
    @LuaMethod(name = "lxsa_getHeadHalfH", global = true)
    public static double getHeadHalfH() { return headHalfH; }
    @LuaMethod(name = "lxsa_setHeadOffset", global = true)
    public static void setHeadOffset(double v) { headOffset = (float) v; }
    @LuaMethod(name = "lxsa_setSlowdownX", global = true)
    public static void setSlowdownX(double v) { slowdownX = clamp01((float) v); }
    @LuaMethod(name = "lxsa_setSlowdownY", global = true)
    public static void setSlowdownY(double v) { slowdownY = clamp01((float) v); }
    @LuaMethod(name = "lxsa_setSlowdownHead", global = true)
    public static void setSlowdownHead(double v) { slowdownHead = clamp01((float) v); }
    @LuaMethod(name = "lxsa_setUpResist", global = true)
    public static void setUpResist(double v) { upResist = clamp01((float) v); }
    @LuaMethod(name = "lxsa_setTrackingFactor", global = true)
    public static void setTrackingFactor(double v) { trackingFactor = clamp01((float) v); }
    @LuaMethod(name = "lxsa_setRecenterResist", global = true)
    public static void setRecenterResist(double v) { recenterResist = Math.max(0F, Math.min(0.95F, (float) v)); }
    @LuaMethod(name = "lxsa_setStickCurve", global = true)
    public static void setStickCurve(double v) { stickCurve = Math.max(0.5F, Math.min(3.0F, (float) v)); }

    // ---- lock-on ----
    @LuaMethod(name = "lxsa_setLockOn", global = true)
    public static void setLockOn(boolean v) { lockOn = v; }
    @LuaMethod(name = "lxsa_setLockCone", global = true)
    public static void setLockCone(double deg) { lockConeDeg = Math.max(5F, Math.min(60F, (float) deg)); }
    @LuaMethod(name = "lxsa_setLockSnap", global = true)
    public static void setLockSnap(double v) { lockSnap = Math.max(0.05F, Math.min(1F, (float) v)); }
    @LuaMethod(name = "lxsa_setLockHead", global = true)
    public static void setLockHead(boolean v) { lockHead = v; }
    @LuaMethod(name = "lxsa_setLockMaxLevels", global = true)
    public static void setLockMaxLevels(double v) { lockMaxLevels = Math.max(0F, Math.min(3.3F, (float) v)); }

    static float clamp01(float v) {
        return v < 0F ? 0F : (v > 1F ? 1F : v);
    }
}
