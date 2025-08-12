package de.markusfisch.android.shadereditor.engine;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import de.markusfisch.android.shadereditor.engine.asset.Asset;
import de.markusfisch.android.shadereditor.engine.asset.AssetLoader;
import de.markusfisch.android.shadereditor.engine.asset.AssetRef;
import de.markusfisch.android.shadereditor.engine.data.DataKey;
import de.markusfisch.android.shadereditor.engine.data.DataProvider;
import de.markusfisch.android.shadereditor.engine.scene.RenderPass;

public interface Engine {
	void registerPlugin(@NonNull Plugin plugin);

	@NonNull
	<T> Engine registerProviderFactory(
			@NonNull DataKey<T> key, @NonNull DataProvider.Factory<T> factory);

	<T extends Asset> void registerAssetLoader(
			@NonNull Class<T> assetType, @NonNull AssetLoader<T> loader);

	/**
	 * Retrieves data from the provider system.
	 *
	 * @param key The type-safe key for the data.
	 * @return The requested data, or null if not available.
	 */
	@Nullable
	<T> T getData(@NonNull DataKey<T> key);

	/**
	 * Submits a render pass to be drawn this frame.
	 * This should be called by plugins during the onRender hook.
	 *
	 * @param renderPass The description of what to render.
	 */
	void submit(@NonNull RenderPass renderPass);

	@NonNull
	<T extends Asset> T loadAsset(@NonNull AssetRef s, @NonNull Class<T> assetType);

	/**
	 * Gets the service for introspecting shader metadata.
	 *
	 * @return The singleton ShaderIntrospector instance.
	 */
	@NonNull
	ShaderIntrospector getShaderIntrospector();
}