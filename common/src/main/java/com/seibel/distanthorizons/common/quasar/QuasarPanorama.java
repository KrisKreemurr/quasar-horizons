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
		servicePortalPanoramas(renderParams);
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
		renderFaceInto(params, colors[set][face], depths[set][face], centers[set], face, start, FACE_SIZE,
			faceViewProj[set][face], faceInvViewProj[set][face]);
	}
	
	/** Renders one 90° cube face of the LODs beyond {@code start} as seen from {@code center} into the given targets. */
	private static void renderFaceInto(RenderParams params, BlazeTextureWrapper color, BlazeTextureWrapper depth,
		double[] center, int face, float start, int size, Matrix4f outViewProj, Matrix4f outInvViewProj)
	{
		color.tryCreateOrResize(size, size);
		depth.tryCreateOrResize(size, size);
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
			outViewProj.set(viewProj);
			outInvViewProj.set(viewProj).invert();
			
			DhMat4f dhView = new DhMat4f(view);
			params.mcModelViewMatrix.set(dhView);
			params.dhModelViewMatrix.set(dhView);
			params.dhProjectionMatrix.set(new DhMat4f(proj));
			params.dhMvmProjMatrix.set(new DhMat4f(viewProj));
			double[] c = center;
			params.exactCameraPosition = new DhVec3d(c[0], c[1], c[2]);
			
			RenderBufferHandler buffers = params.renderBufferHandler;
			buffers.buildRenderList(params);
			
			IProfilerWrapper profiler = MC_CLIENT.getProfiler();
			currentFace = new FaceTarget(color, depth, start, size);
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
	
	
	
	//===================//
	// portal panoramas  //
	//===================//
	
	/*
	 * Quasar MC: far terrain through portals and mirrors (Quasar Immersive Portals). The portal mod asks every frame
	 * for a panorama centred on a portal's camera ({@link #requestPortal}); up to MAX_PORTAL_PANOS are kept, each a
	 * double-buffered cube of the LODs beyond the portal's live terrain. One face is built per frame in total, and only
	 * when a cube is missing, its camera moved past the threshold, it is 10 s old or the start distance changed - the
	 * steady-state cost is zero. The portal mod paints the front cube into its view ({@link #portalView}).
	 */
	private static final int MAX_PORTAL_PANOS = 2;
	private static final long PORTAL_IDLE_MS = 2_000;
	
	private static final class PortalPano
	{
		final BlazeTextureWrapper[][] colors = new BlazeTextureWrapper[2][FACES];
		final BlazeTextureWrapper[][] depths = new BlazeTextureWrapper[2][FACES];
		final Matrix4f[][] viewProj = new Matrix4f[2][FACES];
		final Matrix4f[][] invViewProj = new Matrix4f[2][FACES];
		final double[][] centers = new double[2][3];
		int key = Integer.MIN_VALUE;
		int front = 0;
		boolean valid = false;
		int nextFace = -1;
		long refreshStartMs;
		float builtStart = -1f;
		int builtSize = 0;
		final double[] want = new double[3];
		float wantStart;
		int wantSize;
		long lastRequestMs;
		
		PortalPano(int slot)
		{
			for (int set = 0; set < 2; set++)
			{
				for (int f = 0; f < FACES; f++)
				{
					this.colors[set][f] = BlazeTextureWrapper.createColor("quasar_portal_pano_color_" + slot + "_" + set + "_" + f);
					this.depths[set][f] = BlazeTextureWrapper.createDepth("quasar_portal_pano_depth_" + slot + "_" + set + "_" + f);
					this.viewProj[set][f] = new Matrix4f();
					this.invViewProj[set][f] = new Matrix4f();
				}
			}
		}
	}
	
	private static final PortalPano[] portalPanos = new PortalPano[MAX_PORTAL_PANOS];
	private static int portalRoundRobin = 0;
	private static boolean portalFailed = false;
	private static int portalFacesThisSecond = 0;
	private static int shownPortalFaces = 0;
	private static long portalSecondStart = 0;
	
	/**
	 * Ask for (or keep alive) the far-terrain panorama of portal {@code key}, centred on its camera.
	 * {@code start}: blocks from the centre where the panorama begins (the portal's live terrain ends there);
	 * {@code size}: face resolution in pixels.
	 */
	public static void requestPortal(int key, double x, double y, double z, float start, int size)
	{
		if (portalFailed) return;
		long now = System.currentTimeMillis();
		PortalPano slot = null;
		for (PortalPano p : portalPanos)
		{
			if (p != null && p.key == key) { slot = p; break; }
		}
		if (slot == null)
		{
			// a free slot, or one nobody asked for lately
			for (int i = 0; i < MAX_PORTAL_PANOS; i++)
			{
				PortalPano p = portalPanos[i];
				if (p == null) { p = portalPanos[i] = new PortalPano(i); }
				if (p.key == Integer.MIN_VALUE || now - p.lastRequestMs > PORTAL_IDLE_MS)
				{
					p.key = key;
					p.valid = false;
					p.nextFace = -1;
					slot = p;
					break;
				}
			}
			if (slot == null) return; // all slots busy with nearer portals
		}
		slot.want[0] = x;
		slot.want[1] = y;
		slot.want[2] = z;
		slot.wantStart = start;
		slot.wantSize = Math.max(64, Math.min(2048, size));
		slot.lastRequestMs = now;
	}
	
	/**
	 * The current panorama of portal {@code key}, or null if there is none yet:
	 * { GpuTextureView[6] colors, GpuTextureView[6] depths, float[96] face view-projections,
	 *   float[96] their inverses, double[3] centre, Float far depth, Boolean zero-to-one depth range }.
	 */
	public static Object[] portalView(int key)
	{
		for (PortalPano p : portalPanos)
		{
			if (p == null || p.key != key || !p.valid) continue;
			com.mojang.renderpearl.api.textures.GpuTextureView[] c = new com.mojang.renderpearl.api.textures.GpuTextureView[FACES];
			com.mojang.renderpearl.api.textures.GpuTextureView[] d = new com.mojang.renderpearl.api.textures.GpuTextureView[FACES];
			float[] vp = new float[FACES * 16];
			float[] inv = new float[FACES * 16];
			for (int f = 0; f < FACES; f++)
			{
				c[f] = p.colors[p.front][f].getTextureView();
				d[f] = p.depths[p.front][f].getTextureView();
				p.viewProj[p.front][f].get(vp, f * 16);
				p.invViewProj[p.front][f].get(inv, f * 16);
			}
			double[] center = p.centers[p.front].clone();
			return new Object[] { c, d, vp, inv, center,
				RENDER_DEF.getDepthDirection().farDepth,
				RENDER_DEF.getDepthRange() == EDhApiDepthRange.ZERO_TO_POS_ONE };
		}
		return null;
	}
	
	private static void servicePortalPanoramas(RenderParams params)
	{
		if (portalFailed || params.renderBufferHandler == null || params.exactCameraPosition == null) return;
		try
		{
			long now = System.currentTimeMillis();
			// start refreshes where needed
			for (PortalPano p : portalPanos)
			{
				if (p == null || p.key == Integer.MIN_VALUE || p.nextFace >= 0) continue;
				if (now - p.lastRequestMs > PORTAL_IDLE_MS) continue; // not wanted now: no work
				double[] c = p.centers[p.front];
				double dx = p.want[0] - c[0], dy = p.want[1] - c[1], dz = p.want[2] - c[2];
				double threshold = Math.max(8.0, p.wantStart / 8.0);
				boolean moved = dx * dx + dz * dz > threshold * threshold || Math.abs(dy) > VERTICAL_THRESHOLD;
				if (!p.valid || moved || now - p.refreshStartMs > MAX_AGE_MS
					|| p.wantStart != p.builtStart || p.wantSize != p.builtSize)
				{
					int back = 1 - p.front;
					p.centers[back][0] = p.want[0];
					p.centers[back][1] = p.want[1];
					p.centers[back][2] = p.want[2];
					p.nextFace = 0;
					p.refreshStartMs = now;
					p.builtStart = p.wantStart;
					p.builtSize = p.wantSize;
				}
			}
			// build one face per frame, round-robin over the panoramas being refreshed
			for (int i = 0; i < MAX_PORTAL_PANOS; i++)
			{
				PortalPano p = portalPanos[(portalRoundRobin + i) % MAX_PORTAL_PANOS];
				if (p == null || p.nextFace < 0) continue;
				portalRoundRobin = (portalRoundRobin + i + 1) % MAX_PORTAL_PANOS;
				int back = 1 - p.front;
				DhGpuTimer.mark("Portal panorama face");
				renderFaceInto(params, p.colors[back][p.nextFace], p.depths[back][p.nextFace], p.centers[back],
					p.nextFace, p.builtStart, p.builtSize, p.viewProj[back][p.nextFace], p.invViewProj[back][p.nextFace]);
				portalFacesThisSecond++;
				p.nextFace++;
				if (p.nextFace >= FACES)
				{
					p.front = back;
					p.valid = true;
					p.nextFace = -1;
				}
				break;
			}
			if (now - portalSecondStart >= 1000)
			{
				portalSecondStart = now;
				shownPortalFaces = portalFacesThisSecond;
				portalFacesThisSecond = 0;
			}
		}
		catch (Throwable t)
		{
			portalFailed = true;
			currentFace = null;
			System.err.println("[Quasar Horizons] portal panoramas disabled after an error: " + t);
			t.printStackTrace();
		}
	}
	
	/** F3: portal panoramas in use and faces built per second. */
	public static String getPortalF3Line()
	{
		if (portalFailed) return "Portal panoramas: disabled after an error (see log)";
		long now = System.currentTimeMillis();
		int active = 0, ready = 0;
		for (PortalPano p : portalPanos)
		{
			if (p == null || p.key == Integer.MIN_VALUE || now - p.lastRequestMs > PORTAL_IDLE_MS) continue;
			active++;
			if (p.valid) ready++;
		}
		if (active == 0) return "Portal panoramas: idle";
		return String.format(Locale.ROOT, "Portal panoramas: %d (%d ready), %d faces/s", active, ready, shownPortalFaces);
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
