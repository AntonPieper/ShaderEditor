package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

// Thumbnails asked for on any thread. Each is answered on the GL thread
// from a complete frame of the shader that was set when it was asked
// for, or of a newer one.
final class ThumbnailRequests<C> {
	private record Request<C>(int shader, @NonNull C callback) {
	}

	// Guarded by this.
	private final ArrayList<Request<C>> requests = new ArrayList<>();
	// GL thread only.
	private boolean framed;

	// Any thread, with the number of the shader set last.
	synchronized void add(int shader, @NonNull C callback) {
		requests.add(new Request<>(shader, callback));
	}

	// GL thread, after a frame was drawn completely.
	void onFrame() {
		framed = true;
	}

	// GL thread, when the frames drawn so far are gone: another shader
	// or render size, or a lost context.
	void onFramesLost() {
		framed = false;
	}

	// GL thread. Removes and returns the requests the last complete frame
	// answers, given the number of the newest shader it shows.
	@NonNull
	synchronized List<C> take(int shown) {
		var answered = new ArrayList<C>();
		if (!framed) {
			return answered;
		}
		for (var it = requests.iterator(); it.hasNext(); ) {
			var request = it.next();
			if (request.shader() <= shown) {
				answered.add(request.callback());
				it.remove();
			}
		}
		return answered;
	}
}
