package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

// Thumbnails asked for on any thread, each of the revision offered last.
// The GL thread answers a request from a complete frame of that revision,
// or with nothing once that revision can no longer draw.
final class ThumbnailRequests<C> {
	interface Revisions {
		boolean canDraw(int revision);
	}

	private record Request<C>(int revision, @NonNull C callback) {
	}

	// Guarded by this.
	private final ArrayList<Request<C>> requests = new ArrayList<>();
	// GL thread only. The revision of the last complete frame, or 0.
	private int framed;

	// Any thread, with the number of the revision offered last.
	synchronized void add(int revision, @NonNull C callback) {
		requests.add(new Request<>(revision, callback));
	}

	// GL thread, after a frame of this revision was drawn completely.
	void onFrame(int revision) {
		framed = revision;
	}

	// GL thread, when the frames drawn so far are gone: another shader
	// or render size, or a lost context.
	void onFramesLost() {
		framed = 0;
	}

	// GL thread. Removes and returns the requests the last complete frame
	// answers.
	@NonNull
	synchronized List<C> take() {
		var answered = new ArrayList<C>();
		if (framed == 0) {
			return answered;
		}
		for (var it = requests.iterator(); it.hasNext(); ) {
			var request = it.next();
			if (request.revision() == framed) {
				answered.add(request.callback());
				it.remove();
			}
		}
		return answered;
	}

	// GL thread. Removes and returns the requests of revisions that can
	// no longer draw.
	@NonNull
	synchronized List<C> drop(@NonNull Revisions revisions) {
		var dropped = new ArrayList<C>();
		for (var it = requests.iterator(); it.hasNext(); ) {
			var request = it.next();
			if (!revisions.canDraw(request.revision())) {
				dropped.add(request.callback());
				it.remove();
			}
		}
		return dropped;
	}

	// Removes and returns every request, when nothing will draw anymore.
	@NonNull
	synchronized List<C> takeAll() {
		var all = new ArrayList<C>();
		for (var request : requests) {
			all.add(request.callback());
		}
		requests.clear();
		return all;
	}
}
