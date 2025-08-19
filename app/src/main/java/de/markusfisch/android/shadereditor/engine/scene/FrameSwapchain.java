package de.markusfisch.android.shadereditor.engine.scene;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.GpuObject;

/**
 * A stable, logical identifier for a persistent, double-buffered resource
 * (a "backbuffer") that is managed by the engine's renderer.
 * <p>
 * Plugins use this record to declare their intent to use a feedback loop.
 * The renderer is responsible for creating, sizing, and swapping the
 * underlying GPU textures and framebuffers associated with this name.
 *
 * @param name A unique name to identify this swapchain resource.
 */
public record FrameSwapchain(@NonNull String name) implements GpuObject {
}