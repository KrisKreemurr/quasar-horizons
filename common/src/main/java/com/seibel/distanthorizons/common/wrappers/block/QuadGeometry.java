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

import com.seibel.distanthorizons.core.enums.EDhDirection;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.jetbrains.annotations.Nullable;

/**
 * A Minecraft model quad (IE a BakedQuad) converted into the coordinate 
 * space of the face it's baked onto.
 */
public class QuadGeometry
{
	/** 
	 * In the range [0,1] with (0,0) being the face's top left corner. <br>
	 * U increases to the right. <br>
	 */
	public final float[] faceUByVertex = new float[4];
	/**
	 * In the range [0,1] with (0,0) being the face's top left corner. <br>
	 * V increases downward.
	 */
	public final float[] faceVByVertex = new float[4];
	
	/** 
	 * Increases as it moves towards the viewer. <br>
	 * A depth of 1 
	 */
	public final float[] depthByVertex = new float[4];
	
	/** sprite local texture coordinates in the range [0,1] */
	public final float[] spriteUByVertex = new float[4];
	/** sprite local texture coordinates in the range [0,1] */
	public final float[] spriteVByVertex = new float[4];
	
	/** block space positions in the range [0,1] */
	public final float[] xByVertex = new float[4];
	public final float[] yByVertex = new float[4];
	public final float[] zByVertex = new float[4];
	
	
	public TextureAtlasSprite sprite;
	public boolean tinted;
	@Nullable
	public EDhDirection shadeDirectionOverride;
	
	
	public double getAverageDepth()
	{ return (this.depthByVertex[0] + this.depthByVertex[1] + this.depthByVertex[2] + this.depthByVertex[3]) / 4.0; }
}
