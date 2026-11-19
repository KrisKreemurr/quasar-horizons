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

package com.seibel.distanthorizons.cleanroom.modAccessor;

import com.seibel.distanthorizons.common.commonMixins.IFramebufferDepthTexture;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Accessor for Impetus, an Iris port for 1.12.2.
 * Calls the Iris v0 API through method handles.
 */
public class ImpetusAccessor implements IIrisAccessor
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	/** Handle for IrisApi.isShaderPackInUse. Null if binding failed. */
	private final MethodHandle isShaderPackInUse;
	/** Handle for IrisApi.isRenderingShadowPass. Null if binding failed. */
	private final MethodHandle isRenderingShadowPass;
	
	
	
	public ImpetusAccessor()
	{
		MethodHandle packInUse = null;
		MethodHandle shadowPass = null;
		try
		{
			Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			MethodHandles.Lookup lookup = MethodHandles.lookup();
			Object api = lookup.findStatic(apiClass, "getInstance", MethodType.methodType(apiClass)).invoke();
			packInUse = lookup.findVirtual(apiClass, "isShaderPackInUse", MethodType.methodType(boolean.class)).bindTo(api);
			shadowPass = lookup.findVirtual(apiClass, "isRenderingShadowPass", MethodType.methodType(boolean.class)).bindTo(api);
		}
		catch (Throwable e)
		{
			LOGGER.error("Unable to bind to the Impetus shader API, shader compatibility will be disabled. Error: [" + e.getMessage() + "].", e);
		}
		this.isShaderPackInUse = packInUse;
		this.isRenderingShadowPass = shadowPass;
	}
	
	
	
	@Override
	public String getModName() { return "Impetus"; }
	
	@Override
	public boolean isShaderPackInUse() { return this.invoke(this.isShaderPackInUse); }
	
	@Override
	public boolean isRenderingShadowPass() { return this.invoke(this.isRenderingShadowPass); }
	
	@Override
	public boolean isReverseZDuringShaders() { return false; }
	
	@Override
	public int getFramebufferDepthTextureId(Object framebuffer)
	{
		// Returns the Distant Horizons depth texture id of the framebuffer
		if (framebuffer instanceof IFramebufferDepthTexture)
		{
			return ((IFramebufferDepthTexture) framebuffer).distantHorizons$getDistantHorizonsDepthTexture();
		}
		
		return -1;
	}
	
	private boolean invoke(MethodHandle handle)
	{
		if (handle == null)
		{
			return false;
		}
		
		try
		{
			return (boolean) handle.invokeExact();
		}
		catch (Throwable e)
		{
			throw new RuntimeException("Impetus shader API call failed.", e);
		}
	}
	
}