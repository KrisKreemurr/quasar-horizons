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

package com.seibel.distanthorizons.common.wrappers.modAccessor;

import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IModAccessor;

#if MC_VER <= MC_1_7_10
import com.seibel.distanthorizons.common.wrappers.block.QuadGeometry;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import net.minecraft.block.Block;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.biome.BiomeGenBase;
import org.jetbrains.annotations.Nullable;
import java.util.function.BiConsumer;
#endif

public interface IGtnhLibCommonAccessor extends IModAccessor
{
	void renderTextAboveHotbar(String text);
	
	#if MC_VER <= MC_1_7_10
	/**
	 * Renders the block with GTNHLib's thread local Tessellator
	 * and passes each quad it drew to {@code quadConsumer},
	 * with the block side it lies on (null if it's inside the block). <br>
	 * Quads without a sprite are skipped.
	 */
	void captureQuads(IBlockAccess world, int x, int y, int z, Block block, BiConsumer<QuadGeometry, EDhDirection> quadConsumer);
	
	/** 
	 * @return true if the block's GTNHLib block state data depends on its neighbors (IE fence connections),
	 * it's encoded again when rendering and never saved
	 */
	boolean isBlockStateDataNeighborDependent(Block block);
	
	/** 
	 * @return true if the block's GTNHLib block state data is its own state kept outside its meta (IE a double plant's variant),
	 * it's encoded when the chunk is read and saved
	 */
	boolean isBlockStateDataLocalOnly(Block block);
	
	/** @return the block's data, null for none or if its handler failed */
	@Nullable
	String encodeBlockStateData(IBlockAccess world, int x, int y, int z, Block block, int meta);
	
	/**
	 * @param data null for the handler's default, IE for states stored before the data existed
	 * @return a world only containing the block and what its data adds around it,
	 * null if the block has no handler
	 */
	@Nullable
	IBlockAccess createBlockStateDataWorld(int x, int y, int z, Block block, int meta, @Nullable String data, @Nullable BiomeGenBase biome);
	#endif
}
