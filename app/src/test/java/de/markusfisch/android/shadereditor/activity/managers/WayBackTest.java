package de.markusfisch.android.shadereditor.activity.managers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.ProjectPath;

public class WayBackTest {
	private static final ProjectPath MAIN = ProjectPath.parse("main.frag")
			.value();
	private final Object screen = new Object();

	@Test
	public void goesBackOnePlaceAtATime() {
		Document document = new Document(Text.of("a\nb\nc\n"));
		WayBack way = new WayBack();
		way.push(screen, MAIN, document, 0, null);
		way.push(screen, MAIN, document, 4, null);
		assertEquals(4, way.top(screen).offset);
		way.pop();
		assertEquals(0, way.top(screen).offset);
		way.pop();
		assertNull(way.top(screen));
	}

	@Test
	public void aPlaceMovesWithEditsAboveAndGoesWithItsText() {
		Document document = new Document(Text.of("one\ntwo\nthree\n"));
		WayBack way = new WayBack();
		way.push(screen, MAIN, document, 5, null);
		document.replace(0, 0, Text.of("zero\n"));
		assertEquals(10, way.top(screen).offset);
		// Text after it changes nothing; text around it drops it.
		document.replace(14, 14, Text.of("!"));
		assertEquals(10, way.top(screen).offset);
		document.replace(9, 12, Text.of(""));
		assertNull(way.top(screen));
	}

	@Test
	public void keepsTheLastTwentyAndForgetsWhatAnotherScreenLeft() {
		Document document = new Document(Text.of("x"));
		WayBack way = new WayBack();
		Runnable uses = () -> {
		};
		for (int i = 0; i < WayBack.MOST + 5; ++i) {
			way.push(screen, MAIN, document, 0, uses);
		}
		int count = 0;
		for (; way.top(screen) != null; way.pop()) {
			++count;
		}
		assertEquals(WayBack.MOST, count);
		way.push(screen, MAIN, document, 0, uses);
		assertSame(uses, way.top(screen).then);
		assertNull(way.top(new Object()).then);
	}

	@Test
	public void aReloadDropsThePlacesOfItsDocument() {
		Document document = new Document(Text.of("a\nb\n"));
		WayBack way = new WayBack();
		way.push(screen, MAIN, document, 0, null);
		document.replace(0, document.text().length(), Text.of("a\nb\nc\n"));
		assertNull(way.top(screen));
	}

	@Test
	public void aPlaceDroppedByAnEditStopsListening() {
		Document document = new Document(Text.of("one\ntwo\nthree\n"));
		WayBack way = new WayBack();
		way.push(screen, MAIN, document, 5, null);
		document.replace(4, 8, Text.of(""));
		assertNull(way.top(screen));
		assertEquals(0, way.listened());
	}
}
