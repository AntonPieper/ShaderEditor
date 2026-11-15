package de.markusfisch.android.shadereditor.fragment;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

// The limit of the picture follows what this tells, so every way code
// comes to be shown or not over the picture must be told, the start
// included.
public class CodeShownTest {
	private final List<Boolean> told = new ArrayList<>();
	private final CodeShown code = new CodeShown();

	// A start covers the code until its file is shown, as a restore does;
	// showing the file must show the code over the picture.
	@Test
	public void aStartThatUncoversTellsTheCodeIsShown() {
		code.listen(told::add);
		code.setCovered(true);
		code.setCovered(false);
		assertEquals(List.of(true, false, true), told);
	}

	// The listener of a screen made later learns the fact at once.
	@Test
	public void aListenerIsToldTheFactAsItIs() {
		code.setCovered(true);
		code.listen(told::add);
		assertEquals(List.of(false), told);
	}

	@Test
	public void theCodeButtonHidesAndShowsTheCode() {
		code.listen(told::add);
		code.setHidden(true);
		code.setHidden(false);
		assertEquals(List.of(true, false, true), told);
	}

	// Code hidden with the button stays hidden when the cover goes, and
	// nothing is told twice.
	@Test
	public void codeHiddenUnderTheCoverStaysHidden() {
		code.listen(told::add);
		code.setHidden(true);
		code.setCovered(true);
		code.setCovered(false);
		assertEquals(List.of(true, false), told);
		code.setHidden(false);
		assertEquals(List.of(true, false, true), told);
	}

	// The passes sheet sets the code aside so that the pictures are not
	// limited, and gives it back as it was.
	@Test
	public void theSheetSetsTheCodeAside() {
		code.listen(told::add);
		code.setAside(true);
		code.setAside(false);
		assertEquals(List.of(true, false, true), told);
	}
}
