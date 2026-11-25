package de.markusfisch.android.shadereditor.tree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.project.DirectiveCases;
import de.markusfisch.android.shadereditor.project.DirectiveOracle;

/**
 * What the tree says the names of the cases of {@link MeaningOracle}
 * mean, against what Mesa's compiler did with them when
 * tools/tree/oracle.sh recorded it: Mesa agreed with every reading
 * that is recorded, and the tree still reads each case that way.
 */
public class MeaningsOracleTest {
	private static final String RERUN = ": run tools/tree/oracle.sh";

	@Test
	public void namesMeanWhatMesaSays() throws IOException {
		Map<String, List<String>> records = DirectiveOracle.read(new File(
				DirectiveCases.probes().getParentFile().getParentFile(),
				"tree/mesa.txt"));
		int agreed = 0;
		for (MeaningOracle.Case c : MeaningOracle.cases()) {
			List<String> record = records.get(c.name());
			assertNotNull("No record of " + c.name() + RERUN, record);
			String[] parts = record.get(0).split(" ", 5);
			assertEquals(c.name() + " changed" + RERUN, parts[2],
					DirectiveOracle.sha(c.text()));
			assertEquals(c.name() + " is read differently" + RERUN, parts[3],
					DirectiveOracle.sha(MeaningOracle.reading(new Whole(c.text()))));
			assertFalse(c.name() + ": " + parts[4], parts[4].startsWith("WRONG"));
			agreed += parts[4].equals("agrees") ? 1 : 0;
		}
		assertTrue("agreed: " + agreed, agreed > 100);
	}
}
