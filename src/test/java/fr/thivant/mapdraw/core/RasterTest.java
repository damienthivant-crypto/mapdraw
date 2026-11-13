package fr.thivant.mapdraw.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Critère de validation n°1 : une diagonale donne une suite continue de blocs (aucun trou).
 */
class RasterTest {
	private static List<int[]> line(int x0, int z0, int x1, int z1) {
		List<int[]> out = new ArrayList<>();
		Raster.line(x0, z0, x1, z1, (x, z) -> out.add(new int[] {x, z}));
		return out;
	}

	@Test
	void diagonalIsContinuousByEdges() {
		List<int[]> l = line(0, 0, 5, 5);
		assertEquals(11, l.size());

		for (int i = 1; i < l.size(); i++) {
			int d = Math.abs(l.get(i)[0] - l.get(i - 1)[0]) + Math.abs(l.get(i)[1] - l.get(i - 1)[1]);
			assertEquals(1, d, "deux blocs consécutifs doivent partager une face");
		}
	}

	@Test
	void randomLinesAreContinuousAndAccurate() {
		Random r = new Random(1);

		for (int n = 0; n < 5000; n++) {
			int x0 = r.nextInt(400) - 200, z0 = r.nextInt(400) - 200, x1 = r.nextInt(400) - 200, z1 = r.nextInt(400) - 200;
			List<int[]> l = line(x0, z0, x1, z1);
			assertArrayEquals(new int[] {x0, z0}, l.get(0));
			assertArrayEquals(new int[] {x1, z1}, l.get(l.size() - 1));
			assertEquals(Math.abs(x1 - x0) + Math.abs(z1 - z0) + 1, l.size());
			double len = Math.hypot(x1 - x0, z1 - z0);

			for (int i = 0; i < l.size(); i++) {
				if (i > 0) {
					assertEquals(1, Math.abs(l.get(i)[0] - l.get(i - 1)[0]) + Math.abs(l.get(i)[1] - l.get(i - 1)[1]));
				}

				if (len > 0) {
					int[] p = l.get(i);
					double dist = Math.abs((x1 - x0) * (double) (z0 - p[1]) - (x0 - p[0]) * (double) (z1 - z0)) / len;
					assertTrue(dist <= 1.0, "écart à la droite idéale : " + dist);
				}
			}
		}
	}

	@Test
	void brushWidths() {
		for (int w = 1; w <= 5; w++) {
			Set<Long> cells = new HashSet<>();
			Raster.brush(10, -10, w, cells);
			assertEquals(w * w, cells.size());
		}
	}

	@Test
	void jsonRoundTrip() throws Exception {
		LayerData layer = new LayerData(new LayerMeta(UUID.randomUUID(), "Ville", UUID.randomUUID(), "A", "minecraft:overworld", true));
		layer.set(CellKey.pack(-5, 7), 0x123456);
		layer.set(CellKey.pack(300, -900), 0xABCDEF);
		Path file = Files.createTempDirectory("mapdraw").resolve("overworld.json");
		LayerJson.writeLayers(file, List.of(layer));
		LayerData back = LayerJson.readLayers(file).get(0);
		assertEquals(layer.meta(), back.meta());
		assertEquals(layer.snapshot(), back.snapshot());
	}
}
