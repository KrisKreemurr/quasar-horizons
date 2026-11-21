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

#if MC_VER <= MC_1_12_2

import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.ILodBlockLookup;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import org.jetbrains.annotations.Nullable;
#if MC_VER <= MC_1_7_10
import com.seibel.distanthorizons.common.backports.IBlockState;
import net.minecraft.block.Block;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.util.ForgeDirection;
#else
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Biomes;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
#endif

/**
 * The world a block's getActualState (1.12.2) or
 * the GTNHLib block state data encoding (1.7.10) sees,
 * recording what it reads. <br>
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
	private final int centerX;
	private final int centerY;
	private final int centerZ;

	/** any position other than the block itself was read */
	public boolean readNeighbor = false;
	public boolean readTileEntity = false;
	/** a position outside the loaded LOD data was read */
	public boolean readMissing = false;



	public LodBlockAccess(@Nullable ILodBlockLookup lookup, IBlockState centerState, int centerX, int centerY, int centerZ)
	{
		this.lookup = lookup;
		this.centerState = centerState;
		this.centerX = centerX;
		this.centerY = centerY;
		this.centerZ = centerZ;
	}



	/** @return null for air */
	@Nullable
	private IBlockState getState(int x, int y, int z)
	{
		if (x == this.centerX && y == this.centerY && z == this.centerZ)
		{
			return this.centerState;
		}

		this.readNeighbor = true;
		if (this.lookup == null)
		{
			return null;
		}

		IBlockStateWrapper wrapper = this.lookup.getBlockState(x, y, z);
		if (wrapper == null)
		{
			this.readMissing = true;
			return null;
		}

		// air is the only wrapper without a state
		return ((BlockStateWrapper) wrapper).blockState;
	}

	#if MC_VER <= MC_1_7_10

	@Override
	public Block getBlock(int x, int y, int z)
	{
		IBlockState state = this.getState(x, y, z);
		return (state != null) ? state.getBlock() : Blocks.air;
	}

	@Override
	public int getBlockMetadata(int x, int y, int z)
	{
		IBlockState state = this.getState(x, y, z);
		return (state != null) ? state.getMeta() : 0;
	}

	@Override
	@Nullable
	public TileEntity getTileEntity(int x, int y, int z)
	{
		this.readTileEntity = true;
		return null;
	}

	@Override
	public boolean isAirBlock(int x, int y, int z) { return this.getBlock(x, y, z).isAir(this, x, y, z); }

	/** the same as ChunkCache */
	@Override
	public boolean isSideSolid(int x, int y, int z, ForgeDirection side, boolean _default) { return this.getBlock(x, y, z).isSideSolid(this, x, y, z, side); }

	/** lighting isn't stored per block */
	@Override
	public int getLightBrightnessForSkyBlocks(int x, int y, int z, int min) { return 0; }

	@Override
	public int isBlockProvidingPowerTo(int x, int y, int z, int directionIn) { return 0; }

	@Override
	public BiomeGenBase getBiomeGenForCoords(int x, int z) { return BiomeGenBase.plains; }

	@Override
	public int getHeight() { return (this.lookup != null) ? this.lookup.getLevelWrapper().getMaxHeight() : 256; }

	@Override
	public boolean extendedLevelsInChunkCache() { return false; }

	#else

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
		IBlockState state = this.getState(pos.getX(), pos.getY(), pos.getZ());
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

	#endif

}

#endif
