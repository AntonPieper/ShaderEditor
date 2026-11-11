package de.markusfisch.android.shadereditor.project.legacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import de.markusfisch.android.shadereditor.project.HostDirective;
import de.markusfisch.android.shadereditor.project.HostDirective.Filter;
import de.markusfisch.android.shadereditor.project.HostDirective.Mipmap;
import de.markusfisch.android.shadereditor.project.HostDirective.Wrap;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.ProjectParseResult;
import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;
import de.markusfisch.android.shadereditor.project.SourceLocation;

public class LegacySamplerSettingsTest {
	private static final LegacySamplerSettings TEXTURE =
			LegacySamplerSettings.TEXTURE;

	@Test
	public void untouchedTextureWritesTheOldDefault() {
		assertEquals("min=nearest mag=linear wrap=repeat",
				texture(""));
		assertEquals("min=nearest mag=linear wrap=repeat",
				texture("// a plain comment"));
	}

	@Test
	public void everyMinificationShortcut() {
		assertEquals("min=nearest mag=linear wrap=repeat",
				texture("///min:n;"));
		assertEquals("wrap=repeat", texture("///min:l;"));
		assertEquals("min=nearest mag=linear mipmap=nearest wrap=repeat",
				texture("///min:nn;"));
		assertEquals("mipmap=nearest wrap=repeat", texture("///min:ln;"));
		assertEquals("min=nearest mag=linear mipmap=linear wrap=repeat",
				texture("///min:nl;"));
		assertEquals("mipmap=linear wrap=repeat", texture("///min:ll;"));
	}

	@Test
	public void magnificationAgreeingWithMinificationIsOneFilter() {
		assertEquals("filter=nearest wrap=repeat",
				texture("///min:n;mag:n;"));
		assertEquals("min=linear mag=nearest wrap=repeat",
				texture("///min:l;mag:n;"));
		assertEquals("filter=nearest mipmap=linear wrap=repeat",
				texture("///min:nl;mag:n;"));
	}

	@Test
	public void wrapIsOneValueWhenBothAxesAgree() {
		assertEquals("", texture("///min:l;mag:l;s:c;t:c;"));
		assertEquals("wrap=mirror", texture("///min:l;s:m;t:m;"));
		assertEquals("wrap=mirror,repeat", texture("///min:l;s:m;t:r;"));
		assertEquals("wrap=clamp,mirror", texture("///min:l;s:c;t:m;"));
		assertEquals("min=nearest mag=linear",
				TEXTURE.parse("///s:c;t:c;", false)
						.options(Sampling.IMAGE, false));
	}

	@Test
	public void everyCombinationSamplesAsTheOldRendererDid() {
		String[] mins = {"n", "l", "nn", "ln", "nl", "ll"};
		String[] mags = {"n", "l"};
		String[] wraps = {"c", "r", "m"};
		for (String min : mins) {
			for (String mag : mags) {
				for (String s : wraps) {
					for (String t : wraps) {
						String comment = "///min:" + min + ";mag:" + mag +
								";s:" + s + ";t:" + t + ";";
						Sampling expected = new Sampling(
								min.startsWith("l") ? Filter.LINEAR
										: Filter.NEAREST,
								mag.equals("l") ? Filter.LINEAR
										: Filter.NEAREST,
								min.length() == 1 ? Mipmap.NONE
										: min.endsWith("n") ? Mipmap.NEAREST
										: Mipmap.LINEAR,
								wrap(s), wrap(t));
						assertEquals(comment, expected,
								sampling(TEXTURE.parse(comment, false)
										.options(Sampling.IMAGE, true)));
					}
				}
			}
		}
	}

	@Test
	public void readsTheOldQuirks() {
		assertEquals(TEXTURE.parse("///min:l;", false),
				TEXTURE.parse("  ///foo:l;", false));
		assertEquals(TEXTURE.parse("///min:nl;", false),
				TEXTURE.parse("///min:x;", false));
		assertEquals(TEXTURE.parse("///min:nl;", false),
				TEXTURE.parse("///p:noise;", false));
		assertEquals(TEXTURE.parse("///mag:l;s:r;", false),
				TEXTURE.parse("///mag:x;s:x;", false));
		assertEquals(TEXTURE, TEXTURE.parse("///min:l:n;mag;min;", false));
		assertEquals(TEXTURE, TEXTURE.parse("// ///min:l;", false));
		assertEquals(TEXTURE, TEXTURE.parse(null, false));
		assertEquals(Mipmap.LINEAR,
				TEXTURE.parse("///min: l;", false).mipmap());
	}

	@Test
	public void backbufferReadsAPreset() {
		LegacySamplerSettings settings = LegacySamplerSettings.BACKBUFFER
				.parse("///p:noise;mag:l;", true);
		assertEquals("noise", settings.preset());
		assertEquals("min=nearest mag=linear",
				settings.options(Sampling.PASS, true));
		assertEquals("", LegacySamplerSettings.BACKBUFFER.options(
				Sampling.PASS, true));
		assertNull(LegacySamplerSettings.BACKBUFFER.parse("///min:l;", true)
				.preset());
	}

	@Test
	public void backbufferMipmapBecomesItsBaseFilter() {
		assertEquals("filter=linear wrap=repeat",
				LegacySamplerSettings.BACKBUFFER
						.parse("///min:ll;mag:l;s:r;t:r;", true)
						.withoutMipmap()
						.options(Sampling.PASS, true));
	}

	@Test
	public void cameraTakesNoMipmapAndAlwaysClamps() {
		assertEquals("", TEXTURE.forCamera().options(Sampling.CAMERA, true));
		assertEquals("filter=linear", TEXTURE.parse("///min:nn;s:m;", false)
				.forCamera().options(Sampling.CAMERA, true));
		assertEquals("filter=nearest", TEXTURE.parse("///mag:n;", false)
				.forCamera().options(Sampling.CAMERA, true));
	}

	@Test
	public void dropsOnlyCommentsThatHoldNothingButSettings() {
		assertTrue(LegacySamplerSettings.isSettingsOnly(
				" ///min:l;mag:n;s:c;t:m;", false));
		assertTrue(LegacySamplerSettings.isSettingsOnly("///p:noise", true));
		assertFalse(LegacySamplerSettings.isSettingsOnly("///p:noise", false));
		assertFalse(LegacySamplerSettings.isSettingsOnly(
				"/// note: wrap it", false));
		assertFalse(LegacySamplerSettings.isSettingsOnly(
				"///min:l; why", false));
		assertFalse(LegacySamplerSettings.isSettingsOnly("// min:l;", false));
		assertFalse(LegacySamplerSettings.isSettingsOnly("", false));
	}

	private static String texture(String params) {
		return TEXTURE.parse(params, false).options(Sampling.IMAGE, true);
	}

	private static Sampling sampling(String options) {
		HostDirective directive = HostDirective.parse(
				" se input \"t.png\" " + options,
				new SourceLocation(path("main.glsl"), 1, null));
		assertTrue(options, directive.diagnostics().isEmpty());
		HostDirective.Input input = directive.input();
		ProjectParseResult<Sampling> sampling = Sampling.of(input);
		assertTrue(sampling.isSuccess());
		return sampling.value();
	}

	private static Wrap wrap(String shortcut) {
		return switch (shortcut) {
			case "c" -> Wrap.CLAMP;
			case "m" -> Wrap.MIRROR;
			default -> Wrap.REPEAT;
		};
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}
