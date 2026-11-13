package fr.thivant.mapdraw.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ZoneTest {
	private static Zone square() {
		return Zone.create(Zone.Kind.RECTANGLE, List.of(CellKey.pack(0, 0), CellKey.pack(9, 9)), 0xFF0000, 1, true);
	}

	@Test
	void rasterAndMove() {
		LayerData layer = new LayerData(new LayerMeta(UUID.randomUUID(), "t", UUID.randomUUID(), "me", "d", false));
		layer.enableZoneRaster();
		Zone z = square();
		layer.putZone(z);
		assertEquals(100, layer.cellsInChunk(CellKey.chunkKey(0, 0)).size());

		layer.putZone(z.translated(32, 0));
		assertFalse(layer.hasChunk(CellKey.chunkKey(0, 0)));
		assertEquals(100, layer.cellsInChunk(CellKey.chunkKey(2, 0)).size());
		assertEquals(0, layer.size(), "les zones ne sont pas des traits");
	}

	@Test
	void hitTest() {
		LayerData layer = new LayerData(new LayerMeta(UUID.randomUUID(), "t", UUID.randomUUID(), "me", "d", false));
		Zone z = square();
		layer.putZone(z);
		assertEquals(z.id(), layer.zoneAt(5, 5).id());
		assertNull(layer.zoneAt(40, 40));
	}

	@Test
	void validation() {
		assertTrue(square().valid());
		assertFalse(Zone.create(Zone.Kind.RECTANGLE, List.of(CellKey.pack(0, 0), CellKey.pack(5000, 0)), 0, 1, true).valid());
		assertFalse(Zone.create(Zone.Kind.POLYGON, List.of(CellKey.pack(0, 0), CellKey.pack(5, 0)), 0, 1, true).valid());
	}

	@Test
	void flags() {
		Zone z = square().withOpacity(100).withShowWorld(false);
		int v = z.interiorValue();
		assertTrue(CellValue.isSurface(v));
		assertTrue(CellValue.hiddenInWorld(v));
		assertFalse(CellValue.hiddenOnMap(v));
		assertEquals(1f, CellValue.opacity(v, 0.2f), 1e-6);
		assertEquals(0xFF0000, CellValue.rgb(v));
	}
}
