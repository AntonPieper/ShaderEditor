package de.markusfisch.android.shadereditor.navigation;

/**
 * What the toolbar's second line says of a file of a folder in the
 * editor: the folder it is in, and before that the file that runs when
 * that is another one.
 */
public final class FileLine {
	private FileLine() {
	}

	/**
	 * @param folder  the name of the folder opened, or what stands for a
	 *                single file
	 * @param shown   the path of the file in the editor from the root of
	 *                that folder
	 * @param running the path of the file that runs, or null
	 */
	public static String of(String folder, String shown, String running) {
		int slash = shown.lastIndexOf('/');
		String in = slash < 0
				? folder
				: folder + " / " + shown.substring(0, slash)
						.replace("/", " / ");
		return running == null || running.equals(shown)
				? in
				: "▶ " + running + " · " + in;
	}
}
