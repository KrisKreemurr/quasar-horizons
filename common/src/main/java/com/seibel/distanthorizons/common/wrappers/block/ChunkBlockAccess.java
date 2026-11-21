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

#if MC_VER <= MC_1_7_10

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.util.ForgeDirection;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * The world GTNHLib's block state data encoding sees when a chunk is read,
 * for data that's the block's own state (IE a double plant's variant in its lower half). <br>
 * Only contains the chunk, everything outside of it is air.
 *
 * @see BlockStateDataResolver
 */
public class ChunkBlockAccess implements IBlockAccess
{
	private final Chunk chunk;
	/** by their position relative to the chunk, see {@link ChunkPosition} */
	private final Map<ChunkPosition, TileEntity> tileEntityByPos;
	private final int minX;
	private final int minZ;



	/** @param tileEntityByPos the chunk's tile entities, the chunk's own map isn't thread safe */
	public ChunkBlockAccess(Chunk chunk, Map<ChunkPosition, TileEntity> tileEntityByPos)
	{
		this.chunk = chunk;
		this.tileEntityByPos = tileEntityByPos;
		this.minX = chunk.xPosition * 16;
		this.minZ = chunk.zPosition * 16;
	}



	private boolean isInChunk(int x, int y, int z)
	{
		return y >= 0
			&& x >= this.minX && x < this.minX + 16
			&& z >= this.minZ && z < this.minZ + 16;
	}

	@Override
	public Block getBlock(int x, int y, int z)
	{ return this.isInChunk(x, y, z) ? this.chunk.getBlock(x - this.minX, y, z - this.minZ) : Blocks.air; }

	@Override
	public int getBlockMetadata(int x, int y, int z)
	{ return this.isInChunk(x, y, z) ? this.chunk.getBlockMetadata(x - this.minX, y, z - this.minZ) : 0; }

	@Override
	@Nullable
	public TileEntity getTileEntity(int x, int y, int z)
	{ return this.isInChunk(x, y, z) ? this.tileEntityByPos.get(new ChunkPosition(x - this.minX, y, z - this.minZ)) : null; }

	@Override
	public boolean isAirBlock(int x, int y, int z) { return this.getBlock(x, y, z).isAir(this, x, y, z); }

	/** the same as ChunkCache */
	@Override
	public boolean isSideSolid(int x, int y, int z, ForgeDirection side, boolean _default) { return this.getBlock(x, y, z).isSideSolid(this, x, y, z, side); }

	@Override
	public int getLightBrightnessForSkyBlocks(int x, int y, int z, int min) { return 0; }

	@Override
	public int isBlockProvidingPowerTo(int x, int y, int z, int directionIn) { return 0; }

	@Override
	public BiomeGenBase getBiomeGenForCoords(int x, int z) { return BiomeGenBase.plains; }

	@Override
	public int getHeight() { return 256; }

	@Override
	public boolean extendedLevelsInChunkCache() { return false; }

}

#endif
