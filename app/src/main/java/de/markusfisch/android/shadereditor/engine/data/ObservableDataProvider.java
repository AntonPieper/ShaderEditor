package de.markusfisch.android.shadereditor.engine.data;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import de.markusfisch.android.shadereditor.engine.util.observer.ReadOnlyObservable;

/**
 * A generic DataProvider that wraps a ReadOnlyObservable.
 * It listens to an observable source and makes its value available under a
 * specific DataKey. This is the primary bridge for connecting observable state
 * to the data provider system.
 *
 * @param <T> The type of the data this provider will supply.
 */
public class ObservableDataProvider<T> implements DataProvider<T> {
	@NonNull
	private final ReadOnlyObservable<T> source;
	@Nullable
	private ReadOnlyObservable.Subscription subscription;
	@NonNull
	private T currentValue;

	public ObservableDataProvider(
			@NonNull ReadOnlyObservable<T> source) {
		this.source = source;
		this.currentValue = source.get(); // Initialize with current value.
	}


	@Override
	public void start() {
		if (subscription != null) {
			Log.w("ObservableDataProvider", "start called when already started");
			return;
		}
		subscription = source.subscribe(value -> currentValue = value);
	}

	@Override
	public void stop() {
		if (subscription != null) {
			subscription.unsubscribe();
			subscription = null;
		}
	}

	@NonNull
	@Override
	public T getValue() {
		return currentValue;
	}
}