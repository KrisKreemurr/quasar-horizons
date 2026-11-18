/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.common.wrappers.block;

#if MC_VER > MC_1_7_10 && MC_VER <= MC_1_12_2

import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.ILodBlockLookup;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import org.jetbrains.annotations.Nullable;

/**
 * The world a block's getActualState sees, recording what it reads. <br>
 * With a lookup it's read from LOD data when the block is resolved for rendering,
 * without one everything around the block is air, to probe if it depends on its neighbors at all.
 *
 * @see BlockStateWrapper#resolveRenderState
 */
public class LodBlockAccess implements IBlockAccess
{
	/** null when probing */
	@Nullable
	private final ILodBlockLookup lookup;
	private final IBlockState centerState;
	private final BlockPos centerPos;

	/** any position other than the block itself was read */
	public boolean readNeighbor = false;
	public boolean readTileEntity = false;
	/** a position outside the loaded LOD data was read */
	public boolean readMissing = false;



	public LodBlockAccess(@Nullable ILodBlockLookup lookup, IBlockState centerState, BlockPos centerPos)
	{
		this.lookup = lookup;
		this.centerState = centerState;
		this.centerPos = centerPos;
	}



	@Override
	@Nullable
	public TileEntity getTileEntity(BlockPos pos)
	{
		this.readTileEntity = true;
		return null;
	}

	@Override
	public IBlockState getBlockState(BlockPos pos)
	{
		if (pos.equals(this.centerPos))
		{
			return this.centerState;
		}

		this.readNeighbor = true;
		if (this.lookup == null)
		{
			return Blocks.AIR.getDefaultState();
		}

		IBlockStateWrapper wrapper = this.lookup.getBlockState(pos.getX(), pos.getY(), pos.getZ());
		if (wrapper == null)
		{
			this.readMissing = true;
			return Blocks.AIR.getDefaultState();
		}

		// air is the only wrapper without a state
		IBlockState state = ((BlockStateWrapper) wrapper).blockState;
		return (state != null) ? state : Blocks.AIR.getDefaultState();
	}

	@Override
	public boolean isAirBlock(BlockPos pos)
	{
		IBlockState state = this.getBlockState(pos);
		return state.getBlock().isAir(state, this, pos);
	}

	/** the same as ChunkCache */
	@Override
	public int getStrongPower(BlockPos pos, EnumFacing direction) { return this.getBlockState(pos).getStrongPower(this, pos, direction); }

	/** the same as ChunkCache */
	@Override
	public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean _default)
	{
		if (pos.getY() < 0 || pos.getY() >= 256)
		{
			return _default;
		}
		return this.getBlockState(pos).isSideSolid(this, pos, side);
	}

	/** lighting isn't stored per block */
	@Override
	public int getCombinedLight(BlockPos pos, int lightValue) { return 0; }

	@Override
	public Biome getBiome(BlockPos pos) { return Biomes.PLAINS; }

	@Override
	public WorldType getWorldType() { return WorldType.DEFAULT; }

}

#endif
