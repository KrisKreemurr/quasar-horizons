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

import com.seibel.distanthorizons.common.backports.FakeBlockState;
import com.seibel.distanthorizons.common.backports.FakeWorld;
import com.seibel.distanthorizons.common.wrappers.modAccessor.IGtnhLibCommonAccessor;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import net.minecraft.block.Block;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeGenBase;
import org.jetbrains.annotations.Nullable;

/**
 * Runs GTNHLib's block state data handlers ({@link FakeBlockState#data}). <br>
 * Without GTNHLib no block has data.
 */
public class BlockStateDataResolver
{
	/** @return true if the block's data depends on its neighbors (IE fence connections) */
	public static boolean isNeighborDependent(Block block)
	{
		IGtnhLibCommonAccessor gtnhLib = ModAccessorInjector.INSTANCE.get(IGtnhLibCommonAccessor.class);
		return gtnhLib != null && gtnhLib.isBlockStateDataNeighborDependent(block);
	}

	/** @return the block's data, null for none */
	@Nullable
	public static String encode(IBlockAccess world, int x, int y, int z, Block block, int meta)
	{
		IGtnhLibCommonAccessor gtnhLib = ModAccessorInjector.INSTANCE.get(IGtnhLibCommonAccessor.class);
		return (gtnhLib != null) ? gtnhLib.encodeBlockStateData(world, x, y, z, block, meta) : null;
	}

	/**
	 * The world the block state is shown in. <br>
	 * States with data and a handler get a new one from GTNHLib with their decoded surroundings,
	 * all others the given fake world, updated for them.
	 */
	public static IBlockAccess getWorld(
		FakeWorld fakeWorld, IBlockAccess realWorld, @Nullable BiomeGenBase biome,
		int x, int y, int z,
		FakeBlockState blockState)
	{
		IGtnhLibCommonAccessor gtnhLib = ModAccessorInjector.INSTANCE.get(IGtnhLibCommonAccessor.class);
		if (blockState.data != null && gtnhLib != null)
		{
			IBlockAccess dataWorld = gtnhLib.createBlockStateDataWorld(x, y, z, blockState.block, blockState.meta, blockState.data, biome);
			if (dataWorld != null)
			{
				return dataWorld;
			}
		}

		fakeWorld.update(realWorld, biome, x, y, z, blockState);
		return fakeWorld;
	}

}

#endif
