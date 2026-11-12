package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.adapter.Samples;
import de.markusfisch.android.shadereditor.navigation.Listing;
import de.markusfisch.android.shadereditor.navigation.Order;
import de.markusfisch.android.shadereditor.navigation.Row;
import de.markusfisch.android.shadereditor.navigation.Source;

/** The bundled examples, in their sections, as the level Examples. */
final class ExamplesSource implements Source {
	private final Context context;
	private final NavigationDrawer.Host host;

	ExamplesSource(@NonNull Context context,
			@NonNull NavigationDrawer.Host host) {
		this.context = context;
		this.host = host;
	}

	@Override
	public Listing list(Order order) {
		List<Listing.Section> sections = new ArrayList<>();
		for (Samples.Section section : Samples.Section.values()) {
			List<Row> rows = new ArrayList<>();
			for (int i = 0, size = Samples.ALL.size(); i < size; ++i) {
				Samples.Sample sample = Samples.ALL.get(i);
				if (sample.section() == section) {
					rows.add(new Row(String.valueOf(i),
							Row.Kind.SHADER,
							Row.Picture.resource(sample.thumbId()),
							context.getString(sample.nameId()),
							context.getString(sample.rationaleId()),
							0,
							null,
							EnumSet.noneOf(Row.Mark.class),
							EnumSet.noneOf(Row.Action.class)));
				}
			}
			if (!rows.isEmpty()) {
				sections.add(new Listing.Section(
						context.getString(section.titleId), rows));
			}
		}
		return new Listing(context.getString(R.string.load_sample),
				context.getString(R.string.nav_read_only),
				true,
				false,
				Listing.Bar.NEW_SHADER,
				sections);
	}

	@Override
	public void open(Row row, boolean run) {
		host.onOpenExample(Samples.ALL.get(Integer.parseInt(row.key())));
	}

	@Override
	public void act(Row row, Row.Action action) {
	}
}
