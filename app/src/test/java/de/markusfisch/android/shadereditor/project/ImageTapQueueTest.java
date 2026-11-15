package de.markusfisch.android.shadereditor.project;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.*;
import de.markusfisch.android.shadereditor.editor.Text;
import static org.junit.Assert.*;
/**
 * A tap on an image asks whether the file is a picture; that read must
 * not hold up a save of another project on the shared storage queue
 * (from an independent review's probe).
 */
public class ImageTapQueueTest {
	@Rule public TemporaryFolder temp = new TemporaryFolder();
	private static ProjectPath path(String s) { return ProjectPath.parse(s).value(); }
	@Test public void imageTapMustNotBlockAnotherProjectsSave() throws Exception {
		ExecutorService queue = Executors.newSingleThreadExecutor();
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), saved = new CountDownLatch(1);
		try {
			ProjectSessions sessions = new ProjectSessions(temp.newFolder());
			File root = temp.newFolder();
			Files.writeString(new File(root,"main.frag").toPath(), "void main() {}");
			String a = sessions.open(new ProjectSessions.Locator(ProjectSessions.Kind.DIRECTORY, root.getPath()));
			String b = sessions.open(new ProjectSessions.Locator(ProjectSessions.Kind.DIRECTORY, temp.newFolder().getPath()));
			OpenProject editor = OpenProject.open(a, new DirectoryProjectFiles(root), null, null, sessions, queue, Runnable::run, (r,m) -> {}, OpenProject.MAX_BUDGET);
			CountDownLatch loaded = new CountDownLatch(1);
			editor.load(path("main.frag"), ProjectFiles.UNKNOWN, problem -> loaded.countDown());
			assertTrue(loaded.await(2, TimeUnit.SECONDS));
			var document = editor.document(path("main.frag"));
			document.replace(0, 0, Text.of("// edit\n"));
			OpenProject images = OpenProject.open(b, new ProjectFiles() {
				public Read read(ProjectPath p) { return null; }
				public Listing list(ProjectPath p) { return new Entries(List.of()); }
				public byte[] head(ProjectPath p, int n) {
					entered.countDown();
					try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
					return new byte[]{(byte)137,80,78,71,13,10,26,10};
				}
			}, null, null, sessions, queue, Runnable::run, (r,m) -> {}, OpenProject.MAX_BUDGET);
			images.picture(path("cloud.png"), picture -> {});
			assertTrue(entered.await(2, TimeUnit.SECONDS));
			editor.save(true, result -> saved.countDown());
			boolean savedBeforeImageReturns = saved.await(200, TimeUnit.MILLISECONDS);
			release.countDown();
			assertTrue(saved.await(2, TimeUnit.SECONDS));
			assertTrue(Files.readString(new File(root,"main.frag").toPath()).startsWith("// edit"));
			assertTrue("save waited for the image signature read", savedBeforeImageReturns);
		} finally { release.countDown(); queue.shutdownNow(); queue.awaitTermination(2, TimeUnit.SECONDS); }
	}
}
