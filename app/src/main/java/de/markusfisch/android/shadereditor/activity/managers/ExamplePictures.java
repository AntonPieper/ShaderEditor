package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.adapter.Samples;
import de.markusfisch.android.shadereditor.project.PictureStore;
import de.markusfisch.android.shadereditor.project.Previews;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.Projects;

/**
 * The pictures that come with the examples, put into the examples'
 * store of pictures by path, as any folder keeps the pictures of its
 * shaders, marked as bundled: only where it has none yet, so a picture
 * taken when an example ran on this phone stays, and a run replaces a
 * bundled one. The drawer then shows the examples
 * as any folder, and knows nothing of them.
 */
final class ExamplePictures {
	private static final Executor READING =
			Executors.newSingleThreadExecutor();

	private ExamplePictures() {
	}

	/** Fill the store; kept is called on the main thread after. */
	static void fill(@NonNull Context context, @NonNull Projects projects,
			@NonNull Runnable kept) {
		Context app = context.getApplicationContext();
		Handler main = new Handler(Looper.getMainLooper());
		projects.examples(app.getString(R.string.load_sample), key -> {
			if (key == null) {
				return;
			}
			READING.execute(() -> {
				Previews previews = projects.previews(key);
				if (previews == null) {
					return;
				}
				Map<ProjectPath, byte[]> missing = new LinkedHashMap<>();
				for (Samples.Sample sample : Samples.ALL) {
					ProjectPath path = ProjectPath.parse(sample.path())
							.value();
					Previews.Picture stored = path != null
							? previews.get(path)
							: null;
					if (path == null || (stored != null && PictureStore
							.BUNDLED.equals(stored.revision()))) {
						continue;
					}
					byte[] picture = read(app, sample.thumbId());
					// A capture stays; the bundled picture, kept before it
					// was marked so, is marked now.
					if (picture != null && (stored == null ||
							Arrays.equals(stored.png(), picture))) {
						missing.put(path, picture);
					}
				}
				// Once the last is kept, the drawer reads them.
				int[] left = {missing.size()};
				for (Map.Entry<ProjectPath, byte[]> picture :
						missing.entrySet()) {
					// Marked as bundled: a capture of the example as it
					// runs here replaces it.
					projects.setPreview(key, picture.getKey(),
							PictureStore.BUNDLED, picture.getValue(),
							problem -> {
								if (--left[0] == 0) {
									main.post(kept);
								}
							});
				}
			});
		});
	}

	private static byte[] read(@NonNull Context context, int id) {
		try (InputStream in = context.getResources().openRawResource(id)) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			for (int n; (n = in.read(buffer)) > 0; ) {
				out.write(buffer, 0, n);
			}
			return out.toByteArray();
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}
}
