package de.markusfisch.android.shadereditor.widget;

import android.text.Spannable;

import androidx.annotation.NonNull;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.IdentityHashMap;

/**
 * A text and its spans, collected for one {@code TextView.setText()}.
 * <p>
 * Each span set on a SpannableStringBuilder re-sorts its span tree, so
 * colouring a large text span by span is quadratic. TextView copies a
 * Spanned it is given into its Editable with one sort at the end, so the
 * colours are collected here, where adding a span costs constant time.
 * It is Spannable because TextView copies any Spanned that is not into
 * a SpannableString first, one span at a time.
 */
final class ColouredText implements Spannable {
	@NonNull
	private final String text;
	private final IdentityHashMap<Object, Integer> indices = new IdentityHashMap<>();
	private Object[] spans;
	private int[] starts;
	private int[] ends;
	private int[] flags;
	private int count;

	ColouredText(@NonNull String text, int capacity) {
		this.text = text;
		capacity = Math.max(4, capacity);
		spans = new Object[capacity];
		starts = new int[capacity];
		ends = new int[capacity];
		flags = new int[capacity];
	}

	@Override
	public void setSpan(Object what, int start, int end, int flags) {
		if (start < 0 || end < start || end > text.length()) {
			throw new IndexOutOfBoundsException(
					"setSpan (" + start + " ... " + end + ") of " + text.length());
		}
		Integer index = indices.get(what);
		int i;
		if (index != null) {
			i = index;
		} else {
			if (count == spans.length) {
				int capacity = count * 2;
				spans = Arrays.copyOf(spans, capacity);
				starts = Arrays.copyOf(starts, capacity);
				ends = Arrays.copyOf(ends, capacity);
				this.flags = Arrays.copyOf(this.flags, capacity);
			}
			i = count++;
			spans[i] = what;
			indices.put(what, i);
		}
		starts[i] = start;
		ends[i] = end;
		this.flags[i] = flags;
	}

	@Override
	public void removeSpan(Object what) {
		Integer index = indices.remove(what);
		if (index == null) {
			return;
		}
		int i = index;
		int moved = count - i - 1;
		System.arraycopy(spans, i + 1, spans, i, moved);
		System.arraycopy(starts, i + 1, starts, i, moved);
		System.arraycopy(ends, i + 1, ends, i, moved);
		System.arraycopy(flags, i + 1, flags, i, moved);
		spans[--count] = null;
		for (int j = i; j < count; ++j) {
			indices.put(spans[j], j);
		}
	}

	/**
	 * Returns the spans of {@code kind} that overlap
	 * {@code [queryStart, queryEnd]}, in the order they were set. Like
	 * SpannableStringBuilder, a non-empty span that only touches a
	 * non-empty range does not overlap it.
	 */
	@Override
	@SuppressWarnings("unchecked")
	public <T> T[] getSpans(int queryStart, int queryEnd, Class<T> kind) {
		if (kind == null) {
			return (T[]) Array.newInstance(Object.class, 0);
		}
		int n = 0;
		Object[] found = new Object[count];
		for (int i = 0; i < count; ++i) {
			if (kind.isInstance(spans[i]) &&
					overlaps(starts[i], ends[i], queryStart, queryEnd)) {
				found[n++] = spans[i];
			}
		}
		T[] result = (T[]) Array.newInstance(kind, n);
		System.arraycopy(found, 0, result, 0, n);
		return result;
	}

	@Override
	public int getSpanStart(Object tag) {
		Integer index = indices.get(tag);
		return index == null ? -1 : starts[index];
	}

	@Override
	public int getSpanEnd(Object tag) {
		Integer index = indices.get(tag);
		return index == null ? -1 : ends[index];
	}

	@Override
	public int getSpanFlags(Object tag) {
		Integer index = indices.get(tag);
		return index == null ? 0 : flags[index];
	}

	@Override
	public int nextSpanTransition(int start, int limit, Class kind) {
		if (kind == null) {
			kind = Object.class;
		}
		int next = limit;
		for (int i = 0; i < count; ++i) {
			if (!kind.isInstance(spans[i])) {
				continue;
			}
			if (starts[i] > start && starts[i] < next) {
				next = starts[i];
			}
			if (ends[i] > start && ends[i] < next) {
				next = ends[i];
			}
		}
		return next;
	}

	@Override
	public int length() {
		return text.length();
	}

	@Override
	public char charAt(int index) {
		return text.charAt(index);
	}

	/**
	 * @return the characters only; this is not a text to edit
	 */
	@NonNull
	@Override
	public CharSequence subSequence(int start, int end) {
		return text.substring(start, end);
	}

	@NonNull
	@Override
	public String toString() {
		return text;
	}

	private static boolean overlaps(int start, int end,
			int queryStart, int queryEnd) {
		if (start > queryEnd || end < queryStart) {
			return false;
		}
		return start == end || queryStart == queryEnd ||
				(start != queryEnd && end != queryStart);
	}
}
