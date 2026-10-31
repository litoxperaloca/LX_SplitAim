package lx.splitaim;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoPlayer;
import zombie.characters.component.CharacterInputComponent;
import zombie.characters.ecs.ECSEntity;

/**
 * Fuerza "precision aim" SOLO para el jugador cuyo Lua concedio el aim (stick derecho
 * empujado + arma de fuego + nada de UI capturando el mando). El original usaba un
 * unico flag global: en split-screen el jugador 2 heredaba (o borraba) el del jugador 1.
 */
@Patch(className = "zombie.characters.component.CharacterInputComponent", methodName = "isPrecisionAimKeyDown", warmUp = true)
public class Patch_PrecisionAim {

    @Patch.OnExit
    public static void onExit(@Patch.This(readOnly = true) CharacterInputComponent component,
                              @Patch.Return(readOnly = false) boolean current) {
        if (!current && shouldForce(component)) {
            current = true;
        }
    }

    public static boolean shouldForce(CharacterInputComponent component) {
        try {
            if (!AimState.applyOverride || component == null) return false;
            ECSEntity entity = component.getECSOwnerEntity();
            if (!(entity instanceof IsoPlayer)) return false;
            IsoPlayer player = (IsoPlayer) entity;
            if (!player.isLocal()) return false;
            int p = AimState.idx(player.getIndex());
            return p >= 0 && AimState.aimGranted[p] && !AimState.suppressed[p];
        } catch (Throwable t) {
            return false;
        }
    }
}
