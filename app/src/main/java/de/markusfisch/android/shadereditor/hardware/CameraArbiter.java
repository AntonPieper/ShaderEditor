package de.markusfisch.android.shadereditor.hardware;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;

/**
 * Gives the camera to one client at a time. The most recent request wins;
 * the client it displaces waits and gets the camera back when every newer
 * client has withdrawn. CameraX binds one camera per lifecycle owner and
 * many devices cannot stream two at once, so two renderers never share.
 * Main thread only.
 */
final class CameraArbiter {
	interface Client {
		void acquire();

		void release();
	}

	private final ArrayList<Client> clients = new ArrayList<>();

	void request(@NonNull Client client) {
		if (clients.contains(client)) {
			return;
		}
		Client holder = getHolder();
		if (holder != null) {
			holder.release();
		}
		clients.add(client);
		client.acquire();
	}

	void withdraw(@NonNull Client client) {
		boolean held = client == getHolder();
		if (!clients.remove(client) || !held) {
			return;
		}
		client.release();
		Client next = getHolder();
		if (next != null) {
			next.acquire();
		}
	}

	@Nullable
	Client getHolder() {
		return clients.isEmpty() ? null : clients.get(clients.size() - 1);
	}
}