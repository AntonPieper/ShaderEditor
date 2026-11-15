package de.markusfisch.android.shadereditor.widget;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public class SignatureViewTest {
	private static final SignatureView.Signature SMOOTHSTEP =
			new SignatureView.Signature("smoothstep",
					List.of("edge0", "edge1", "x"), 1);

	@Test
	public void writesTheCallAndFindsTheCurrentParameter() {
		assertEquals("smoothstep(edge0, edge1, x)", SMOOTHSTEP.text());
		assertEquals("smoothstep(edge0, ".length(), SMOOTHSTEP.start());
		assertEquals("smoothstep(".length(), new SignatureView.Signature(
				"smoothstep", List.of("edge0", "edge1", "x"), 0).start());
		// More arguments than parameters: none is current.
		assertEquals(-1, new SignatureView.Signature("smoothstep",
				List.of("edge0", "edge1", "x"), 3).start());
	}

	// What does not fit is cut from the left, so the end stays.
	@Test
	public void cutsFromTheLeft() {
		assertEquals("smoothstep(edge0, edge1, x) 18 23", fit(SMOOTHSTEP, 27));
		assertEquals("\u2026oothstep(edge0, edge1, x) 17 22",
				fit(SMOOTHSTEP, 26));
		assertEquals("\u2026edge1, x) 1 6", fit(SMOOTHSTEP, 10));
		// Fewer than six characters are not worth showing.
		assertEquals("null", fit(SMOOTHSTEP, 5));
	}

	// The current parameter stays where the cut would take it away:
	// then what follows it is cut, and the end still stands.
	@Test
	public void keepsTheCurrentParameter() {
		SignatureView.Signature disc = new SignatureView.Signature("disc",
				List.of("p", "center", "radius", "soft"), 1);
		assertEquals("disc(p, center, radius, soft)", disc.text());
		assertEquals("\u2026center\u2026) 1 7", fit(disc, 10));
		assertEquals("\u2026cent\u2026) 1 5", fit(disc, 7));
		assertEquals("\u2026center, radius, soft) 1 7", fit(disc, 22));
		// The last parameter is the end of the call.
		assertEquals("\u2026us, soft) 5 9", fit(new SignatureView.Signature(
				"disc", List.of("p", "center", "radius", "soft"), 3), 10));
		// Past the last parameter none is current.
		assertEquals("\u2026us, soft) 0 0", fit(new SignatureView.Signature(
				"disc", List.of("p", "center", "radius", "soft"), 4), 10));
	}

	private static String fit(SignatureView.Signature signature, int room) {
		SignatureView.Shown shown = SignatureView.fit(signature, room);
		return shown == null ? "null"
				: shown.text() + " " + shown.from() + " " + shown.to();
	}
}
