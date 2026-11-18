package exact;

import java.util.List;

/** The strict reader's verdict on one program, for callers outside. */
public final class Verdict {
	/** "reads", "refuses", "error" or "error in some configurations". */
	public final String status;
	/** Why, and the offset in the text where it stopped, or -1. */
	public final String why;
	public final int at;

	private Verdict(String status, String why, int at) {
		this.status = status;
		this.why = why;
		this.at = at;
	}

	public static Verdict of(String text) {
		List<Exact.Reading> all = Exact.readings(text, Main.version(text));
		Exact.Reading first = null;
		int fails = 0;
		for (Exact.Reading r : all) {
			if (r.fail != null) {
				++fails;
				first = first == null ? r : first;
			}
		}
		if (first == null) {
			return new Verdict("reads", null, -1);
		}
		if (first.refused) {
			return new Verdict("refuses", first.fail, first.failAt);
		}
		return new Verdict(fails < all.size()
				? "error in some configurations" : "error", first.fail,
				first.failAt);
	}
}
