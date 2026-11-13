package de.markusfisch.android.shadereditor.widget;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class TextActionsTest {
	private static final int CUT = android.R.id.cut;
	private static final int COPY = android.R.id.copy;
	private static final int PASTE = android.R.id.paste;
	private static final int SELECT_ALL = android.R.id.selectAll;

	@Test
	public void aSelectionOffersCutCopyPasteAndSelectAll() {
		assertArrayEquals(new int[]{CUT, COPY, PASTE, SELECT_ALL},
				TextActions.offered(2, 5, 10, true));
		assertArrayEquals(new int[]{CUT, COPY, SELECT_ALL},
				TextActions.offered(2, 5, 10, false));
	}

	@Test
	public void aCaretOffersPasteAndSelectAll() {
		assertArrayEquals(new int[]{PASTE, SELECT_ALL},
				TextActions.offered(4, 4, 10, true));
	}

	@Test
	public void selectAllIsOfferedOnlyIfItSelectsMore() {
		assertArrayEquals(new int[]{CUT, COPY, PASTE},
				TextActions.offered(0, 10, 10, true));
		assertArrayEquals(new int[]{PASTE},
				TextActions.offered(0, 0, 0, true));
	}
}
