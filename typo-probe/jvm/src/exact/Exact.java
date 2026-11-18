package exact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntToLongFunction;

import exact.Pre.Fail;
import exact.Pre.Tok;

/** One program read exactly, and the classes of names over readings. */
final class Exact {
	static final int MAX_CONFIGS = 8;
	/** The key of everything the language itself names. */
	static final long LANGUAGE = -1;

	static final class Reading {
		Map<String, Boolean> config;
		List<Tok> toks;
		int[] bind;
		List<Pre.Ref> refs;
		List<int[]> links;
		List<int[]> same = List.of();
		Set<String> asked;
		String fail;
		int failAt = -1;
		boolean refused;
		Parse parse;
		List<Integer> directives;

		/**
		 * Counts of what is declared: globals, functions, structs,
		 * fields, macros, parameters, locals; then the parameters and
		 * locals of functions without a directive and without a token
		 * out of a macro; then functions, and those of that kind.
		 */
		int[] counts() {
			int[] n = new int[11];
			boolean[] free = new boolean[parse.functions.size()];
			for (int f = 0; f < free.length; ++f) {
				int[] range = parse.functions.get(f);
				free[f] = true;
				for (int i = range[0]; i <= range[1]; ++i) {
					free[f] &= toks.get(i).via < 0;
				}
				for (int d : directives) {
					free[f] &= d < toks.get(range[0]).at ||
							d > toks.get(range[1]).at;
				}
				++n[9];
				n[10] += free[f] ? 1 : 0;
			}
			for (int i = 0; i < toks.size(); ++i) {
				int kind = "gfsm_pl".indexOf(parse.declared[i]);
				if (parse.declared[i] == 0 || kind < 0) {
					continue;
				}
				++n[kind];
				int f = parse.functionOf[i];
				if (kind >= 5 && f >= 0 && free[f]) {
					++n[kind + 2];
				}
			}
			for (Pre.Ref ref : refs) {
				n[4] += ref.role() == 'D' ? 1 : 0;
			}
			return n;
		}
	}

	static Reading read(String text, int version,
			Map<String, Boolean> config) {
		return read(text, version, config, false);
	}

	static Reading read(String text, int version,
			Map<String, Boolean> config, boolean isolate) {
		Reading r = new Reading();
		r.config = config;
		Pre pre = new Pre(text, version, config);
		try {
			pre.run();
			Parse parse = new Parse(pre.out, version);
			parse.isolate = isolate;
			r.parse = parse;
			r.toks = pre.out;
			r.refs = pre.refs;
			r.links = pre.links;
			r.asked = pre.asked;
			parse.run();
			r.parse = parse;
			r.directives = pre.directives;
			r.bind = parse.bind;
			r.same = parse.same;
		} catch (Fail f) {
			r.fail = f.getMessage();
			r.failAt = f.at;
			r.refused = f instanceof Pre.Refuse;
			r.asked = pre.asked;
		}
		return r;
	}

	/** Every outcome of what only the driver decides. */
	static List<Reading> readings(String text, int version) {
		List<Reading> all = new ArrayList<>();
		List<Map<String, Boolean>> todo = new ArrayList<>();
		Set<Map<String, Boolean>> seen = new HashSet<>();
		todo.add(new HashMap<>());
		while (!todo.isEmpty()) {
			Map<String, Boolean> config = todo.remove(todo.size() - 1);
			if (!seen.add(config)) {
				continue;
			}
			Reading r = read(text, version, config);
			all.add(r);
			if (all.size() > MAX_CONFIGS) {
				r.fail = "too many conditions only the driver decides";
				return all;
			}
			for (String name : r.asked) {
				if (!config.containsKey(name)) {
					for (boolean value : new boolean[]{false, true}) {
						Map<String, Boolean> next = new HashMap<>(config);
						next.put(name, value);
						todo.add(next);
					}
				}
			}
		}
		// A reading that asked about a name it was not told is the
		// same as one of those that were told.
		all.removeIf(r -> r.fail == null &&
				!r.config.keySet().containsAll(r.asked));
		return all;
	}

	/** The classes: which written names are one thing. */
	static final class Classes {
		private final Map<Long, Integer> ids = new HashMap<>();
		private final List<Integer> parent = new ArrayList<>();
		final Set<Long> unknown = new HashSet<>();

		private int id(long key) {
			Integer id = ids.get(key);
			if (id == null) {
				id = parent.size();
				ids.put(key, id);
				parent.add(id);
			}
			return id;
		}

		private int root(int id) {
			while (parent.get(id) != id) {
				parent.set(id, parent.get(parent.get(id)));
				id = parent.get(id);
			}
			return id;
		}

		void join(long a, long b) {
			int x = root(id(a)), y = root(id(b));
			if (x != y) {
				parent.set(x, y);
			}
		}

		boolean has(long key) {
			return ids.containsKey(key);
		}

		boolean same(long a, long b) {
			return has(a) && has(b) && root(ids.get(a)) == root(ids.get(b));
		}

		/** Add what one reading says; origin maps an offset to a key. */
		void add(Reading r, IntToLongFunction origin) {
			for (int i = 0; i < r.toks.size(); ++i) {
				int b = r.bind[i];
				if (b == Parse.NOT) {
					continue;
				}
				long key = origin.applyAsLong(r.toks.get(i).at);
				id(key);
				if (b == Parse.UNKNOWN) {
					unknown.add(key);
				} else if (b == Parse.NONE) {
					join(key, LANGUAGE);
				} else {
					join(key, origin.applyAsLong(r.toks.get(b).at));
				}
			}
			for (int[] pair : r.same) {
				join(origin.applyAsLong(r.toks.get(pair[0]).at),
						origin.applyAsLong(r.toks.get(pair[1]).at));
			}
			for (int[] pair : r.links) {
				join(origin.applyAsLong(pair[0]), origin.applyAsLong(pair[1]));
			}
			// The preprocessor has one namespace: a name is one macro.
			Map<String, Long> first = new HashMap<>();
			for (Pre.Ref ref : r.refs) {
				long key = origin.applyAsLong(ref.at());
				Long other = first.putIfAbsent(ref.name(), key);
				join(key, other == null ? key : other);
				if (ref.name().startsWith("GL_") ||
						ref.name().contains("__")) {
					join(key, LANGUAGE);
				}
			}
		}

		/** The keys of the class of a key. */
		Set<Long> of(long key) {
			Set<Long> all = new TreeSet<>();
			for (long other : ids.keySet()) {
				if (same(key, other)) {
					all.add(other);
				}
			}
			return all;
		}

		Map<Integer, Set<Long>> all() {
			Map<Integer, Set<Long>> all = new HashMap<>();
			for (Map.Entry<Long, Integer> e : ids.entrySet()) {
				all.computeIfAbsent(root(e.getValue()),
						k -> new TreeSet<>()).add(e.getKey());
			}
			return all;
		}
	}

	/**
	 * Whether a reading of the edited text is the reading before, but
	 * for the spelling of the renamed tokens: null if so, else what
	 * differs. names has the new name of every renamed token by its
	 * offset in the text before, lengths the length of its old name.
	 */
	static String check(Reading before, Reading after,
			Map<Integer, String> names, Map<Integer, Integer> lengths) {
		if (after.fail != null) {
			return "does not read: " + after.fail;
		}
		java.util.TreeMap<Integer, Integer> shift = new java.util.TreeMap<>();
		int sum = 0;
		for (int at : new TreeSet<>(names.keySet())) {
			sum += names.get(at).length() - lengths.get(at);
			shift.put(at, sum);
		}
		if (before.toks.size() != after.toks.size()) {
			return "another number of tokens";
		}
		for (int i = 0; i < before.toks.size(); ++i) {
			Tok a = before.toks.get(i), b = after.toks.get(i);
			if (shift(a.at, shift) != b.at ||
					(a.via < 0 ? -1 : shift(a.via, shift)) != b.via) {
				return "a token comes from another place: " + b.text;
			}
			if (!b.text.equals(names.getOrDefault(a.at, a.text))) {
				return "another token: " + b.text;
			}
			if (before.bind[i] != after.bind[i]) {
				return b.text + " means something else";
			}
		}
		if (before.refs.size() != after.refs.size() ||
				before.links.size() != after.links.size()) {
			return "another number of macro names";
		}
		for (int i = 0; i < before.refs.size(); ++i) {
			Pre.Ref a = before.refs.get(i), b = after.refs.get(i);
			if (shift(a.at(), shift) != b.at() || a.role() != b.role() ||
					!b.name().equals(names.getOrDefault(a.at(), a.name()))) {
				return "a macro name is read differently: " + b.name();
			}
		}
		for (int i = 0; i < before.links.size(); ++i) {
			int[] a = before.links.get(i), b = after.links.get(i);
			if (shift(a[0], shift) != b[0] || shift(a[1], shift) != b[1]) {
				return "a macro's parameter is read differently";
			}
		}
		return null;
	}

	private static int shift(int at, java.util.TreeMap<Integer, Integer> s) {
		Map.Entry<Integer, Integer> e = s.lowerEntry(at);
		return at + (e == null ? 0 : e.getValue());
	}

	/** The text with the tokens at offsets replaced by a name. */
	static String replace(String text, Map<Integer, String> names,
			Map<Integer, Integer> lengths) {
		StringBuilder sb = new StringBuilder();
		int last = 0;
		for (int at : new TreeSet<>(names.keySet())) {
			sb.append(text, last, at).append(names.get(at));
			last = at + lengths.get(at);
		}
		return sb.append(text.substring(last)).toString();
	}
}
