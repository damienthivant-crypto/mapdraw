package fr.thivant.mapdraw.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ShapesTest {
	private static Set<Long> set(long[] a) {
		Set<Long> s = new HashSet<>();

		for (long k : a) {
			s.add(k);
		}

		return s;
	}

	@Test
	void rectangleHasNoHoles() throws Exception {
		for (int w = 1; w <= 5; w++) {
			Shapes.Shape r = Shapes.rectangle(0, 0, 29, 19, w, true);
			int span = Raster.brushHigh(w) - Raster.brushLow(w);
			assertTrue(Collections.disjoint(set(r.outline()), set(r.interior())));
			assertEquals((30 + span) * (20 + span), r.size());
		}
	}

	@Test
	void circleContourIsContinuous() throws Exception {
		Set<Long> o = set(Shapes.ellipse(0, 0, 20, 20, 1, true).outline());

		for (long k : o) {
			int x = CellKey.x(k), z = CellKey.z(k), n = 0;

			for (long q : new long[] {CellKey.pack(x + 1, z), CellKey.pack(x - 1, z), CellKey.pack(x, z + 1), CellKey.pack(x, z - 1)}) {
				if (o.contains(q)) {
					n++;
				}
			}

			assertTrue(n >= 2, "case de contour isolée en " + x + "," + z);
		}
	}

	@Test
	void polygonFill() throws Exception {
		Shapes.Shape p = Shapes.polygon(List.of(CellKey.pack(0, 0), CellKey.pack(20, 0), CellKey.pack(0, 20)), 1, true);
		assertTrue(set(p.interior()).contains(CellKey.pack(3, 3)));
		assertTrue(!set(p.interior()).contains(CellKey.pack(15, 15)));
	}

	@Test
	void bigPolygonIsFast() throws Exception {
		long t0 = System.nanoTime();
		Shapes.Shape big = Shapes.polygon(List.of(CellKey.pack(0, 0), CellKey.pack(900, 50), CellKey.pack(1000, 900), CellKey.pack(100, 1000)), 3, true);
		assertTrue(big.size() > 700_000);
		assertTrue((System.nanoTime() - t0) / 1_000_000 < 3000);
	}

	@Test
	void tooLargeIsRefused() {
		assertThrows(Shapes.TooLargeException.class,
				() -> Shapes.polygon(List.of(CellKey.pack(0, 0), CellKey.pack(5000, 0), CellKey.pack(0, 5000)), 1, true));
	}

	@Test
	void longIntMapMatchesHashMap() {
		Random r = new Random(3);
		LongIntMap m = new LongIntMap();
		Map<Long, Integer> ref = new HashMap<>();

		for (int i = 0; i < 200_000; i++) {
			long k = CellKey.pack(r.nextInt(300) - 150, r.nextInt(300) - 150);

			if (r.nextInt(3) < 2) {
				int v = r.nextInt(1 << 25);
				Integer old = ref.put(k, v);
				assertEquals(old == null ? LongIntMap.NONE : old, m.put(k, v));
			} else {
				Integer old = ref.remove(k);
				assertEquals(old == null ? LongIntMap.NONE : old, m.remove(k));
			}
		}

		assertEquals(ref.size(), m.size());
		ref.forEach((k, v) -> assertEquals((int) v, m.get(k)));
	}

	@Test
	void surfaceFlag() {
		int v = CellValue.surface(0x123456);
		assertTrue(CellValue.isSurface(v));
		assertEquals(0x123456, CellValue.rgb(v));
	}
}
