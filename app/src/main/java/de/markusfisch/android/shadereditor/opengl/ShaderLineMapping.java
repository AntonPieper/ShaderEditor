package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.project.SourceMap;

final class ShaderLineMapping {
	private static final ShaderLineMapping IDENTITY =
			new ShaderLineMapping(List.of(), null);

	@NonNull
	private final List<InsertedLines> insertions;
	@Nullable
	private final SourceMap sourceMap;

	private ShaderLineMapping(
			@NonNull List<InsertedLines> insertions,
			@Nullable SourceMap sourceMap) {
		this.insertions = List.copyOf(insertions);
		this.sourceMap = sourceMap;
	}

	@NonNull
	static ShaderLineMapping identity() {
		return IDENTITY;
	}

	@NonNull
	static ShaderLineMapping withLeadingInsertedLines(int lineCount) {
		return identity().addInsertedLinesAfterSourceLine(0, lineCount);
	}

	/**
	 * Map source lines further to project files through sourceMap.
	 */
	@NonNull
	static ShaderLineMapping withSourceMap(@NonNull SourceMap sourceMap) {
		return new ShaderLineMapping(List.of(), sourceMap);
	}

	@Nullable
	SourceMap getSourceMap() {
		return sourceMap;
	}

	int toSourceLine(int preparedLine) {
		if (preparedLine < 1) {
			return -1;
		}

		int removedLines = 0;
		for (InsertedLines insertion : insertions) {
			int startLine = insertion.sourceLine + removedLines + 1;
			int endLine = startLine + insertion.lineCount - 1;
			if (preparedLine < startLine) {
				break;
			}
			if (preparedLine <= endLine) {
				return -1;
			}
			removedLines += insertion.lineCount;
		}

		return Math.max(preparedLine - removedLines, -1);
	}

	@NonNull
	ShaderLineMapping addInsertedLinesAfterSourceLine(int sourceLine, int lineCount) {
		if (lineCount <= 0) {
			return this;
		}

		ArrayList<InsertedLines> updatedInsertions = new ArrayList<>(insertions);
		int last = updatedInsertions.size() - 1;
		if (last >= 0) {
			InsertedLines previous = updatedInsertions.get(last);
			if (previous.sourceLine == sourceLine) {
				updatedInsertions.set(last, new InsertedLines(
						sourceLine,
						previous.lineCount + lineCount));
				return new ShaderLineMapping(updatedInsertions, sourceMap);
			}
		}
		updatedInsertions.add(new InsertedLines(sourceLine, lineCount));
		return new ShaderLineMapping(updatedInsertions, sourceMap);
	}

	private static final class InsertedLines {
		private final int sourceLine;
		private final int lineCount;

		private InsertedLines(int sourceLine, int lineCount) {
			this.sourceLine = sourceLine;
			this.lineCount = lineCount;
		}
	}
}
