package de.markusfisch.android.shadereditor.navigation;

/**
 * Lists one level and acts on its rows. The drawer knows what it shows
 * only through this; it never asks what kind of storage is behind it.
 */
public interface Source {
	/**
	 * What is known of the level now. Called on the main thread, so it
	 * must not wait for storage; a source that learns more asks the
	 * drawer to list it again.
	 */
	Listing list(Order order);

	/** Opens the row's file in the editor and, when asked, runs it. */
	void open(Row row, boolean run);

	/** Does one of the actions the row offers. */
	void act(Row row, Row.Action action);
}
