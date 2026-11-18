package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What {@link DirectiveRun} says of the cases of {@link DirectiveCases},
 * against what Mesa's compiler did with them, as tools/directives/oracle.sh
 * recorded it: no line is off that Mesa compiles, and no expansion is
 * shown that Mesa was not found to compile the same.
 */
public class DirectiveRunOracleTest {
	private static final String RERUN = ": run tools/directives/oracle.sh";

	@Test
	public void agreesWithMesa() throws IOException {
		File file = new File(DirectiveCases.probes().getParentFile(),
				"mesa.txt");
		Map<String, List<String>> records = DirectiveOracle.read(file);
		int off = 0;
		int shown = 0;
		for (DirectiveCases.Case c : DirectiveCases.all()) {
			List<String> record = records.get(c.name());
			assertNotNull("No record of " + c.name() + RERUN, record);
			assertEquals(c.name() + " changed" + RERUN,
					record.get(0).split(" ")[2], DirectiveOracle.sha(c.text()));
			TreeSet<Integer> active = new TreeSet<>();
			Set<String> same = new HashSet<>();
			for (String line : record) {
				if (line.startsWith("active ")) {
					active = DirectiveOracle.lines(line.substring(7));
				} else if (line.startsWith("expansion ") &&
						!line.split(" ")[4].equals("differs")) {
					same.add(line.substring(10).replaceFirst(
							" (same|unseen) ", " "));
				}
			}
			for (int n : c.off()) {
				assertFalse(c.name() + ": line " + n +
						" is off, and Mesa compiles it", active.contains(n));
				++off;
			}
			for (DirectiveCases.Expansion e : c.expansions()) {
				assertTrue(c.name() + ": " + e + " is not what Mesa " +
						"compiled" + RERUN, same.contains(e.line() + " " +
						e.column() + " " + e.name() + " " + e.text()));
				++shown;
			}
		}
		assertTrue(off > 1000);
		assertTrue(shown > 1000);
	}
}
