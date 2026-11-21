package com.seibel.distanthorizons.forge17.modAccessor;

import com.seibel.distanthorizons.common.wrappers.block.QuadGeometry;
import com.seibel.distanthorizons.common.wrappers.block.TextureAtlasSpriteWrapper;
import com.seibel.distanthorizons.common.wrappers.modAccessor.IGtnhLibCommonAccessor;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.enums.MinecraftTextFormat;

import com.gtnewhorizon.gtnhlib.blockstate.core.BlockStateDataHandler;
import com.gtnewhorizon.gtnhlib.blockstate.core.BlockStateDataWorld;
import com.gtnewhorizon.gtnhlib.blockstate.registry.BlockStateDataRegistry;
import com.gtnewhorizon.gtnhlib.client.model.BlockRenderCapture;
import com.gtnewhorizon.gtnhlib.client.model.WorldContext;
import com.gtnewhorizon.gtnhlib.client.model.baked.BakedModel;
import com.gtnewhorizon.gtnhlib.client.renderer.cel.model.quad.ModelQuadView;
import com.gtnewhorizon.gtnhlib.client.renderer.cel.model.quad.properties.ModelQuadFacing;
import com.gtnewhorizon.gtnhlib.util.AboveHotbarHUD;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeGenBase;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;

public class GtnhLibAccessor implements IGtnhLibCommonAccessor
{
	@Override
	public String getModName() { return "gtnhlib"; }
	
	@Override
	public void renderTextAboveHotbar(String text)
	{ AboveHotbarHUD.renderTextAboveHotbar(MinecraftTextFormat.WHITE + text, 60, true, true); }
	
	@Override
	public boolean isBlockStateDataNeighborDependent(Block block)
	{
		BlockStateDataHandler handler = BlockStateDataRegistry.getHandler(block);
		return handler != null && handler.getType() == BlockStateDataHandler.Type.NEIGHBOR_DEPENDENT;
	}
	
	@Override
	@Nullable
	public String encodeBlockStateData(IBlockAccess world, int x, int y, int z, Block block, int meta)
	{
		BlockStateDataHandler handler = BlockStateDataRegistry.getHandler(block);
		return (handler != null) ? BlockStateDataRegistry.encode(handler, world, x, y, z, block, meta) : null;
	}
	
	@Override
	@Nullable
	public IBlockAccess createBlockStateDataWorld(int x, int y, int z, Block block, int meta, String data, @Nullable BiomeGenBase biome)
	{
		BlockStateDataHandler handler = BlockStateDataRegistry.getHandler(block);
		if (handler == null)
		{
			return null;
		}
		
		BlockStateDataWorld world = new BlockStateDataWorld(x, y, z, block, meta, biome);
		BlockStateDataRegistry.decode(handler, block, meta, data, world);
		return world;
	}
	
	@Override
	public void captureQuads(IBlockAccess world, int x, int y, int z, Block block, BiConsumer<QuadGeometry, EDhDirection> quadConsumer)
	{
		BakedModel model = BlockRenderCapture.capture(world, x, y, z, block);
		
		// the captured model groups its quads by the block side they lie on
		WorldContext context = new WorldContext();
		for (ModelQuadFacing facing : ModelQuadFacing.VALUES)
		{
			context.quadFacing = facing;
			EDhDirection cullDirection = getDhDirection(facing);
			for (ModelQuadView quad : model.getQuads(context))
			{
				QuadGeometry geometry = decodeQuad(quad);
				if (geometry != null)
				{
					quadConsumer.accept(geometry, cullDirection);
				}
			}
		}
	}
	
	/** @return null if the quad has no sprite */
	@Nullable
	private static QuadGeometry decodeQuad(ModelQuadView quad)
	{
		QuadGeometry geometry = new QuadGeometry();
		
		geometry.sprite = (TextureAtlasSprite) quad.celeritas$getSprite();
		if (geometry.sprite == null)
		{
			return null;
		}
		
		float minU = TextureAtlasSpriteWrapper.getMinU(geometry.sprite);
		float maxU = TextureAtlasSpriteWrapper.getMaxU(geometry.sprite);
		float minV = TextureAtlasSpriteWrapper.getMinV(geometry.sprite);
		float maxV = TextureAtlasSpriteWrapper.getMaxV(geometry.sprite);
		for (int vertexIndex = 0; vertexIndex < 4; vertexIndex++)
		{
			geometry.xByVertex[vertexIndex] = quad.getX(vertexIndex);
			geometry.yByVertex[vertexIndex] = quad.getY(vertexIndex);
			geometry.zByVertex[vertexIndex] = quad.getZ(vertexIndex);
			
			// vertex UVs are texture atlas coordinates and
			// need to be converted into sprite local coordinates
			geometry.spriteUByVertex[vertexIndex] = (maxU != minU) ? ((quad.getTexU(vertexIndex) - minU) / (maxU - minU)) : 0.0f;
			geometry.spriteVByVertex[vertexIndex] = (maxV != minV) ? ((quad.getTexV(vertexIndex) - minV) / (maxV - minV)) : 0.0f;
		}
		
		geometry.tinted = quad.hasColor();
		// unshaded quads (IE crossed squares) are as bright as faces pointing up
		if (!quad.hasDirectionalShading())
		{
			geometry.shadeDirectionOverride = EDhDirection.UP;
		}
		return geometry;
	}
	
	/** @return null for quads inside the block */
	@Nullable
	private static EDhDirection getDhDirection(ModelQuadFacing facing)
	{
		switch (facing)
		{
			case NEG_Y: return EDhDirection.DOWN;
			case POS_Y: return EDhDirection.UP;
			case NEG_Z: return EDhDirection.NORTH;
			case POS_Z: return EDhDirection.SOUTH;
			case NEG_X: return EDhDirection.WEST;
			case POS_X: return EDhDirection.EAST;
			default: return null;
		}
	}
	
}
