package com.seibel.distanthorizons.common.backports;

#if MC_VER <= MC_1_7_10

import java.util.Objects;

import com.seibel.distanthorizons.common.wrappers.modAccessor.IRpleCommonAccessor;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import org.jetbrains.annotations.Nullable;

/**
 * Allows for smoother use of blocks in
 * {@link IBlockStateWrapper}
 */
public class FakeBlockState implements IBlockState
{
	private static final IRpleCommonAccessor RPLE_ACCESSOR = ModAccessorInjector.INSTANCE.get(IRpleCommonAccessor.class);
	
	public final Block block;
	public final int meta;
	/**
	 * Data from the block's GTNHLib block state data handler, null for none. <br>
	 * Blocks whose data is their own state (IE a double plant's variant) get it when the chunk is read and it's saved,
	 * blocks whose data depends on their surroundings (IE fence connections) only get it when resolved for rendering.
	 */
	@Nullable
	public final String data;
	private final int idAndMeta;
	
	
	
	//==============//
	// constructors //
	//==============//
	//region
	
	public FakeBlockState(Block block, int meta) { this(block, meta, Block.getIdFromBlock(block)); }
	public FakeBlockState(Block block, int meta, int blockId) { this(block, meta, blockId, null); }
	public FakeBlockState(Block block, int meta, int blockId, @Nullable String data)
	{
		this.block = block;
		this.meta = meta;
		this.data = data;
		this.idAndMeta = packIdAndMeta(blockId, meta);
	}
	
	//endregion
	
	
	
	//=========//
	// getters //
	//=========//
	//region
	
	@Override
	public Material getMaterial() { return this.block.getMaterial(); }
	
	@Override
	public Block getBlock() { return this.block; }
	
	@Override
	public int getMeta() { return this.meta; }
	
	@Override
	public int getLightValue() { return getLightEmission(this.block, this.meta); }
	public static int getLightEmission(Block block, int meta)
	{
		if (RPLE_ACCESSOR != null)
		{
			return RPLE_ACCESSOR.getColor(block, meta);
		}
		
		return Math.min(block.getLightValue(), LodUtil.MAX_MC_LIGHT);
	}
	
	public int getIdAndMeta() { return this.idAndMeta; }
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	@Override
	public boolean equals(Object obj)
	{
		if (!(obj instanceof FakeBlockState))
		{
			return false;
		}
		
		FakeBlockState that = (FakeBlockState) obj;
		return this.meta == that.meta 
			&& Objects.equals(this.block, that.block)
			&& Objects.equals(this.data, that.data);
	}
	
	@Override
	public int hashCode() { return (31 * this.idAndMeta) + Objects.hashCode(this.data); }
	public static int packIdAndMeta(int blockId, int meta) { return (blockId << 16) + meta; }
	
	//endregion
	
	
	
}

#endif
