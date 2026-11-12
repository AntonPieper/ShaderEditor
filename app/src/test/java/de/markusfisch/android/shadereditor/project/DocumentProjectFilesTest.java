package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.provider.DocumentsContract;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.project.ProjectFiles.Change;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Content;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Done;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Entries;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Entry;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Failed;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Offers;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Refusal;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Refused;

public class DocumentProjectFilesTest {
	private static final Failed OPEN_FOLDER =
			new Failed(DocumentProjectFiles.OPEN_FOLDER);

	private final FakeDocuments documents = new FakeDocuments();
	private final FakeDocuments.Node shader = documents.put("dir/wave.glsl",
			"void main() {}");
	private DocumentProjectFiles files = open(true);

	@Test
	public void pathsComeFromTheName() {
		assertEquals("main.glsl", named("main.glsl"));
		assertEquals("caf\u00e9.glsl", named("cafe\u0301.glsl"));
		assertEquals("a_b_c_d_.glsl", named("a/b\\c\"d\u0001.glsl"));
		assertEquals("_..", named(".."));
		assertEquals("_.", named("."));
		assertEquals("_.git", named(".git"));
		assertEquals("_c:x.glsl", named("c:x.glsl"));
		assertEquals("_https:x", named("https:x"));
		assertEquals("shader.glsl", named(""));
		assertEquals("shader.glsl", named(null));
		assertEquals("notes.txt", named("notes.txt"));
	}

	@Test
	public void readsTheDocumentAndNothingBesideIt() {
		ProjectPath wave = files.getPath();
		assertEquals("wave.glsl", wave.toString());
		assertEquals("void main() {}", text(((Content) files.read(wave))
				.bytes()));
		assertEquals(OPEN_FOLDER, files.read(path("common.glsl")));
		assertEquals(OPEN_FOLDER, files.read(path("dir/wave.glsl")));
		assertNull(files.read(ProjectManifest.FILE));
		assertEquals(new Entries(List.of(new Entry("wave.glsl", false))),
				files.list(null));
		assertEquals(OPEN_FOLDER, files.list(path("dir")));
		for (String call : documents.calls) {
			assertTrue(call, call.endsWith(" " + shader.id));
		}
	}

	@Test
	public void runsAloneAndAsksForTheFolderForAnInclude() {
		Preparation alone = Preparation.prepare(files, files.getPath(),
				ProjectLimits.DEFAULT, BuiltinNames.ALL);
		assertNotNull(alone.diagnostics().toString(), alone.plan());

		shader.bytes = bytes("#include \"common.glsl\"\nvoid main() {}");
		Preparation include = Preparation.prepare(files, files.getPath(),
				ProjectLimits.DEFAULT, BuiltinNames.ALL);
		assertNull(include.plan());
		List<String> messages = new ArrayList<>();
		for (ProjectDiagnostic d : include.diagnostics()) {
			messages.add(d.message());
		}
		assertEquals(List.of("Could not read common.glsl: " +
				DocumentProjectFiles.OPEN_FOLDER), messages);
	}

	@Test
	public void writesTheDocumentAtItsRevision() {
		ProjectPath wave = files.getPath();
		String seen = ((Content) files.read(wave)).revision();
		Change change = files.write(wave, bytes("new"), seen);
		assertEquals(new Done(((Content) files.read(wave)).revision()),
				change);
		assertEquals("new", documents.text(shader));
		assertRefused(Refusal.CONFLICT, files.write(wave, bytes("x"), seen));
		assertRefused(Refusal.CONFLICT, files.write(wave, bytes("x"), null));
		assertEquals("new", documents.text(shader));
	}

	@Test
	public void changesBesideTheDocumentAreUnsupported() {
		int count = documents.nodes.size();
		assertRefused(Refusal.UNSUPPORTED, files.write(path("common.glsl"),
				bytes("x"), null));
		assertRefused(Refusal.UNSUPPORTED, files.createFolder(path("lib")));
		assertRefused(Refusal.UNSUPPORTED, files.rename(files.getPath(),
				path("other.glsl")));
		assertRefused(Refusal.UNSUPPORTED, files.delete(files.getPath()));
		assertEquals(count, documents.nodes.size());
		assertEquals("wave.glsl", shader.name);
	}

	@Test
	public void readOnlyDocumentOrGrantIsNotWritten() {
		assertEquals(new Offers(true, false, false, false, false),
				files.offers(files.getPath()));
		assertEquals(Offers.NONE, files.offers(null));
		String seen = ((Content) files.read(files.getPath())).revision();
		shader.flags = DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
		assertRefused(Refusal.UNSUPPORTED, files.write(files.getPath(),
				bytes("x"), seen));
		assertEquals(Offers.NONE.write(), files.offers(files.getPath()).write());

		shader.flags = FakeDocuments.FILE_FLAGS;
		files = open(false);
		assertRefused(Refusal.UNSUPPORTED, files.write(files.getPath(),
				bytes("x"), seen));
		assertEquals(Offers.NONE, files.offers(files.getPath()));
		assertEquals("void main() {}", documents.text(shader));
	}

	@Test
	public void lostPermissionFails() {
		documents.failure = new SecurityException("revoked");
		assertEquals(new Failed(DocumentProjectFiles.LOST_FILE),
				files.read(files.getPath()));
		assertEquals(new Refused(Refusal.FAILED,
				DocumentProjectFiles.LOST_FILE), files.write(files.getPath(),
				bytes("x"), null));
		assertEquals(Offers.NONE, files.offers(files.getPath()));
	}

	@Test
	public void vanishedDocumentFails() {
		documents.nodes.remove(shader.id);
		assertEquals(new Failed("it is no longer available"),
				files.read(files.getPath()));
	}

	private DocumentProjectFiles open(boolean writable) {
		return new DocumentProjectFiles(documents, shader.id, shader.name,
				writable);
	}

	private static String named(String name) {
		return DocumentProjectFiles.path(name).toString();
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}

	private static void assertRefused(Refusal refusal, Change change) {
		assertTrue(String.valueOf(change), change instanceof Refused);
		assertEquals(String.valueOf(change), refusal,
				((Refused) change).refusal());
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	private static String text(byte[] bytes) {
		return new String(bytes, StandardCharsets.UTF_8);
	}
}
