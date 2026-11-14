package de.markusfisch.android.shadereditor.opengl;

// How bright the picture may be behind code. With a limit, each pixel is
// clamped to what a surface shows, 0 to 1, and divided by one plus the
// strength times its brightest channel: a dark pixel stays almost as it
// is, white and anything brighter land on the ceiling, and the hue is
// kept. Without one, a pixel is left as it is.
public final class PictureLimit {
	// The brightest a pixel may be, of 255.
	public static final int DIM = 0x1a;
	public static final int BALANCED = 0x33;
	public static final int BRIGHT = 0x4d;
	// The picture as it is.
	public static final int NONE = 0xff;

	static final long FADE_NANOS = 150000000L;

	private PictureLimit() {
	}

	// The presentation shader's uniform for a ceiling of 1.
	static float strength(float ceiling) {
		return (1f - ceiling) / ceiling;
	}

	// One channel as the presentation shader shows it.
	static float apply(float channel, float brightest, float strength) {
		if (strength <= 0f) {
			return channel;
		}
		return clamp(channel) / (1f + strength * clamp(brightest));
	}

	private static float clamp(float channel) {
		return Math.min(1f, Math.max(0f, channel));
	}
}
