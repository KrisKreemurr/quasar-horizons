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

package com.seibel.distanthorizons.common.wrappers.minecraft;

import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;

#if MC_VER <= MC_1_12_2
import net.minecraft.profiler.Profiler;
#else
import net.minecraft.util.profiling.ProfilerFiller;
#endif

public class ProfilerWrapper implements IProfilerWrapper
{
	#if MC_VER <= MC_1_12_2
	public Profiler profiler;
	#else
	public ProfilerFiller profiler;
	#endif
	
	
	
	#if MC_VER <= MC_1_12_2
	public ProfilerWrapper(Profiler newProfiler)
	#else
	public ProfilerWrapper(ProfilerFiller newProfiler)
	#endif
	{ this.profiler = newProfiler; }
	
	
	
	// Quasar Horizons: track DH's render block so its steps can be GPU-timed
	private static final String DH_RENDER_BLOCK = "LOD GL setup";
	private int depth = 0;
	private int renderBlockDepth = -1;
	/** only the thread that runs DH's render block is tracked (the wrapper can be shared) */
	private volatile Thread renderThread;
	
	@Override
	public IProfileBlock push(String newSection) 
	{
		Thread current = Thread.currentThread();
		if (this.renderThread == null && DH_RENDER_BLOCK.equals(newSection))
		{
			this.renderThread = current;
		}
		boolean tracked = current == this.renderThread;
		if (tracked)
		{
			this.depth++;
			if (this.renderBlockDepth < 0 && DH_RENDER_BLOCK.equals(newSection))
			{
				this.renderBlockDepth = this.depth;
				com.seibel.distanthorizons.common.quasar.DhGpuTimer.begin(newSection);
			}
		}
		

		#if MC_VER <= MC_1_12_2
		this.profiler.startSection(newSection);
		#else
		this.profiler.push(newSection);
		#endif
		return new ProfileBlock(this.profiler, tracked ? this : null);
	}
	
	@Override
	public void popPush(String newSection) 
	{
		if (Thread.currentThread() == this.renderThread && this.depth == this.renderBlockDepth)
		{
			com.seibel.distanthorizons.common.quasar.DhGpuTimer.mark(newSection);
		}
		

		#if MC_VER <= MC_1_12_2
		this.profiler.endStartSection(newSection);
		#else
		this.profiler.popPush(newSection) ;
		#endif
	}
	
	
	
	//================//
	// helper classes //
	//================//
	//region
	
	public static class ProfileBlock implements IProfileBlock
	{
		#if MC_VER <= MC_1_12_2
		public Profiler profiler;
		#else
		public ProfilerFiller profiler;
		#endif

		#if MC_VER <= MC_1_12_2
		public ProfileBlock(Profiler newProfiler)
		#else
		public ProfileBlock(ProfilerFiller newProfiler)
		#endif
		{ this.profiler = newProfiler; }
		
		private ProfilerWrapper owner;
		
		#if MC_VER <= MC_1_12_2
		public ProfileBlock(Profiler newProfiler, ProfilerWrapper owner)
		#else
		public ProfileBlock(ProfilerFiller newProfiler, ProfilerWrapper owner)
		#endif
		{
			this.profiler = newProfiler;
			this.owner = owner;
		}
		
		@Override
		public void close()
		{
			if (this.owner != null)
			{
				if (this.owner.depth == this.owner.renderBlockDepth)
				{
					com.seibel.distanthorizons.common.quasar.DhGpuTimer.end();
					this.owner.renderBlockDepth = -1;
				}
				this.owner.depth--;
			}
			

			#if MC_VER <= MC_1_12_2
			this.profiler.endSection();
			#else
			this.profiler.pop();
			#endif
		}
	}
	
	//endregion
	
	
	
}
