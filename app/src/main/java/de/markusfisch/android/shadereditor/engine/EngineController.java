package de.markusfisch.android.shadereditor.engine;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;

import de.markusfisch.android.shadereditor.engine.asset.Asset;
import de.markusfisch.android.shadereditor.engine.asset.AssetLoader;
import de.markusfisch.android.shadereditor.engine.asset.AssetProvider;
import de.markusfisch.android.shadereditor.engine.asset.AssetRef;
import de.markusfisch.android.shadereditor.engine.data.CachingDataProviderManager;
import de.markusfisch.android.shadereditor.engine.data.DataKey;
import de.markusfisch.android.shadereditor.engine.data.DataProvider;
import de.markusfisch.android.shadereditor.engine.data.DataProviderManager;
import de.markusfisch.android.shadereditor.engine.data.EngineDataKeys;
import de.markusfisch.android.shadereditor.engine.data.ObservableDataProvider;
import de.markusfisch.android.shadereditor.engine.pipeline.CommandBuffer;
import de.markusfisch.android.shadereditor.engine.util.observer.ObservableValue;

public class EngineController {
	@NonNull
	public final Engine facade = new EngineFacade();
	private final Deque<ProviderRegistration<?>> addedDataSources = new ArrayDeque<>();
	@NonNull
	private final CachingDataProviderManager dataProviderManager;
	@NonNull
	private final AssetProvider assetProvider;
	@NonNull
	private final Renderer renderer;
	@NonNull
	private final ShaderIntrospector shaderIntrospector;
	@NonNull
	private final PluginManager pluginManager;
	@NonNull
	private final ObservableValue<Viewport> viewport = ObservableValue.of(new Viewport(0, 0));
	@NonNull
	private final CommandBuffer currentFrame = new CommandBuffer(new ArrayList<>());

	public EngineController(
			@NonNull DataProviderManager dataProviderManager,
			@NonNull AssetProvider assetProvider,
			@NonNull Renderer renderer,
			@NonNull ShaderIntrospector shaderIntrospector) {
		this.dataProviderManager = new CachingDataProviderManager(dataProviderManager);
		this.assetProvider = assetProvider;
		this.renderer = renderer;
		this.shaderIntrospector = shaderIntrospector;
		this.pluginManager = new PluginManager();
		facade.registerProviderFactory(
				EngineDataKeys.VIEWPORT_RESOLUTION,
				() -> new ObservableDataProvider<>(viewport));
	}

	public void setup() {
		renderer.onSurfaceCreated();
		pluginManager.setupPlugins(facade);
		processAddedDataSources();
	}

	public void setViewport(int width, int height) {
		viewport.set(new Viewport(width, height));
		renderer.onSurfaceChanged(width, height);
	}

	public void renderFrame() {
		dataProviderManager.invalidateCache();
		pluginManager.setupPlugins(facade);
		processAddedDataSources();

		// 1. Pre-render hooks
		pluginManager.preRender(facade);

		// 2. Clear last frame's work and ask plugins to queue this frame's work
		currentFrame.cmds().clear();
		pluginManager.render(facade);

		// 3. Execute the queued render passes
		if (!currentFrame.cmds().isEmpty()) {
			renderer.execute(currentFrame);
		}

		// 4. Post-render hooks
		pluginManager.postRender(facade);
	}

	public void shutdown() {
		pluginManager.teardown(facade);
		addedDataSources.clear();
		currentFrame.cmds().clear();
		dataProviderManager.stopActiveProviders();
	}

	@NonNull
	public Engine getFacade() {
		return facade;
	}

	private void processAddedDataSources() {
		ProviderRegistration<?> registration;
		while ((registration = addedDataSources.poll()) != null) {
			registerProvider(registration);
		}
	}

	private <T> void registerProvider(@NonNull ProviderRegistration<T> registration) {
		dataProviderManager.register(registration.key(), registration.factory());
	}

	private record ProviderRegistration<T>(
			@NonNull DataKey<T> key, @NonNull DataProvider.Factory<T> factory
	) {
	}

	private class EngineFacade implements Engine {
		@Override
		public void registerPlugin(@NonNull Plugin plugin) {
			pluginManager.registerPlugin(plugin);
		}

		@NonNull
		@Override
		public <T> Engine registerProviderFactory(
				@NonNull DataKey<T> key, @NonNull DataProvider.Factory<T> factory) {
			var entry = new ProviderRegistration<>(
					key,
					factory);
			addedDataSources.add(entry);
			return this;
		}

		@Override
		public <T extends Asset> void registerAssetLoader(
				@NonNull Class<T> assetType,
				@NonNull AssetLoader<T> loader) {
			assetProvider.registerLoader(assetType, loader);
		}

		@Nullable
		@Override
		public <T> T getData(@NonNull DataKey<T> key) {
			return dataProviderManager.getData(key);
		}

		@Override
		public void submitCommands(@NonNull CommandBuffer commands) {
			currentFrame.cmds().addAll(commands.cmds());
		}

		@NonNull
		@Override
		public <T extends Asset> T loadAsset(@NonNull AssetRef s, @NonNull Class<T> assetType) {
			return assetProvider.load(s, assetType);
		}

		@NonNull
		@Override
		public ShaderIntrospector getShaderIntrospector() {
			return shaderIntrospector;
		}
	}
}