/*
 *    This file is part of Quasar Horizons, a fork of the Distant Horizons mod,
 *    licensed under the GNU LGPL v3 License.
 */

package com.seibel.distanthorizons.common.quasar;

#if MC_VER >= MC_26_3_0
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.device.GpuDevice;
#endif

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Quasar Horizons: measures how long the GPU spends on each of DH's render steps.
 *
 * DH already marks its steps with profiler sections ("LOD Opaque", "LOD SSAO", "LOD Fog", ...).
 * At each step boundary we write a GPU timestamp; a few frames later (when the GPU has finished)
 * we read them back and keep a smoothed time per step, shown on the F3 screen.
 */
public final class DhGpuTimer
{
	private static final int MAX_MARKS = 32;
	private static final int FRAMES = 4; // results are read back FRAMES - 1 frames later
	private static final double SMOOTHING = 0.05;
	
	#if MC_VER >= MC_26_3_0
	private static GpuQueryPool pool;
	#endif
	private static boolean failed;
	private static boolean active;
	private static int slot;
	private static final int[] markCount = new int[FRAMES];
	private static final String[][] labels = new String[FRAMES][MAX_MARKS];
	
	/** smoothed milliseconds per step, in the order DH runs them */
	private static final Map<String, Double> smoothedMs = new LinkedHashMap<>();
	private static double smoothedTotalMs;
	private static boolean hasResults;
	
	private DhGpuTimer() {}
	
	
	
	/** Called when DH starts its render block for the frame. */
	public static void begin(String firstStep)
	{
		#if MC_VER >= MC_26_3_0
		if (failed) return;
		try
		{
			GpuDevice device = RenderSystem.getDevice();
			if (pool == null)
			{
				pool = device.createTimestampQueryPool(MAX_MARKS * FRAMES);
			}
			
			slot = (slot + 1) % FRAMES;
			// this slot was recorded FRAMES frames ago, the GPU is done with it by now
			resolve(slot, device.getDeviceInfo().timestampPeriod());
			markCount[slot] = 0;
			active = true;
			mark(firstStep);
		}
		catch (Throwable t)
		{
			disable(t);
		}
		#endif
	}
	
	/** Called at each DH step boundary (profiler popPush). */
	public static void mark(String step)
	{
		#if MC_VER >= MC_26_3_0
		if (!active || failed) return;
		int count = markCount[slot];
		if (count >= MAX_MARKS) return;
		try
		{
			RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pool, slot * MAX_MARKS + count);
			labels[slot][count] = step;
			markCount[slot] = count + 1;
		}
		catch (Throwable t)
		{
			disable(t);
		}
		#endif
	}
	
	/** Called when DH's render block for the frame ends. */
	public static void end()
	{
		#if MC_VER >= MC_26_3_0
		if (!active) return;
		mark("end");
		active = false;
		#endif
	}
	
	
	
	#if MC_VER >= MC_26_3_0
	private static void resolve(int frameSlot, float nanosPerTick)
	{
		int count = markCount[frameSlot];
		if (count < 2) return;
		
		OptionalLong[] values = pool.getValues(frameSlot * MAX_MARKS, count);
		Map<String, Double> frameMs = new LinkedHashMap<>();
		double total = 0;
		for (int i = 0; i + 1 < count; i++)
		{
			if (!values[i].isPresent() || !values[i + 1].isPresent()) return; // not ready, skip this frame
			double ms = (values[i + 1].getAsLong() - values[i].getAsLong()) * (double) nanosPerTick / 1_000_000.0;
			if (ms < 0) return;
			frameMs.merge(labels[frameSlot][i], ms, Double::sum);
			total += ms;
		}
		
		if (!hasResults)
		{
			smoothedMs.putAll(frameMs);
			smoothedTotalMs = total;
			hasResults = true;
			return;
		}
		
		for (Map.Entry<String, Double> e : frameMs.entrySet())
		{
			smoothedMs.merge(e.getKey(), e.getValue(), (old, now) -> old + (now - old) * SMOOTHING);
		}
		// steps that didn't run this frame fade out
		smoothedMs.replaceAll((step, ms) -> frameMs.containsKey(step) ? ms : ms * (1 - SMOOTHING));
		smoothedMs.values().removeIf(ms -> ms < 0.001);
		smoothedTotalMs += (total - smoothedTotalMs) * SMOOTHING;
	}
	#endif
	
	private static void disable(Throwable t)
	{
		failed = true;
		active = false;
		System.err.println("[Quasar Horizons] GPU timers disabled: " + t);
	}
	
	
	
	/** Lines for the F3 screen. */
	public static List<String> getF3Lines()
	{
		List<String> lines = new ArrayList<>();
		if (failed)
		{
			lines.add("Quasar Horizons GPU timers: unavailable");
			return lines;
		}
		if (!hasResults)
		{
			lines.add("Quasar Horizons GPU: waiting for data");
			return lines;
		}
		lines.add(QuasarPanorama.getF3Line());
		lines.add(String.format(Locale.ROOT, "Quasar Horizons GPU: %.2f ms total", smoothedTotalMs));
		for (Map.Entry<String, Double> e : smoothedMs.entrySet())
		{
			lines.add(String.format(Locale.ROOT, "  %s: %.3f ms", e.getKey(), e.getValue()));
		}
		return lines;
	}
}
