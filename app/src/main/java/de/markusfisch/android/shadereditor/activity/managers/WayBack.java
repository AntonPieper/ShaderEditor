package de.markusfisch.android.shadereditor.activity.managers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.ProjectPath;

/**
 * The way back: the places the editor jumped from, the last on top, at
 * most {@link #MOST}. A place keeps its offset in its document as the
 * text changes, as line marks do, and is dropped when the text around it
 * is removed: a place that cannot be told apart is better missing than
 * wrong. What follows going back to a place, Uses showing again, belongs
 * to the screen that put it there; another screen, made after a
 * configuration change, goes back to the place alone. Call it on the
 * main thread.
 */
final class WayBack {
	static final int MOST = 20;

	/** Where a jump left from. */
	static final class Place {
		@NonNull
		ProjectPath path;
		@NonNull
		final Document document;
		int offset;
		@Nullable
		Runnable then;

		private Place(@NonNull ProjectPath path, @NonNull Document document,
				int offset, @Nullable Runnable then) {
			this.path = path;
			this.document = document;
			this.offset = offset;
			this.then = then;
		}
	}

	private final ArrayDeque<Place> places = new ArrayDeque<>();
	private final Map<Document, Document.Listener> listening =
			new IdentityHashMap<>();
	// The screen whose then the places hold.
	@Nullable
	private Object owner;

	/** Put the place offset of document, the file at path, on top. */
	void push(@NonNull Object owner, @NonNull ProjectPath path,
			@NonNull Document document, int offset, @Nullable Runnable then) {
		adopt(owner);
		if (places.size() == MOST) {
			places.removeFirst();
			stopListening();
		}
		places.addLast(new Place(path, document, offset, then));
		if (!listening.containsKey(document)) {
			Document.Listener listener = new Document.Listener() {
				@Override
				public void onChange(Document.Change change, Text before) {
					moved(document, change);
				}
			};
			listening.put(document, listener);
			document.addListener(listener);
		}
	}

	/** Return the place on top for owner, or null. */
	@Nullable
	Place top(@NonNull Object owner) {
		adopt(owner);
		return places.peekLast();
	}

	/** Take the place on top away. */
	void pop() {
		places.pollLast();
		stopListening();
	}

	private void adopt(@NonNull Object owner) {
		if (this.owner != owner) {
			this.owner = owner;
			for (Place place : places) {
				place.then = null;
			}
		}
	}

	private void moved(@NonNull Document document,
			@NonNull Document.Change change) {
		int from = change.start();
		int to = from + change.removed().length();
		for (Iterator<Place> it = places.iterator(); it.hasNext(); ) {
			Place place = it.next();
			if (place.document != document) {
				continue;
			}
			if (from < place.offset && place.offset < to) {
				it.remove();
			} else {
				place.offset = change.map(place.offset);
			}
		}
	}

	// Stops listening to documents no place is in any more.
	private void stopListening() {
		for (Iterator<Map.Entry<Document, Document.Listener>> it =
				listening.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Document, Document.Listener> entry = it.next();
			boolean used = false;
			for (Place place : places) {
				used |= place.document == entry.getKey();
			}
			if (!used) {
				entry.getKey().removeListener(entry.getValue());
				it.remove();
			}
		}
	}
}
