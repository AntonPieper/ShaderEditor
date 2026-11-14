package de.markusfisch.android.shadereditor.activity.managers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.database.dao.ShaderDao;

public class ShaderInsertsTest {
	// A database that only counts what is asked of it.
	private static final class Rows extends ShaderDao {
		final List<String> inserted = new ArrayList<>();

		@SuppressWarnings("DataFlowIssue")
		Rows() {
			super(null, null);
		}

		@Override
		public long insertShader(@NonNull String shader,
				@Nullable String name, @Nullable byte[] thumbnail,
				float quality) {
			inserted.add(shader);
			return inserted.size();
		}

		@Override
		public long insertShaderFromResource(@NonNull Context context,
				@Nullable String name, int sourceId, int thumbId,
				float quality) {
			inserted.add("resource " + sourceId);
			return inserted.size();
		}
	}

	// A row made behind the library would turn up as a file nobody
	// made at the next start: where the shaders are files, no row is
	// made, whatever asks.
	@Test
	public void whereShadersAreFilesNoRowIsEverMade() {
		Rows rows = new Rows();
		ShaderInserts inserts = new ShaderInserts(rows, true);
		assertFalse(inserts.possible());
		assertEquals(0, inserts.insert("void main() {}", null, null, 1f));
		assertEquals(0, inserts.insertFromResource(null, "Sample", 7, 8, 1f));
		assertTrue(rows.inserted.isEmpty());
	}

	@Test
	public void whereShadersAreRowsOneIsMadeAsBefore() {
		Rows rows = new Rows();
		ShaderInserts inserts = new ShaderInserts(rows, false);
		assertTrue(inserts.possible());
		assertEquals(1, inserts.insert("void main() {}", null, null, 1f));
		assertEquals(2, inserts.insertFromResource(null, "Sample", 7, 8, 1f));
		assertEquals(List.of("void main() {}", "resource 7"), rows.inserted);
	}

	// The editor's shader has that one way to make a row and no other.
	@Test
	public void theEditorsShaderMakesRowsThroughItAlone() throws IOException {
		String source = new String(Files.readAllBytes(new File(
				"src/main/java/de/markusfisch/android/shadereditor/" +
						"activity/managers/ShaderManager.java").toPath()),
				StandardCharsets.UTF_8);
		for (String insert : new String[]{".insertShader(",
				".insertShaderFromResource(", ".insertNewShader("}) {
			assertFalse("ShaderManager calls " + insert,
					source.contains("shader" + insert));
		}
		assertTrue(source.contains("inserts.insert("));
	}
}
