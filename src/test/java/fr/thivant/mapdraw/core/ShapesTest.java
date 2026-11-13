package fr.thivant.mapdraw.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ShapesTest {
	@Test
	void rectangle() {
		Shapes.Shape r = Shapes.rectangle(0, 0, 9, 4, 1, true);
		assertEquals(26, r.outline().size());
		assertEquals(24, r.interior().size());
		assertTrue(Collections.disjoint(r.outline(), r.interior()));
	}

	@Test
	void circleContourIsContinuous() {
		Set<Long> o = Shapes.ellipse(0, 0, 20, 20, 1, true).outline();

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
	void polygonFill() {
		Shapes.Shape p = Shapes.polygon(List.of(CellKey.pack(0, 0), CellKey.pack(20, 0), CellKey.pack(0, 20)), 1, true);
		assertTrue(p.interior().contains(CellKey.pack(3, 3)));
		assertTrue(!p.interior().contains(CellKey.pack(15, 15)));
	}

	@Test
	void surfaceFlag() {
		int v = CellValue.surface(0x123456);
		assertTrue(CellValue.isSurface(v));
		assertEquals(0x123456, CellValue.rgb(v));
	}
}
