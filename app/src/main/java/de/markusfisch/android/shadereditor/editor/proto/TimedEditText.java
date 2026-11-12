package de.markusfisch.android.shadereditor.editor.proto;

import android.content.Context;
import android.graphics.Canvas;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;

/**
 * THROWAWAY PROTOTYPE (proto/editor-core). The platform's EditText with
 * the same clocks as the prototype view, to compare.
 */
final class TimedEditText extends EditText {
	private final Stats stats;
	private long keyStart;
	private long editNanos;
	private String keyLabel;
	long measureNanos;
	long firstDrawEnd;

	TimedEditText(Context context, Stats stats) {
		super(context);
		this.stats = stats;
	}

	// As the app's editor does: without it an autofill service is sent
	// the whole text when the field gains focus, and 235 KB of text is
	// a transaction too large.
	@Override
	public int getAutofillType() {
		return AUTOFILL_TYPE_NONE;
	}

	@Override
	protected void onMeasure(int widthSpec, int heightSpec) {
		long start = System.nanoTime();
		super.onMeasure(widthSpec, heightSpec);
		measureNanos += System.nanoTime() - start;
	}

	@Override
	protected void onDraw(Canvas canvas) {
		long start = System.nanoTime();
		super.onDraw(canvas);
		long now = System.nanoTime();
		if (firstDrawEnd == 0) {
			firstDrawEnd = now;
		}
		stats.draw(now - start, 0);
		if (keyStart != 0) {
			stats.key(keyLabel, editNanos, now - start, now - keyStart,
					getLineCount());
			keyStart = 0;
		}
	}

	@Override
	public InputConnection onCreateInputConnection(EditorInfo out) {
		InputConnection target = super.onCreateInputConnection(out);
		if (target == null) {
			return null;
		}
		return new InputConnectionWrapper(target, true) {
			@Override
			public boolean commitText(CharSequence text, int position) {
				begin("commitText " + text.length());
				boolean result = super.commitText(text, position);
				end();
				return result;
			}

			@Override
			public boolean setComposingText(CharSequence text, int position) {
				begin("setComposingText " + text.length());
				boolean result = super.setComposingText(text, position);
				end();
				return result;
			}

			@Override
			public boolean deleteSurroundingText(int before, int after) {
				begin("deleteSurroundingText " + before + "," + after);
				boolean result = super.deleteSurroundingText(before, after);
				end();
				return result;
			}
		};
	}

	void begin(String label) {
		if (keyStart == 0) {
			keyStart = System.nanoTime();
			keyLabel = label;
			measureNanos = 0;
		}
	}

	void end() {
		editNanos = System.nanoTime() - keyStart;
	}
}
