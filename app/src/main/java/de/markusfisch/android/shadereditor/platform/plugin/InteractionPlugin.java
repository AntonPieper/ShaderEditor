package de.markusfisch.android.shadereditor.platform.plugin;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.Engine;
import de.markusfisch.android.shadereditor.engine.Plugin;
import de.markusfisch.android.shadereditor.engine.data.InteractionDataKeys;
import de.markusfisch.android.shadereditor.engine.data.ObservableDataProvider;
import de.markusfisch.android.shadereditor.engine.data.Vec2;
import de.markusfisch.android.shadereditor.engine.util.observer.ReadOnlyObservable;

/**
 * A plugin that provides data related to user interactions, such as touch
 * input and wallpaper offsets. This plugin acts as a bridge, connecting
 * observable data sources from the UI layer to the engine's data provider system.
 */
public class InteractionPlugin implements Plugin {
	@NonNull
	private final ReadOnlyObservable<Vec2> touchPositionSource;
	@NonNull
	private final ReadOnlyObservable<Vec2> wallpaperOffsetSource;

	public InteractionPlugin(
			@NonNull ReadOnlyObservable<Vec2> touchPositionSource,
			@NonNull ReadOnlyObservable<Vec2> wallpaperOffsetSource
	) {
		this.touchPositionSource = touchPositionSource;
		this.wallpaperOffsetSource = wallpaperOffsetSource;
	}

	@Override
	public void onSetup(@NonNull Engine engine) {
		// Register providers that will expose the observable values to the engine.
		engine.registerProviderFactory(
				InteractionDataKeys.TOUCH_POSITION,
				() -> new ObservableDataProvider<>(touchPositionSource)
		).registerProviderFactory(
				InteractionDataKeys.WALLPAPER_OFFSET,
				() -> new ObservableDataProvider<>(wallpaperOffsetSource)
		);
	}
}