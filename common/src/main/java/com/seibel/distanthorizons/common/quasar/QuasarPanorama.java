/*
 *    This file is part of Quasar Horizons, a fork of the Distant Horizons mod,
 *    licensed under the GNU LGPL v3 License.
 */

package com.seibel.distanthorizons.common.quasar;

#if MC_VER < MC_26_3_0
public final class QuasarPanorama
{
	public static float liveFarClipDistance = 0f;
	public static Object currentFace() { return null; }
	public static String getF3Line() { return ""; }
	public static boolean skipContainer(Object container, Object cam, Object face, float nearClip) { return false; }
}
#else

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiTransparency;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.common.render.blaze.BlazeDhMetaRenderer;
import com.seibel.distanthorizons.common.render.blaze.BlazeDhTerrainRenderer;
import com.seibel.distanthorizons.common.render.blaze.util.BlazePostProcessUtil;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPassWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPipelineBuilderWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.texture.BlazeTextureWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.uniform.BlazeUniformBufferWrapper;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.render.RenderBufferHandler;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.RenderUtil;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Locale;

/**
 * Quasar Horizons: far-LOD panorama.
 *
 * LODs beyond {@link #startDistance} are rendered into a 360° panorama (6 cube faces) centered on the
 * camera, refreshed only when the camera has moved a bit or every few seconds, one face per frame,
 * into a back buffer that is swapped in when complete. Every frame the panorama is painted into DH's
 * color/depth textures before the live LOD pass, which only draws LODs up to the start distance
 * (plus a margin so there is never a gap).
 *
 * Toggled with the system properties "quasarhorizons.panorama" (true/false) and
 * "quasarhorizons.panoramaStart" (blocks), which Quasium's Video Settings section sets.
 */
public final class QuasarPanorama
{
	private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	private static final AbstractDhRenderApiDefinition RENDER_DEF = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
	
	private static final int FACES = 6;
	private static final int FACE_SIZE = 1280;
	private static final long MAX_AGE_MS = 10_000;
	/** vertical movement changes what far terrain hides much more than horizontal movement does */
	private static final float VERTICAL_THRESHOLD = 6f;
	/** generous vertical extent used when bounding LOD sections (they have no height in their position) */
	private static final double MAX_VERTICAL_EXTENT = 512;
	
	/** +X, -X, +Y, -Y, +Z, -Z (matches the composite shader's face order) */
	private static final Matrix4f[] FACE_VIEW = {
		new Matrix4f().lookAlong(1, 0, 0, 0, 1, 0),
		new Matrix4f().lookAlong(-1, 0, 0, 0, 1, 0),
		new Matrix4f().lookAlong(0, 1, 0, 0, 0, -1),
		new Matrix4f().lookAlong(0, -1, 0, 0, 0, 1),
		new Matrix4f().lookAlong(0, 0, 1, 0, 1, 0),
		new Matrix4f().lookAlong(0, 0, -1, 0, 1, 0),
	};
	
	
	public static final class FaceTarget
	{
		public final BlazeTextureWrapper color;
		public final BlazeTextureWrapper depth;
		public final float nearClipDistance;
		public final int size;
		
		FaceTarget(BlazeTextureWrapper color, BlazeTextureWrapper depth, float nearClipDistance, int size)
		{
			this.color = color;
			this.depth = depth;
			this.nearClipDistance = nearClipDistance;
			this.size = size;
		}
	}
	
	/** Read by the terrain renderer: LODs farther than this are left to the panorama (0 = draw everything). */
	public static float liveFarClipDistance = 0f;
	
	private static FaceTarget currentFace;
	public static FaceTarget currentFace() { return currentFace; }
	
	// settings
	private static boolean enabled;
	private static float startDistance = 512f;
	
	// two sets of faces: one shown, one being rebuilt
	private static final BlazeTextureWrapper[][] colors = new BlazeTextureWrapper[2][FACES];
	private static final BlazeTextureWrapper[][] depths = new BlazeTextureWrapper[2][FACES];
	private static final Matrix4f[][] faceViewProj = new Matrix4f[2][FACES];
	private static final Matrix4f[][] faceInvViewProj = new Matrix4f[2][FACES];
	private static final double[][] centers = new double[2][3];
	private static int front = 0;
	private static boolean frontValid = false;
	private static int nextFace = -1;
	private static long refreshStartMs = 0;
	private static float builtStartDistance = -1f;
	private static int refreshCount = 0;
	
	// composite pass
	private static boolean compositeInit = false;
	private static RenderPipeline compositePipeline;
	private static GpuBuffer screenVbo;
	private static final BlazeUniformBufferWrapper compositeUniforms = new BlazeUniformBufferWrapper("quasarPanoCompositeUniformBlock");
	
	private static boolean failed = false;
	
	static
	{
		for (int set = 0; set < 2; set++)
		{
			for (int f = 0; f < FACES; f++)
			{
				colors[set][f] = BlazeTextureWrapper.createColor("quasar_panorama_color_" + set + "_" + f);
				depths[set][f] = BlazeTextureWrapper.createDepth("quasar_panorama_depth_" + set + "_" + f);
				faceViewProj[set][f] = new Matrix4f();
				faceInvViewProj[set][f] = new Matrix4f();
			}
		}
	}
	
	private QuasarPanorama() {}
	
	
	
	//=============//
	// frame entry //
	//=============//
	
	/** Called right after DH clears its color/depth textures, before the live LOD pass. */
	public static void afterClear(RenderParams renderParams)
	{
		liveFarClipDistance = 0f;
		readSettings();
		if (!enabled || failed || renderParams.renderBufferHandler == null || renderParams.exactCameraPosition == null)
		{
			frontValid = false;
			nextFace = -1;
			return;
		}
		
		try
		{
			float vanillaEdge = RenderUtil.getNearClipPlaneInBlocks();
			float start = Math.max(startDistance, vanillaEdge + 64f);
			float moveThreshold = Math.max(16f, start / 16f);
			DhVec3d cam = renderParams.exactCameraPosition;
			
			// start a refresh when needed
			if (nextFace < 0)
			{
				long now = System.currentTimeMillis();
				double[] c = centers[front];
				double dx = cam.x - c[0], dy = cam.y - c[1], dz = cam.z - c[2];
				boolean moved = (dx * dx + dz * dz) > (double) moveThreshold * moveThreshold
					|| Math.abs(dy) > VERTICAL_THRESHOLD;
				if (!frontValid || moved || now - refreshStartMs > MAX_AGE_MS || start != builtStartDistance)
				{
					int back = 1 - front;
					centers[back][0] = cam.x;
					centers[back][1] = cam.y;
					centers[back][2] = cam.z;
					nextFace = 0;
					refreshStartMs = now;
					builtStartDistance = start;
				}
			}
			
			// build one face of the back set per frame
			if (nextFace >= 0)
			{
				DhGpuTimer.mark("Panorama face");
				renderFace(renderParams, 1 - front, nextFace, start);
				nextFace++;
				if (nextFace >= FACES)
				{
					front = 1 - front;
					frontValid = true;
					nextFace = -1;
					refreshCount++;
				}
			}
			
			if (frontValid)
			{
				DhGpuTimer.mark("Panorama composite");
				composite(renderParams);
				liveFarClipDistance = start + moveThreshold + 32f;
			}
		}
		catch (Throwable t)
		{
			failed = true;
			currentFace = null;
			liveFarClipDistance = 0f;
			System.err.println("[Quasar Horizons] LOD panorama disabled after an error: " + t);
			t.printStackTrace();
		}
	}
	
	private static void readSettings()
	{
		enabled = Boolean.parseBoolean(System.getProperty("quasarhorizons.panorama", "false"));
		try
		{
			startDistance = Float.parseFloat(System.getProperty("quasarhorizons.panoramaStart", "512"));
		}
		catch (NumberFormatException e)
		{
			startDistance = 512f;
		}
	}
	
	
	
	//==============//
	// face render  //
	//==============//
	
	private static void renderFace(RenderParams params, int set, int face, float start)
	{
		BlazeTextureWrapper color = colors[set][face];
		BlazeTextureWrapper depth = depths[set][face];
		color.tryCreateOrResize(FACE_SIZE, FACE_SIZE);
		depth.tryCreateOrResize(FACE_SIZE, FACE_SIZE);
		color.clearColor(0);
		depth.clearDepth(RENDER_DEF.getDepthDirection().farDepth);
		
		// save the live camera
		DhApiMat4f savedMcMvm = params.mcModelViewMatrix.copy();
		DhApiMat4f savedDhMvm = params.dhModelViewMatrix.copy();
		DhApiMat4f savedDhProj = params.dhProjectionMatrix.copy();
		DhApiMat4f savedDhMvmProj = params.dhMvmProjMatrix.copy();
		DhVec3d savedCam = params.exactCameraPosition;
		
		try
		{
			// 90° square projection with the live projection's depth setup
			Matrix4f proj = DhMat4f.createJomlMatrix(savedDhProj);
			proj.m00(Math.signum(proj.m00()) == 0 ? 1f : Math.signum(proj.m00()));
			proj.m11(Math.signum(proj.m11()) == 0 ? 1f : Math.signum(proj.m11()));
			proj.m20(0f);
			proj.m21(0f);
			
			Matrix4f view = FACE_VIEW[face];
			Matrix4f viewProj = new Matrix4f(proj).mul(view);
			faceViewProj[set][face].set(viewProj);
			faceInvViewProj[set][face].set(viewProj).invert();
			
			DhMat4f dhView = new DhMat4f(view);
			params.mcModelViewMatrix.set(dhView);
			params.dhModelViewMatrix.set(dhView);
			params.dhProjectionMatrix.set(new DhMat4f(proj));
			params.dhMvmProjMatrix.set(new DhMat4f(viewProj));
			double[] c = centers[set];
			params.exactCameraPosition = new DhVec3d(c[0], c[1], c[2]);
			
			RenderBufferHandler buffers = params.renderBufferHandler;
			buffers.buildRenderList(params);
			
			IProfilerWrapper profiler = MC_CLIENT.getProfiler();
			currentFace = new FaceTarget(color, depth, start, FACE_SIZE);
			BlazeDhTerrainRenderer.INSTANCE.render(params, true, buffers.getColumnRenderBuffers(), profiler);
			if (Config.Client.Advanced.Graphics.Quality.transparency.get() == EDhApiTransparency.COMPLETE)
			{
				BlazeDhTerrainRenderer.INSTANCE.render(params, false, buffers.getColumnRenderBuffers(), profiler);
			}
		}
		finally
		{
			currentFace = null;
			params.mcModelViewMatrix.set(savedMcMvm);
			params.dhModelViewMatrix.set(savedDhMvm);
			params.dhProjectionMatrix.set(savedDhProj);
			params.dhMvmProjMatrix.set(savedDhMvmProj);
			params.exactCameraPosition = savedCam;
			// put the live render list back
			params.renderBufferHandler.buildRenderList(params);
		}
	}
	
	
	
	/**
	 * Lets the terrain renderer skip whole LOD sections that would be thrown away pixel by pixel anyway:
	 * - live pass: sections entirely inside DH's near clip (covered by real chunks; DH discards those
	 *   pixels) and, with the panorama on, sections entirely beyond the panorama split
	 * - panorama face: sections entirely inside the panorama's start distance
	 * Skipping whole sections is what saves GPU time; the shader's distance discard only trims edges.
	 */
	public static boolean skipContainer(LodBufferContainer container, DhVec3d cam, FaceTarget face, float nearClip)
	{
		if (cam == null) return false;
		
		int width = DhSectionPos.getBlockWidth(container.pos);
		double minX = container.minCornerBlockPos.getX(), minZ = container.minCornerBlockPos.getZ();
		double maxX = minX + width, maxZ = minZ + width;
		
		// farthest horizontal distance from the camera to the section
		double fx = Math.max(Math.abs(minX - cam.x), Math.abs(maxX - cam.x));
		double fz = Math.max(Math.abs(minZ - cam.z), Math.abs(maxZ - cam.z));
		double farthestSq = fx * fx + fz * fz;
		
		if (face == null)
		{
			// Entirely within the near clip horizontally: real chunks cover that whole column,
			// and DH discards these pixels anyway.
			if (nearClip > 0f && farthestSq < (double) nearClip * nearClip) return true;
			
			if (liveFarClipDistance > 0f)
			{
				// nearest horizontal distance from the camera to the section
				double nx = Math.max(Math.max(minX - cam.x, 0), cam.x - maxX);
				double nz = Math.max(Math.max(minZ - cam.z, 0), cam.z - maxZ);
				return nx * nx + nz * nz > (double) liveFarClipDistance * liveFarClipDistance;
			}
			return false;
		}
		else
		{
			// farthest distance from the panorama center (with a generous height)
			return farthestSq + MAX_VERTICAL_EXTENT * MAX_VERTICAL_EXTENT < (double) face.nearClipDistance * face.nearClipDistance;
		}
	}
	
	
	
	//===========//
	// composite //
	//===========//
	
	private static void tryInitComposite()
	{
		if (compositeInit) return;
		compositeInit = true;
		
		RenderPipelineBuilderWrapper builder = new RenderPipelineBuilderWrapper();
		builder.withFaceCulling(false);
		builder.withDepthWrite(true);
		builder.withDepthTest(RenderPipelineBuilderWrapper.EDhDepthTest.NONE); // always pass, we write our own depth
		builder.withColorWrite(true);
		builder.withoutBlend();
		builder.withPolygonMode(RenderPipelineBuilderWrapper.EDhPolygonMode.FILL);
		builder.withName("quasar_panorama_composite");
		builder.withVertexShader("copy/blaze/vert");
		builder.withFragmentShader("quasar/panorama/composite");
		for (int f = 0; f < FACES; f++) builder.withSampler("uC" + f);
		for (int f = 0; f < FACES; f++) builder.withSampler("uD" + f);
		builder.withUniformBuffer("panoUniformBlock");
		builder.withVertexFormat(BlazePostProcessUtil.createVertexFormat());
		builder.withVertexMode(RenderPipelineBuilderWrapper.EDhVertexMode.TRIANGLE_FAN);
		compositePipeline = builder.build();
		
		screenVbo = BlazePostProcessUtil.createAndUploadScreenVertexData("QuasarPanoramaComposite");
	}
	
	private static void composite(RenderParams params)
	{
		tryInitComposite();
		
		DhMat4f invCombined = new DhMat4f(params.dhMvmProjMatrix);
		invCombined.invert();
		double[] c = centers[front];
		DhVec3d cam = params.exactCameraPosition;
		
		for (int f = 0; f < FACES; f++) compositeUniforms.putMat4f(new DhMat4f(faceViewProj[front][f]));
		for (int f = 0; f < FACES; f++) compositeUniforms.putMat4f(new DhMat4f(faceInvViewProj[front][f]));
		compositeUniforms
			.putMat4f(invCombined) // uInvCombined
			.putMat4f(params.dhMvmProjMatrix) // uCombined
			.putVec4f((float) (c[0] - cam.x), (float) (c[1] - cam.y), (float) (c[2] - cam.z), 0f) // uCenterOffset
			.putFloat(RENDER_DEF.getDepthDirection().farDepth) // uFarDepth
			.putInt(RENDER_DEF.getDepthRange() == EDhApiDepthRange.ZERO_TO_POS_ONE ? 1 : 0) // uZeroToOne
			.finishAndUpload();
		
		try (RenderPassWrapper pass = new RenderPassWrapper(
			() -> "distantHorizons:QuasarPanoramaComposite",
			BlazeDhMetaRenderer.INSTANCE.dhColorTextureWrapper,
			BlazeDhMetaRenderer.INSTANCE.dhDepthTextureWrapper))
		{
			for (int f = 0; f < FACES; f++) pass.bindTexture("uC" + f, colors[front][f]);
			for (int f = 0; f < FACES; f++) pass.bindTexture("uD" + f, depths[front][f]);
			pass.setPipeline(compositePipeline);
			pass.setUniform("panoUniformBlock", compositeUniforms);
			pass.setVertexBuffer(screenVbo);
			pass.draw(4);
		}
	}
	
	
	
	//=====//
	// F3  //
	//=====//
	
	public static String getF3Line()
	{
		readSettings();
		if (!enabled) return "Quasar Horizons panorama: off";
		if (failed) return "Quasar Horizons panorama: disabled after an error (see log)";
		return String.format(Locale.ROOT, "Quasar Horizons panorama: %s, start %.0f blocks, %d refreshes%s",
			frontValid ? "active" : "building",
			builtStartDistance,
			refreshCount,
			nextFace >= 0 ? " (refreshing " + nextFace + "/6)" : "");
	}
}
#endif
