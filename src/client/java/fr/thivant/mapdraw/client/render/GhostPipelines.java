package fr.thivant.mapdraw.client.render;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import net.minecraft.client.renderer.RenderPipelines;

import fr.thivant.mapdraw.MapDraw;

/**
 * Pipelines de rendu des blocs fantômes.
 * <p>
 * Ils réutilisent le shader vanilla des boîtes de debug (position + couleur) : aucun shader
 * personnalisé, rien au-delà de ce que Minecraft utilise déjà (compatible macOS / OpenGL 4.1).
 * Seuls changent la profondeur (testée ou non), l'écriture de profondeur (désactivée) et le culling.
 */
public final class GhostPipelines {
	/** Translucide, caché derrière les blocs. Minecraft 26.x utilise une profondeur inversée (reverse-Z). */
	public static final RenderPipeline DEPTH_TESTED = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
			.withLocation(MapDraw.id("pipeline/ghost_blocks"))
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.withCull(false)
			.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
			.build();

	/** Translucide, visible à travers les blocs. */
	public static final RenderPipeline SEE_THROUGH = RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
			.withLocation(MapDraw.id("pipeline/ghost_blocks_see_through"))
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.withCull(false)
			.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
			.build();

	private GhostPipelines() {
	}
}
