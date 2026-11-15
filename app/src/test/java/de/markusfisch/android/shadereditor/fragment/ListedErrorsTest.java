package de.markusfisch.android.shadereditor.fragment;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import de.markusfisch.android.shadereditor.fragment.ListedErrors.State;

// The open list of errors shows the errors of the current build once
// they are known, and only a build without errors closes it.
public class ListedErrorsTest {
	private final ListedErrors listed = new ListedErrors();

	// A recreate: the new screen starts with nothing running, then shows
	// the file with its last build's errors, then the new build reports.
	@Test
	public void nothingRunningAtAStartIsNotABuildWithoutErrors() {
		assertEquals(State.UNKNOWN, listed.state());
		listed.unknown();
		assertEquals(State.UNKNOWN, listed.state());
		listed.kept(true, true);
		assertEquals(State.ERRORS, listed.state());
		listed.reported(true);
		assertEquals(State.ERRORS, listed.state());
	}

	// After a process death the file brings nothing; the build decides.
	@Test
	public void aFileWithoutKeptErrorsWaitsForItsBuild() {
		listed.kept(false, true);
		assertEquals(State.UNKNOWN, listed.state());
		listed.reported(true);
		assertEquals(State.ERRORS, listed.state());
	}

	@Test
	public void onlyABuildWithoutErrorsMeansNone() {
		listed.reported(true);
		listed.unknown();
		assertEquals(State.UNKNOWN, listed.state());
		listed.reported(false);
		assertEquals(State.NONE, listed.state());
	}

	// With the preview in a screen of its own nothing builds here, so
	// a file without kept errors leaves nothing to wait for.
	@Test
	public void withoutABuildToComeNothingKeptIsNone() {
		listed.kept(false, false);
		assertEquals(State.NONE, listed.state());
		listed.kept(true, false);
		assertEquals(State.ERRORS, listed.state());
	}
}
