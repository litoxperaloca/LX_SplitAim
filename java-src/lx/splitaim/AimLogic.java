package lx.splitaim;

import java.util.ArrayList;

import zombie.CombatManager;
import zombie.GameTime;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoUtils;
import zombie.iso.PlayerCamera;
import zombie.iso.Vector2;
import zombie.iso.Vector3;
import zombie.input.AimingMode;

/**
 * Logica del aim assist (llamada desde los advices de Patch_LerpAiming).
 *
 * Diferencias clave con el mod original:
 *  1. Estado POR JUGADOR (AimState.*[p]).
 *  2. Proyeccion RELATIVA AL VIEWPORT: el reticulo de mando (AimingReticle.aimingPositions)
 *     vive en pixeles relativos al viewport del jugador. El original usaba
 *     LuaManager.GlobalObject.isoToScreenX/Y, que SUMA IsoCamera.getScreenLeft/Top(player):
 *     para el jugador 1 es 0 (funciona), para el jugador 2 desplaza todas las zonas
 *     media pantalla -> nunca "engancha". Ese era el bug de split-screen.
 *  3. Lock-on estilo B41: el stick elige al zombie mas alineado con su direccion y el
 *     reticulo se posa sobre su torso proyectado en 3D, asi el rayo balistico de B42
 *     resuelve solo la altura (zombies en el piso, arrastrandose o en otro nivel).
 *
 * Todo corre en el hilo principal (AimingReticle.update), sin concurrencia.
 * Cualquier excepcion se captura: un fallo del mod nunca debe tumbar el frame del juego.
 */
public final class AimLogic {
    private AimLogic() {}

    /** Salida de onEnter: nuevo target del reticulo (solo valido si onEnter devolvio true). */
    public static float outTargetX;
    public static float outTargetY;

    private static final Vector3 bone1 = new Vector3();
    private static final Vector3 bone2 = new Vector3();
    private static int errors = 0;
    private static boolean disabledByErrors = false;

    // limites razonables para el rendimiento (PC modesta)
    private static final int MAX_CANDIDATES_SCAN = 400;

    // ------------------------------------------------------------------ onEnter
    public static boolean onEnter(AimingMode mode, IsoPlayer player, Vector2 src, float targetX, float targetY,
                                  float centerX, float centerY) {
        if (disabledByErrors || player == null) return false;
        int p = AimState.idx(player.getIndex());
        if (p < 0) return false;
        AimState.inZone[p] = false;
        AimState.inHead[p] = false;
        AimState.tracking[p] = false;
        AimState.aiming[p] = false;
        AimState.pendingTrack[p] = false;
        AimState.pendingLock[p] = false;
        try {
            return onEnterImpl(p, mode, player, src, targetX, targetY, centerX, centerY);
        } catch (Throwable t) {
            fail("onEnter", t);
            AimState.resetPlayer(p);
            return false;
        }
    }

    private static boolean onEnterImpl(int p, AimingMode mode, IsoPlayer player, Vector2 src, float targetX, float targetY,
                                       float centerX, float centerY) {
        if (!AimState.applyOverride || AimState.suppressed[p] || mode == null || src == null) {
            AimState.lockId[p] = -1;
            return false;
        }
        // solo modo preciso con arma a distancia (en los modos "fixed" vanilla ignora el target)
        if (mode.fixedAtMinDistance || !mode.name().startsWith("RANGED")) {
            AimState.lockId[p] = -1;
            return false;
        }
        InventoryItem item = player.getPrimaryHandItem();
        if (!(item instanceof HandWeapon)) { AimState.lockId[p] = -1; return false; }
        HandWeapon weapon = (HandWeapon) item;
        if (!weapon.isRanged()) { AimState.lockId[p] = -1; return false; }

        AimState.aiming[p] = true;

        // ---- curva polar del stick (1.0 = vanilla) ----
        float tx = targetX;
        float ty = targetY;
        float curve = AimState.stickCurve;
        if (curve < 0.999F || curve > 1.001F) {
            float hx = Math.max(1F, centerX);
            float hy = Math.max(1F, centerY);
            float ax = (tx - centerX) / hx;
            float ay = (ty - centerY) / hy;
            float r = (float) Math.sqrt(ax * ax + ay * ay);
            if (r > 1.0E-4F) {
                float rc = (float) Math.pow(Math.min(r, 1.5F), curve);
                float s = rc / r;
                tx = centerX + ax * s * hx;
                ty = centerY + ay * s * hy;
            }
        }
        boolean changed = (tx != targetX) || (ty != targetY);

        IsoCell cell = player.getCell();
        if (cell == null) { outTargetX = tx; outTargetY = ty; return changed; }
        ArrayList<IsoZombie> list = cell.getZombieList();
        if (list == null || list.isEmpty()) {
            AimState.lockId[p] = -1;
            AimState.stickyId[p] = -1;
            outTargetX = tx; outTargetY = ty;
            return changed;
        }

        PlayerCamera cam = IsoCamera.cameras[p];
        if (cam == null) { outTargetX = tx; outTargetY = ty; return changed; }
        float zoom = cam.zoom <= 0F ? 1F : cam.zoom;
        float vpW = IsoCamera.getScreenWidth(p);
        float vpH = IsoCamera.getScreenHeight(p);

        float range = weapon.getMaxRange() * weapon.getRangeMod(player);
        if (range < 1F) range = 1F;
        float range2 = range * range;
        float px = player.getX();
        float py = player.getY();
        float pz = player.getZ();

        // ================= LOCK-ON (estilo B41) =================
        if (AimState.lockOn) {
            float dx = tx - centerX;
            float dy = ty - centerY;
            float mag = (float) Math.sqrt(dx * dx + dy * dy);
            float half = Math.max(1F, Math.min(centerX, centerY));
            if (mag / half >= 0.08F) {
                float dirX = dx / mag;
                float dirY = dy / mag;
                double coneRad = Math.toRadians(AimState.lockConeDeg);
                float cosCone = (float) Math.cos(coneRad);
                float cosKeep = (float) Math.cos(Math.min(Math.PI / 2.2, coneRad * 1.6));
                int prevLock = AimState.lockId[p];

                IsoZombie best = null;
                float bestScore = Float.MAX_VALUE;
                float bestSX = 0F, bestSY = 0F;
                int n = Math.min(list.size(), MAX_CANDIDATES_SCAN);
                for (int i = 0; i < n; ++i) {
                    IsoZombie z = list.get(i);
                    if (!isValidTarget(z)) continue;
                    float dzLevels = Math.abs(z.getZ() - pz);
                    if (dzLevels > AimState.lockMaxLevels) continue;
                    float ddx = z.getX() - px;
                    float ddy = z.getY() - py;
                    float d2 = ddx * ddx + ddy * ddy;
                    if (d2 > range2 || d2 < 0.04F) continue;
                    // prefiltro barato con la posicion de los pies
                    float fx = toScreenX(cam, zoom, z.getX(), z.getY(), z.getZ());
                    float fy = toScreenY(cam, zoom, z.getX(), z.getY(), z.getZ());
                    float vx = fx - centerX;
                    float vy = fy - centerY;
                    float vl = (float) Math.sqrt(vx * vx + vy * vy);
                    boolean sticky = z.getID() == prevLock;
                    float cosA = vl < 4F ? 1F : (vx * dirX + vy * dirY) / vl;
                    if (cosA < (sticky ? cosKeep : cosCone) - 0.15F) continue;
                    if (!player.CanSee(z)) continue;
                    // punto de impacto 3D (torso / cabeza / cuerpo tirado)
                    if (!aimPoint(z, bone1)) continue;
                    float sx = toScreenX(cam, zoom, bone1.x, bone1.y, bone1.z);
                    float sy = toScreenY(cam, zoom, bone1.x, bone1.y, bone1.z);
                    if (sx < 0F || sy < 0F || sx > vpW || sy > vpH) continue;
                    vx = sx - centerX;
                    vy = sy - centerY;
                    vl = (float) Math.sqrt(vx * vx + vy * vy);
                    cosA = vl < 4F ? 1F : (vx * dirX + vy * dirY) / vl;
                    if (cosA < (sticky ? cosKeep : cosCone)) continue;
                    float ang = (float) Math.acos(Math.max(-1F, Math.min(1F, cosA)));
                    float distN = (float) Math.sqrt(d2) / range;
                    float score = ang + 0.45F * distN;
                    if (sticky) score *= 0.55F;
                    if (score < bestScore) {
                        bestScore = score;
                        best = z;
                        bestSX = sx;
                        bestSY = sy;
                    }
                }
                if (best != null) {
                    AimState.lockId[p] = best.getID();
                    AimState.stickyId[p] = best.getID();
                    AimState.lockScreenX[p] = bestSX;
                    AimState.lockScreenY[p] = bestSY;
                    AimState.pendingLock[p] = true;
                    AimState.inZone[p] = true;
                    AimState.inHead[p] = AimState.lockHead;
                    outTargetX = bestSX;
                    outTargetY = bestSY;
                    return true;
                }
            }
            AimState.lockId[p] = -1;
        }

        // ================= SOFT-SNAP (zonas del mod original) =================
        float srcX = src.x;
        float srcY = src.y;
        int stickyPrev = AimState.stickyId[p];
        IsoZombie hit = null;
        boolean hitHead = false;
        float trackDX = 0F, trackDY = 0F;
        boolean hasTrack = false;
        int n = Math.min(list.size(), MAX_CANDIDATES_SCAN);
        for (int i = 0; i < n; ++i) {
            IsoZombie z = list.get(i);
            if (!isValidTarget(z)) continue;
            if (Math.abs(z.getZ() - pz) > 3.3F) continue;
            float ddx = z.getX() - px;
            float ddy = z.getY() - py;
            if (ddx * ddx + ddy * ddy > range2) continue;
            float zx = z.getX(), zy = z.getY(), zz = z.getZ();
            float anchorX = toScreenX(cam, zoom, zx, zy, zz);
            float anchorY = toScreenY(cam, zoom, zx, zy, zz);
            if (anchorX < -64F || anchorY < -64F || anchorX > vpW + 64F || anchorY > vpH + 64F) continue;
            if (!player.CanSee(z)) continue;
            // altura del sprite en pantalla ~ 1 nivel de alto (personaje parado)
            float topY = toScreenY(cam, zoom, zx, zy, zz + 0.75F);
            float spriteH = anchorY - topY;
            if (spriteH < 10F) spriteH = 80F / zoom;

            boolean inHead = false;
            if (z.isStanding() && headPoint(z, bone2)) {
                float hsx = toScreenX(cam, zoom, bone2.x, bone2.y, bone2.z);
                float hsy = toScreenY(cam, zoom, bone2.x, bone2.y, bone2.z);
                if (Math.abs(srcX - hsx) <= spriteH * AimState.headHalfW
                        && Math.abs(srcY - hsy) <= spriteH * AimState.headHalfH) {
                    inHead = true;
                }
            }
            boolean inBody = false;
            if (!inHead) {
                float halfW = spriteH * AimState.hitboxWidth * 0.5F;
                float top = anchorY - spriteH * AimState.hitboxHeight;
                float bottom = anchorY + spriteH * AimState.hitboxDown;
                inBody = srcX >= anchorX - halfW && srcX <= anchorX + halfW && srcY >= top && srcY <= bottom;
            }
            if (inHead || inBody) {
                boolean isSticky = z.getID() == stickyPrev;
                if (isSticky || hit == null) {
                    hit = z;
                    hitHead = inHead;
                    // tracking: cuanto se movio el zombie en pantalla desde el frame anterior
                    float prevX = z.getLastX();
                    float prevY = z.getLastY();
                    float prevZ = z.getLastZ();
                    float psx = toScreenX(cam, zoom, prevX, prevY, prevZ);
                    float psy = toScreenY(cam, zoom, prevX, prevY, prevZ);
                    trackDX = anchorX - psx;
                    trackDY = anchorY - psy;
                    hasTrack = Math.abs(trackDX) < 40F && Math.abs(trackDY) < 40F;
                    if (isSticky) break;
                }
            }
        }

        if (hit == null) {
            AimState.stickyId[p] = -1;
            outTargetX = tx;
            outTargetY = ty;
            return changed;
        }
        AimState.stickyId[p] = hit.getID();
        AimState.inZone[p] = true;
        AimState.inHead[p] = hitHead;
        float slowX = hitHead ? AimState.slowdownHead : AimState.slowdownX;
        float slowY = hitHead ? AimState.slowdownHead : AimState.slowdownY;
        if (!hitHead && ty < srcY) {
            slowY = 1.0F - (1.0F - slowY) * (1.0F - AimState.upResist);
        }
        outTargetX = srcX + (tx - srcX) * slowX;
        outTargetY = srcY + (ty - srcY) * slowY;
        if (hasTrack) {
            AimState.pendingTrack[p] = true;
            AimState.pendingTrackDX[p] = trackDX;
            AimState.pendingTrackDY[p] = trackDY;
            AimState.tracking[p] = true;
        }
        return true;
    }

    // ------------------------------------------------------------------ onExit
    public static void onExit(IsoPlayer player, Vector2 src, float centerX, float centerY, Vector2 out) {
        if (disabledByErrors || player == null || out == null || src == null) return;
        int p = AimState.idx(player.getIndex());
        if (p < 0 || !AimState.applyOverride || !AimState.aiming[p]) return;
        try {
            long now = System.currentTimeMillis();
            float dt = 0.016F;
            long last = AimState.lastFrameMs[p];
            if (last != 0L) {
                float raw = (float) (now - last) / 1000.0F;
                dt = raw < 0.005F ? 0.005F : (raw > 0.05F ? 0.05F : raw);
            }
            AimState.lastFrameMs[p] = now;

            if (AimState.pendingLock[p]) {
                // B41: el reticulo va rapido hacia el blanco elegido (y lo sigue si se mueve)
                float k = AimState.lockSnap * (dt / 0.016F);
                if (k > 1F) k = 1F;
                float lx = AimState.lockScreenX[p];
                float ly = AimState.lockScreenY[p];
                out.x = src.x + (lx - src.x) * k;
                out.y = src.y + (ly - src.y) * k;
                return;
            }

            if (AimState.recenterResist > 0.001F) {
                float dx = out.x - src.x;
                float dy = out.y - src.y;
                float toCx = centerX - src.x;
                float toCy = centerY - src.y;
                float len = (float) Math.sqrt(toCx * toCx + toCy * toCy);
                if (len > 0.001F) {
                    float ux = toCx / len;
                    float uy = toCy / len;
                    float radial = dx * ux + dy * uy;
                    if (radial > 0F) {
                        dx -= ux * radial * AimState.recenterResist;
                        dy -= uy * radial * AimState.recenterResist;
                    }
                }
                out.x = src.x + dx;
                out.y = src.y + dy;
            }
            if (AimState.pendingTrack[p]) {
                out.x += AimState.pendingTrackDX[p] * AimState.trackingFactor;
                out.y += AimState.pendingTrackDY[p] * AimState.trackingFactor;
            }
        } catch (Throwable t) {
            fail("onExit", t);
        }
    }

    // ------------------------------------------------------------------ helpers
    private static boolean isValidTarget(IsoZombie z) {
        if (z == null || z.isDead()) return false;
        if (z.getCurrentSquare() == null) return false;
        if (z.isFakeDead() || z.isReanimatedForGrappleOnly()) return false;
        return true;
    }

    /** Proyeccion mundo -> pixeles relativos al viewport del jugador (mismo espacio que el reticulo). */
    static float toScreenX(PlayerCamera cam, float zoom, float x, float y, float z) {
        return (IsoUtils.XToScreen(x + cam.fixJigglyModelsSquareX, y + cam.fixJigglyModelsSquareY, z, 0) - cam.getOffX()) / zoom;
    }

    static float toScreenY(PlayerCamera cam, float zoom, float x, float y, float z) {
        return (IsoUtils.YToScreen(x + cam.fixJigglyModelsSquareX, y + cam.fixJigglyModelsSquareY, z, 0) - cam.getOffY()) / zoom;
    }

    /** Punto a apuntar: pecho si esta parado (o cabeza si la opcion lo pide), columna si esta en el piso. */
    private static boolean aimPoint(IsoZombie z, Vector3 out) {
        boolean standing = z.isStanding() && !z.isProne() && !z.isCrawling();
        if (!standing) {
            CombatManager.getBoneWorldPos(z, "Bip01_Spine", out);
            if (!finite(out)) out.set(z.getX(), z.getY(), z.getZ() + 0.05F);
            return true;
        }
        if (AimState.lockHead && headPoint(z, out)) return true;
        CombatManager.getBoneWorldPos(z, "Bip01_Spine", out);
        headPoint(z, bone2);
        if (finite(out) && finite(bone2)) {
            // pecho = 60% del camino entre columna baja y cabeza
            out.set(out.x + (bone2.x - out.x) * 0.6F, out.y + (bone2.y - out.y) * 0.6F, out.z + (bone2.z - out.z) * 0.6F);
            return true;
        }
        if (finite(out)) return true;
        out.set(z.getX(), z.getY(), z.getZ() + 0.45F);
        return true;
    }

    private static boolean headPoint(IsoZombie z, Vector3 out) {
        CombatManager.getBoneWorldPos(z, "Bip01_Head", out);
        if (!finite(out)) return false;
        // el hueso "Head" esta en el cuello: subir un poco (offset configurable)
        float off = AimState.headOffset;
        if (off > 0.001F) out.z += 0.06F * off;
        return true;
    }

    private static boolean finite(Vector3 v) {
        return !Float.isNaN(v.x) && !Float.isNaN(v.y) && !Float.isNaN(v.z)
                && !Float.isInfinite(v.x) && !Float.isInfinite(v.y) && !Float.isInfinite(v.z);
    }

    private static void fail(String where, Throwable t) {
        errors++;
        if (errors <= 5) {
            System.err.println("[LX_SplitAim] error en " + where + ": " + t);
            t.printStackTrace();
        }
        if (errors >= 50 && !disabledByErrors) {
            disabledByErrors = true;
            System.err.println("[LX_SplitAim] demasiados errores: aim assist desactivado hasta reiniciar (el juego sigue normal).");
        }
    }
}
