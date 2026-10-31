package lx.splitaim;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoPlayer;
import zombie.input.AimingMode;
import zombie.iso.Vector2;

/**
 * Hook en AimingMode.lerpAiming (llamado por AimingReticle.update() una vez por jugador
 * con mando, cada frame). Los advices solo delegan a AimLogic (codigo testeable,
 * con try/catch) para que el bytecode inyectado sea minimo.
 */
@Patch(className = "zombie.input.AimingMode", methodName = "lerpAiming", warmUp = true)
public class Patch_LerpAiming {

    @Patch.OnEnter
    public static void onEnter(@Patch.This(readOnly = true) AimingMode mode,
                               @Patch.Argument(value = 0, readOnly = true) IsoPlayer player,
                               @Patch.Argument(value = 1, readOnly = true) Vector2 src,
                               @Patch.Argument(value = 2, readOnly = false) float targetX,
                               @Patch.Argument(value = 3, readOnly = false) float targetY,
                               @Patch.Argument(value = 4, readOnly = true) float centerX,
                               @Patch.Argument(value = 5, readOnly = true) float centerY) {
        if (AimLogic.onEnter(mode, player, src, targetX, targetY, centerX, centerY)) {
            targetX = AimLogic.outTargetX;
            targetY = AimLogic.outTargetY;
        }
    }

    @Patch.OnExit
    public static void onExit(@Patch.Argument(value = 0, readOnly = true) IsoPlayer player,
                              @Patch.Argument(value = 1, readOnly = true) Vector2 src,
                              @Patch.Argument(value = 4, readOnly = true) float centerX,
                              @Patch.Argument(value = 5, readOnly = true) float centerY,
                              @Patch.Argument(value = 7, readOnly = true) Vector2 outResult) {
        AimLogic.onExit(player, src, centerX, centerY, outResult);
    }
}
