package de.markusfisch.android.shadereditor.engine.util.observer;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicReference;

public class ObservableValue<T> implements ReadOnlyObservable<T>, AutoCloseable {

	private final Set<Observer<T>> observers = new CopyOnWriteArraySet<>();
	@NonNull
	private final AtomicReference<T> currentValue;

	private ObservableValue(T initialValue) {
		this.currentValue = new AtomicReference<>(initialValue);
	}

	@NonNull
	@Contract("_ -> new")
	public static <T extends Record> ObservableValue<T> of(T initialValue) {
		return new ObservableValue<>(initialValue);
	}

	public T get() {
		return currentValue.get();
	}

	@NonNull
	public Subscription subscribe(@NonNull Observer<T> observer) {
		observers.add(observer);
		observer.update(currentValue.get());
		return () -> observers.remove(observer);
	}

	public void set(T value) {
		currentValue.set(value);
		for (var observer : observers) {
			observer.update(value);
		}
	}

	@Override
	public void close() {
		observers.clear();
	}
}
