package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.project.HostCompletion.Choice;
import de.markusfisch.android.shadereditor.project.HostCompletion.Entry;
import de.markusfisch.android.shadereditor.project.HostCompletion.Offer;

public class HostCompletionTest {
	private static final ProjectPath INK = path("fx/ink.frag");
	private static final Map<String, List<Entry>> FOLDERS = Map.of(
			"", List.of(new Entry("fx", true), new Entry("lib", true),
					new Entry("main.frag", false)),
			"fx", List.of(new Entry("textures", true),
					new Entry(".hidden.frag", false),
					new Entry("Blur.frag", false),
					new Entry("bright.frag", false),
					new Entry("common.glsl", false),
					new Entry("ink.frag", false),
					new Entry("noise.png", false),
					new Entry("notes.md", false),
					new Entry("seed.frag", false),
					new Entry("shader.json", false)),
			"lib", List.of(new Entry("color.glsl", false),
					new Entry("noise.glsl", false),
					new Entry("util.h", false),
					new Entry("tile.png", false)));

	@Test
	public void offersShadersThenPicturesThenFoldersInAnInputPath() {
		assertEquals(List.of("Blur.frag", "bright.frag", "ink.frag",
						"seed.frag", "noise.png", "textures/", "../"),
				words("#pragma se input \"|"));
	}

	@Test
	public void narrowsByWhatIsTypedIgnoringCase() {
		Offer offer = offer("#pragma se input \"b|");
		assertEquals(List.of("Blur.frag", "bright.frag"), offer.words());
		assertEquals(1, offer.typed());
	}

	@Test
	public void leavesOutTheNameTypedWholeAndClosed() {
		assertEquals(List.of(), words("#pragma se input \"seed.frag|\""));
		assertEquals(List.of("seed.frag"),
				words("#pragma se input \"seed.frag|"));
	}

	@Test
	public void ownsTheStripOnlyWhereItCompletes() {
		String input = "#pragma se input \"blur.frag\" filter=linear";
		// In the path, among the options, at the command.
		assertTrue(owns(input, 20));
		assertTrue(owns(input, input.length()));
		assertTrue(owns("#pragma se ", 11));
		assertTrue(owns("#include \"lib/", 14));
		// The language's: after an include's path, in a comment, on
		// other lines, the line after a directive, a commented line.
		assertFalse(owns("#include \"a.glsl\" ", 19));
		assertFalse(owns("#pragma se input \"a.png\" // fil", 32));
		assertFalse(owns("#pragma once", 12));
		assertFalse(owns("\tcolor = mix(", 14));
		assertFalse(owns("", 0));
		assertFalse(HostCompletion.owns(input, 20, false));
	}

	@Test
	public void choosingAFileClosesTheQuote() {
		assertEquals("#pragma se input \"Blur.frag\"|",
				choose("#pragma se input \"b|", "Blur.frag"));
		assertEquals("#pragma se input \"Blur.frag\"| filter=linear",
				choose("#pragma se input \"b|\" filter=linear", "Blur.frag"));
	}

	@Test
	public void choosingTheFileItselfReadsItsLastFrame() {
		assertEquals("#pragma se input \"ink.frag\" frame=previous|",
				choose("#pragma se input \"i|", "ink.frag"));
		assertEquals("#pragma se input \"ink.frag\"| frame=current",
				choose("#pragma se input \"i|\" frame=current", "ink.frag"));
	}

	@Test
	public void choosingAFolderStaysInThePath() {
		assertEquals("#pragma se input \"textures/|\"",
				choose("#pragma se input \"t|\"", "textures/"));
	}

	@Test
	public void goesUpOnlyBelowTheRootAndWhenNothingIsTyped() {
		assertEquals(List.of("main.frag", "fx/", "lib/"),
				HostCompletion.offer("#include \"\"", 10, path("main.frag"),
						HostCompletionTest::list, false).words());
		assertEquals(List.of(), words("#pragma se input \"../../x|"));
		assertEquals("#pragma se input \"../|",
				choose("#pragma se input \"|", "../"));
	}

	@Test
	public void offersTextForAnIncludeGlslFirst() {
		assertEquals(List.of("color.glsl", "noise.glsl", "util.h"),
				words("#include \"../lib/|"));
	}

	@Test
	public void offersShadersAndPicturesForAFirstFrame() {
		assertEquals(List.of("seed.frag"),
				words("#pragma se target scale=0.5 initial=\"s|"));
	}

	@Test
	public void offersOptionsAfterThePathByWhatItReads() {
		assertEquals(List.of("frame=", "filter=", "wrap=", "min=", "mag="),
				words("#pragma se input \"blur.frag\" |"));
		assertEquals(List.of("filter=", "wrap=", "min=", "mag=", "mipmap="),
				words("#pragma se input \"noise.png\" |"));
		assertEquals(List.of("frame=", "wrap="),
				words("#pragma se input \"blur.frag\" filter=linear |"));
		assertEquals(List.of("format=", "initial=", "present="),
				words("#pragma se target size=64x64 |"));
		assertEquals("#pragma se target initial=\"|",
				choose("#pragma se target i|", "initial="));
	}

	@Test
	public void offersTheValuesOfAnOption() {
		assertEquals(List.of("nearest", "linear"),
				words("#pragma se input \"blur.frag\" filter=|"));
		assertEquals(List.of("previous"),
				words("#pragma se input \"blur.frag\" frame=p|"));
		assertEquals(List.of("clamp", "repeat", "mirror"),
				words("#pragma se input \"a.png\" wrap=repeat,|"));
		assertEquals(List.of("1", "0.5", "0.25", "0.125"),
				words("#pragma se target scale=|"));
		assertEquals(List.of("0.5", "0.25", "0.125"),
				words("#pragma se target scale=0.|"));
		assertEquals("#pragma se input \"a.png\" filter=linear| wrap=clamp",
				choose("#pragma se input \"a.png\" filter=l| wrap=clamp",
						"linear"));
	}

	@Test
	public void offersHalfFloatOnlyWhereTheDeviceDrawsIt() {
		assertEquals(List.of("rgba8"),
				words("#pragma se target format=|"));
		assertEquals(List.of("rgba8", "rgba16f"),
				HostCompletion.offer("#pragma se target format=", 25, INK,
						HostCompletionTest::list, true).words());
	}

	@Test
	public void offersTheCommands() {
		assertEquals(List.of("input", "target"), words("#pragma se |"));
		assertEquals("#pragma se target |",
				choose("#pragma se t|", "target"));
	}

	@Test
	public void leavesOtherLinesAndCommentsToTheLanguage() {
		assertNull(offer("float x = 1.0;|"));
		assertNull(offer("#pragma once|"));
		assertNull(offer("#pragma se input \"a.png\" // fil|"));
		assertNull(offer("#define X|"));
		assertNull(offer("#pragma se|"));
	}

	@Test
	public void offersNothingWhileTheFolderIsNotListed() {
		Offer offer = HostCompletion.offer("#pragma se input \"", 18, INK,
				folder -> null, false);
		assertEquals(List.of(), offer.words());
	}

	@Test
	public void offersEveryOptionThatTheDirectiveReads() {
		assertEquals(new HashSet<>(HostDirective.INPUT_KEYS),
				new HashSet<>(HostCompletion.INPUT_OPTIONS));
		assertEquals(new HashSet<>(HostDirective.TARGET_KEYS),
				new HashSet<>(HostCompletion.TARGET_OPTIONS));
	}

	@Test
	public void findsThePathAtTheCaret() {
		HostCompletion.Path at = HostCompletion.pathAt(
				"#pragma se input \"blur.frag\" filter=linear", 20);
		assertEquals("blur.frag", at.text(
				"#pragma se input \"blur.frag\" filter=linear"));
		assertTrue(at.closed());
		assertNull(HostCompletion.pathAt(
				"#pragma se input \"blur.frag\" filter=linear", 35));
	}

	private static boolean owns(String line, int column) {
		return HostCompletion.owns(line, column, true);
	}

	@Test
	public void namesTheListedFileThePathIsAbout() {
		String line = "#include \"../lib/noise.glsl\"";
		HostCompletion.Named named = HostCompletion.named(line, 15, INK,
				HostCompletionTest::list);
		assertEquals(path("lib/noise.glsl"), named.path());
		assertEquals(HostCompletion.Kind.INCLUDE, named.kind());
		assertNull(HostCompletion.named("#include \"../lib/nois.glsl\"",
				15, INK, HostCompletionTest::list));
		assertNull(HostCompletion.named("#include \"../lib\"", 13, INK,
				HostCompletionTest::list));
		assertNull(HostCompletion.named(line, 15, INK, folder -> null));
	}

	@Test
	public void offersTheNearestNameOfAMissingFile() {
		HostCompletion.Missing missing = HostCompletion.missing(
				"#pragma se input \"blurr.frag\" filter=linear", INK,
				"File not found: fx/blurr.frag", HostCompletionTest::list);
		assertEquals(path("fx/blurr.frag"), missing.path());
		assertEquals("Blur.frag", missing.near());
		assertTrue(missing.creatable());
		assertEquals(new Choice("Blur.frag", 18, 28, "Blur.frag", 27),
				missing.use());
	}

	@Test
	public void offersANearNameOnlyWhenOneIsClearlyNearest() {
		List<Entry> entries = FOLDERS.get("fx");
		// Case alone.
		assertEquals("Blur.frag", HostCompletion.nearest("blur.frag",
				HostCompletion.Kind.INPUT, entries));
		// A swap of two letters.
		assertEquals("seed.frag", HostCompletion.nearest("sede.frag",
				HostCompletion.Kind.INPUT, entries));
		// Three letters off.
		assertNull(HostCompletion.nearest("glow.frag",
				HostCompletion.Kind.INPUT, entries));
		// Two files one letter off.
		assertNull(HostCompletion.nearest("aa.frag",
				HostCompletion.Kind.INPUT, List.of(new Entry("ab.frag", false),
						new Entry("ac.frag", false))));
		// Only a file of the same kind.
		assertNull(HostCompletion.nearest("noise.frag",
				HostCompletion.Kind.INPUT, entries));
		assertEquals("noise.png", HostCompletion.nearest("nose.png",
				HostCompletion.Kind.INPUT, entries));
	}

	@Test
	public void saysNothingOfOtherMessagesOrPaths() {
		assertNull(HostCompletion.missing("#pragma se input \"a.frag\"",
				INK, "Unknown key", HostCompletionTest::list));
		assertNull(HostCompletion.missing("#pragma se input \"a.frag\"",
				INK, "File not found: fx/b.frag", HostCompletionTest::list));
		HostCompletion.Missing picture = HostCompletion.missing(
				"#pragma se input \"../tile.png\"", INK,
				"File not found: tile.png", HostCompletionTest::list);
		assertNull(picture.near());
		assertTrue(!picture.creatable());
	}

	@Test
	public void retargetsOnlyThePathThatNamesTheFile() {
		String line = "#include \"../lib/noise.glsl\" // noise";
		HostCompletion.Choice choice = HostCompletion.retarget(line, INK,
				path("lib/noise.glsl"), path("lib/value.glsl"));
		assertEquals("#include \"../lib/value.glsl\" // noise",
				line.substring(0, choice.start()) + choice.text() +
						line.substring(choice.end()));
		assertNull(HostCompletion.retarget(line, INK, path("noise.glsl"),
				path("value.glsl")));
	}

	private static List<String> words(String marked) {
		return offer(marked).words();
	}

	private static Offer offer(String marked) {
		int caret = marked.indexOf('|');
		return HostCompletion.offer(marked.replace("|", ""), caret, INK,
				HostCompletionTest::list, false);
	}

	// The line with the choice applied, the caret marked.
	private static String choose(String marked, String word) {
		String line = marked.replace("|", "");
		Choice choice = offer(marked).choice(word);
		String chosen = line.substring(0, choice.start()) + choice.text() +
				line.substring(choice.end());
		return chosen.substring(0, choice.caret()) + "|" +
				chosen.substring(choice.caret());
	}

	private static List<Entry> list(ProjectPath folder) {
		return FOLDERS.get(folder == null ? "" : folder.toString());
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}
