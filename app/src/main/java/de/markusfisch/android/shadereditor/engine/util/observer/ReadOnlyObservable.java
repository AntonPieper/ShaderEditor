package de.markusfisch.android.shadereditor.engine.util.observer;

import androidx.annotation.NonNull;

import java.util.function.Function;

/**
 * A read-only view of a value that can be observed for changes.
 * This interface prevents consumers from modifying the underlying value.
 *
 * @param <T> The type of the value.
 */
public interface ReadOnlyObservable<T> {
	/**
	 * A handle to an active observation, allowing the observer to unsubscribe.
	 */
	@FunctionalInterface
	interface Subscription {
		void unsubscribe();
	}

	T get();

	@NonNull
	Subscription subscribe(@NonNull Observer<T> observer);

	/**
	 * Returns a new ReadOnlyObservable that is a live, transformed view of this one.
	 * <p>
	 * When this (the source) observable changes, the new observable will emit
	 * a new value by applying the mapper function to the source value.
	 *
	 * @param mapper A function to transform items from type T to type U.
	 * @param <U>    The type of the items in the new observable.
	 * @return A new, mapped ReadOnlyObservable.
	 */
	@NonNull
	default <U> ReadOnlyObservable<U> map(@NonNull Function<? super T, ? extends U> mapper) {
		return new MappedObservable<>(this, mapper);
	}

	/**
	 * An internal implementation of a ReadOnlyObservable that maps a source
	 * observable of type T to a new one of type U.
	 */
	class MappedObservable<T, U> implements ReadOnlyObservable<U> {
		private final ReadOnlyObservable<T> source;
		private final Function<? super T, ? extends U> mapper;

		private MappedObservable(
				@NonNull ReadOnlyObservable<T> source,
				@NonNull Function<? super T, ? extends U> mapper) {
			this.source = source;
			this.mapper = mapper;
		}

		/**
		 * @return The current value, transformed on the fly.
		 */
		@Override
		public U get() {
			return mapper.apply(source.get());
		}

		/**
		 * Subscribes to the mapped observable.
		 * <p>
		 * Internally, this creates a subscription to the source observable and
		 * transforms each value before passing it to the downstream observer.
		 *
		 * @param downstreamObserver The observer for the mapped values (type U).
		 * @return A subscription that, when unsubscribed, will detach from the
		 * original source observable.
		 */
		@NonNull
		@Override
		public Subscription subscribe(@NonNull Observer<U> downstreamObserver) {
			// Create a new observer that will listen to the source (type T).
			// When it receives a value, it maps it and passes it to the
			// downstream observer (type U).
			Observer<T> mappingObserver = tValue -> {
				U uValue = mapper.apply(tValue);
				downstreamObserver.update(uValue);
			};

			// Subscribe our mapping adapter to the original source.
			// The returned subscription controls this link.
			return source.subscribe(mappingObserver);
		}
	}
}