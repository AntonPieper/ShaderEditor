package de.markusfisch.android.shadereditor.engine;

/**
 * A marker interface for any object that represents a resource on the GPU,
 * such as a texture, framebuffer, or vertex buffer.
 * <p>
 * This allows the platform-specific renderer to manage the lifecycle of these
 * resources without the core engine needing to know about the underlying graphics API.
 */
public interface GpuObject {
}