package fr.thivant.mapdraw.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Hauteur de surface côté client, équivalente à la heightmap MOTION_BLOCKING_NO_LEAVES.
 * <p>
 * Cette heightmap n'est pas envoyée aux clients : on part de MOTION_BLOCKING (disponible côté client)
 * puis on descend tant que le bloc est un feuillage ou n'a pas de collision (herbe haute, fleurs…).
 */
public final class Terrain {
	/** Colonne non chargée. */
	public static final int UNKNOWN = Integer.MIN_VALUE;
	private static final int MAX_SCAN = 96;

	private Terrain() {
	}

	/** Y où placer le bloc fantôme (juste au-dessus du bloc solide le plus haut), ou UNKNOWN. */
	public static int surfaceY(ClientLevel level, int x, int z, BlockPos.MutableBlockPos pos) {
		if (!level.hasChunk(x >> 4, z >> 4)) {
			return UNKNOWN;
		}

		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		int minY = level.getMinY();

		for (int i = 0; i < MAX_SCAN && y > minY; i++) {
			pos.set(x, y - 1, z);
			BlockState state = level.getBlockState(pos);

			if (state.is(BlockTags.LEAVES) || (state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty())) {
				y--;
				continue;
			}

			break;
		}

		return y;
	}
}
