package org.pinnaclesmp.fragguard;

import org.bukkit.Material;
import org.bukkit.event.block.Action;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CausalActivationScopeTest {
    @Test
    void onlyActualActivationControlsCanSeedInteractionOwnership() throws Exception {
        BlockChangeListener listener = new BlockChangeListener(null, mock(Database.class));
        Method method = BlockChangeListener.class.getDeclaredMethod(
                "isDirectActivation", Material.class, Action.class);
        method.setAccessible(true);
        assertTrue((boolean) method.invoke(listener, Material.LEVER, Action.RIGHT_CLICK_BLOCK));
        assertTrue((boolean) method.invoke(listener, Material.STONE_BUTTON, Action.RIGHT_CLICK_BLOCK));
        assertTrue((boolean) method.invoke(listener, Material.STONE_PRESSURE_PLATE, Action.PHYSICAL));
        for (Material passive : new Material[]{Material.PISTON, Material.STICKY_PISTON,
                Material.OBSERVER, Material.REDSTONE_WIRE, Material.TNT,
                Material.REPEATER, Material.COMPARATOR}) {
            assertFalse((boolean) method.invoke(listener, passive, Action.RIGHT_CLICK_BLOCK),
                    passive + " must not attribute unrelated subsequent machinery activity");
        }
        assertFalse((boolean) method.invoke(listener, Material.STONE_BUTTON, Action.PHYSICAL));
        assertFalse((boolean) method.invoke(listener, null, Action.RIGHT_CLICK_BLOCK));
    }
}
