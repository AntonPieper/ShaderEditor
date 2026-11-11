package de.markusfisch.android.shadereditor.hardware;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class CameraArbiterTest {
	private final List<String> events = new ArrayList<>();
	private final CameraArbiter arbiter = new CameraArbiter();

	@Test
	public void soleClientGetsTheCameraAndGivesItBack() {
		var editor = client("editor");
		arbiter.request(editor);
		assertSame(editor, arbiter.getHolder());
		arbiter.withdraw(editor);
		assertNull(arbiter.getHolder());
		assertEquals(List.of("editor+", "editor-"), events);
	}

	@Test
	public void newestRequestTakesTheCameraFromTheHolder() {
		var editor = client("editor");
		var preview = client("preview");
		arbiter.request(editor);
		arbiter.request(preview);
		assertSame(preview, arbiter.getHolder());
		assertEquals(List.of("editor+", "editor-", "preview+"), events);
	}

	@Test
	public void displacedClientGetsTheCameraBackWhenTheNewerOneWithdraws() {
		var editor = client("editor");
		var preview = client("preview");
		arbiter.request(editor);
		arbiter.request(preview);
		events.clear();
		arbiter.withdraw(preview);
		assertSame(editor, arbiter.getHolder());
		assertEquals(List.of("preview-", "editor+"), events);
	}

	@Test
	public void waitingClientWithdrawsWithoutDisturbingTheHolder() {
		var editor = client("editor");
		var preview = client("preview");
		arbiter.request(editor);
		arbiter.request(preview);
		events.clear();
		arbiter.withdraw(editor);
		assertSame(preview, arbiter.getHolder());
		assertEquals(List.of(), events);
		arbiter.withdraw(preview);
		assertNull(arbiter.getHolder());
		assertEquals(List.of("preview-"), events);
	}

	@Test
	public void cameraPassesDownThroughEveryWaitingClient() {
		var a = client("a");
		var b = client("b");
		var c = client("c");
		arbiter.request(a);
		arbiter.request(b);
		arbiter.request(c);
		events.clear();
		arbiter.withdraw(c);
		arbiter.withdraw(b);
		assertSame(a, arbiter.getHolder());
		assertEquals(List.of("c-", "b+", "b-", "a+"), events);
	}

	@Test
	public void repeatedRequestAndUnknownWithdrawAreIgnored() {
		var editor = client("editor");
		arbiter.withdraw(editor);
		arbiter.request(editor);
		arbiter.request(editor);
		assertEquals(List.of("editor+"), events);
		arbiter.withdraw(editor);
		arbiter.withdraw(editor);
		assertEquals(List.of("editor+", "editor-"), events);
	}

	@NonNull
	private CameraArbiter.Client client(String name) {
		return new CameraArbiter.Client() {
			@Override
			public void acquire() {
				events.add(name + "+");
			}

			@Override
			public void release() {
				events.add(name + "-");
			}
		};
	}
}