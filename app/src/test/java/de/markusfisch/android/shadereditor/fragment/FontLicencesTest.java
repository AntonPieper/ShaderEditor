package de.markusfisch.android.shadereditor.fragment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FontLicencesTest {
	@Test
	public void everyBundledFontHasItsLicenceText() throws Exception {
		String xml = Files.readString(new File(
				"src/main/res/values/preference_font.xml").toPath(),
				StandardCharsets.UTF_8);
		String values = xml.substring(xml.indexOf("<array name=\"font_values\""));
		Matcher item = Pattern.compile("<item>(\\w+)</item>").matcher(values);
		int bundled = 0;
		while (item.find()) {
			String font = item.group(1);
			// The system font is not part of the app
			if (font.equals("monospace")) {
				continue;
			}
			File licence = new File("../licenses/fonts/" + font + ".txt");
			assertTrue(licence + " is missing", licence.isFile());
			String text = Files.readString(licence.toPath(),
					StandardCharsets.UTF_8);
			assertTrue(font + " has no copyright line",
					text.contains("Copyright") || text.contains("©"));
			assertTrue(font + " has no licence text",
					text.contains("SIL OPEN FONT LICENSE"));
			++bundled;
		}
		assertTrue(bundled > 0);
	}

	@Test
	public void reflowJoinsWrappedLinesAndKeepsStructure() {
		assertEquals("Copyright 2020 A\n\n---\nSIL OPEN FONT LICENSE\n---\n\n" +
						"PREAMBLE\nThe goals are to stimulate worldwide development.\n\n" +
						"1) Neither the Font Software nor any of its components.",
				FontLicencesSheet.reflow("Copyright 2020 A\r\n\r\n\r\n" +
						"---\r\nSIL OPEN FONT LICENSE\r\n---\r\n\r\n" +
						"PREAMBLE\r\nThe goals are to stimulate\r\n" +
						"worldwide development.\r\n\r\n" +
						"1) Neither the Font Software\r\n" +
						"nor any of its components.\r\n"));
	}

	@Test
	public void everyLicenceReflowsCleanly() throws Exception {
		File[] files = new File("../licenses/fonts").listFiles();
		assertTrue(files != null && files.length > 0);
		for (File file : files) {
			String text = FontLicencesSheet.reflow(Files.readString(
					file.toPath(), StandardCharsets.UTF_8));
			assertFalse(file + " has CR", text.contains("\r"));
			assertFalse(file + " has blank runs", text.contains("\n\n\n"));
			assertTrue(file + " lost a heading", text.contains(
					"\n\nPREAMBLE\nThe goals of the Open Font License"));
			assertTrue(file + " lost the disclaimer", text.contains(
					"\n\nDISCLAIMER\nTHE FONT SOFTWARE IS PROVIDED"));
		}
	}
}
