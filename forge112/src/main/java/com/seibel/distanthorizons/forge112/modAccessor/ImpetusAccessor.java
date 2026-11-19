/*
 * This file is part of Distant Horizons, licensed under the GNU LGPL v3 License.
 * Copyright (C) 2026 Distant Horizons contributors
 */

package com.seibel.distanthorizons.forge112.modAccessor;

import com.seibel.distanthorizons.common.commonMixins.IFramebufferDepthTexture;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;
import net.irisshaders.iris.api.v0.IrisApi;

/** Accessor for Impetus through the Iris v0 API. */
public final class ImpetusAccessor implements IIrisAccessor
{
	@Override
	public String getModName()
	{
		return "Impetus";
	}

	@Override
	public boolean isShaderPackInUse()
	{
		return IrisApi.getInstance().isShaderPackInUse();
	}

	@Override
	public boolean isRenderingShadowPass()
	{
		return IrisApi.getInstance().isRenderingShadowPass();
	}

	@Override
	public boolean isReverseZDuringShaders()
	{
		return false;
	}

	@Override
	public boolean supportsMultiBackend()
	{
		return false;
	}

	@Override
	public int getFramebufferDepthTextureId(Object framebuffer)
	{
		if (framebuffer instanceof IFramebufferDepthTexture)
		{
			return ((IFramebufferDepthTexture) framebuffer).distantHorizons$getDistantHorizonsDepthTexture();
		}
		return -1;
	}
}